package com.pokelocation.app.pogo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.pokelocation.app.geo.Geo;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class PogoPanelTest {
    private static final double LAT = -23.55, LNG = -46.63;

    private static Pogo.Mon at(String name, double lat, double lng) {
        Pogo.Mon m = new Pogo.Mon();
        m.name = name;
        m.lat = lat;
        m.lng = lng;
        return m;
    }

    @Test
    public void boxContainsTheRadiusCircle() {
        double[] b = PogoPanel.around(LAT, LNG, 2000);
        assertEquals(2000, Geo.distance(LAT, LNG, b[0], LNG), 5);
        assertEquals(2000, Geo.distance(LAT, LNG, b[2], LNG), 5);
        assertEquals(2000, Geo.distance(LAT, LNG, LAT, b[1]), 5);
        assertEquals(2000, Geo.distance(LAT, LNG, LAT, b[3]), 5);
        assertTrue(b[0] > b[2] && b[1] < b[3]);
    }

    @Test
    public void nearDropsBoxCornersAndSortsByDistance() {
        double[] b = PogoPanel.around(LAT, LNG, 1000);
        Pogo.Mon corner = at("corner", b[0], b[3]), far = at("far", LAT + 0.008, LNG), close = at("close", LAT + 0.001, LNG);
        List<Pogo.Mon> got = PogoPanel.near(Arrays.asList(corner, far, close), LAT, LNG, 1000);
        assertEquals(2, got.size());
        assertEquals("close", got.get(0).name);
        assertEquals("far", got.get(1).name);
    }

    @Test
    public void rangeChipText() {
        assertEquals("IV ≥ 90", PogoPanel.range("IV", 90.0, null));
        assertEquals("PC ≤ 1500", PogoPanel.range("PC", null, 1500));
        assertEquals("Nível 20–30.5", PogoPanel.range("Nível", 20.0, 30.5));
    }

    @Test
    public void dragOnlyOpensTowardTheBinUpToItsWidth() {
        assertEquals(-30, PogoPanel.slide(0, -30, 72, -1), 0);
        assertEquals(-72, PogoPanel.slide(0, -300, 72, -1), 0);
        assertEquals(0, PogoPanel.slide(0, 40, 72, -1), 0);
        assertEquals(-32, PogoPanel.slide(-72, 40, 72, -1), 0);
        assertEquals(72, PogoPanel.slide(0, 300, 72, 1), 0);
        assertEquals(0, PogoPanel.slide(0, -40, 72, 1), 0);
    }

    @Test
    public void releasePastHalfLocksOpenShortDragGoesBack() {
        assertEquals(-72, PogoPanel.settle(-40, 72), 0);
        assertEquals(0, PogoPanel.settle(-30, 72), 0);
        assertEquals(72, PogoPanel.settle(50, 72), 0);
        assertEquals(0, PogoPanel.settle(0, 72), 0);
    }

    @Test
    public void binTapOnlyInsideTheRevealedBlock() {
        assertTrue(PogoPanel.onBin(950, 50, 16, 0, 1000, 100, -72, 64));   // fora da área do item deslocado [−56, 928]
        assertFalse(PogoPanel.onBin(930, 50, 16, 0, 1000, 100, -72, 64));
        assertFalse(PogoPanel.onBin(950, 150, 16, 0, 1000, 100, -72, 64));
        assertFalse(PogoPanel.onBin(950, 50, 16, 0, 1000, 100, 0, 64));
        assertTrue(PogoPanel.onBin(40, 50, 16, 0, 1000, 100, 72, 64));
        assertFalse(PogoPanel.onBin(950, 50, 16, 0, 1000, 100, 72, 64));
    }
}
