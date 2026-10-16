package com.pokelocation.app.pogo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PogoListActivityTest {
    @Test
    public void alertPushTextBecomesMonUnknownIsDefault() {
        Pogo.Mon m = PogoListActivity.alertMon("abc", "Dratini", "97.8", "512", "1700000000000", "1");
        assertEquals("abc", m.reveal);
        assertEquals("Dratini", m.name);
        assertEquals(97.8, m.iv, 0);
        assertEquals(512, m.cp);
        assertEquals(1_700_000_000_000L, m.despawnMs);
        assertTrue(m.shiny);
        assertTrue(Double.isNaN(m.lat));
        m = PogoListActivity.alertMon("abc", "Dratini", "", null, "x", null);
        assertEquals(-1, m.iv, 0);
        assertEquals(0, m.cp);
        assertEquals(0, m.despawnMs);
        assertFalse(m.shiny);
    }
}
