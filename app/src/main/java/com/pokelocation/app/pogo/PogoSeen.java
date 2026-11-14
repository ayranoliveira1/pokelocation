package com.pokelocation.app.pogo;

import android.content.Context;

import com.pokelocation.app.App;
import com.pokelocation.app.Io;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Spawns já tocados. Cada processo grava só o próprio arquivo (como o pogo/pogo_ov do PogoFilter) e lê os dos outros
 * no refresh; has() só lê memória, então o draw e o render nunca tocam o disco.
 */
final class PogoSeen {
    static final long KEEP = 60 * 60_000L, KEEP_UNKNOWN = 2 * KEEP;
    static final int MAX = 500;
    private static final int[] PROCS = {App.PROC_MAIN, App.PROC_OVERLAY, App.PROC_MAP};

    // Copy-on-write: nunca mutados depois de publicados, para o has() do draw ler sem trava.
    private static volatile Map<String, Long> own = Collections.emptyMap(), others = Collections.emptyMap();
    private static final Object LOCK = new Object();
    // Só no Io.
    private static boolean ownLoaded, othersLoaded;
    private static final long[] stamp = new long[4];

    private PogoSeen() {}

    static String key(Pogo.Mon m) {
        if (m.spawn != null) return m.spawn;
        String k = m.encounter != null && !m.encounter.isEmpty() ? m.encounter
                : m.reveal != null && !m.reveal.isEmpty() ? "r:" + m.reveal
                : m.id > 0 ? "i:" + m.id + "|" + e5(m.lat) + "|" + e5(m.lng) + "|" + m.despawnMs
                : null;
        if (k == null || k.indexOf('\t') >= 0 || k.indexOf('\n') >= 0) return null;
        return m.spawn = k;
    }

    private static String e5(double v) { return Double.isNaN(v) ? "" : String.valueOf(Math.round(v * 1e5)); }

    static long until(Pogo.Mon m, long now) { return m.despawnMs > 0 ? Math.max(m.despawnMs, now) + KEEP : now + KEEP_UNKNOWN; }

    static boolean has(Pogo.Mon m, long now) {
        String k = key(m);
        if (k == null) return false;
        Long u = own.get(k);
        if (u != null && u > now) return true;
        u = others.get(k);
        return u != null && u > now;
    }

    static void mark(Context c, Pogo.Mon m) {
        String k = key(m);
        if (k == null) return;
        long now = System.currentTimeMillis(), until = until(m, now);
        synchronized (LOCK) {
            Long u = own.get(k);
            if (u != null && u >= until) return;
            own = put(own, k, until, now);
        }
        String name = file(App.process());
        if (name == null) return;
        Context app = c.getApplicationContext();
        Io.run(() -> {
            ensureOwn(app);
            try {
                write(new File(app.getFilesDir(), name), format(own));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    static void refresh(Context c, Runnable changed) {
        Context app = c.getApplicationContext();
        Io.run(() -> {
            ensureOwn(app);
            int self = App.process(), i = 0;
            if (file(self) == null) return;
            boolean dirty = !othersLoaded;
            File dir = app.getFilesDir();
            for (int p : PROCS) {
                if (p == self) continue;
                File f = new File(dir, file(p));
                long t = f.lastModified(), n = f.length();
                if (stamp[i] != t || stamp[i + 1] != n) dirty = true;
                stamp[i] = t;
                stamp[i + 1] = n;
                i += 2;
            }
            if (!dirty) return;
            othersLoaded = true;
            long now = System.currentTimeMillis();
            Map<String, Long> m = new HashMap<>();
            for (int p : PROCS) if (p != self) parse(read(new File(dir, file(p))), now, m);
            others = m;
            if (changed != null) Io.main().post(changed);
        });
    }

    /** 1ª tarefa do Io no processo: junta o próprio arquivo ao que já foi marcado, para a gravação não apagá-lo. */
    private static void ensureOwn(Context app) {
        if (ownLoaded) return;
        ownLoaded = true;
        String name = file(App.process());
        if (name == null) return;
        long now = System.currentTimeMillis();
        Map<String, Long> m = new HashMap<>();
        parse(read(new File(app.getFilesDir(), name)), now, m);
        synchronized (LOCK) {
            for (Map.Entry<String, Long> e : own.entrySet()) m.merge(e.getKey(), e.getValue(), Math::max);
            trim(m, now);
            own = m;
        }
    }

    static String file(int proc) {
        switch (proc) {
            case App.PROC_MAIN: return "pogo_seen";
            case App.PROC_OVERLAY: return "pogo_seen_ov";
            case App.PROC_MAP: return "pogo_seen_map";
            default: return null;
        }
    }

    static Map<String, Long> put(Map<String, Long> own, String key, long until, long now) {
        Map<String, Long> m = new HashMap<>(own);
        m.merge(key, until, Math::max);
        trim(m, now);
        return m;
    }

    // ponytail: tira o menor um a um (O(n) cada); só passa de MAX por poucos, salvo no merge do ensureOwn.
    private static void trim(Map<String, Long> m, long now) {
        m.values().removeIf(u -> u <= now);
        while (m.size() > MAX) {
            Map.Entry<String, Long> min = null;
            for (Map.Entry<String, Long> e : m.entrySet()) if (min == null || e.getValue() < min.getValue()) min = e;
            m.remove(min.getKey());
        }
    }

    static String format(Map<String, Long> m) {
        StringBuilder b = new StringBuilder(m.size() * 32);
        for (Map.Entry<String, Long> e : m.entrySet()) b.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
        return b.toString();
    }

    static void parse(String text, long now, Map<String, Long> into) {
        if (text == null) return;
        for (String line : text.split("\n")) {
            int tab = line.lastIndexOf('\t');
            if (tab <= 0) continue;
            long u;
            try {
                u = Long.parseLong(line.substring(tab + 1));
            } catch (NumberFormatException e) {
                continue;
            }
            if (u > now) into.merge(line.substring(0, tab), u, Math::max);
        }
    }

    static void write(File f, String text) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(f)) {
            tmp.delete();
            throw new IOException("rename " + f);
        }
    }

    static String read(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
            return out.toString("UTF-8");
        } catch (IOException e) {
            return null;
        }
    }
}
