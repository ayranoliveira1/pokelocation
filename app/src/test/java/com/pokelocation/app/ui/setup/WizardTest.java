package com.pokelocation.app.ui.setup;

import static com.pokelocation.app.ui.setup.Wizard.*;
import static org.junit.Assert.*;

import org.junit.Test;

public class WizardTest {
    private final boolean[] granted = new boolean[COUNT];

    private Wizard wizard(boolean hub) { return new Wizard(s -> granted[s], hub); }

    @Test
    public void onlyPendingStepsAndAutoAdvanceOnResume() {
        granted[NOTIFICATION] = true;
        granted[DEV_OPTIONS] = true;
        Wizard w = wizard(false);
        assertEquals(WELCOME, w.screen);
        assertArrayEquals(new int[]{LOCATION, OVERLAY, MOCK}, w.steps);
        w.start();
        assertEquals(LOCATION, w.current());
        assertEquals(1, w.stepNumber());
        assertEquals(3, w.stepTotal());
        w.revalidate();                         // nada mudou
        assertEquals(LOCATION, w.current());
        granted[LOCATION] = true;
        granted[OVERLAY] = true;
        w.revalidate();                         // pula os dois satisfeitos
        assertEquals(MOCK, w.current());
        assertEquals(3, w.stepNumber());
    }

    @Test
    public void nothingPendingGoesToComplete() {
        java.util.Arrays.fill(granted, true);
        assertEquals(COMPLETE, wizard(false).screen);
    }

    @Test
    public void iveDoneOnlyAfterAttemptAndVerifies() {
        Wizard w = wizard(false);
        w.start();
        w.skip();
        w.skip();
        w.skip();
        assertEquals(DEV_OPTIONS, w.current());
        assertTrue(w.isSkipped(NOTIFICATION));
        assertFalse(w.showIveDone());
        w.primary();
        assertTrue(w.showIveDone());
        w.iveDone();
        assertTrue(w.verificationError);
        assertEquals(DEV_OPTIONS, w.current());
        granted[DEV_OPTIONS] = true;
        w.iveDone();
        assertEquals(MOCK, w.current());
        assertFalse(w.verificationError);
        assertFalse(w.showIveDone());           // não tentado ainda
        w.skip();
        assertEquals(COMPLETE, w.screen);
    }

    @Test
    public void permissionResults() {
        Wizard w = wizard(false);
        w.start();
        w.permissionResult(false);              // notificação negada não bloqueia
        assertEquals(LOCATION, w.current());
        w.permissionResult(false);
        assertEquals(LOCATION_DENIED, w.screen);
        granted[LOCATION] = true;
        w.revalidate();
        assertEquals(STEP, w.screen);
        assertEquals(OVERLAY, w.current());
    }

    @Test
    public void overlayDenialAfterTwoOrRestricted() {
        granted[NOTIFICATION] = granted[LOCATION] = true;
        Wizard w = wizard(false);
        w.start();
        w.overlayReturned(false);
        assertEquals(STEP, w.screen);
        w.overlayReturned(false);
        assertEquals(OVERLAY_DENIAL, w.screen);
        granted[OVERLAY] = true;
        w.overlayReturned(false);
        assertEquals(DEV_OPTIONS, w.current());

        granted[OVERLAY] = false;
        Wizard r = wizard(false);
        r.start();
        r.overlayReturned(true);
        assertEquals(OVERLAY_DENIAL, r.screen);
    }

    @Test
    public void hubFixReturnsToHub() {
        Wizard w = wizard(true);
        assertEquals(COMPLETE, w.screen);
        assertEquals(COUNT, w.steps.length);
        w.fix(MOCK);
        assertEquals(STEP, w.screen);
        assertEquals(MOCK, w.current());
        assertEquals(5, w.stepNumber());
        w.revalidate();
        assertEquals(STEP, w.screen);
        granted[MOCK] = true;
        w.revalidate();
        assertEquals(COMPLETE, w.screen);
        assertEquals(-1, w.fixing);

        w.fix(NOTIFICATION);
        w.permissionResult(false);              // no hub, negação = erro de verificação
        assertTrue(w.verificationError);
        assertEquals(STEP, w.screen);
    }

    @Test
    public void saveRestore() {
        Wizard w = wizard(false);
        w.start();
        w.skip();
        w.primary();
        Wizard r = wizard(false);
        r.restore(w.save(), w.steps);
        assertEquals(w.current(), r.current());
        assertEquals(w.skipped, r.skipped);
        assertEquals(w.attempted, r.attempted);
    }

    @Test
    public void manufacturers() {
        assertEquals(FAMILY_SAMSUNG, Wizard.family("samsung"));
        assertEquals(FAMILY_SAMSUNG, Wizard.family("vivo"));
        assertEquals(FAMILY_ONEPLUS, Wizard.family("realme"));
        assertEquals(FAMILY_XIAOMI, Wizard.family("Xiaomi"));
        assertEquals(FAMILY_DEFAULT, Wizard.family("Google"));
        assertEquals(FAMILY_DEFAULT, Wizard.family(null));
        assertEquals("OnePlus", Wizard.brand("OnePlus"));
        assertEquals("Google", Wizard.brand("Google"));
        assertNull(Wizard.brand("realme"));
    }
}
