package com.pokelocation.app.social.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class NotifyTest {
    @Test public void groupMessagesComeFromTheMemberAndOneToOneFromTheConversation() {
        assertEquals("@ana", Notify.sender(false, "@ana", null));
        assertEquals("@bia", Notify.sender(true, "Raid", "@bia"));
        assertEquals("?", Notify.sender(true, "Raid", null));
    }

    @Test public void aFullListLosesTheOldestChatNeverTheAlerts() {
        String[] ids = {"pogo_alerts", "chat:a", "chat:b"};
        long[] changed = {1, 5, 3};
        assertEquals("chat:b", Notify.evict(ids, changed, 3, "chat:c"));
        assertNull(Notify.evict(ids, changed, 4, "chat:c"));
        assertNull(Notify.evict(ids, changed, 3, "chat:a"));
        assertNull(Notify.evict(new String[]{"pogo_alerts"}, new long[]{1}, 1, "chat:c"));
    }

    @Test public void logoutRemovesOnlyChatShortcuts() {
        assertTrue(Notify.chatShortcut(Notify.shortcutId("c1")));
        assertFalse(Notify.chatShortcut("pogo_alerts"));
    }
}
