package com.pokelocation.app.pogo;

import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.os.Process;
import android.util.Log;
import android.util.LruCache;

import com.pokelocation.app.Io;
import com.pokelocation.app.social.net.Api;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
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
 * Ícones dos Pokémon com a cadeia de fallback do site. Imagens da Niantic/TPC, que o nosso
 * servidor busca no GitHub: nunca no APK, só baixadas sob demanda para cacheDir/pogo_icons (bytes do PNG). Um bitmap
 * por ícone, 96 px.
 */
public final class PogoIcons {
    /** Pelo nosso servidor, que busca no PokeMiners e no UICONS (GitHub) e guarda; o nome do arquivo é o de lá. */
    static final String PM = "/v1/pogo/icon/pm/", UICONS = "/v1/pogo/icon/u/";
    /** Decodifica em até ~256 px (256 KB passageiros): o recorte da margem precisa da resolução cheia do PNG. */
    private static final int TRIM_PX = 256;
    /** O mesmo zoom para todas as espécies (só os grandes ganham menos, para não cortar); ajuste aqui. */
    private static final float MAX_ZOOM = 1.5f;
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
    /** Travas fixas por faixa de nome de arquivo (sem mapa nem remove: com eles 2 de 3 threads baixavam juntas). */
    private static final Object[] FILES = new Object[16];

    static {
        for (int i = 0; i < FILES.length; i++) FILES[i] = new Object();
    }
    private static final int OK = 0, NOT_FOUND = 1, SKIP = 2, HIT = 3; // SKIP: nem 200 nem 404 (429, 5xx), vai para a próxima URL

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
            // o site não tira o _NOEVOLVE e cai no 404
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
                int got = onePerFile(f.getName(), () -> {
                    if (f.exists()) return HIT;   // outra chave da mesma imagem (o fallback u25.png) acabou de baixar
                    if (miss.exists() && System.currentTimeMillis() - miss.lastModified() < MISS_MS) return NOT_FOUND;
                    return download(c, url, f, miss);
                });
                if (got == NOT_FOUND) continue;
                if (got == SKIP) {
                    soft = true;
                    continue;
                }
                Bitmap b = decode(f.getPath());
                if (b != null) {
                    if (got == HIT) f.setLastModified(System.currentTimeMillis()); // o sweep apaga pelo uso, não pela data do download
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

    interface Work { int run() throws IOException; }

    /**
     * Uma trava por arquivo: o PENDING junta pelo Pokémon, mas chaves diferentes caem no mesmo fallback (o shiny e o
     * normal no u25.png) e as 4 threads o baixavam juntas. Quem chega depois espera e acha o arquivo no disco.
     */
    static int onePerFile(String name, Work work) throws IOException {
        synchronized (FILES[(name.hashCode() & 0x7FFFFFFF) % FILES.length]) {
            return work.run();
        }
    }

    private static void failed(String key, long until) {
        synchronized (LOCK) {
            FAILED.put(key, until);
        }
    }

    /**
     * OK = gravado em f; NOT_FOUND = 404, ou 400 do nome fora da lista do servidor (os dois definitivos: marca .404);
     * SKIP = outro status (429, 5xx, sem sessão); IOException = sem rede.
     */
    private static int download(Context c, String path, File f, File miss) throws IOException {
        try {
            Api.pogoFile(c, path, null, f, MAX_BYTES, TIMEOUT_MS);   // o PNG nunca muda: no disco não se revalida
        } catch (Api.Error e) {
            if (e.http != 404 && e.http != 400) {
                Log.w(TAG, f.getName() + ": " + e.http);
                return SKIP;
            }
            new FileOutputStream(miss).close();
            return NOT_FOUND;
        }
        synchronized (LOCK) {
            if ((sDisk += f.length()) > DISK_MAX) sSwept = false;
        }
        return OK;
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
        o.inSampleSize = sample(o.outWidth, o.outHeight, TRIM_PX);
        Bitmap src = BitmapFactory.decodeFile(path, o);
        if (src == null) return null;
        int[] sq = square(src.getWidth(), src.getHeight(), opaque(src));
        Matrix m = new Matrix();
        m.setScale((float) TARGET_PX / sq[2], (float) TARGET_PX / sq[2]);
        Bitmap out = Bitmap.createBitmap(src, sq[0], sq[1], sq[2], sq[2], m, true);
        if (out != src) src.recycle();   // só existiu aqui: nenhuma view o segura
        return out;
    }

    /** {esquerda, topo, direita, base} do que tem alfa visível, ou null se a imagem é toda transparente. */
    private static int[] opaque(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight(), l = w, t = h, r = -1, bo = -1;
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            b.getPixels(row, 0, w, 0, y, w, 1);
            for (int x = 0; x < w; x++) {
                if ((row[x] >>> 24) <= 16) continue;
                if (x < l) l = x;
                if (x > r) r = x;
                if (y < t) t = y;
                bo = y;
            }
        }
        return r < 0 ? null : new int[]{l, t, r + 1, bo + 1};
    }

    /**
     * {x, y, lado} do quadrado centrado na parte opaca e preso na imagem. Os PNGs do PokeMiners já vêm em escala
     * (Weedle 29% da imagem, Kyogre 99%): todos ganham o mesmo zoom de MAX_ZOOM, e quem não cabe nele ganha só o que
     * cabe com 3% de folga. Assim o pequeno segue menor que o grande e nenhum é cortado. Quadrado porque o mapa estica o
     * ícone num quadrado.
     */
    static int[] square(int w, int h, int[] box) {
        int max = Math.min(w, h);
        if (box == null) return new int[]{(w - max) / 2, (h - max) / 2, max};
        int fit = Math.round(Math.max(box[2] - box[0], box[3] - box[1]) * 1.06f);
        int side = Math.min(max, Math.max(Math.round(max / MAX_ZOOM), fit));
        int x = (box[0] + box[2]) / 2 - side / 2, y = (box[1] + box[3]) / 2 - side / 2;
        return new int[]{Math.max(0, Math.min(x, w - side)), Math.max(0, Math.min(y, h - side)), side};
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
