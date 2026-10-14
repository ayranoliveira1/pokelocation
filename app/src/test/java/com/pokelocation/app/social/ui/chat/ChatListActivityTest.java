package com.pokelocation.app.social.ui.chat;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ChatListActivityTest {
    @Test
    public void avatarInitialSkipsTheAtAndFormatting() {
        assertEquals("A", ChatListActivity.initial("@ana.lu"));
        assertEquals("T", ChatListActivity.initial("time do raid"));
        assertEquals("Z", ChatListActivity.initial("ZZZZ-1111"));
        assertEquals("?", ChatListActivity.initial(null));
        assertEquals("?", ChatListActivity.initial("@"));
        assertEquals("\uD83D\uDD25", ChatListActivity.initial("\uD83D\uDD25 raid"));
        assertEquals("É", ChatListActivity.initial("école"));
    }
}
