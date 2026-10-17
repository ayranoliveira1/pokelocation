package com.pokelocation.app.pogo;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PogoLayerTest {
    private static final long NOW = 1_000_000_000L;

    @Test
    public void boxRoundsTo4PlacesSoTinyPansAreTheSameBox() {
        assertArrayEquals(new double[]{40.8, -74.0201, 40.7, -73.93},
                PogoLayer.box(40.800004, -74.020149, 40.69999, -73.93004), 0);
        assertArrayEquals(PogoLayer.box(40.80001, -74.02, 40.7, -73.93), PogoLayer.box(40.80004, -74.02, 40.7, -73.93), 0);
    }

    @Test
    public void antimeridianIsNotFetched() {
        assertNull(PogoLayer.box(10, 179, -10, -179));
    }

    @Test
    public void diagonalLimitIs12500km() {
        assertFalse(PogoLayer.tooBig(PogoLayer.box(40.8, -74.02, 40.7, -73.93)));
        assertFalse(PogoLayer.tooBig(new double[]{0, 0, 0, 112}));   // ~12.454 km no equador
        assertTrue(PogoLayer.tooBig(new double[]{0, 0, 0, 113}));    // ~12.565 km
    }

    @Test
    public void newBoxFetchesNowSameBoxWaitsForTheTick() {
        assertTrue(PogoLayer.wait(false, NOW - 1_000, 0, NOW) <= 0);
        assertEquals(59_000, PogoLayer.wait(true, NOW - 1_000, 0, NOW));
        assertTrue(PogoLayer.wait(true, NOW - 60_000, 0, NOW) <= 0);
    }

    @Test
    public void retryAfterDefersEvenANewBox() {
        assertEquals(46_000, PogoLayer.wait(false, NOW - 1_000, NOW + 46_000, NOW));
        assertEquals(90_000, PogoLayer.wait(true, NOW, NOW + 90_000, NOW));
    }

    @Test
    public void despawnedAreGoneUnknownStays() {
        assertTrue(PogoText.gone(NOW, NOW));
        assertFalse(PogoText.gone(NOW + 1, NOW));
        assertFalse(PogoText.gone(0, NOW));
    }

    @Test
    public void levelAndClockFormat() {
        assertEquals("30", PogoText.level(30));
        assertEquals("30.5", PogoText.level(30.5));
        assertEquals("12:05", PogoText.clock(725_400));
        assertEquals("0:00", PogoText.clock(-5));
    }

    @Test
    public void cardStaysInsideTheMapAndFlipsAboveWhenNoRoomBelow() {
        // mapa 1000x800, card 300x200, folga 20, margem 10
        assertArrayEquals(new int[]{350, 420}, PogoLayer.place(500, 400, 20, 300, 200, 1000, 800, 10));
        assertArrayEquals(new int[]{690, 420}, PogoLayer.place(990, 400, 20, 300, 200, 1000, 800, 10));   // borda direita
        assertArrayEquals(new int[]{10, 420}, PogoLayer.place(5, 400, 20, 300, 200, 1000, 800, 10));      // borda esquerda
        assertArrayEquals(new int[]{350, 480}, PogoLayer.place(500, 700, 20, 300, 200, 1000, 800, 10));   // embaixo: abre acima
        assertArrayEquals(new int[]{350, 10}, PogoLayer.place(500, 100, 20, 300, 790, 1000, 800, 10));    // não cabe: topo
    }
}
