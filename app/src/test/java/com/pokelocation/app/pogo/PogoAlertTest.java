package com.pokelocation.app.pogo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class PogoAlertTest {
    @Test public void titleLikeTheDesign() {
        long now = 1_000_000;
        assertEquals("✨ Dratini 100% CP512 · 12 min", PogoAlert.title("Dratini", "100", "512", now + 11 * 60_000 + 1, now, true));
        assertEquals("Dratini · 1 min", PogoAlert.title("Dratini", "", null, now + 5_000, now, false));
        assertEquals("? 90%", PogoAlert.title(null, "90", "", now - 1, now, false));
    }

    @Test public void pushBecomesTheMonTheIconNeeds() {
        Map<String, String> d = new HashMap<>();
        d.put("t", "p");
        d.put("n", "Rattata");
        assertNull(PogoAlert.mon(d)); // servidor antigo, sem id: sem foto
        d.put("id", "19");
        d.put("f", "Alola");
        d.put("c", "");
        d.put("s", "1");
        Pogo.Mon m = PogoAlert.mon(d);
        assertEquals(19, m.id);
        assertEquals("Rattata", m.name);
        assertEquals("Alola", m.form);
        assertNull(m.costume);
        assertTrue(m.shiny);
        assertEquals(PogoIcons.PM + "pm19.fALOLA.s.icon.png", PogoIcons.urls(m.id, m.name, m.form, m.costume, m.shiny).get(0));
        d.put("id", "x");
        assertNull(PogoAlert.mon(d));
        d.put("id", "0");
        assertNull(PogoAlert.mon(d));
        d.put("id", "25");
        d.remove("s");
        assertFalse(PogoAlert.mon(d).shiny);
    }
}
