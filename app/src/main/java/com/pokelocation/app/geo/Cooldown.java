package com.pokelocation.app.geo;

import android.content.Context;

import com.pokelocation.app.R;
import com.pokelocation.app.data.Prefs;

/** Tabela de cooldown (16 faixas [min,max) em metros) e a linha "Distance: … - Cooldown: … - Elapsed: …". */
public final class Cooldown {
    public static final int TIERS = 16;
    private static final double[] MAX_M = {10, 100, 500, 1_000, 5_000, 10_000, 25_000, 30_000, 65_000, 81_000,
            100_000, 250_000, 500_000, 750_000, 1_000_000};
    private static final int[] COOLDOWN_S = {0, 3, 15, 30, 120, 360, 660, 840, 1320, 1500, 2100, 2700, 3600, 4500, 5400, 7200};
    private static final String[] LABELS = {"< 10 m", "10 - 99 m", "100 - 499 m", "500 - 999 m", "1 - 4 km", "5 - 9 km",
            "10 - 24 km", "25 - 29 km", "30 - 64 km", "65 - 80 km", "81 - 99 km", "100 - 249 km", "250 - 499 km",
            "500 - 749 km", "750 - 999 km", ">= 1000 km"};

    private Cooldown() {}

    /** Faixa [min,max); acima de tudo → 15. */
    public static int tier(double meters) {
        for (int i = 0; i < MAX_M.length; i++) if (meters < MAX_M[i]) return i;
        return TIERS - 1;
    }

    public static long cooldownMs(int tier) {
        return COOLDOWN_S[Math.max(0, Math.min(TIERS - 1, tier))] * 1000L;
    }

    /** "< 10 m" … ">= 1000 km". */
    public static String tierLabel(int tier) { return LABELS[Math.max(0, Math.min(TIERS - 1, tier))]; }

    /** "Xkm Ym" (km omitido se 0), a partir dos metros inteiros. */
    public static String formatDistance(double meters) {
        long m = meters > 0 ? (long) meters : 0;
        long km = m / 1000;
        return km > 0 ? km + "km " + (m % 1000) + "m" : m + "m";
    }

    /** "1h 15m", "30s", "0s" (partes zero omitidas). */
    public static String formatDuration(long ms) {
        long s = Math.max(0, ms) / 1000, h = s / 3600, m = s / 60 % 60;
        s %= 60;
        StringBuilder sb = new StringBuilder();
        if (h > 0) sb.append(h).append('h');
        if (m > 0) sb.append(sb.length() > 0 ? " " : "").append(m).append('m');
        if (s > 0) sb.append(sb.length() > 0 ? " " : "").append(s).append('s');
        return sb.length() == 0 ? "0s" : sb.toString();
    }

    /** "N day(s)" (plural) se ≥ 1 dia; senão "Hh Mm Ss" sem zeros à esquerda. */
    public static String formatElapsed(Context c, long ms) {
        long s = Math.max(0, ms) / 1000;
        long days = s / 86400;
        if (days >= 1) return c.getResources().getQuantityString(R.plurals.days, (int) Math.min(days, Integer.MAX_VALUE), days);
        return formatHms(s);
    }

    static String formatHms(long s) {
        long h = s / 3600, m = s / 60 % 60;
        s %= 60;
        StringBuilder sb = new StringBuilder();
        if (h > 0) sb.append(h).append("h ");
        if (h > 0 || m > 0) sb.append(m).append("m ");
        return sb.append(s).append('s').toString();
    }

    public static boolean ready(double meters, long elapsedMs) { return elapsedMs >= cooldownMs(tier(meters)); }

    public static boolean visible(Context c) {
        return Prefs.getBool(c, Prefs.DISPLAY_DISTANCE_INFO) && Prefs.getLong(c, Prefs.LAST_MOCKED_TIME) != 0;
    }

    public static CharSequence line(Context c, double meters, long lastMockedTime, long now) {
        return c.getString(R.string.distance_info, formatDistance(meters), formatDuration(cooldownMs(tier(meters))),
                formatElapsed(c, now - lastMockedTime));
    }

    public static int color(Context c, double meters, long lastMockedTime, long now) {
        return c.getColor(ready(meters, now - lastMockedTime) ? R.color.success : R.color.danger);
    }
}
