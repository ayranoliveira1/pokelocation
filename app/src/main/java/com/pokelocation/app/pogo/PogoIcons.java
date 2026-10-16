package com.pokelocation.app.pogo;

import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Process;
import android.util.Log;
import android.util.LruCache;

import com.pokelocation.app.App;
import com.pokelocation.app.Io;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Ícones dos Pokémon com a cadeia de fallback do site (docs/pogo/icones.md §1). Imagens da Niantic/TPC servidas pelo
 * GitHub: nunca no APK, só baixadas sob demanda para cacheDir/pogo_icons (bytes do PNG). Um bitmap por ícone, 96 px.
 */
public final class PogoIcons {
    static final String PM = "https://raw.githubusercontent.com/PokeMiners/pogo_assets/master/Images/"
            + "Pokemon%20-%20256x256/Addressable%20Assets/";
    static final String UICONS = "https://raw.githubusercontent.com/nileplumb/PkmnShuffleMap/refs/heads/master/UICONS/pokemon/";
    /** 96 px exatos (36 KB): 4 MB guardam ~110 ícones, mais que as espécies distintas de um lookup de 100. */
    private static final int TARGET_PX = 96, MEM_BYTES = 4 << 20, MAX_BYTES = 256 * 1024, TIMEOUT_MS = 10_000;
    private static final long DISK_MAX = 8L << 20, MISS_MS = 7L * 24 * 3600_000, RETRY_MS = 60_000;

    private static final String TAG = "PogoIcons";
    private static final Object LOCK = new Object();
    private static LruCache<String, Bitmap> sMem;
    private static ThreadPoolExecutor sExec;
    private static boolean sSwept;
    /** Bytes no disco desde o último sweep: passou do teto, o próximo fetch varre de novo (o seletor baixa centenas). */
    private static long sDisk;
    private static final Map<String, List<Consumer<Bitmap>>> PENDING = new HashMap<>();
    /** chave → quando tentar de novo (cadeia toda 404 ou rede fora). */
    private static final Map<String, Long> FAILED = new HashMap<>();
    private static final int OK = 0, NOT_FOUND = 1, SKIP = 2; // SKIP: nem 200 nem 404 (429, 5xx), vai para a próxima URL

    private PogoIcons() {}

    public static Bitmap cached(Pogo.Mon m) {
        synchronized (LOCK) {
            return sMem == null ? null : sMem.get(key(m));
        }
    }

    /** cb na main; null se nenhum ícone da cadeia veio (o chamador mantém o desenho de hoje). */
    public static void load(Context c, Pogo.Mon m, Consumer<Bitmap> cb) {
        String key = key(m);
        Context app = c.getApplicationContext();
        synchronized (LOCK) {
            Bitmap b = mem(app).get(key);
            Long retry = FAILED.get(key);
            if (b != null || (retry != null && retry > System.currentTimeMillis())) {
                Bitmap r = b;
                Io.main().post(() -> cb.accept(r));
                return;
            }
            List<Consumer<Bitmap>> waiting = PENDING.get(key);
            if (waiting != null) {
                waiting.add(cb);
                return;
            }
            PENDING.put(key, new ArrayList<>(Arrays.asList(cb)));
        }
        List<String> chain = urls(m.id, m.name, m.form, m.costume, m.shiny);
        exec().execute(() -> {
            Bitmap b = fetch(app, chain, key);
            List<Consumer<Bitmap>> cbs;
            synchronized (LOCK) {
                if (b != null) mem(app).put(key, b);
                cbs = PENDING.remove(key);
            }
            Io.main().post(() -> { for (Consumer<Bitmap> k : cbs) k.accept(b); });
        });
    }

