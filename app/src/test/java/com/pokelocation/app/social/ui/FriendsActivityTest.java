package com.pokelocation.app.social.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class FriendsActivityTest {
    @Test
    public void prettySplitsEightCharCodes() {
        assertEquals("ABCD-1234", FriendsActivity.pretty("ABCD1234"));
        assertEquals("ABC", FriendsActivity.pretty("ABC"));
        assertNull(FriendsActivity.pretty(null));
    }
}
