package com.pokelocation.app.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.location.Location;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class LocationInjectorTest {

    /** LocationManager falso. Cada falha programada lança uma vez, na próxima chamada do provider. */
    static class TestInjector extends LocationInjector {
        final List<String> added = new ArrayList<>(), sent = new ArrayList<>();
        final Map<String, RuntimeException> addFails = new HashMap<>(), sendFails = new HashMap<>();

        TestInjector() { super(false); }

        @Override void addProvider(String p) {
            added.add(p);
            RuntimeException e = addFails.remove(p);
            if (e != null) throw e;
        }

        @Override void sendLocation(String p, Location l) {
            sent.add(p);
            RuntimeException e = sendFails.remove(p);
            if (e != null) throw e;
        }

        /** register() sem as chamadas que ele mesmo faz contadas como recriação. */
        TestInjector registered() {
            assertTrue(register());
            added.clear();
            return this;
        }

        void tick() { publish(-23.55, -46.63, 760, 1.4f, true, 5, 90, true); }
        int adds(String p) { return Collections.frequency(added, p); }
        int sends(String p) { return Collections.frequency(sent, p); }
    }

    @Test public void recreatesAndResendsWhenSystemRemovedProvider() {
        TestInjector t = new TestInjector().registered();
        t.sendFails.put("gps", new IllegalArgumentException("gps provider is not a test provider"));
        t.tick();
        assertEquals(1, t.adds("gps"));
        assertEquals(2, t.sends("gps"));
        assertEquals(0, t.adds("network"));
        assertEquals(1, t.sends("network"));
        t.tick();
        assertEquals(1, t.adds("gps"));
        assertEquals(3, t.sends("gps"));
    }

    @Test public void failedRecreationRetriesNextTick() {
        TestInjector t = new TestInjector().registered();
        t.sendFails.put("gps", new IllegalArgumentException());
        t.addFails.put("gps", new IllegalArgumentException());
        t.tick();
        t.sendFails.put("gps", new IllegalArgumentException());
        t.tick();
        assertEquals(2, t.adds("gps"));
        assertEquals(3, t.sends("gps"));
    }

    @Test public void normalPathDoesNotRecreate() {
        TestInjector t = new TestInjector().registered();
        t.tick();
        t.tick();
        assertEquals(0, t.added.size());
        assertEquals(2, t.sends("gps"));
    }

    @Test public void securityExceptionDeactivatesProvider() {
        TestInjector t = new TestInjector().registered();
        t.sendFails.put("gps", new IllegalArgumentException());
        t.addFails.put("gps", new SecurityException());
        t.tick();
        assertEquals(1, t.adds("gps"));
        assertEquals(1, t.sends("gps"));
        t.tick();
        assertEquals(1, t.adds("gps"));
        assertEquals(1, t.sends("gps"));
        assertEquals(2, t.sends("network"));
    }

    @Test public void otherExceptionDoesNotRecreate() {
        TestInjector t = new TestInjector().registered();
        t.sendFails.put("gps", new IllegalStateException());
        t.tick();
        assertEquals(0, t.adds("gps"));
        assertEquals(1, t.sends("gps"));
    }

    @Test public void inactiveProviderIsSkipped() {
        TestInjector t = new TestInjector();
        t.addFails.put("passive", new IllegalArgumentException());
        t.registered();
        t.sendFails.put("gps", new IllegalArgumentException());
        t.tick();
        t.tick();
        assertEquals(0, t.adds("passive"));
        assertEquals(0, t.sends("passive"));
        assertEquals(1, t.adds("gps"));
    }
}
