package com.pokelocation.app.pogo;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class PogoAlertColTest {
    private static Pogo.Mon mon(String h, String rc) {
        Pogo.Mon m = new Pogo.Mon();
        m.historyId = h;
        m.reveal = rc;
        return m;
    }

    private static Pogo.Mon mon(String h, long despawn, int id) {
        Pogo.Mon m = mon(h, "rc" + h);
        m.despawnMs = despawn;
        m.id = id;
        return m;
    }

    private static List<Pogo.Mon> list(int n) {
        List<Pogo.Mon> l = new ArrayList<>();
        for (int i = 0; i < n; i++) l.add(mon("h" + i, "rc" + i));
        return l;
    }

    @Test public void othersAreTheTwoModesYouAreNotIn() {
        assertArrayEquals(new int[]{PogoAlertCol.MODE_CANDY, PogoAlertCol.MODE_ALERTS}, PogoAlertCol.others(PogoAlertCol.MODE_POKEMON));
        assertArrayEquals(new int[]{PogoAlertCol.MODE_POKEMON, PogoAlertCol.MODE_ALERTS}, PogoAlertCol.others(PogoAlertCol.MODE_CANDY));
        assertArrayEquals(new int[]{PogoAlertCol.MODE_POKEMON, PogoAlertCol.MODE_CANDY}, PogoAlertCol.others(PogoAlertCol.MODE_ALERTS));
    }

    @Test public void unknownSavedModeFallsBackToPokemon() {
        assertEquals(PogoAlertCol.MODE_POKEMON, PogoAlertCol.mode(7));
        assertEquals(PogoAlertCol.MODE_POKEMON, PogoAlertCol.mode(-1));
        assertEquals(PogoAlertCol.MODE_ALERTS, PogoAlertCol.mode(2));
    }

    @Test public void sameByHistoryIdThenByRevealCode() {
        assertTrue(PogoAlertCol.same(mon("1", "a"), mon("1", "b")));
        assertFalse(PogoAlertCol.same(mon("1", "a"), mon("2", "a")));
        assertTrue(PogoAlertCol.same(mon(null, "a"), mon("2", "a")));
        assertFalse(PogoAlertCol.same(mon(null, "a"), mon("2", "b")));
        assertFalse(PogoAlertCol.same(mon(null, null), mon(null, null)));
    }

    @Test public void pushGoesOnTopOnlyOnce() {
        List<Pogo.Mon> l = list(3);
        Pogo.Mon m = mon("new", "x");
        assertTrue(PogoAlertCol.push(l, m));
        assertSame(m, l.get(0));
        assertFalse(PogoAlertCol.push(l, mon("new", "y")));
        assertEquals(4, l.size());
    }

    @Test public void pushTrimsTheTailAtTheCap() {
        List<Pogo.Mon> l = list(PogoAlertCol.MAX);
        Pogo.Mon oldest = l.get(PogoAlertCol.MAX - 1);
        assertTrue(PogoAlertCol.push(l, mon("new", "x")));
        assertEquals(PogoAlertCol.MAX, l.size());
        assertFalse(l.contains(oldest));
    }

    @Test public void firstPageKeepsPushesThatArrivedWhileLoading() {
        List<Pogo.Mon> page = list(3);
        Pogo.Mon a = mon("a", "ra"), b = mon("b", "rb");
        List<Pogo.Mon> out = PogoAlertCol.first(Arrays.asList(a, mon("h0", "rc0"), b), page);
        assertEquals(5, out.size());
        assertSame(b, out.get(0));
        assertSame(a, out.get(1));
        assertSame(page.get(0), out.get(2));
    }

    @Test public void appendSkipsDuplicatesAndKeepsOrder() {
        List<Pogo.Mon> l = list(2);
        Pogo.Mon x = mon("x", "rx"), y = mon("y", "ry");
        assertEquals(2, PogoAlertCol.append(l, Arrays.asList(mon("h1", "rc1"), x, y)));
        assertEquals(4, l.size());
        assertSame(x, l.get(2));
        assertSame(y, l.get(3));
        List<Pogo.Mon> full = list(PogoAlertCol.MAX);
        assertEquals(0, PogoAlertCol.append(full, Arrays.asList(x, y)));
        assertEquals(PogoAlertCol.MAX, full.size());
    }

    @Test public void aliveDropsOnlyDespawned() {
        long now = 1_000;
        Pogo.Mon unknown = mon("u", 0, 1), gone = mon("g", now, 1), live = mon("l", now + 1, 1);
        List<Pogo.Mon> all = Arrays.asList(unknown, gone, live), out = new ArrayList<>();
        PogoAlertCol.visible(all, true, m -> true, now, out);
        assertEquals(Arrays.asList(unknown, live), out);
        PogoAlertCol.visible(all, false, m -> true, now, out);
        assertEquals(all, out);
    }

    @Test public void visibleAppliesTheSpeciesPredicate() {
        Pogo.Mon pika = mon("p", 0, 25), rat = mon("r", 0, 19);
        List<Pogo.Mon> out = new ArrayList<>();
        PogoAlertCol.visible(Arrays.asList(pika, rat), false, m -> m.id == 25, 0, out);
        assertEquals(Arrays.asList(pika), out);
    }

    @Test public void needMoreOnlyWhenShortWithCursorAndIdle() {
        assertTrue(PogoAlertCol.needMore(9, "n", false, false));
        assertFalse(PogoAlertCol.needMore(PogoAlertCol.ROWS, "n", false, false));
        assertFalse(PogoAlertCol.needMore(9, null, false, false));
        assertFalse(PogoAlertCol.needMore(9, "n", true, false));
        assertFalse(PogoAlertCol.needMore(9, "n", false, true));
    }

    @Test public void atEndWithinSlack() {
        assertTrue(PogoAlertCol.atEnd(900, 1000, 2000, 100));
        assertFalse(PogoAlertCol.atEnd(899, 1000, 2000, 100));
        assertTrue(PogoAlertCol.atEnd(0, 1000, 500, 0));
    }

    @Test public void windowHasOneScreenOfMarginAndClamps() {
        assertArrayEquals(new int[]{0, 20}, PogoAlertCol.window(0, 1000, 100, 200));
        assertArrayEquals(new int[]{40, 70}, PogoAlertCol.window(5000, 1000, 100, 200));
        assertArrayEquals(new int[]{40, 60}, PogoAlertCol.window(5000, 1000, 100, 60));
        assertArrayEquals(new int[]{0, 0}, PogoAlertCol.window(0, 1000, 100, 0));
        assertArrayEquals(new int[]{0, 20}, PogoAlertCol.window(0, 0, 100, 200));
    }

    @Test public void alertsColumnHeightIsTenBubbles() {
        assertEquals(1100, PogoAlertCol.colMax(1500, 100, 10, PogoAlertCol.MODE_ALERTS));
        assertEquals(800, PogoAlertCol.colMax(800, 100, 10, PogoAlertCol.MODE_ALERTS));
        assertEquals(1500, PogoAlertCol.colMax(1500, 100, 10, PogoAlertCol.MODE_POKEMON));
        assertEquals(1500, PogoAlertCol.colMax(1500, 100, 10, PogoAlertCol.MODE_CANDY));
    }

    @Test public void filteredWhenAliveOrAnySpecies() {
        assertFalse(PogoAlertCol.filtered(new PogoFilter(), false));
        assertTrue(PogoAlertCol.filtered(new PogoFilter(), true));
        PogoFilter ids = new PogoFilter();
        ids.pokemonIds.add(25);
        assertTrue(PogoAlertCol.filtered(ids, false));
        PogoFilter forms = new PogoFilter();
        forms.speciesForms.add(new int[]{19, 61});
        assertTrue(PogoAlertCol.filtered(forms, false));
    }
}
