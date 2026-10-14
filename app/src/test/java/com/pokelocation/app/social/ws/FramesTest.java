package com.pokelocation.app.social.ws;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;

public class FramesTest {
    @Test public void authBytes() throws IOException {
        // [tipo 1][u16 2]["ab"]
        assertArrayEquals(new byte[]{1, 0, 2, 'a', 'b'}, Frames.auth("ab"));
        assertEquals("ab", Frames.decode(Frames.auth("ab")).peer);
    }

    @Test public void envelopeBytes() {
        // [2][u16 1]"p"[u16 1]"m"[u32 2][9,8]
        assertArrayEquals(new byte[]{2, 0, 1, 'p', 0, 1, 'm', 0, 0, 0, 2, 9, 8}, Frames.envelope("p", "m", new byte[]{9, 8}));
    }

    @Test public void envelopeRoundTrip() throws IOException {
        Frames.Frame f = Frames.decode(Frames.envelope("usuário", "id-1", new byte[]{1, 2, 3}));
        assertEquals(Frames.ENVELOPE, f.type);
        assertEquals("usuário", f.peer);
        assertEquals("id-1", f.msgId);
        assertArrayEquals(new byte[]{1, 2, 3}, f.payload);
    }

    @Test public void ackRoundTrip() throws IOException {
        Frames.Frame f = Frames.decode(Frames.ack("u", "m"));
        assertEquals(Frames.ACK, f.type);
        assertEquals("u", f.peer);
        assertEquals("m", f.msgId);
    }

    @Test public void locationAndOff() throws IOException {
        assertArrayEquals(new byte[]{4, 0, 0, 0, 1, 7}, Frames.location(new byte[]{7}));
        assertArrayEquals(new byte[]{6}, Frames.locationOff());
        Frames.Frame off = Frames.decode(Frames.locationOff());
        assertEquals(Frames.LOCATION_OFF, off.type);
        assertNull(off.peer);
    }

    @Test public void serverFrames() throws IOException {
        Frames.Frame up = Frames.decode(new byte[]{5, 0, 1, 'f', 0, 0, 0, 1, 42});
        assertEquals(Frames.LOCATION_UPDATE, up.type);
        assertEquals("f", up.peer);
        assertArrayEquals(new byte[]{42}, up.payload);
        Frames.Frame off = Frames.decode(new byte[]{6, 0, 1, 'f'});
        assertEquals("f", off.peer);
        Frames.Frame err = Frames.decode(new byte[]{7, 0, 3, 'b', 'a', 'd'});
        assertEquals(Frames.ERROR, err.type);
        assertEquals("bad", err.code);
    }

    @Test public void rejectsLengthPastEnd() {
        assertBad(new byte[]{1, 0, 9, 'a'});
        assertBad(new byte[]{4, 0x7f, 0, 0, 0, 1});
        assertBad(new byte[]{4, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff});
        assertBad(new byte[]{2, 0, 1, 'p'});
    }

    @Test public void rejectsTrailingBytesUnknownTypeAndEmpty() {
        assertBad(new byte[]{1, 0, 1, 'a', 0});
        assertBad(new byte[]{99});
        assertBad(new byte[0]);
    }

    @Test(expected = IllegalArgumentException.class) public void strTooLong() {
        Frames.auth(new String(new char[65536]).replace('\0', 'a'));
    }

    private static void assertBad(byte[] f) {
        try {
            Frames.decode(f);
            fail();
        } catch (IOException expected) {
        }
    }
}
