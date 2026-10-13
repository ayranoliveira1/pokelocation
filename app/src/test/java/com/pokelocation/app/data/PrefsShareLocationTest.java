package com.pokelocation.app.data;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PrefsShareLocationTest {
    @Test
    public void shareLocationDefaultsOn() {
        assertEquals(Boolean.TRUE, Prefs.defaultOf(Prefs.SHARE_LOCATION));
    }
}
