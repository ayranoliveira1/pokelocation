package com.pokelocation.app.social.chat;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class NotifyTest {
    @Test public void groupMessagesComeFromTheMemberAndOneToOneFromTheConversation() {
        assertEquals("@ana", Notify.sender(false, "@ana", null));
        assertEquals("@bia", Notify.sender(true, "Raid", "@bia"));
        assertEquals("?", Notify.sender(true, "Raid", null));
    }
}
