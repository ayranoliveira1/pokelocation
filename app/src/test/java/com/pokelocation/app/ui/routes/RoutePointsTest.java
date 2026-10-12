package com.pokelocation.app.ui.routes;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class RoutePointsTest {
    private static RoutePoints of(int n) {
        RoutePoints p = new RoutePoints(null);
        for (int i = 0; i < n; i++) p.add(i, i * 10, Double.NaN);
        return p;
    }

    private static double[] lats(RoutePoints p) {
        double[] out = new double[p.size()];
        for (int i = 0; i < out.length; i++) out[i] = p.lat(i);
        return out;
    }

    @Test public void addGrowsAndKeepsOrder() {
        RoutePoints p = of(10);
        assertEquals(10, p.size());
        assertArrayEquals(new double[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9}, lats(p), 0);
        assertEquals(90, p.lng(9), 0);
        assertEquals(30, p.toArray().length);
    }

    @Test public void moveDownUpAndClamp() {
        RoutePoints p = of(5);
        assertEquals(3, p.move(0, 3));
        assertArrayEquals(new double[]{1, 2, 3, 0, 4}, lats(p), 0);
        assertEquals(0, p.move(3, -7));
        assertArrayEquals(new double[]{0, 1, 2, 3, 4}, lats(p), 0);
        assertEquals(4, p.move(1, 99));
        assertArrayEquals(new double[]{0, 2, 3, 4, 1}, lats(p), 0);
        assertEquals(10, p.lng(4), 0);
    }

    @Test public void removeShifts() {
        RoutePoints p = of(4);
        p.remove(1);
        assertArrayEquals(new double[]{0, 2, 3}, lats(p), 0);
        p.remove(2);
        assertArrayEquals(new double[]{0, 2}, lats(p), 0);
    }

    @Test public void copiesInput() {
        double[] src = {1, 2, 3, 4, 5, 6};
        RoutePoints p = new RoutePoints(src);
        p.set(0, 9, 9, 9);
        assertEquals(1, src[0], 0);
        assertEquals(2, p.size());
    }

    @Test public void eta() {
        assertEquals(3_600_000, RoutePoints.etaMs(10_000, 10f));
        assertEquals(0, RoutePoints.etaMs(500, 0f));
    }
}
