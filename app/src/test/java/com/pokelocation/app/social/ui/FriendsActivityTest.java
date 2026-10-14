package com.pokelocation.app.social.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FriendsActivityTest {
    @Test
    public void prettySplitsEightCharCodes() {
        assertEquals("ABCD-1234", FriendsActivity.pretty("ABCD1234"));
        assertEquals("ABC", FriendsActivity.pretty("ABC"));
        assertNull(FriendsActivity.pretty(null));
    }

    @Test
    public void labelPrefersUsernameOverCode() {
        assertEquals("@ana.lu", FriendsActivity.label("ana.lu", "ABCD1234"));
        assertEquals("ABCD-1234", FriendsActivity.label(null, "ABCD1234"));
        assertEquals("", FriendsActivity.label(null, null));
    }

    @Test
    public void avatarInitialIsUppercaseFirstLetter() {
        assertEquals("A", FriendsActivity.initial("ana", "ZZZZ1111"));
        assertEquals("Z", FriendsActivity.initial(null, "ZZZZ1111"));
        assertEquals("?", FriendsActivity.initial(null, null));
    }

    /** Até 15 s desde a última posição: online; depois, "visto há X s" (a posição some do servidor em 60 s). */
    @Test
    public void onlineUpTo15Seconds() {
        assertTrue(FriendsActivity.online(15));
        assertFalse(FriendsActivity.online(16));
    }
}
