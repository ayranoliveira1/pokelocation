package com.pokelocation.app.social.store;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Spec §7: identity do amigo mudou (reinstalação) → aceita, avisa no chat e refaz a sessão. */
public class SignalStoreTest {
    private static final byte[] A = {5, 1, 2}, B = {5, 1, 3};

    @Test public void firstSightIsNotAReplacement() { assertFalse(SignalStore.replaced(null, A)); }

    @Test public void sameKeyIsNotAReplacement() { assertFalse(SignalStore.replaced(A, A.clone())); }

    @Test public void differentKeyIsAReplacement() { assertTrue(SignalStore.replaced(A, B)); }
}