    /**
     * Para o push de alerta: memória, senão disco/rede no executor, no máximo timeoutMs. Não entra no LRU (a notificação
     * copia o bitmap e nada fica retido); estourou o prazo = null, e o download segue e fica no disco para a próxima.
     */
    static Bitmap now(Context c, Pogo.Mon m, long timeoutMs) {
        String key = key(m);
        synchronized (LOCK) {
            Bitmap b = sMem == null ? null : sMem.get(key);
            Long retry = FAILED.get(key);
            if (b != null || retry != null && retry > System.currentTimeMillis()) return b;
        }
        Context app = c.getApplicationContext();
        List<String> chain = urls(m.id, m.name, m.form, m.costume, m.shiny);
        try {
            return exec().submit(() -> fetch(app, chain, key)).get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Ao desligar a camada e ao fechar o painel no :overlay; pouca memória já chama sozinho. Só solta as referências
     * (sem recycle: um bitmap ainda numa view derrubaria o processo); o GC devolve os pixels.
     */
    public static void trim() {
        synchronized (LOCK) {
            if (sMem != null) sMem.trimToSize(0);
            FAILED.clear();
        }
        Pogo.forgetEnriched();
    }

    /** Ordem do site: costume shiny, costume, forma shiny, forma (ou sem forma), UICONS. */
    static List<String> urls(int id, String name, String form, String costume, boolean shiny) {
        List<String> out = new ArrayList<>(5);
        if (costume != null && !costume.isEmpty() && !digits(costume)) { // id numérico do lookup/push: não é o enum do arquivo
            // o site não tira o _NOEVOLVE e cai no 404 (icones.md §1)
            String base = "pm" + id + ".c" + costume.replace("_NOEVOLVE", "");
            if (shiny) out.add(PM + base + ".s.icon.png");
            out.add(PM + base + ".icon.png");
        }
        String f = formSuffix(id, name, form);
        String base = "pm" + id + (f != null ? ".f" + f : "");
        if (shiny) out.add(PM + base + ".s.icon.png");
        out.add(PM + base + ".icon.png");
        out.add(UICONS + id + ".png");
        return out;
    }

    /** O lookup traz o rótulo ("Copy 2019"); o arquivo usa o sufixo (COPY_2019). */
    static String formSuffix(int id, String name, String form) {
        String f = form == null ? "" : form.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        if (f.equals("NORMAL") || f.equals("FORM_UNSET") || digits(f)) f = "";
        if (id == 664 || id == 665 || f.equals("S") || f.equals("ULTRA")) return null;
        if (f.isEmpty()) return id == 646 || id == 649 ? "NORMAL" : null;
        if ((id == 201 || id == 412 || id == 413) && name != null)
            return name.trim().toUpperCase(Locale.ROOT).replace(' ', '_') + "_" + f;
        return f;
    }

    private static boolean digits(String s) {
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) < '0' || s.charAt(i) > '9') return false;
        return !s.isEmpty();
    }

    /** Maior potência de 2 que ainda deixa o menor lado ≥ alvo. */
    static int sample(int w, int h, int target) {
        int n = 1;
        while (target > 0 && Math.min(w, h) / (n * 2) >= target) n *= 2;
        return n;
    }

    static String key(Pogo.Mon m) {
        String k = m.iconKey;
        if (k == null) m.iconKey = k = m.id + "|" + m.form + "|" + m.costume + "|" + m.shiny;
        return k;
    }

    /** Nome no disco: o último segmento da URL; os UICONS ganham prefixo para não colidir. */
    static String fileName(String url) {
        String seg = url.substring(url.lastIndexOf('/') + 1);
        return url.startsWith(UICONS) ? "u" + seg : seg;
    }

    private static Bitmap fetch(Context c, List<String> chain, String key) {
        File dir = new File(c.getCacheDir(), "pogo_icons");
        if (!dir.isDirectory() && !dir.mkdirs()) return null;
        sweep(dir);
        boolean soft = false;
        for (String url : chain) {
            File f = new File(dir, fileName(url)), miss = new File(dir, f.getName() + ".404");
            if (miss.exists() && System.currentTimeMillis() - miss.lastModified() < MISS_MS) continue;
            try {
                boolean hit = f.exists();
                if (!hit) {
                    int got = download(url, f, miss);
                    if (got == NOT_FOUND) continue;
                    if (got == SKIP) {
                        soft = true;
                        continue;
                    }
                }
                Bitmap b = decode(f.getPath());
                if (b != null) {
                    if (hit) f.setLastModified(System.currentTimeMillis()); // o sweep apaga pelo uso, não pela data do download
                    return b;
                }
                f.delete();
            } catch (IOException e) {
                Log.w(TAG, f.getName() + ": " + e);
                failed(key, System.currentTimeMillis() + RETRY_MS); // sem rede: a cadeia seguinte daria o mesmo
                return null;
            }
        }
        failed(key, soft ? System.currentTimeMillis() + RETRY_MS : Long.MAX_VALUE);
        return null;
    }

    private static void failed(String key, long until) {
        synchronized (LOCK) {
            FAILED.put(key, until);
        }
    }

