package com.pokelocation.app.pogo;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** O que a tela PoGo mostra a cada onStart, conforme a conta PokeLocation e o vínculo PoGo daquele momento. */
public class PogoActivityTest {
    @Test public void socialLoginWithoutPogoOffersSignIn() {
        assertEquals(PogoActivity.SIGN_IN, PogoActivity.screen(false, true, false));
    }

    @Test public void noSocialAccountAsksForIt() {
        assertEquals(PogoActivity.NEED_ACCOUNT, PogoActivity.screen(false, false, false));
        assertEquals(PogoActivity.NEED_ACCOUNT, PogoActivity.screen(false, false, true));
    }

    @Test public void linkedShowsAccount() {
        assertEquals(PogoActivity.LINKED, PogoActivity.screen(false, true, true));
    }

    @Test public void busyWins() {
        assertEquals(PogoActivity.BUSY, PogoActivity.screen(true, true, false));
        assertEquals(PogoActivity.BUSY, PogoActivity.screen(true, false, false));
    }
}
