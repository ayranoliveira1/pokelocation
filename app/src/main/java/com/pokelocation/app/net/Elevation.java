package com.pokelocation.app.net;

import android.content.Context;

import com.pokelocation.app.data.Prefs;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Open-Meteo: altitude do terreno. BLOQUEANTE (chamar no Io). */
public final class Elevation {
    private static final int TIMEOUT_MS = 3000;
    /** LRU de 32 células (~100 m: lat/lng × 1000 arredondados). */
    private static final Map<Long, Double> CACHE = new LinkedHashMap<Long, Double>(40, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Double> e) { return size() > 32; }
    };

    private Elevation() {}

    /** NaN se elevation_lookup_enabled=false; sem rede ou erro → 0. */
    public static double fetch(Context c, double lat, double lng) {
        if (!Prefs.getBool(c, Prefs.ELEVATION_LOOKUP_ENABLED)) return Double.NaN;
        long key = (Math.round(lat * 1000) << 32) ^ (Math.round(lng * 1000) & 0xFFFFFFFFL);
        synchronized (CACHE) {
            Double v = CACHE.get(key);
            if (v != null) return v;
        }
        HttpURLConnection con = null;
        try {
            URL url = new URL(String.format(Locale.US,
                    "https://api.open-meteo.com/v1/elevation?latitude=%.6f&longitude=%.6f", lat, lng));
            con = (HttpURLConnection) url.openConnection();
            con.setConnectTimeout(TIMEOUT_MS);
            con.setReadTimeout(TIMEOUT_MS);
            con.setRequestProperty("User-Agent", c.getPackageName());
            if (con.getResponseCode() != 200) return 0;
            ByteArrayOutputStream bo = new ByteArrayOutputStream(64);
            try (InputStream in = con.getInputStream()) {
                byte[] b = new byte[512];
                int r;
                while ((r = in.read(b)) > 0 && bo.size() < 16_384) bo.write(b, 0, r);
            }
            double v = new JSONObject(bo.toString("UTF-8")).getJSONArray("elevation").getDouble(0);
            if (Double.isNaN(v) || Double.isInfinite(v)) return 0;
            synchronized (CACHE) {
                CACHE.put(key, v);
            }
            return v;
        } catch (Exception e) {
            return 0;
        } finally {
            if (con != null) con.disconnect();
        }
    }
}
