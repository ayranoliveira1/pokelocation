package com.pokelocation.app.social.store;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Identity do amigo mudou (reinstalação) → aceita e avisa; a sessão é refeita. */
public class SignalStoreTest {
    private static final byte[] A = {5, 1, 2}, B = {5, 1, 3};

    @Test public void firstSightIsNotAReplacement() { assertFalse(SignalStore.replaced(null, A)); }

    @Test public void sameKeyIsNotAReplacement() { assertFalse(SignalStore.replaced(A, A.clone())); }

    @Test public void differentKeyIsAReplacement() { assertTrue(SignalStore.replaced(A, B)); }
}