    /** OK = gravado em f; NOT_FOUND = 404 (marca .404); SKIP = outro status; IOException = sem rede. */
    private static int download(String url, File f, File miss) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        // sem disconnect() no caminho feliz: corpo lido até o fim devolve a conexão ao pool (sem novo TLS por ícone)
        try {
            con.setConnectTimeout(TIMEOUT_MS);
            con.setReadTimeout(TIMEOUT_MS);
            con.setUseCaches(false);
            // fora do main, o último pedido do lote não deixa TLS ocioso no pool por minutos
            if (App.process() != App.PROC_MAIN && exec().getQueue().isEmpty()) con.setRequestProperty("Connection", "close");
            int status = con.getResponseCode();
            if (status == 404) {
                con.disconnect();
                new FileOutputStream(miss).close();
                return NOT_FOUND;
            }
            if (status != 200) {
                Log.w(TAG, f.getName() + ": " + status);
                con.disconnect();
                return SKIP;
            }
            File tmp = File.createTempFile(f.getName(), ".tmp", f.getParentFile()); // main e :overlay podem baixar juntos
            try (InputStream in = con.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[8192];
                int r, total = 0;
                while ((r = in.read(buf)) > 0) {
                    if ((total += r) > MAX_BYTES) throw new IOException("icon too large");
                    out.write(buf, 0, r);
                }
            } catch (IOException e) {
                tmp.delete();
                throw e;
            }
            if (!tmp.renameTo(f)) tmp.delete();
            synchronized (LOCK) {
                if ((sDisk += f.length()) > DISK_MAX) sSwept = false;
            }
            return OK;
        } catch (IOException | RuntimeException e) {
            con.disconnect();
            throw e;
        }
    }

    /** Buffer de decodificação por thread do executor: sem os 16 KB que o decodeFile alocaria a cada chamada. */
    private static final ThreadLocal<byte[]> TEMP = ThreadLocal.withInitial(() -> new byte[16 * 1024]);

    /** Direto do arquivo: sem byte[] intermediário a cada recarga do LRU. */
    private static Bitmap decode(String path) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inTempStorage = TEMP.get();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        o.inJustDecodeBounds = false;
        o.inSampleSize = sample(o.outWidth, o.outHeight, TARGET_PX);
        int side = Math.min(o.outWidth, o.outHeight) / o.inSampleSize;
        if (side > TARGET_PX) {
            o.inScaled = true;
            o.inDensity = side;
            o.inTargetDensity = TARGET_PX;
        }
        return BitmapFactory.decodeFile(path, o);
    }

    /** Uma vez por processo: .404 vencidos fora e, acima de 8 MB, os PNGs mais antigos primeiro. */
    private static void sweep(File dir) {
        synchronized (LOCK) {
            if (sSwept) return;
            sSwept = true;
        }
        File[] all = dir.listFiles();
        if (all == null) return;
        Arrays.sort(all, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        long total = 0, now = System.currentTimeMillis();
        for (File f : all) total += f.length();
        for (File f : all) {
            boolean stale = f.getName().endsWith(".404") && now - f.lastModified() >= MISS_MS;
            if (!stale && total <= DISK_MAX) continue;
            total -= f.length();
            f.delete();
        }
        synchronized (LOCK) {
            sDisk = total;
        }
    }

    private static LruCache<String, Bitmap> mem(Context app) {
        if (sMem == null) {
            sMem = new LruCache<String, Bitmap>(MEM_BYTES) {
                @Override protected int sizeOf(String k, Bitmap v) { return v.getByteCount(); }
            };
            app.registerComponentCallbacks(new ComponentCallbacks2() {
                @Override public void onTrimMemory(int level) {
                    if (level >= TRIM_MEMORY_RUNNING_LOW) trim();
                }
                @Override public void onConfigurationChanged(Configuration c) {}
                @Override public void onLowMemory() { trim(); }
            });
        }
        return sMem;
    }

    private static synchronized ThreadPoolExecutor exec() {
        if (sExec == null) {
            // LIFO: o pedido mais novo (o que está na tela agora) sai primeiro
            LinkedBlockingDeque<Runnable> q = new LinkedBlockingDeque<Runnable>() {
                @Override public boolean offer(Runnable r) { return offerFirst(r); }
            };
            sExec = new ThreadPoolExecutor(4, 4, 6, TimeUnit.SECONDS, q, r -> new Thread(() -> {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                r.run();
            }, "pl-pogo-icon"));
            sExec.allowCoreThreadTimeOut(true);
        }
        return sExec;
    }
}
