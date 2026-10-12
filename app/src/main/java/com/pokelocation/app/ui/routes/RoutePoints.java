package com.pokelocation.app.ui.routes;

import com.pokelocation.app.geo.Geo;

/** Pontos editáveis de uma rota: um único double[] intercalado (lat,lng,alt) com capacidade. Sem android.*. */
final class RoutePoints {
    private double[] a;
    private int n;

    RoutePoints(double[] pts) {
        n = pts == null ? 0 : pts.length / 3;
        a = new double[Math.max(n, 4) * 3];
        if (n > 0) System.arraycopy(pts, 0, a, 0, n * 3);
    }

    int size() { return n; }
    double lat(int i) { return a[i * 3]; }
    double lng(int i) { return a[i * 3 + 1]; }
    double alt(int i) { return a[i * 3 + 2]; }

    void set(int i, double lat, double lng, double alt) {
        int k = i * 3;
        a[k] = lat;
        a[k + 1] = lng;
        a[k + 2] = alt;
    }

    void add(double lat, double lng, double alt) {
        if ((n + 1) * 3 > a.length) {
            double[] b = new double[(n + 1 + (n >> 1)) * 3];
            System.arraycopy(a, 0, b, 0, n * 3);
            a = b;
        }
        set(n++, lat, lng, alt);
    }

    void remove(int i) {
        System.arraycopy(a, (i + 1) * 3, a, i * 3, (n - i - 1) * 3);
        n--;
    }

    /** Move o ponto from para to (clamp em [0, n-1]); devolve o destino efetivo. */
    int move(int from, int to) {
        to = Math.max(0, Math.min(n - 1, to));
        if (to == from) return to;
        int k = from * 3;
        double lat = a[k], lng = a[k + 1], alt = a[k + 2];
        if (to > from) System.arraycopy(a, (from + 1) * 3, a, k, (to - from) * 3);
        else System.arraycopy(a, to * 3, a, (to + 1) * 3, (from - to) * 3);
        set(to, lat, lng, alt);
        return to;
    }

    double[] toArray() {
        double[] out = new double[n * 3];
        System.arraycopy(a, 0, out, 0, n * 3);
        return out;
    }

    /** Distância total em metros. */
    double length() {
        double sum = 0;
        for (int i = 1; i < n; i++) sum += Geo.distance(lat(i - 1), lng(i - 1), lat(i), lng(i));
        return sum;
    }

    /** Tempo para percorrer meters a kmh; 0 se a velocidade não for positiva. */
    static long etaMs(double meters, float kmh) {
        if (!(kmh > 0)) return 0;
        return Math.round(meters / (kmh / 3.6) * 1000);
    }
}
