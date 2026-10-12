package com.pokelocation.app.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.pokelocation.app.geo.Geo;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public class MockEngineImplTest {
    private static final double NaN = Double.NaN;

    /** Ruído zerado (noisy=false) para resultados determinísticos. */
    private static EngineConfig cfg(int processMs, boolean suspended, boolean saveLocation, boolean noisy) {
        float n = noisy ? 1f : 0f;
        return EngineConfig.forTest(processMs, 1000, 1000, 0.25 * n, 5 * n, n, 3 * n, 2, 5, 6, 10, 10, 40, 75,
                saveLocation, suspended, false, true, 5, false);
    }

    private static EngineConfig cfg() { return cfg(1000, false, false, false); }

    /** Relógio e saída falsos. pump() roda o próximo passo agendado. */
    static class TestEngine extends MockEngineImpl implements MockEngine.Listener, MockEngine.SuspendedPublisher {
        long now = 10_000, wall = 1_000_000, scheduled = -1;
        int publishes, saves, progressCalls;
        double pLat, pLng;
        float pBearing;
        boolean pHasSpeed;
        final List<Integer> countdowns = new ArrayList<>();
        final List<String> states = new ArrayList<>();

        TestEngine(EngineConfig c) {
            super(c);
            setListener(this);
            setSuspendedPublisher(this);
        }

        @Override long uptime() { return now; }
        @Override long wallTime() { return wall; }
        @Override void scheduleAt(long t) { scheduled = t; }
        @Override void cancelSchedule() { scheduled = -1; }
        @Override void registerProviders() {}
        @Override void unregisterProviders() {}
        @Override void saveLive(double la, double ln, double al) { saves++; }
        @Override void publish(double la, double ln, double al, float sp, boolean hs, float acc, float br, boolean hb) {
            publishes++;
            pLat = la;
            pLng = ln;
            pBearing = br;
            pHasSpeed = hs;
        }

        @Override public void publishSuspended(double la, double ln, double al, float sp, float acc, float br) {
            publish(la, ln, al, sp, true, acc, br, true);
        }

        @Override public void onRouteProgress(int i, int n) { progressCalls++; }
        @Override public void onRouteStateChanged(boolean has, boolean playing) { states.add(has + "/" + playing); }
        @Override public void onAutoSuspendCountdown(int s) { countdowns.add(s); }

        void pump() {
            wall += scheduled - now;
            now = scheduled;
            onLoop();
        }
    }

    @Test public void freezeTable() {   // 10 §3.1 fato 1
        assertEquals(30, MockEngineImpl.freezeTicks(100));
        assertEquals(6, MockEngineImpl.freezeTicks(500));
        assertEquals(2, MockEngineImpl.freezeTicks(1000));
        assertEquals(1, MockEngineImpl.freezeTicks(2000));
        assertEquals(0, MockEngineImpl.freezeTicks(3000));
    }

    @Test public void stickMapsToCompassAndSpeed() {   // F39, F57
        float[][] cases = {{90, 0}, {0, 90}, {-90, 180}, {180, 270}};
        for (float[] k : cases) {
            TestEngine e = new TestEngine(cfg());
            e.start(0, 0, NaN);
            e.setStick(k[0], 1f);
            e.pump();
            assertEquals(k[1], e.pBearing, 1e-3);
            assertEquals(10 / 3.6, Geo.distance(0, 0, e.pLat, e.pLng), 1e-3);
        }
        TestEngine half = new TestEngine(cfg());
        half.start(0, 0, NaN);
        half.setStick(90, 0.5f);
        half.pump();
        assertEquals(5 / 3.6, Geo.distance(0, 0, half.pLat, half.pLng), 1e-3);
    }

    @Test public void updateIntervalDecoupledFromProcess() {   // F60: injeta 10×/s, anda 1×/s
        TestEngine e = new TestEngine(cfg(100, false, false, false));
        e.start(0, 0, NaN);
        e.setStick(90, 1f);
        double last = 0;
        int moves = 0;
        for (int i = 0; i < 20; i++) {
            e.pump();
            if (e.pLat != last) { moves++; last = e.pLat; }
        }
        assertEquals(20, e.publishes);
        assertEquals(2, moves);
    }

    @Test public void teleportFreezesSpeedAndPublishesDestination() {   // F58, rep ← pos
        TestEngine e = new TestEngine(cfg());
        e.start(0, 0, NaN);
        e.pump();
        assertTrue(e.pHasSpeed);
        e.teleport(1, 1, NaN);
        e.pump();
        assertFalse(e.pHasSpeed);
        assertEquals(1, e.pLat, 1e-9);
        assertEquals(1, e.pLng, 1e-9);
        e.pump();
        assertFalse(e.pHasSpeed);
        e.pump();
        assertTrue(e.pHasSpeed);
    }

    @Test public void suspendedCountdown() {   // 11 §2.4
        TestEngine e = new TestEngine(cfg(1000, true, false, false));
        e.start(0, 0, NaN);
        assertEquals("[5]", e.countdowns.toString());
        for (int i = 0; i < 4; i++) e.pump();
        assertEquals("[5, 4, 3, 2, 1]", e.countdowns.toString());
        assertEquals(0, e.publishes);              // parado: só injeta no fim
        e.pump();
        assertEquals(1, e.publishes);
        assertEquals("[5, 4, 3, 2, 1, 5]", e.countdowns.toString());

        e.setAutoSuspendActive(false);             // "X": contagem segue girando, sem injeção nem texto
        for (int i = 0; i < 5; i++) e.pump();
        assertEquals(1, e.publishes);
        assertEquals(6, e.countdowns.size());

        e.setStick(90, 1f);                        // stick em uso: injeta a cada segundo
        for (int i = 0; i < 5; i++) e.pump();
        assertEquals(6, e.publishes);

        e.teleport(1, 1, NaN);                     // teleporte: injeção imediata
        assertEquals(7, e.publishes);
        e.injectNow();
        assertEquals(8, e.publishes);
    }

    @Test public void suspendedRoutePlayingAdvancesEverySecond() {   // DECISIONS dúvida 2
        TestEngine e = new TestEngine(cfg(1000, true, false, false));
        e.start(0, 0, NaN);
        e.startRoute(new double[]{0, 0, NaN, 0.01, 0, NaN}, MockEngine.ROUTE_STOP, true);
        assertEquals(1, e.publishes);              // tick do teleporte
        double last = e.pLat;
        for (int i = 0; i < 3; i++) {
            e.pump();
            assertTrue(e.pLat > last);
            last = e.pLat;
        }
        assertEquals(4, e.publishes);
        e.setRoutePlaying(false);
        e.pump();
        assertEquals(4, e.publishes);              // pausado e parado: nada
    }

    @Test public void stopRouteEndsOnLastPoint() {   // F69 Stop
        TestEngine e = new TestEngine(cfg());
        e.start(0, 0, NaN);
        double bLat = Geo.metersToLat(2);
        e.startRoute(new double[]{0, 0, NaN, bLat, 0, NaN}, MockEngine.ROUTE_STOP, true);
        assertEquals("[true/true]", e.states.toString());
        e.pump();                                  // chega em A
        assertTrue(e.hasRoute());
        e.pump();                                  // B a menos de um passo: snap e fim
        assertFalse(e.hasRoute());
        assertEquals("[true/true, false/false]", e.states.toString());
        assertEquals(bLat, e.lat(), 0);
        assertEquals(-1, e.nextIndex());
    }

    @Test public void walkToStartWalksFromCurrentPosition() {   // F70
        TestEngine e = new TestEngine(cfg());
        e.start(0, 0, NaN);
        double aLat = Geo.metersToLat(100);
        e.startRoute(new double[]{aLat, 0, NaN, aLat * 2, 0, NaN}, MockEngine.ROUTE_STOP, false);
        e.pump();
        assertEquals(10 / 3.6, Geo.distance(0, 0, e.lat(), e.lng()), 1e-3);
        assertTrue(e.isRoutePlaying());
    }

    @Test public void teleportPausesRoute() {
        TestEngine e = new TestEngine(cfg());
        e.start(0, 0, NaN);
        e.startRoute(new double[]{0, 0, NaN, 1, 0, NaN}, MockEngine.ROUTE_LOOP, true);
        e.teleport(5, 5, NaN);
        assertTrue(e.hasRoute());
        assertFalse(e.isRoutePlaying());
        assertTrue(e.next());                      // next volta a tocar e faz snap no próximo
        assertTrue(e.isRoutePlaying());
    }

    @Test public void saveLiveOnlyMovingAndThrottled() {   // F64
        TestEngine e = new TestEngine(cfg(1000, false, true, false));
        e.start(0, 0, NaN);
        for (int i = 0; i < 5; i++) e.pump();
        assertEquals(0, e.saves);                  // parado não grava
        e.setStick(0, 1f);
        for (int i = 0; i < 12; i++) e.pump();
        assertEquals(3, e.saves);                  // no máx. a cada 5 s
    }

    @Test public void tickDoesNotAllocate() throws Exception {   // N09 / R2
        // Por reflexão: o classpath de compilação do teste é o android.jar (sem java.lang.management).
        Object mx = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null);
        Method allocatedBytes = Class.forName("com.sun.management.ThreadMXBean").getMethod("getThreadAllocatedBytes", long.class);
        TestEngine e = new TestEngine(cfg(1000, false, false, true));
        e.setListener(null);
        e.start(0, 0, 10);
        for (int i = 0; i < 20_000; i++) { e.setStick(i % 360, (i / 1000) % 2); e.pump(); }   // aquece
        long tid = Thread.currentThread().getId();
        long before = (Long) allocatedBytes.invoke(mx, tid);
        for (int i = 0; i < 20_000; i++) { e.setStick(i % 360, (i / 1000) % 2); e.pump(); }
        long allocated = (Long) allocatedBytes.invoke(mx, tid) - before;
        assertTrue("alocou " + allocated + " B em 20k ticks", allocated < 1024);
    }
}
