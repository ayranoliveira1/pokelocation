package com.pokelocation.app.social.ui.chat;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ConvRowTest {
    @Test
    public void avatarInitialSkipsTheAtAndFormatting() {
        assertEquals("A", ConvRow.initial("@ana.lu"));
        assertEquals("T", ConvRow.initial("time do raid"));
        assertEquals("Z", ConvRow.initial("ZZZZ-1111"));
        assertEquals("?", ConvRow.initial(null));
        assertEquals("?", ConvRow.initial("@"));
        assertEquals("\uD83D\uDD25", ConvRow.initial("\uD83D\uDD25 raid"));
        assertEquals("É", ConvRow.initial("école"));
    }
}
