package com.pokelocation.app.geo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GeoTest {
    @Test
    public void distanceSaoPauloRio() {
        // Centro de SP → centro do RJ: ~360,8 km em grande círculo (conferido por projeção equiretangular)
        double d = Geo.distance(-23.5505, -46.6333, -22.9068, -43.1729);
        assertEquals(360_800, d, 360_800 * 0.005);
    }

    @Test
    public void offsetBearingRoundTrip() {
        double[] o = new double[2];
        for (double b = -180; b < 180; b += 37) {
            Geo.offset(-23.55, -46.63, 1234, b, o);
            assertEquals(1234, Geo.distance(-23.55, -46.63, o[0], o[1]), 0.01);
            double diff = ((Geo.bearing(-23.55, -46.63, o[0], o[1]) - b) % 360 + 540) % 360 - 180;
            assertEquals(0, diff, 0.01);
        }
    }

    @Test
    public void bearingRange() {
        double b = Geo.bearing(0, 0.001, 0, 0);   // oeste exato
        assertTrue(b >= -180 && b < 180);
        assertEquals(-90, b, 1e-6);
    }

    @Test
    public void generateCirclePassesThroughOrigin() {
        double[] p = Geo.generate(-23.55, -46.63, 1, 12, 100);
        assertEquals(36, p.length);
        int last = 11;
        assertEquals(0, Geo.distance(-23.55, -46.63, p[last * 3], p[last * 3 + 1]), 0.01);
        assertTrue(Double.isNaN(p[2]));
        // todos a 100 m do centro (100 m ao sul da origem)
        double[] c = new double[2];
        Geo.offset(-23.55, -46.63, 100, 180, c);
        for (int i = 0; i < 12; i++) assertEquals(100, Geo.distance(c[0], c[1], p[i * 3], p[i * 3 + 1]), 0.01);
    }

    @Test
    public void generateRandomStepsWithinOffset() {
        double[] p = Geo.generate(10, 10, 0, 50, 30);
        double lat = 10, lng = 10;
        for (int i = 0; i < 50; i++) {
            assertTrue(Geo.distance(lat, lng, p[i * 3], p[i * 3 + 1]) <= 30.001);
            lat = p[i * 3];
            lng = p[i * 3 + 1];
        }
    }

    @Test
    public void simplifyStraightLineToTwoPoints() {
        int n = 3000;
        double[] p = new double[n * 3];
        for (int i = 0; i < n; i++) {
            p[i * 3] = -23.0 + i * 1e-5;
            p[i * 3 + 1] = -46.0 + i * 1e-5;
            p[i * 3 + 2] = Double.NaN;
        }
        double[] s = Geo.simplify(p, 1);
        assertEquals(6, s.length);
        assertEquals(p[0], s[0], 0);
        assertEquals(p[(n - 1) * 3 + 1], s[4], 0);
    }

    @Test
    public void simplifyKeepsCorner() {
        double[] p = {0, 0, 0, 0, 0.001, 0, 0, 0.002, 0, 0.001, 0.002, 0, 0.002, 0.002, 0};
        assertEquals(9, Geo.simplify(p, 1).length);
    }

    @Test
    public void interpolateMidpoint() {
        double[] o = new double[2];
        Geo.interpolate(0, 0, 0, 10, 0.5, o);
        assertEquals(0, o[0], 1e-9);
        assertEquals(5, o[1], 1e-9);
    }

    @Test
    public void stickToCompass() {
        assertEquals(90, Geo.stickToCompass(0), 1e-6);
        assertEquals(0, Geo.stickToCompass(90), 1e-6);
        assertEquals(270, Geo.stickToCompass(180), 1e-6);
        assertEquals(180, Geo.stickToCompass(-90), 1e-6);
        assertEquals(315, Geo.stickToCompass(135), 1e-6);
    }

    @Test
    public void lengthSumsSegments() {
        double[] p = {0, 0, 0, 0, 1, 0, 0, 2, 0};
        assertEquals(2 * Geo.distance(0, 0, 0, 1), Geo.length(p), 1e-6);
    }
}
