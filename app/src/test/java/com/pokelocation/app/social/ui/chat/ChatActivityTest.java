package com.pokelocation.app.social.ui.chat;

import static org.junit.Assert.assertEquals;

import com.pokelocation.app.ipc.Intents;
import com.pokelocation.app.social.chat.Chat;

import org.junit.Test;

public class ChatActivityTest {
    private static Chat.Conv conv(long mine, long theirs) {
        Chat.Conv c = new Chat.Conv();
        c.myLiveUntil = mine;
        c.liveUntil = theirs;
        return c;
    }

    @Test
    public void liveBadgeComesFromTheConversationNotThePage() {
        long now = 1000;
        assertEquals(0, ConvRow.liveUntil(null, now));
        assertEquals(0, ConvRow.liveUntil(conv(0, 0), now));
        assertEquals(0, ConvRow.liveUntil(conv(999, 1000), now));
        assertEquals(2000, ConvRow.liveUntil(conv(2000, 0), now));
        assertEquals(3000, ConvRow.liveUntil(conv(2000, 3000), now));
        assertEquals(2000, ConvRow.liveUntil(conv(2000, 500), now));
    }

    @Test
    public void pinOpensTeleportPanelWithJoystickOnElseTheForm() {
        assertEquals(Intents.INT_TELEPORT_UI, ChatActivity.pinAction(true));
        assertEquals(Intents.INT_FILL_LOCATION, ChatActivity.pinAction(false));
    }

    @Test
    public void safetyNumberInBlocksOfFive() {
        assertEquals("12345 67890\n11111 22222", ChatActivity.blocks("12345678901111122222", 2));
        assertEquals("123", ChatActivity.blocks("123", 4));
    }
}
