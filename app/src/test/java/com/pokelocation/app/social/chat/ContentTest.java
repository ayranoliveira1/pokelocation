package com.pokelocation.app.social.chat;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class ContentTest {
    @Test public void messageRoundTrip() {
        Chat.Msg m = new Chat.Msg();
        m.type = Chat.LIVE_START;
        m.ts = 1_700_000_000_123L;
        m.liveUntil = 1_700_000_900_000L;
        m.lat = -23.5;
        m.lng = -46.6;
        m.text = "olá";
        byte[] plain = Content.msg(m);
        assertEquals(Content.MSG, plain[0]);
        Chat.Msg r = Content.msg(plain);
        assertEquals(Chat.LIVE_START, r.type);
        assertEquals(m.ts, r.ts);
        assertEquals(m.liveUntil, r.liveUntil);
        assertEquals(-23.5, r.lat, 0);
        assertEquals(-46.6, r.lng, 0);
        assertEquals("olá", r.text);
    }

    @Test public void malformedMessageIsNull() {
        assertNull(Content.msg(new byte[]{Content.MSG, 1, 0, 0}));
        assertNull(Content.msg(new byte[0]));
        assertNull(Content.msg(new byte[]{Content.GROUP_INFO}));
    }

    @Test public void fromOthersOnlyUserTypes() {
        for (int t : new int[]{Chat.TEXT, Chat.LOC, Chat.LIVE_START, Chat.LIVE_STOP}) {
            Chat.Msg m = new Chat.Msg();
            m.type = t;
            m.ts = 1000;
            assertEquals(true, Content.acceptable(m));
        }
        for (int t : new int[]{Chat.UNREADABLE, Chat.IDENTITY_CHANGED, 0, 99}) {
            Chat.Msg m = new Chat.Msg();
            m.type = t;
            assertEquals(false, Content.acceptable(m));
        }
    }

    @Test public void liveUntilIsCapped() {
        Chat.Msg m = new Chat.Msg();
        m.type = Chat.LIVE_START;
        m.ts = 1_000_000;
        m.liveUntil = Long.MAX_VALUE;
        Content.acceptable(m);
        assertEquals(1_000_000 + Content.MAX_LIVE_MS, m.liveUntil);
        m.liveUntil = 1_000_000 + 60_000;
        Content.acceptable(m);
        assertEquals(1_000_000 + 60_000, m.liveUntil);
    }

    @Test public void groupIdMustBeUuid() {
        assertEquals(true, Content.isGroupId("11111111-2222-3333-4444-555555555555"));
        assertEquals(false, Content.isGroupId("d:11111111-2222-3333-4444-555555555555"));
        assertEquals(false, Content.isGroupId("x"));
        assertEquals(false, Content.isGroupId(null));
    }

    @Test public void groupInfoRoundTrip() {
        byte[] plain = Content.groupInfo("11111111-2222-3333-4444-555555555555", "Raid 🐉");
        assertEquals(Content.GROUP_INFO, plain[0]);
        assertArrayEquals(new String[]{"11111111-2222-3333-4444-555555555555", "Raid 🐉"}, Content.groupInfo(plain));
        assertNull(Content.groupInfo(new byte[]{Content.GROUP_INFO, 0, 9, 'a'}));
    }
}
