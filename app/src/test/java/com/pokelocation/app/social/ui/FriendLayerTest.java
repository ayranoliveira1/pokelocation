package com.pokelocation.app.social.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FriendLayerTest {
    private static final long NOW = 1_000_000_000L;

    @Test
    public void visibleUpTo60sThenGone() {
        assertTrue(FriendLayer.visible(NOW, NOW));
        assertTrue(FriendLayer.visible(NOW - 60_000, NOW));
        assertFalse(FriendLayer.visible(NOW - 60_001, NOW));
    }

    @Test
    public void clockSkewAheadCountsAsNow() {
        assertTrue(FriendLayer.visible(NOW + 30_000, NOW));
        assertEquals(0, FriendLayer.ageSeconds(NOW + 30_000, NOW));
        assertEquals(42, FriendLayer.ageSeconds(NOW - 42_500, NOW));
    }

    @Test
    public void initialsAreFirstFourOfCode() {
        assertEquals("ABCD", FriendLayer.initials("ABCD1234"));
        assertEquals("AB", FriendLayer.initials("AB"));
        assertNull(FriendLayer.initials(null));
    }
}
