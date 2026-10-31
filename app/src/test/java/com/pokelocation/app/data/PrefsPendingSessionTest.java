package com.pokelocation.app.data;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public class PrefsPendingSessionTest {
    @Test
    public void onlyANewerPendingSessionApplies() {
        assertTrue(Prefs.newer(2000L, 1000L));
        assertTrue(Prefs.newer(2000L, null));
        assertFalse(Prefs.newer(1000L, 2000L));
        assertFalse(Prefs.newer(null, 1000L));
        assertFalse(Prefs.newer(2000, 1000L));    // não é o long gravado pelo :overlay
    }

    @Test
    public void appliedOnce() {
        assertTrue(Arrays.asList(Prefs.SESSION_KEYS).contains(Prefs.LAST_MOCKED_TIME));
        assertFalse(Prefs.newer(2000L, 2000L));
    }
}
