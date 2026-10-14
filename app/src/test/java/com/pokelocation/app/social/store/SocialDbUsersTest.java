package com.pokelocation.app.social.store;

import static org.junit.Assert.assertEquals;

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
}
