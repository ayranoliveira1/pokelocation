package com.pokelocation.app.geo;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CoordParser {
    private static final String NUM = "([-+]?(?:\\d+\\.?\\d*|\\.\\d+))";
    private static final Pattern PAIR = Pattern.compile("^\\s*" + NUM + "\\s*(?:[,;]\\s*|\\s+)" + NUM + "\\s*$");
    private static final Pattern FORMAT = Pattern.compile("([-+]?[0-9]+\\.?[0-9]*)[,;][ ]*([-+]?[0-9]+\\.?[0-9]*)");

    private CoordParser() {}

    /** "lat, lng" | "lat; lng" | "lat lng"; |lat| ≤ 90, |lng| ≤ 180. */
    public static boolean parse(String text, double[] out2) {
        if (text == null) return false;
        Matcher m = PAIR.matcher(text);
        if (!m.matches()) return false;
        double lat = Double.parseDouble(m.group(1)), lng = Double.parseDouble(m.group(2));
        if (Math.abs(lat) > 90 || Math.abs(lng) > 180) return false;
        out2[0] = lat;
        out2[1] = lng;
        return true;
    }

    /** 1º match da regex → "g1, g2"; null se não achar. */
    public static String format(String text) {
        if (text == null) return null;
        Matcher m = FORMAT.matcher(text);
        return m.find() ? m.group(1) + ", " + m.group(2) : null;
    }

    /** "lat, lng". */
    public static String text(double lat, double lng) { return num(lat) + ", " + num(lng); }

    /** Para ler (chat, alerta): 5 casas, ~1 m; o text() tem a precisão cheia do teleporte. */
    public static String label(double lat, double lng) { return String.format(Locale.ROOT, "%.5f, %.5f", lat, lng); }

    /** "lat,lng". */
    public static String snippet(double lat, double lng) { return num(lat) + "," + num(lng); }

    /** Número sem notação científica (ex.: 1.0E-5 → 0.000010). */
    public static String num(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return String.valueOf(v);
        String s = Double.toString(v);
        return s.indexOf('E') >= 0 ? new BigDecimal(s).stripTrailingZeros().toPlainString() : s;
    }

    /** "lat,lng[,alt];…" (trim; inválidos descartados); null se vazio. */
    public static double[] waypoints(String s) {
        if (s == null) return null;
        String[] items = s.split(";");
        double[] out = new double[items.length * 3];
        int n = 0;
        for (String item : items) {
            String[] p = item.trim().split(",");
            if (p.length < 2 || p.length > 3) continue;
            try {
                double lat = Double.parseDouble(p[0].trim()), lng = Double.parseDouble(p[1].trim());
                double alt = p.length == 3 ? Double.parseDouble(p[2].trim()) : Double.NaN;
                if (Double.isNaN(lat) || Double.isNaN(lng) || Math.abs(lat) > 90 || Math.abs(lng) > 180) continue;
                if (Double.isInfinite(alt)) alt = Double.NaN;
                out[n * 3] = lat;
                out[n * 3 + 1] = lng;
                out[n * 3 + 2] = alt;
                n++;
            } catch (NumberFormatException ignored) {
            }
        }
        if (n == 0) return null;
        double[] r = new double[n * 3];
        System.arraycopy(out, 0, r, 0, r.length);
        return r;
    }
}
