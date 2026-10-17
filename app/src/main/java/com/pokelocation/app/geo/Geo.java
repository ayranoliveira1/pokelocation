package com.pokelocation.app.geo;

/** Geodésia esférica (R = 6 371 009 m). Pontos de rota intercalados lat,lng,alt. */
public final class Geo {
    public static final double R = 6371009.0;

    private Geo() {}

    /** Haversine, metros. */
    public static double distance(double lat1, double lng1, double lat2, double lng2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = p2 - p1, dl = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * R * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    /** Rumo inicial, graus [-180,180). */
    public static double bearing(double lat1, double lng1, double lat2, double lng2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2), dl = Math.toRadians(lng2 - lng1);
        double y = Math.sin(dl) * Math.cos(p2);
        double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        double b = Math.toDegrees(Math.atan2(y, x));
        return b >= 180 ? b - 360 : b;
    }

    /** Destino esférico, sem alocar: out2 = {lat, lng}, lng em [-180,180). */
    public static void offset(double lat, double lng, double meters, double bearingDeg, double[] out2) {
        double d = meters / R, th = Math.toRadians(bearingDeg);
        double p1 = Math.toRadians(lat), l1 = Math.toRadians(lng);
        double sinP2 = Math.sin(p1) * Math.cos(d) + Math.cos(p1) * Math.sin(d) * Math.cos(th);
        double p2 = Math.asin(Math.max(-1, Math.min(1, sinP2)));
        double l2 = l1 + Math.atan2(Math.sin(th) * Math.sin(d) * Math.cos(p1), Math.cos(d) - Math.sin(p1) * sinP2);
        out2[0] = Math.toDegrees(p2);
        out2[1] = normLng(Math.toDegrees(l2));
    }

    private static double normLng(double lng) {
        double l = ((lng + 180) % 360 + 360) % 360 - 180;
        return l;
    }

    /** Slerp no grande círculo; linear se δ < 1e-6. f em [0,1]. */
    public static void interpolate(double lat1, double lng1, double lat2, double lng2, double f, double[] out2) {
        double p1 = Math.toRadians(lat1), l1 = Math.toRadians(lng1), p2 = Math.toRadians(lat2), l2 = Math.toRadians(lng2);
        double cp1 = Math.cos(p1), cp2 = Math.cos(p2);
        double a = Math.sin((p2 - p1) / 2), b = Math.sin((l2 - l1) / 2);
        double delta = 2 * Math.asin(Math.min(1, Math.sqrt(a * a + cp1 * cp2 * b * b)));
        if (delta < 1e-6) {
            out2[0] = lat1 + (lat2 - lat1) * f;
            out2[1] = lng1 + (lng2 - lng1) * f;
            return;
        }
        double s = Math.sin(delta);
        double ka = Math.sin((1 - f) * delta) / s, kb = Math.sin(f * delta) / s;
        double x = ka * cp1 * Math.cos(l1) + kb * cp2 * Math.cos(l2);
        double y = ka * cp1 * Math.sin(l1) + kb * cp2 * Math.sin(l2);
        double z = ka * Math.sin(p1) + kb * Math.sin(p2);
        out2[0] = Math.toDegrees(Math.atan2(z, Math.sqrt(x * x + y * y)));
        out2[1] = Math.toDegrees(Math.atan2(y, x));
    }

    public static double metersToLat(double meters) { return Math.toDegrees(meters / R); }

    public static double metersToLng(double meters, double atLat) {
        double c = Math.cos(Math.toRadians(atLat));
        return Math.toDegrees(meters / (R * Math.max(c, 1e-12)));
    }

    /** Ângulo matemático do stick (0 = leste, anti-horário) → bússola [0,360). */
    public static float stickToCompass(float angleDeg) {
        float c = 90f - angleDeg;
        if (angleDeg > 90f) c += 360f;
        c %= 360f;
        return c < 0 ? c + 360f : c;
    }

    public static double random(double min, double max) { return min + Math.random() * (max - min); }

    /** Sem a origem; alt NaN. mode 0 random, 1 circle (o último ponto coincide com a origem). */
    public static double[] generate(double lat, double lng, int mode, int count, double offsetM) {
        if (count <= 0) return new double[0];
        double[] pts = new double[count * 3];
        double[] o = new double[2];
        if (mode == 1) {
            offset(lat, lng, offsetM, 180, o);
            double cLat = o[0], cLng = o[1];
            for (int i = 1; i <= count; i++) {
                offset(cLat, cLng, offsetM, 360.0 / count * i, o);
                put(pts, i - 1, o[0], o[1]);
            }
        } else {
            double pLat = lat, pLng = lng;
            for (int i = 0; i < count; i++) {
                offset(pLat, pLng, random(0, offsetM), random(0, 360), o);
                pLat = o[0];
                pLng = o[1];
                put(pts, i, pLat, pLng);
            }
        }
        return pts;
    }

    private static void put(double[] pts, int i, double lat, double lng) {
        pts[i * 3] = lat;
        pts[i * 3 + 1] = lng;
        pts[i * 3 + 2] = Double.NaN;
    }

    /** Douglas-Peucker (pts intercalados), tolerância em metros; sempre mantém as pontas. */
    public static double[] simplify(double[] pts, double toleranceM) {
        int n = pts == null ? 0 : pts.length / 3;
        if (n <= 2) return pts == null ? new double[0] : pts.clone();
        boolean[] keep = new boolean[n];
        keep[0] = keep[n - 1] = true;
        int[] stack = new int[2 * n];
        int sp = 0;
        stack[sp++] = 0;
        stack[sp++] = n - 1;
        double cosLat = Math.cos(Math.toRadians(pts[0]));
        while (sp > 0) {
            int b = stack[--sp], a = stack[--sp];
            double ax = x(pts, a, cosLat), ay = y(pts, a), bx = x(pts, b, cosLat), by = y(pts, b);
            double dx = bx - ax, dy = by - ay, len2 = dx * dx + dy * dy;
            double maxD = -1;
            int idx = -1;
            for (int i = a + 1; i < b; i++) {
                double px = x(pts, i, cosLat) - ax, py = y(pts, i) - ay;
                double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, (px * dx + py * dy) / len2));
                double ex = px - t * dx, ey = py - t * dy, d = ex * ex + ey * ey;
                if (d > maxD) {
                    maxD = d;
                    idx = i;
                }
            }
            if (idx > 0 && maxD > toleranceM * toleranceM) {
                keep[idx] = true;
                stack[sp++] = a;
                stack[sp++] = idx;
                stack[sp++] = idx;
                stack[sp++] = b;
            }
        }
        int m = 0;
        for (boolean k : keep) if (k) m++;
        double[] out = new double[m * 3];
        int j = 0;
        for (int i = 0; i < n; i++) {
            if (!keep[i]) continue;
            System.arraycopy(pts, i * 3, out, j * 3, 3);
            j++;
        }
        return out;
    }

    private static double x(double[] p, int i, double cosLat) { return Math.toRadians(p[i * 3 + 1]) * cosLat * R; }

    private static double y(double[] p, int i) { return Math.toRadians(p[i * 3]) * R; }

    /** Soma das distâncias entre pontos consecutivos, metros. */
    public static double length(double[] pts) {
        int n = pts == null ? 0 : pts.length / 3;
        double s = 0;
        for (int i = 1; i < n; i++) s += distance(pts[i * 3 - 3], pts[i * 3 - 2], pts[i * 3], pts[i * 3 + 1]);
        return s;
    }
}
