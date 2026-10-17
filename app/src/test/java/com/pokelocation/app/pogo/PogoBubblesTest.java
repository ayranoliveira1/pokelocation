package com.pokelocation.app.pogo;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
}
