package com.pokelocation.app.social.crypto;

import android.content.Context;
import android.os.Build;
import android.os.Process;
import android.util.Log;

import com.pokelocation.app.App;
import com.pokelocation.app.BuildConfig;
import com.pokelocation.app.Io;
import com.pokelocation.app.social.Session;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;

/**
 * libsignal nativa baixada no login: o APK leva só o stub libsignal_jni.so; o .so real vem do GitHub (reserva no
 * servidor) com .part retomável por Range, gunzip, SHA-256 fixado em BuildConfig.LIBSIGNAL_SO e rename atômico para
 * files/libsignal/ somente leitura. O hash é conferido a cada carga. Nenhuma classe org.signal pode ser tocada antes de
 * loaded(): o ART resolveria os JNI no stub vazio.
 */
public final class SignalLoader {
    static final String SO = "libsignal_jni.so", PART = SO + ".gz.part", TMP = SO + ".tmp";
    public static final String NO_SPACE = "no_space";
    private static final String GITHUB = "https://github.com/ayranoliveira1/pokelocation/releases/download/";
    private static final int ROUNDS = 4, TIMEOUT_MS = 20_000, STEP = 128 * 1024;

    /** Callbacks na main. */
    public interface Listener {
        /** Bytes do .gz; total -1 se desconhecido. */
        void onProgress(long done, long total);
        void onReady();
        /** Dá para tentar de novo (start). NO_SPACE → sem espaço; o resto → rede. */
        void onError(IOException e);
        /** ABI sem .so publicado ou a lib não carrega: definitivo no processo. */
        void onUnavailable();
    }

    /** Sem .so para a ABI do processo ou o System.load falhou: não adianta tentar de novo. */
    static final class Unavailable extends IOException {
        Unavailable(String why) { super(why); }
    }

    interface Fetch { Resp open(String url, long from) throws IOException; }

    interface Progress { void on(long done, long total); }

    /** start 0 = o servidor ignorou o Range; total -1 = desconhecido. */
    static final class Resp {
        final InputStream in;
        final long start, total;

        Resp(InputStream in, long start, long total) {
            this.in = in;
            this.start = start;
            this.total = total;
        }
    }

    private static volatile boolean sLoaded, sUnavailable, sKicked;
    private static boolean sAgain; // start() com o thread rodando: mais uma passada (o login pode ter chegado depois da checagem)
    private static volatile Listener sListener;
    private static Thread sThread;
    private static final Object LOAD = new Object();

    private SignalLoader() {}

    /** Só depois disto as classes org.signal podem ser usadas. */
    public static boolean loaded() { return sLoaded; }

    /**
     * Thread própria, não o Io (único: um download de 3 MB travaria o app). Sem login só baixa e confere: System.load
     * só com login. Idempotente: com uma carga em andamento troca o listener e pede mais uma passada.
     */
    public static synchronized void start(Context c, Listener l) {
        sListener = l;
        if (sLoaded || sUnavailable) {
            post(null);
            return;
        }
        if (sThread != null) {
            sAgain = true;
            return;
        }
        Context app = c.getApplicationContext();
        sThread = new Thread(() -> {
            IOException err;
            do {
                err = null;
                try {
                    load(app);
                } catch (Unavailable e) {
                    Log.w("SignalLoader", "unavailable: " + e.getMessage());
                    sUnavailable = true;
                } catch (IOException e) {
                    err = e;
                } catch (Throwable e) { // nada aqui pode derrubar o processo principal
                    Log.w("SignalLoader", "load", e);
                    sUnavailable = true;
                }
            } while (again());
            kick(app);
            post(err);
        }, "pl-signal");
        sThread.setPriority(Thread.MIN_PRIORITY);
        sThread.start();
    }

    /** Principal: o flag do prefs. :overlay: o prefs dele pode estar velho → a sessão no banco (sob o social.lock). */
    private static boolean loggedInNow(Context c) {
        if (App.process() == App.PROC_MAIN) return Session.loggedIn(c);
        try {
            return Session.access(c) != null;
        } catch (IOException e) {
            return false;
        }
    }

    private static synchronized boolean again() {
        boolean more = sAgain && !sLoaded && !sUnavailable;
        sAgain = false;
        if (!more) sThread = null;
        return more;
    }

    /**
     * Agenda Keys.ensure no Io quando a lib carrega e em todo login/refresh: o refresh do access (~15 min de uso) é a
     * checagem periódica de prekeys. Keys fica em outra classe para nada de org.signal ser resolvido antes do System.load.
     */
    public static void kick(Context c) {
        // só o principal gera chaves: no :overlay o loggedIn() do prefs pode estar velho
        if (App.process() != App.PROC_MAIN || !sLoaded || sKicked || !Session.loggedIn(c)) return;
        sKicked = true;
        Context app = c.getApplicationContext();
        Io.run(() -> {
            sKicked = false;
            try {
                Keys.ensure(app);
            } catch (IOException e) {
                Log.w("SignalLoader", "keys: " + e.getMessage()); // só o código: nada de corpo/token no log
            }
        });
    }

