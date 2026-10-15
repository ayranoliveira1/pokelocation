package com.pokelocation.app.social.loc;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LocationSharerTest {
    @Test public void stoppedFriendRepublishesEvery9s() {
        assertTrue(LocationSharer.due(Double.NaN, Double.NaN, 0, -23.5, -46.6, 1));
        assertFalse(LocationSharer.due(-23.5, -46.6, 1000, -23.5, -46.6, 9_999));
        assertTrue(LocationSharer.due(-23.5, -46.6, 1000, -23.5, -46.6, 10_000));
        assertTrue(LocationSharer.due(-23.5, -46.6, 1000, -23.50006, -46.6, 1001)); // ~6,7 m
    }
}
