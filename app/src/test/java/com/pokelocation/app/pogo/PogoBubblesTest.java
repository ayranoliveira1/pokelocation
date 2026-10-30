package com.pokelocation.app.pogo;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

public class PogoBubblesTest {
    private static final int W = 1080, H = 2400, COL_W = 150, COL_H = 900, FULL = 1400, M = 20, TOP = 100;
    /** O joystick da gaveta no padrão: encostado à direita, na metade da tela. */
    private static final int[] STICK = {W - 300, H / 2, 300, 500};

    private static Pogo.Mon mon(long despawn) {
        Pogo.Mon m = new Pogo.Mon();
        m.despawnMs = despawn;
        return m;
    }

    @Test public void defaultTopCornerByMode() {
        assertArrayEquals(new int[]{M, TOP}, PogoBubbles.place(true, -1, -1, COL_W, COL_H, FULL, W, H, M, TOP, null));
        assertArrayEquals(new int[]{W - COL_W - M, TOP}, PogoBubbles.place(false, -1, -1, COL_W, COL_H, FULL, W, H, M, TOP, null));
    }

    /** Com 10 bolhas a coluna do canto chegaria ao joystick; ela desce ao lado dele, por dentro. */
    @Test public void drawerDefaultNeverReachesTheStick() {
        assertArrayEquals(new int[]{W - 300 - COL_W - M, TOP}, PogoBubbles.place(false, -1, -1, COL_W, COL_H, FULL, W, H, M, TOP, STICK));
        assertArrayEquals(new int[]{W - COL_W - M, TOP}, PogoBubbles.place(false, -1, -1, COL_W, COL_H, H / 2 - TOP, W, H, M, TOP, STICK)); // cabe acima
        int[] left = {0, H / 2, 300, 500};   // arrastado para a esquerda: o canto já não o encosta
        assertArrayEquals(new int[]{W - COL_W - M, TOP}, PogoBubbles.place(false, -1, -1, COL_W, COL_H, FULL, W, H, M, TOP, left));
        int[] wide = {M, 0, W - M, H};   // nem à esquerda cabe: à direita dele, até onde der
        assertArrayEquals(new int[]{W - COL_W, TOP}, PogoBubbles.place(false, -1, -1, COL_W, COL_H, FULL, W, H, M, TOP, wide));
    }

    @Test public void savedPositionWinsUnlessOffScreen() {
        assertArrayEquals(new int[]{400, 500}, PogoBubbles.place(true, 400, 500, COL_W, COL_H, FULL, W, H, M, TOP, null));
        assertArrayEquals(new int[]{W - 150, H / 2}, PogoBubbles.place(false, W - 150, H / 2, COL_W, COL_H, FULL, W, H, M, TOP, STICK)); // escolha de quem arrastou
        assertArrayEquals(new int[]{M, TOP}, PogoBubbles.place(true, W, 500, COL_W, COL_H, FULL, W, H, M, TOP, null)); // girou ou trocou de tela
        assertArrayEquals(new int[]{400, H - COL_H}, PogoBubbles.place(true, 400, H - 10, COL_W, COL_H, FULL, W, H, M, TOP, null)); // 10 bolhas: sobe
    }

    @Test public void opensTowardTheMiddle() {
        assertTrue(PogoBubbles.onLeft(0, COL_W, W));
        assertTrue(PogoBubbles.onLeft(W / 2 - COL_W, COL_W, W));
        assertFalse(PogoBubbles.onLeft(W / 2, COL_W, W));
        assertFalse(PogoBubbles.onLeft(W - COL_W, COL_W, W));
    }

    @Test public void cardOpensOnTheRoomierSideWithinTheScreen() {
        int max = 788;
        assertArrayEquals(new int[]{M + COL_W + M, max}, PogoBubbles.cardBox(M, COL_W, W, M, max));
        assertArrayEquals(new int[]{W - COL_W - 2 * M - max, max}, PogoBubbles.cardBox(W - COL_W - M, COL_W, W, M, max));
        int mid = (W - COL_W) / 2;
        assertArrayEquals(new int[]{mid + COL_W + M, mid - 2 * M}, PogoBubbles.cardBox(mid, COL_W, W, M, max));
        assertArrayEquals(new int[]{M, 715 - 2 * M}, PogoBubbles.cardBox(715, COL_W, W, M, max)); // QA: cobria a coluna
    }