    /** O download segue. */
    public static void detach(Listener l) {
        if (sListener == l) sListener = null;
    }

    private static void post(IOException err) {
        Io.main().post(() -> {
            Listener l = sListener;
            if (l == null) return;
            if (sUnavailable) l.onUnavailable();
            else if (err != null) l.onError(err);
            else l.onReady(); // carregou, ou baixou sem login (nada a mostrar)
        });
    }

    /** Bloqueante. Retentativas com espera de 2/4/8 s. */
    static void load(Context c) throws IOException {
        synchronized (LOAD) { // não o lock da classe: start() roda na main e não pode esperar um download
            loadLocked(c);
        }
    }

    private static void loadLocked(Context c) throws IOException {
        if (sLoaded) return;
        // ABI do PROCESSO (a do stub carregado), não a 1ª do aparelho: x86 com houdini lista armeabi-v7a, e carregar
        // ARM num processo x86 quebra. Sem .so publicado para ela (x86) → indisponível, sem baixar.
        String[] abis = Process.is64Bit() ? Build.SUPPORTED_64_BIT_ABIS : Build.SUPPORTED_32_BIT_ABIS;
        String[] pin = abis.length == 0 ? null : pin(BuildConfig.LIBSIGNAL_SO, abis[0]);
        if (pin == null) throw new Unavailable("abi " + (abis.length == 0 ? "?" : abis[0]));
        String name = "libsignal-" + BuildConfig.LIBSIGNAL_VERSION + "/libsignal_jni-" + pin[0] + ".so.gz";
        String[] urls = {GITHUB + name, BuildConfig.SOCIAL_API + "/static/" + name};
        File dir = new File(c.getFilesDir(), "libsignal");
        long[] last = {-STEP};
        Progress p = (done, total) -> {
            if (done - last[0] < STEP && done != total) return;
            last[0] = done;
            Io.main().post(() -> {
                Listener l = sListener;
                if (l != null) l.onProgress(done, total);
            });
        };
        IOException err = null;
        for (int round = 0; round < ROUNDS; round++) {
            try {
                if (round > 0) Thread.sleep(1000L << round);
                sLoaded = fetchAndLoad(dir, pin[1], Long.parseLong(pin[2]), c.getFilesDir().getUsableSpace(), urls,
                        SignalLoader::http, p, () -> loggedInNow(c), System::load);
                return;
            } catch (InterruptedException e) {
                throw new IOException(e);
            } catch (Unavailable e) {
                throw e;
            } catch (IOException e) {
                err = e;
                if (NO_SPACE.equals(e.getMessage())) break;
            }
        }
        throw err;
    }

    /**
     * System.load só se loggedIn DEPOIS do download (o download corre em paralelo à autenticação). false = .so pronto,
     * não carregado. Falha de link → apaga o .so e Unavailable.
     */
    static boolean fetchAndLoad(File dir, String sha, long size, long free, String[] urls, Fetch f, Progress p,
                                BooleanSupplier loggedIn, Consumer<String> loader) throws IOException {
        File failed = new File(dir, "failed-" + sha); // link já falhou com este sha: não baixa 3 MB para falhar igual
        if (failed.exists()) throw new Unavailable("load (lembrado)");
        File so = fetch(dir, sha, size, free, urls, f, p);
        if (!loggedIn.getAsBoolean()) return false;
        try {
            loader.accept(so.getAbsolutePath());
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            // determinístico (ABI/linker): apaga, não tenta de novo
            so.setWritable(true);
            so.delete();
            try {
                failed.createNewFile();
            } catch (IOException ignored) {
                // sem o marcador só se perde a economia; o Unavailable vale do mesmo jeito
            }
            Unavailable u = new Unavailable("load");
            u.initCause(e);
            throw u;
        }
        return true;
    }

    /** Nunca deixa instalado algo não verificado. */
    static File fetch(File dir, String sha, long size, long free, String[] urls, Fetch f, Progress p) throws IOException {
        File so = new File(dir, SO), part = new File(dir, PART), tmp = new File(dir, TMP);
        if (so.exists()) {
            if (verified(so, sha)) return so;
            so.setWritable(true);
            so.delete();
        }
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("mkdir");
        // .gz < .so: o que falta do .gz + o .so descomprimido
        if (free < 2 * size - part.length()) throw new IOException(NO_SPACE);
        IOException err = null;
        for (String url : urls) {
            try {
                download(url, part, size, f, p);
                err = null;
                break;
            } catch (IOException e) {
                err = e; // .part fica para a próxima URL/rodada retomar
            }
        }
        if (err != null) throw err;
        try {
            if (!inflate(part, tmp, sha, size)) throw new IOException("bad_hash");
            if (!tmp.setReadOnly() || !tmp.renameTo(so)) throw new IOException("install");
        } catch (IOException e) {
            tmp.delete();
            throw e;
        } finally {
            part.delete(); // completo: ou instalou, ou é lixo (corrompido/hash errado) e recomeça do zero
        }
        return so;
    }

