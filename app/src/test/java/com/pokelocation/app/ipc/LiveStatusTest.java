package com.pokelocation.app.ipc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.pokelocation.app.model.Coord;

import org.junit.Test;

public class LiveStatusTest {
    @Test public void engineOnlyWhenRunningWithValidLatLng() {
        Coord c = LiveStatus.engine(true, 1.5, -2.5, 30);
        assertEquals(1.5, c.lat, 0);
        assertEquals(-2.5, c.lng, 0);
        assertEquals(30, c.alt, 0);
        assertNull(LiveStatus.engine(false, 1.5, -2.5, 30));   // resposta fria: posição das prefs, quem decide é o fallback
        assertNull(LiveStatus.engine(true, Double.NaN, 0, 0));
        assertNull(LiveStatus.engine(true, 91, 0, 0));
    }
}
