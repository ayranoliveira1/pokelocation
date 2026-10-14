package com.pokelocation.app.social.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PanelWsTest {
    private static final Chat.Listener L = new Chat.Listener() {
        @Override public void onMessage(Chat.Msg m) {}

        @Override public void onConversationsChanged() {}
    };

    @Test public void openingThePanelDoesNotAskForTheWsSendingDoes() {
        ChatSocket.panel(L, true);
        assertFalse(ChatSocket.wsWanted());
        ChatSocket.wantWs();
        assertTrue(ChatSocket.wsWanted());
        ChatSocket.panel(L, false);
        assertTrue(ChatSocket.wsWanted()); // quem decide se zera é a outbox, no tick
    }

    @Test public void closingWithOutboxKeepsTheWsUntilItDrains() {
        assertEquals(Rules.OUT_KEEP, Rules.overlayOutbox(true, true, false, 0));
        assertEquals(Rules.OUT_KEEP, Rules.overlayOutbox(true, false, true, 1000));
        assertEquals(Rules.OUT_DONE, Rules.overlayOutbox(true, false, false, 1000));
        assertEquals(Rules.OUT_GIVE_UP, Rules.overlayOutbox(true, false, true, Rules.OUTBOX_GRACE_MS + 1));
        assertEquals(Rules.OUT_DONE, Rules.overlayOutbox(false, false, true, 0));
    }
}
