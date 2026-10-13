package com.pokelocation.app.social.ws;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class WsClientTest {
    private static final byte[] MASK = {1, 2, 3, 4};

    /** RFC 6455 §1.3. */
    @Test public void acceptKeyRfcVector() {
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", WsClient.accept("dGhlIHNhbXBsZSBub25jZQ=="));
    }

    @Test public void handshakeOk() throws IOException {
        WsClient.checkHandshake("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "sec-websocket-accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n", "dGhlIHNhbXBsZSBub25jZQ==");
    }

    @Test public void handshakeRejectsWrongAcceptOrStatus() {
        assertBadHandshake("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: xxx\r\n");
        assertBadHandshake("HTTP/1.1 200 OK\r\nUpgrade: websocket\r\nSec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n");
        assertBadHandshake("HTTP/1.1 101 Switching Protocols\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n");
        // RFC 6455 §4.1: sem "Connection: Upgrade" o cliente DEVE falhar
        assertBadHandshake("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nSec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n");
    }

    private static void assertBadHandshake(String head) {
        try {
            WsClient.checkHandshake(head, "dGhlIHNhbXBsZSBub25jZQ==");
            fail();
        } catch (IOException expected) {
        }
    }

    /** Cliente → servidor: FIN+binário, bit de máscara, chave, payload XOR chave. */
    @Test public void clientFrameIsMasked() {
        assertArrayEquals(new byte[]{(byte) 0x82, (byte) 0x83, 1, 2, 3, 4, 'a' ^ 1, 'b' ^ 2, 'c' ^ 3},
                WsClient.frame(WsClient.BIN, "abc".getBytes(), MASK));
    }

    @Test public void clientFrameLengths() {
        assertEquals(2 + 4 + 125, WsClient.frame(WsClient.BIN, new byte[125], MASK).length);
        byte[] f126 = WsClient.frame(WsClient.BIN, new byte[126], MASK);
        assertEquals((byte) (0x80 | 126), f126[1]);
        assertEquals(126, (f126[2] & 0xff) << 8 | (f126[3] & 0xff));
        byte[] f64k = WsClient.frame(WsClient.BIN, new byte[65536], MASK);
        assertEquals((byte) (0x80 | 127), f64k[1]);
        assertEquals(2 + 8 + 4 + 65536, f64k.length);
        assertEquals(1, f64k[7]); // 65536 = 0x0000000000010000
    }

    @Test public void readsServerFrame() throws IOException {
        WsClient.Raw r = WsClient.read(new ByteArrayInputStream(new byte[]{(byte) 0x82, 2, 7, 8}), 100);
        assertEquals(WsClient.BIN, r.op);
        assertTrue(r.fin);
        assertArrayEquals(new byte[]{7, 8}, r.data);
        byte[] big = new byte[4 + 300];
        big[0] = (byte) 0x82;
        big[1] = 126;
        big[2] = 1;
        big[3] = 44; // 300
        assertEquals(300, WsClient.read(new ByteArrayInputStream(big), 1000).data.length);
    }

    @Test public void rejectsMaskedServerFrameAndOversize() {
        assertBadRead(new byte[]{(byte) 0x82, (byte) 0x81, 1, 2, 3, 4, 5}, 100);
        assertBadRead(new byte[]{(byte) 0x82, 127, (byte) 0x80, 0, 0, 0, 0, 0, 0, 1}, 100); // negativo
        assertBadRead(new byte[]{(byte) 0x82, 126, 1, 0}, 100); // 256 > max
        assertBadRead(new byte[]{(byte) 0x82, 5, 1}, 100); // cortado
    }

    private static void assertBadRead(byte[] b, int max) {
        try {
            WsClient.read(new ByteArrayInputStream(b), max);
            fail();
        } catch (IOException expected) {
        }
    }

    /** Ping vira pong com o mesmo payload; fragmentos se juntam; close responde close e termina. */
    @Test public void pumpHandlesPingFragmentsAndClose() throws IOException {
        ByteArrayOutputStream in = new ByteArrayOutputStream();
        in.write(new byte[]{(byte) 0x89, 2, 'h', 'i'});         // ping "hi"
        in.write(new byte[]{0x02, 2, 1, 2});                     // binário, FIN=0
        in.write(new byte[]{(byte) 0x80, 1, 3});                 // continuação, FIN=1
        in.write(new byte[]{(byte) 0x82, 1, 9});                 // binário inteiro
        in.write(new byte[]{(byte) 0x88, 2, 0x03, (byte) 0xe8}); // close 1000
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<byte[]> got = new ArrayList<>();
        WsClient.pump(new ByteArrayInputStream(in.toByteArray()), out, new Object(), got::add);
        assertEquals(2, got.size());
        assertArrayEquals(new byte[]{1, 2, 3}, got.get(0));
        assertArrayEquals(new byte[]{9}, got.get(1));
        List<byte[]> sent = unmask(out.toByteArray());
        assertEquals(2, sent.size());
        assertArrayEquals(new byte[]{(byte) 0x8A, 'h', 'i'}, sent.get(0)); // pong
        assertEquals((byte) 0x88, sent.get(1)[0]);                         // close
    }

    /** v1 só tem texto e localização (spec §10): mensagem > 64 KB é erro, não 3 MB de pico no :overlay. */
    @Test public void pumpRejectsMessageOver64k() {
        ByteArrayOutputStream in = new ByteArrayOutputStream();
        in.write(new byte[]{0x02, 126, (byte) 0x80, 0}, 0, 4); // binário FIN=0, 32768
        in.write(new byte[32768], 0, 32768);
        in.write(new byte[]{0x00, 126, (byte) 0x80, 1}, 0, 4); // continuação FIN=0, 32769 → total 65537
        in.write(new byte[32769], 0, 32769);
        in.write(new byte[]{(byte) 0x80, 0, (byte) 0x88, 0}, 0, 4); // fecha a mensagem e o close: sem teto, terminaria ok
        try {
            WsClient.pump(new ByteArrayInputStream(in.toByteArray()), new ByteArrayOutputStream(), new Object(), b -> {});
            fail();
        } catch (IOException expected) {
        }
    }

    @Test public void pumpRejectsText() {
        try {
            WsClient.pump(new ByteArrayInputStream(new byte[]{(byte) 0x81, 1, 'a'}), new ByteArrayOutputStream(),
                    new Object(), b -> {});
            fail();
        } catch (IOException expected) {
        }
    }

    @Test public void pumpEndOfStreamIsError() {
        try {
            WsClient.pump(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream(), new Object(), b -> {});
            fail();
        } catch (IOException expected) {
        }
    }

    @Test public void backoffGrowsWithJitterBetween1And60s() {
        assertEquals(1000, WsClient.backoff(0, 0));
        assertEquals(1500, WsClient.backoff(0, 0.999999), 1);
        assertEquals(8000, WsClient.backoff(3, 0));
        assertEquals(60_000, WsClient.backoff(6, 0.9));
        assertEquals(60_000, WsClient.backoff(50, 0));
    }

    /** Frames de cliente → [opcode|FIN, payload desmascarado]. */
    private static List<byte[]> unmask(byte[] b) {
        List<byte[]> out = new ArrayList<>();
        int i = 0;
        while (i < b.length) {
            int op = b[i] & 0xff, len = b[i + 1] & 0x7f;
            assertTrue((b[i + 1] & 0x80) != 0);
            byte[] f = new byte[1 + len];
            f[0] = (byte) op;
            for (int j = 0; j < len; j++) f[1 + j] = (byte) (b[i + 6 + j] ^ b[i + 2 + (j & 3)]);
            out.add(f);
            i += 6 + len;
        }
        return out;
    }
}
