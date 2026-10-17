package com.pokelocation.app.pogo;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** O que a tela PoGo mostra a cada onStart, conforme o vínculo PoGo (sem a conta PokeLocation ela nem abre). */
public class PogoActivityTest {
    @Test public void withoutPogoOffersSignIn() {
        assertEquals(PogoActivity.SIGN_IN, PogoActivity.screen(false, false));
    }

    @Test public void linkedShowsAccount() {
        assertEquals(PogoActivity.LINKED, PogoActivity.screen(false, true));
    }

    @Test public void busyWins() {
        assertEquals(PogoActivity.BUSY, PogoActivity.screen(true, true));
        assertEquals(PogoActivity.BUSY, PogoActivity.screen(true, false));
    }
}
