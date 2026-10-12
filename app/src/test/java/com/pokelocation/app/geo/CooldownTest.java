package com.pokelocation.app.geo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CooldownTest {
    @Test
    public void tierBoundaries() {
        double[] lower = {0, 10, 100, 500, 1_000, 5_000, 10_000, 25_000, 30_000, 65_000, 81_000, 100_000, 250_000,
                500_000, 750_000, 1_000_000};
        for (int t = 0; t < Cooldown.TIERS; t++) {
            assertEquals(t, Cooldown.tier(lower[t]));
            if (t > 0) assertEquals(t - 1, Cooldown.tier(lower[t] - 0.001));
        }
        assertEquals(15, Cooldown.tier(40_000_000));
    }

    @Test
    public void cooldownTable() {
        assertEquals(0, Cooldown.cooldownMs(0));
        assertEquals(3_000, Cooldown.cooldownMs(1));
        assertEquals(120_000, Cooldown.cooldownMs(4));
        assertEquals(4_500_000, Cooldown.cooldownMs(13));
        assertEquals(7_200_000, Cooldown.cooldownMs(15));
        assertEquals("< 10 m", Cooldown.tierLabel(0));
        assertEquals(">= 1000 km", Cooldown.tierLabel(15));
    }

    @Test
    public void formats() {
        assertEquals("999m", Cooldown.formatDistance(999.9));
        assertEquals("1km 0m", Cooldown.formatDistance(1000));
        assertEquals("12km 345m", Cooldown.formatDistance(12_345.6));
        assertEquals("0s", Cooldown.formatDuration(0));
        assertEquals("30s", Cooldown.formatDuration(30_000));
        assertEquals("1h 15m", Cooldown.formatDuration(4_500_000));
        assertEquals("2h", Cooldown.formatDuration(7_200_000));
        assertEquals("5s", Cooldown.formatHms(5));
        assertEquals("1m 0s", Cooldown.formatHms(60));
        assertEquals("1h 0m 5s", Cooldown.formatHms(3605));
    }

    @Test
    public void readyExactlyAtCooldown() {
        assertTrue(Cooldown.ready(5, 0));
        assertFalse(Cooldown.ready(50, 2_999));
        assertTrue(Cooldown.ready(50, 3_000));
    }
}
