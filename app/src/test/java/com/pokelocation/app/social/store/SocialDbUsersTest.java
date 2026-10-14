package com.pokelocation.app.social.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SocialDbUsersTest {
    @Test public void lastReleaseIsTheOnlyOneThatLetsItClose() {
        SocialDb.use();
        SocialDb.use();
        SocialDb.release();
        assertEquals(1, SocialDb.users());
        SocialDb.release();
        assertEquals(0, SocialDb.users());
        SocialDb.release();
        assertEquals(0, SocialDb.users());
    }

    @Test public void lateOverlayTaskNeverReopensAfterLastRelease() {
        SocialDb.use();
        SocialDb.release();
        assertFalse(SocialDb.useIfHeld(false));
        assertEquals(0, SocialDb.users());

        SocialDb.use();
        assertTrue(SocialDb.useIfHeld(false));
        assertEquals(2, SocialDb.users());
        SocialDb.release();
        SocialDb.release();

        assertTrue(SocialDb.useIfHeld(true));
        SocialDb.release();
    }
}
