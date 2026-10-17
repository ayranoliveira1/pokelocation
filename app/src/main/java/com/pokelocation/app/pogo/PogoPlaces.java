package com.pokelocation.app.pogo;

import android.content.Context;
import android.location.Address;
import android.location.Geocoder;
import android.os.Process;
import android.util.LongSparseArray;

import com.pokelocation.app.Io;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * "Bairro, Cidade" pelo Geocoder do Android para quem não tem local (o lookup não traz). Cache por célula de ~1 km
 * (coordenadas em 2 casas), uma consulta por vez numa thread própria; "" = sem nome (fica só a distância). A mesma
 * thread faz o enrich do card, para ele nunca ficar na frente do reveal.
 */
final class PogoPlaces {
    private static final long RETRY_MS = 30_000;
    // Só na main. LongSparseArray: o getView das linhas consulta a cada tick de 1 s, sem boxing de Long.
    private static final LongSparseArray<String> CACHE = new LongSparseArray<>();
    private static final LongSparseArray<ArrayList<Runnable>> PENDING = new LongSparseArray<>();
    private static long failedAt;
    private static Geocoder sGeocoder;   // só na thread ONE
    private static final ThreadPoolExecutor ONE = new ThreadPoolExecutor(1, 1, 6, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(), r -> new Thread(() -> {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                r.run();
            }, "pl-pogo-info"));

    static {
        ONE.allowCoreThreadTimeOut(true);
    }

    private PogoPlaces() {}

    /** null = ainda não consultado; "" = sem nome. Só na main. */
    static String cached(double lat, double lng) {
        return CACHE.get(cell(lat, lng));
    }

    /** Pede o nome da célula (uma consulta por célula; quem pede durante ela também é avisado). Só na main. */
    static void request(Context c, double lat, double lng, Runnable onDone) {
        if (Double.isNaN(lat) || Double.isNaN(lng)) return;
        long k = cell(lat, lng);
        if (CACHE.indexOfKey(k) >= 0) return;
        ArrayList<Runnable> waiting = PENDING.get(k);
        if (waiting != null) {
            if (!waiting.contains(onDone)) waiting.add(onDone);
            return;
        }
        if (System.currentTimeMillis() - failedAt < RETRY_MS) return;   // sem rede/serviço: não tenta a cada tick
        waiting = new ArrayList<>(2);
        waiting.add(onDone);
        PENDING.put(k, waiting);
        Context app = c.getApplicationContext();
        ONE.execute(() -> {
            String name = lookup(app, lat, lng);
            Io.main().post(() -> {
                ArrayList<Runnable> cbs = PENDING.get(k);
                PENDING.remove(k);
                if (name == null) {
                    failedAt = System.currentTimeMillis();
                    return;
                }
                CACHE.put(k, name);
                if (cbs != null) for (Runnable r : cbs) r.run();
            });
        });
    }

    /** O enrich do card: fora da thread do reveal. */
    static void run(Runnable r) {
        ONE.execute(r);
    }

    /** null = falha passageira (não guarda: tenta de novo depois do RETRY_MS). */
    @SuppressWarnings("deprecation")   // a versão com listener é API 33+; esta roda na thread própria
    private static String lookup(Context app, double lat, double lng) {
        if (!Geocoder.isPresent()) return "";
        try {
            if (sGeocoder == null) sGeocoder = new Geocoder(app);
            List<Address> l = sGeocoder.getFromLocation(lat, lng, 1);
            return l == null || l.isEmpty() ? "" : name(l.get(0).getSubLocality(), l.get(0).getLocality(), l.get(0).getSubAdminArea());
        } catch (Exception e) {   // sem rede/serviço: fica só a distância por enquanto
            return null;
        }
    }

    /** "Bairro, Cidade"; sem cidade, a sub-região. */
    static String name(String sub, String city, String region) {
        String c = !empty(city) ? city : region;
        if (empty(sub)) return empty(c) ? "" : c;
        return empty(c) || sub.equals(c) ? sub : sub + ", " + c;
    }

    private static boolean empty(String s) { return s == null || s.isEmpty(); }

    /** Célula de 0,01° (~1,1 km), como chave única. */
    static long cell(double lat, double lng) {
        return (Math.round(lat * 100) + 9000) * 40_000L + (Math.round(lng * 100) + 18000);
    }
}
