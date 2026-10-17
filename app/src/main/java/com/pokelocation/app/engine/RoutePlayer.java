package com.pokelocation.app.engine;

/**
 * Reprodução de rota, lógica pura (sem android.*). Pontos intercalados lat,lng,alt (sem cópia).
 * Modelo cur/next/dir: cur = último waypoint alcançado (-1 antes do 1º), next = próximo alvo,
 * dir = sentido (só Reverse usa -1).
 */
public final class RoutePlayer {
    private double[] pts;
    private int n, cur = -1, next = -1, dir = 1, mode;

    public void start(double[] pts, int mode) {
        set(pts, mode);
        cur = -1;
        next = n > 0 ? 0 : -1;
        dir = 1;
    }

    /** nextIndex absoluto em pts; reversed = andando para índices menores (só em Reverse). */
    public void resume(double[] pts, int mode, boolean reversed, int nextIndex) {
        set(pts, mode);
        if (n == 0) { next = -1; return; }
        dir = mode == MockEngine.ROUTE_REVERSE && reversed ? -1 : 1;
        next = nextIndex < 0 ? 0 : Math.min(nextIndex, n - 1);
        int c = next - dir;
        cur = c < 0 || c >= n ? -1 : c;
    }

    /** Chegou em next: cur ← next e avança. false = fim da rota (Stop no último ponto, ou n = 1). */
    public boolean arrive() {
        if (n == 0) return false;
        cur = next;
        if (n == 1 || (mode != MockEngine.ROUTE_LOOP && mode != MockEngine.ROUTE_REVERSE && cur >= n - 1)) return false;
        next = stepFrom(cur);
        return true;
    }

    /**
     * ROUTE_PREV: sobre cur volta mais um no sentido atual, senão volta a cur.
     * Stop trava nas pontas, Loop dá a volta, Reverse reflete e inverte o sentido. Atualiza cur/next; devolve o alvo.
     */
    public int previous(boolean atCur) {
        if (n == 0) return -1;
        int t = atCur ? cur - dir : cur;
        if (mode == MockEngine.ROUTE_LOOP) {
            t = Math.floorMod(t, n);
        } else if (mode == MockEngine.ROUTE_REVERSE && cur >= 0) {   // sem waypoint alcançado: clamp em 0, sem inverter
            if (t < 0) { t = -t; dir = -dir; }
            else if (t >= n) { t = 2 * (n - 1) - t; dir = -dir; }
        }
        t = t < 0 ? 0 : Math.min(t, n - 1);
        cur = t;
        next = stepFrom(t);
        return t;
    }

    public void clear() {
        pts = null;
        n = 0;
        cur = next = -1;
        dir = 1;
        mode = 0;
    }

    public boolean active() { return n > 0; }
    public int cur() { return cur; }
    public int nextIndex() { return next; }
    public boolean reversed() { return dir < 0; }
    public int mode() { return mode; }
    public int total() { return n; }
    public double[] points() { return pts; }

    /** Progresso exibido "i/n" (1-based). */
    public int progress1() { return n == 0 ? 0 : Math.max(cur, 0) + 1; }

    /** Alvos restantes (inclui next): Stop e Loop até o fim da volta (Loop recomeça em n), Reverse até a ponta no sentido atual. */
    public int remaining() {
        if (n == 0) return 0;
        if (mode == MockEngine.ROUTE_REVERSE) return dir > 0 ? n - next : next + 1;
        return n - next;
    }

    public double lat(int i) { return pts[i * 3]; }
    public double lng(int i) { return pts[i * 3 + 1]; }
    public double alt(int i) { return pts[i * 3 + 2]; }

    private void set(double[] p, int m) {
        pts = p;
        n = p == null ? 0 : p.length / 3;
        mode = m;
    }

    private int stepFrom(int c) {
        if (n == 1) return 0;
        if (mode == MockEngine.ROUTE_LOOP) return (c + 1) % n;
        if (mode == MockEngine.ROUTE_REVERSE) {
            int t = c + dir;
            if (t < 0 || t >= n) { dir = -dir; t = c + dir; }
            return t;
        }
        return Math.min(c + 1, n - 1);
    }
}