    @Test public void countTogglesFiveAndTen() {
        assertEquals(10, PogoBubbles.nextCount(5));
        assertEquals(5, PogoBubbles.nextCount(10));
        assertEquals(5, PogoBubbles.nextCount(7)); // valor salvo estranho volta ao padrão
    }

    @Test public void nextDespawnOnlyAmongTheShown() {
        long now = 1_000_000;
        List<Pogo.Mon> l = new ArrayList<>(Arrays.asList(mon(now + 9000), mon(0), mon(now + 4000), mon(now + 1000)));
        assertEquals(now + 4000, PogoBubbles.nextDespawn(l, 3, now)); // o 4º não está na coluna
        assertEquals(now + 1000, PogoBubbles.nextDespawn(l, 10, now));
        assertEquals(0, PogoBubbles.nextDespawn(Arrays.asList(mon(0), mon(now - 1)), 5, now)); // nada a agendar
    }

    @Test public void candyCountCycleRespectsCap() {
        assertEquals(10, PogoBubbles.nextCandyCount(5, 10));
        assertEquals(5, PogoBubbles.nextCandyCount(10, 10));
        assertEquals(25, PogoBubbles.nextCandyCount(10, 25));
        assertEquals(5, PogoBubbles.nextCandyCount(25, 25));
        assertEquals(5, PogoBubbles.nextCandyCount(100, 100));
        assertEquals(5, PogoBubbles.nextCandyCount(5, 5));
        assertEquals(5, PogoBubbles.nextCandyCount(5, 0));
    }

    @Test public void candyCountClampsSaved() {
        assertEquals(10, PogoBubbles.candyCount(25, 10));
        assertEquals(10, PogoBubbles.candyCount(10, 25));
        assertEquals(5, PogoBubbles.candyCount(7, 25));
        assertEquals(25, PogoBubbles.candyCount(50, 30));
        assertEquals(5, PogoBubbles.candyCount(10, -1));
    }

    @Test public void visitedIsCoordsAndExpiry() {
        Map<String, Long> visited = new HashMap<>();
        visited.put("a", 900L);
        assertTrue(PogoBubbles.isVisited(visited, candy("a", 900)));
        assertFalse(PogoBubbles.isVisited(visited, candy("a", 2000))); // spawn novo no mesmo lugar
        assertFalse(PogoBubbles.isVisited(null, candy("a", 900)));
    }

    @Test public void newListDropsVisitedInOrder() {
        Map<String, Long> visited = new HashMap<>();
        visited.put("b", 900L);
        visited.put("d", 900L);
        List<Pogo.Candy> l = new ArrayList<>(Arrays.asList(candy("a", 900), candy("b", 900), candy("c", 900), candy("d", 900), candy("e", 900)));
        PogoBubbles.dropVisited(l, visited);
        assertEquals(Arrays.asList("a", "c", "e"), coords(l));
    }

    @Test public void visitedThatLeftAndCameBackStillDrops() {
        Map<String, Long> visited = new HashMap<>();
        visited.put("a", 900L);
        PogoBubbles.pruneVisited(visited, 500); // "a" fora da lista: continua lembrada
        List<Pogo.Candy> l = new ArrayList<>(Arrays.asList(candy("a", 900), candy("b", 900)));
        PogoBubbles.dropVisited(l, visited);
        assertEquals(Collections.singletonList("b"), coords(l));
    }

    @Test public void pruneForgetsOnlyAfterExpiry() {
        Map<String, Long> visited = new HashMap<>();
        visited.put("a", 1000L);
        visited.put("b", 0L);
        visited.put("c", 500L);
        PogoBubbles.pruneVisited(visited, 999);
        assertEquals(new HashSet<>(Arrays.asList("a", "b")), visited.keySet());
        PogoBubbles.pruneVisited(visited, 1000);
        assertEquals(Collections.singleton("b"), visited.keySet());
    }

    private static List<String> coords(List<Pogo.Candy> l) {
        List<String> out = new ArrayList<>();
        for (Pogo.Candy c : l) out.add(c.coords);
        return out;
    }

    private static Pogo.Candy candy(String coords, long expires) {
        return new Pogo.Candy(coords, null, 0, 0, Double.NaN, expires);
    }
}
