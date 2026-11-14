package com.pokelocation.app.pogo;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** A coluna do joystick no modo alertas, sem View: modos, lista sem repetidos, filtro, páginas e janela de bitmaps. */
final class PogoAlertCol {
    static final int MODE_POKEMON = 0, MODE_CANDY = 1, MODE_ALERTS = 2, PAGE = 50, MAX = 200, ROWS = 10;

    private PogoAlertCol() {}

    static int mode(int saved) {
        return saved >= MODE_POKEMON && saved <= MODE_ALERTS ? saved : MODE_POKEMON;
    }

    static int[] others(int mode) {
        return mode == MODE_POKEMON ? new int[]{MODE_CANDY, MODE_ALERTS}
                : mode == MODE_CANDY ? new int[]{MODE_POKEMON, MODE_ALERTS} : new int[]{MODE_POKEMON, MODE_CANDY};
    }

    /** O push sem "h" não foi gravado no histórico; aí só o reveal identifica o spawn. */
    static boolean same(Pogo.Mon a, Pogo.Mon b) {
        if (a.historyId != null && b.historyId != null) return a.historyId.equals(b.historyId);
        return a.reveal != null && a.reveal.equals(b.reveal);
    }

    private static boolean has(List<Pogo.Mon> l, Pogo.Mon m) {
        for (Pogo.Mon x : l) if (same(x, m)) return true;
        return false;
    }

    static boolean push(List<Pogo.Mon> l, Pogo.Mon m) {
        if (has(l, m)) return false;
        l.add(0, m);
        if (l.size() > MAX) l.subList(MAX, l.size()).clear();
        return true;
    }

    /** early vem na ordem de chegada: empurrado um a um, o último fica no topo. */
    static List<Pogo.Mon> first(List<Pogo.Mon> early, List<Pogo.Mon> page) {
        List<Pogo.Mon> out = new ArrayList<>(page.subList(0, Math.min(page.size(), MAX)));
        for (Pogo.Mon m : early) push(out, m);
        return out;
    }

    static int append(List<Pogo.Mon> l, List<Pogo.Mon> page) {
        int added = 0;
        for (Pogo.Mon m : page) {
            if (l.size() >= MAX) break;
            if (has(l, m)) continue;
            l.add(m);
            added++;
        }
        return added;
    }

    static void visible(List<Pogo.Mon> all, boolean alive, Predicate<Pogo.Mon> wants, long now, List<Pogo.Mon> out) {
        out.clear();
        for (Pogo.Mon m : all) if (!(alive && PogoText.gone(m.despawnMs, now)) && wants.test(m)) out.add(m);
    }

    static boolean needMore(int shown, String next, boolean busy, boolean stuck) {
        return shown < ROWS && next != null && !busy && !stuck;
    }

    static boolean atEnd(int scrollY, int viewH, int contentH, int slack) {
        return scrollY + viewH >= contentH - slack;
    }

    /** Antes do layout a altura é 0: usa a da coluna cheia para não deixar tudo sem foto. */
    static int[] window(int scrollY, int viewH, int step, int n) {
        int h = viewH > 0 ? viewH : ROWS * step;
        int to = Math.min(n, (scrollY + 2 * h + step - 1) / step);
        int from = Math.min(to, Math.max(0, scrollY - h) / step);
        return new int[]{from, to};
    }

    static int colMax(int avail, int size, int gap, int mode) {
        return mode == MODE_ALERTS ? Math.min(avail, ROWS * (size + gap)) : avail;
    }

    static boolean filtered(PogoFilter species, boolean alive) {
        return alive || !species.pokemonIds.isEmpty() || !species.speciesForms.isEmpty();
    }
}
