package com.pokelocation.app.pogo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class PogoListActivityTest {
    private static Map<String, String> push(String... kv) {
        Map<String, String> d = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) d.put(kv[i], kv[i + 1]);
        return d;
    }

    @Test
    public void alertPushTextBecomesMonWithIconFields() {
        Pogo.Mon m = PogoListActivity.alertMon(push("rc", "abc", "n", "Dratini", "id", "147", "f", "NORMAL", "c", "",
                "iv", "97.8", "cp", "512", "d", "1700000000000", "s", "1"));
        assertEquals("abc", m.reveal);
        assertEquals("Dratini", m.name);
        assertEquals(147, m.id);
        assertEquals("NORMAL", m.form);
        assertNull(m.costume);
        assertEquals(97.8, m.iv, 0);
        assertEquals(512, m.cp);
        assertEquals(1_700_000_000_000L, m.despawnMs);
        assertTrue(m.shiny);
        assertTrue(Double.isNaN(m.lat));
    }

    @Test
    public void unknownIsDefaultAndNoIdStillOpens() {
        Pogo.Mon m = PogoListActivity.alertMon(push("rc", "abc", "n", "Dratini", "iv", "", "d", "x"));
        assertEquals("abc", m.reveal);
        assertEquals("Dratini", m.name);
        assertEquals(0, m.id);
        assertEquals(-1, m.iv, 0);
        assertEquals(0, m.cp);
        assertEquals(0, m.despawnMs);
        assertFalse(m.shiny);
    }
}