    private static void download(String url, File part, long size, Fetch f, Progress p) throws IOException {
        long have = part.length();
        Resp r = f.open(url, have);
        try (InputStream in = r.in) {
            if (r.start != 0 && r.start != have) {
                part.delete(); // servidor confuso: a próxima tentativa recomeça do zero
                throw new IOException("range");
            }
            long done = r.start;
            try (FileOutputStream out = new FileOutputStream(part, r.start > 0)) {
                byte[] b = new byte[64 * 1024];
                for (int n; (n = in.read(b)) > 0; ) {
                    if (done + n > size) { // .gz é sempre menor que o .so: corpo sem fim não enche o disco
                        out.close();
                        part.delete();
                        throw new IOException("too_big");
                    }
                    out.write(b, 0, n);
                    done += n;
                    if (p != null) p.on(done, r.total);
                }
            }
        }
        if (r.total >= 0 && part.length() != r.total) throw new IOException("cut");
    }

    /** gunzip gz → out, com SHA-256 e teto de size bytes (gzip-bomba não enche o disco). */
    private static boolean inflate(File gz, File out, String sha, long size) throws IOException {
        MessageDigest md = sha256();
        long n = 0;
        try (InputStream in = new GZIPInputStream(new FileInputStream(gz), 64 * 1024);
             FileOutputStream o = new FileOutputStream(out)) {
            byte[] b = new byte[64 * 1024];
            for (int r; (r = in.read(b)) > 0; ) {
                if ((n += r) > size) return false;
                md.update(b, 0, r);
                o.write(b, 0, r);
            }
            o.getFD().sync(); // rename atômico só depois dos dados no disco
        }
        return n == size && hex(md.digest()).equals(sha);
    }

    static boolean verified(File so, String sha) {
        MessageDigest md = sha256();
        try (InputStream in = new FileInputStream(so)) {
            byte[] b = new byte[64 * 1024];
            for (int r; (r = in.read(b)) > 0; ) md.update(b, 0, r);
        } catch (IOException e) {
            return false;
        }
        return hex(md.digest()).equals(sha);
    }

    /** {abi, sha256, bytes} de abi em cfg ("abi:sha:bytes,…"); null se não houver .so publicado para ela. */
    static String[] pin(String cfg, String abi) {
        for (String e : cfg.split(",")) {
            String[] p = e.split(":");
            if (p.length == 3 && p[0].equals(abi)) return p;
        }
        return null;
    }

    private static Resp http(String url, long from) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(TIMEOUT_MS);
        con.setReadTimeout(TIMEOUT_MS);
        con.setRequestProperty("User-Agent", BuildConfig.APPLICATION_ID + "/" + BuildConfig.VERSION_NAME);
        con.setRequestProperty("Accept-Encoding", "identity"); // bytes crus: o Range conta sobre o .gz
        if (from > 0) con.setRequestProperty("Range", "bytes=" + from + "-");
        int st = con.getResponseCode();
        // só HTTPS, também depois dos redirects (GitHub → objects.githubusercontent.com)
        if (!"https".equals(con.getURL().getProtocol())) {
            con.disconnect();
            throw new IOException("not https");
        }
        if (st == 200) return new Resp(con.getInputStream(), 0, con.getContentLengthLong());
        if (st == 206) {
            // Content-Range: bytes a-b/total
            String cr = con.getHeaderField("Content-Range");
            try {
                long a = Long.parseLong(cr.substring(cr.indexOf(' ') + 1, cr.indexOf('-')));
                String t = cr.substring(cr.indexOf('/') + 1);
                return new Resp(con.getInputStream(), a, "*".equals(t) ? -1 : Long.parseLong(t));
            } catch (RuntimeException e) {
                con.disconnect();
                throw new IOException("content-range");
            }
        }
        con.disconnect();
        // 416: o .part já tem o arquivo todo (app morto antes de descomprimir) → segue para gunzip + hash
        if (st == 416 && from > 0) return new Resp(new java.io.ByteArrayInputStream(new byte[0]), from, from);
        throw new IOException("http " + st);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] b) {
        char[] h = "0123456789abcdef".toCharArray(), out = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) {
            out[2 * i] = h[(b[i] >> 4) & 15];
            out[2 * i + 1] = h[b[i] & 15];
        }
        return new String(out);
    }
}
