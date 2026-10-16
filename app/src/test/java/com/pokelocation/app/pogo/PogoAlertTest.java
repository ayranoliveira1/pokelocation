package com.pokelocation.app.pogo;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PogoAlertTest {
    @Test public void titleLikeTheDesign() {
        long now = 1_000_000;
        assertEquals("✨ Dratini 100% CP512 · 12 min", PogoAlert.title("Dratini", "100", "512", now + 11 * 60_000 + 1, now, true));
        assertEquals("Dratini · 1 min", PogoAlert.title("Dratini", "", null, now + 5_000, now, false));
        assertEquals("? 90%", PogoAlert.title(null, "90", "", now - 1, now, false));
    }
}
