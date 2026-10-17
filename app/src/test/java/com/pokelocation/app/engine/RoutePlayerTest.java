package com.pokelocation.app.engine;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RoutePlayerTest {
    /** n pontos com lat = índice (A=0, B=1, C=2, D=3). */
    private static double[] pts(int n) {
        double[] p = new double[n * 3];
        for (int i = 0; i < n; i++) { p[i * 3] = i; p[i * 3 + 1] = 0; p[i * 3 + 2] = Double.NaN; }
        return p;
    }

    private static int[] arrivals(RoutePlayer r, int k) {
        int[] out = new int[k];
        for (int i = 0; i < k; i++) { r.arrive(); out[i] = r.cur(); }
        return out;
    }

    @Test public void reverseVisitsEndsOnce() {   // A,B,C,D,C,B,A,B…
        RoutePlayer r = new RoutePlayer();
        r.start(pts(4), MockEngine.ROUTE_REVERSE);
        assertArrayEquals(new int[]{0, 1, 2, 3, 2, 1, 0, 1, 2, 3}, arrivals(r, 10));
    }

    @Test public void loopWraps() {
        RoutePlayer r = new RoutePlayer();
        r.start(pts(3), MockEngine.ROUTE_LOOP);
        assertArrayEquals(new int[]{0, 1, 2, 0, 1, 2, 0}, arrivals(r, 7));
        assertEquals(2, r.remaining());   // chegou em A: faltam B, C
    }

    @Test public void loopRemainingCountsDownPerLap() {   // não fica fixo em n
        RoutePlayer r = new RoutePlayer();
        r.start(pts(3), MockEngine.ROUTE_LOOP);
        assertEquals(3, r.remaining());
        r.arrive();
        assertEquals(2, r.remaining());
        r.arrive();
        assertEquals(1, r.remaining());
        r.arrive();                       // C → próximo é A: nova volta
        assertEquals(3, r.remaining());
    }

    @Test public void stopEndsOnLastPoint() {
        RoutePlayer r = new RoutePlayer();
        r.start(pts(3), MockEngine.ROUTE_STOP);
        assertEquals(1, r.progress1());
        assertEquals(3, r.remaining());
        assertTrue(r.arrive());   // A
        assertTrue(r.arrive());   // B
        assertEquals(2, r.progress1());
        assertEquals(1, r.remaining());
        assertFalse(r.arrive());  // C = fim
    }

    @Test public void singlePointEndsInEveryMode() {
        for (int m = 0; m < 3; m++) {
            RoutePlayer r = new RoutePlayer();
            r.start(pts(1), m);
            assertFalse(r.arrive());
        }
    }

    @Test public void prevQaCases() {   // A-B-C-D em Stop
        RoutePlayer r = new RoutePlayer();
        r.start(pts(4), MockEngine.ROUTE_STOP);
        arrivals(r, 3);                         // em C
        assertEquals(1, r.previous(true));      // sobre C → B
        assertEquals(2, r.nextIndex());         // anda até C e D
        r.arrive();                             // C
        assertEquals(2, r.previous(false));     // no meio de C→D → C
        assertEquals(3, r.nextIndex());
    }

    @Test public void prevStopClampsAtZero() {
        RoutePlayer r = new RoutePlayer();
        r.start(pts(4), MockEngine.ROUTE_STOP);
        r.arrive();                             // A
        assertEquals(0, r.previous(true));
        assertEquals(1, r.nextIndex());
    }

    @Test public void prevLoopWrapsToLast() {
        RoutePlayer r = new RoutePlayer();
        r.start(pts(4), MockEngine.ROUTE_LOOP);
        r.arrive();
        assertEquals(3, r.previous(true));
        assertEquals(0, r.nextIndex());
    }

    @Test public void prevReverseReflects() {
        RoutePlayer r = new RoutePlayer();
        r.start(pts(4), MockEngine.ROUTE_REVERSE);
        arrivals(r, 4);                         // em D, sentido já invertido
        assertTrue(r.reversed());
        assertEquals(2, r.previous(true));      // veio de C
        assertFalse(r.reversed());
        assertEquals(3, r.nextIndex());         // volta a D
        r.arrive();                             // D
        r.arrive();                             // C
        r.arrive();                             // B
        r.arrive();                             // A (inverte para subir)
        assertEquals(1, r.previous(true));      // veio de B
        assertTrue(r.reversed());
        assertEquals(0, r.nextIndex());
    }

    @Test public void prevReverseBeforeFirstWaypointGoesToStart() {   // Walk To Start ainda indo para pts[0]
        RoutePlayer r = new RoutePlayer();
        r.start(pts(4), MockEngine.ROUTE_REVERSE);
        assertEquals(0, r.previous(false));
        assertFalse(r.reversed());
        assertEquals(1, r.nextIndex());
    }

    @Test public void resumeKeepsDirection() {
        RoutePlayer r = new RoutePlayer();
        r.resume(pts(4), MockEngine.ROUTE_REVERSE, true, 1);
        assertEquals(2, r.cur());
        assertEquals(1, r.nextIndex());
        assertTrue(r.reversed());
        assertArrayEquals(new int[]{1, 0, 1, 2, 3, 2}, arrivals(r, 6));

        r.resume(pts(4), MockEngine.ROUTE_STOP, true, 0);   // reversed não vale em Stop
        assertEquals(-1, r.cur());
        assertFalse(r.reversed());
        assertEquals(1, r.progress1());
    }
}
