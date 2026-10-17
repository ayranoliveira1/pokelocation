package com.pokelocation.app.ipc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.pokelocation.app.ui.main.MainActivity;

import org.junit.Test;

public class IntentsTest {
    @Test
    public void mainActivityNameMatchesClass() {
        assertEquals(MainActivity.class.getName(), Intents.MAIN_ACTIVITY);
    }

    @Test
    public void validLatLngRejectsNaNInfinityAndOutOfRange() {
        assertTrue(Intents.validLatLng(-90, 180));
        assertFalse(Intents.validLatLng(Double.NaN, 0));
        assertFalse(Intents.validLatLng(0, Double.POSITIVE_INFINITY));
        assertFalse(Intents.validLatLng(500, 0));
        assertFalse(Intents.validLatLng(0, -180.5));
    }
}
