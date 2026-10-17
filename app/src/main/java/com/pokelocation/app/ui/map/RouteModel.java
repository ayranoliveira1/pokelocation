package com.pokelocation.app.ui.map;

import com.pokelocation.app.geo.Geo;

import java.util.Arrays;

/**
 * Estado puro da rota do mapa (sem android.*): pontos intercalados lat,lng,alt; índice 0 = início.
 * {@code next} = índice absoluto do próximo alvo de uma rota retomada (-1 = nenhum), como no MockEngine.
 */
final class RouteModel {
    static final int START_PICK = 0, START_ROUTE = 1, START_TELEPORT = 2, START_WALK = 3;

    private double[] p = new double[48];
    private int n;
    int next = -1;

    int size() { return n; }
    boolean hasStart() { return n > 0; }
    boolean hasWaypoints() { return n > 1; }
    double lat(int i) { return p[i * 3]; }
    double lng(int i) { return p[i * 3 + 1]; }
    double alt(int i) { return p[i * 3 + 2]; }

    void setStart(double lat, double lng, double alt) {
        if (n == 0) n = 1;
        set(0, lat, lng, alt);
    }

    void setAlt(int i, double alt) { p[i * 3 + 2] = alt; }

    /** Adiciona no fim; sem início, o ponto vira o início. */
    void add(double lat, double lng, double alt) {
        ensure(n + 1);
        set(n++, lat, lng, alt);
    }

    void load(double[] pts, int nextIndex) {
        int c = pts == null ? 0 : pts.length / 3;
        ensure(c);
        if (c > 0) System.arraycopy(pts, 0, p, 0, c * 3);
        n = c;
        next = nextIndex >= 0 && nextIndex < c ? nextIndex : -1;
    }

    void move(int i, double lat, double lng) {
        p[i * 3] = lat;
        p[i * 3 + 1] = lng;
    }

    /** Copy: insere o ponto médio entre i-1 e i (i ≥ 1). Devolve o índice do novo ponto (= i), ou -1. */
    int insertMid(int i) {
        if (i < 1 || i >= n) return -1;
        double[] out = new double[2];
        Geo.interpolate(lat(i - 1), lng(i - 1), lat(i), lng(i), 0.5, out);
        double alt = midAlt(alt(i - 1), alt(i));
        ensure(n + 1);
        System.arraycopy(p, i * 3, p, (i + 1) * 3, (n - i) * 3);
        set(i, out[0], out[1], alt);
        n++;
        if (next >= i) next++;
        return i;
    }

    /** Início e próximo alvo não podem ser apagados. */
    boolean canDelete(int i) { return i >= 1 && i < n && i != next; }

    boolean delete(int i) {
        if (!canDelete(i)) return false;
        System.arraycopy(p, (i + 1) * 3, p, i * 3, (n - i - 1) * 3);
        n--;
        if (next > i) next--;
        return true;
    }

    void clearWaypoints() {
        if (n > 1) n = 1;
        next = -1;
    }

    void clear() {
        n = 0;
        next = -1;
    }

    double[] toArray() { return Arrays.copyOf(p, n * 3); }

    /** Altitude do ponto médio: média; a que existir; NaN se nenhuma. */
    static double midAlt(double a, double b) {
        if (Double.isNaN(a)) return b;
        if (Double.isNaN(b)) return a;
        return (a + b) / 2;
    }

    static int startAction(boolean pick, boolean hasWaypoints, boolean teleportWalk, boolean setRoute, int startMode) {
        if (pick) return START_PICK;
        if (hasWaypoints) return START_ROUTE;
        if (!teleportWalk && !(setRoute && startMode == 1)) return START_TELEPORT;
        return START_WALK;
    }

    /** Linha vermelha atual → alvo. */
    static boolean showWalkLine(boolean routeOn, boolean teleportWalk, boolean resuming, int startMode) {
        return (routeOn || teleportWalk) && (resuming || startMode == 1 || teleportWalk);
    }

    private void set(int i, double lat, double lng, double alt) {
        p[i * 3] = lat;
        p[i * 3 + 1] = lng;
        p[i * 3 + 2] = alt;
    }

    private void ensure(int count) {
        if (count * 3 > p.length) p = Arrays.copyOf(p, Math.max(count * 3, p.length * 2));
    }
}
