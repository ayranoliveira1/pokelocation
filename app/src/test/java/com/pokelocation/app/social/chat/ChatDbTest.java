package com.pokelocation.app.social.chat;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ChatDbTest {
    @Test public void bodyRoundTrip() {
        Chat.Msg m = new Chat.Msg();
        ChatDb.readBody(ChatDb.body(-23.5, -46.625, "olá 👋"), m);
        assertEquals(-23.5, m.lat, 0);
        assertEquals(-46.625, m.lng, 0);
        assertEquals("olá 👋", m.text);
    }

    @Test public void bodyWithoutText() {
        Chat.Msg m = new Chat.Msg();
        ChatDb.readBody(ChatDb.body(1, 2, null), m);
        assertEquals("", m.text);
    }

    /** O preview da lista corta sem partir um emoji (par substituto) no meio. */
    @Test public void previewCutsOnCodePoints() {
        assertEquals("abc", ChatDb.preview("abc"));
        String longText = new String(new char[200]).replace('\0', 'x');
        assertEquals(ChatDb.PREVIEW_CHARS, ChatDb.preview(longText).length());
        String emojis = new String(new char[ChatDb.PREVIEW_CHARS - 2]).replace('\0', 'y') + "👋👋👋";
        String p = ChatDb.preview(emojis);
        assertEquals(ChatDb.PREVIEW_CHARS, p.codePointCount(0, p.length()));
        assertEquals(true, p.endsWith("👋…")); // o corte caiu depois de um emoji inteiro
    }
}
