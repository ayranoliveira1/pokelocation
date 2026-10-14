package com.pokelocation.app.social.loc;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LocationsTest {
    /** f64 lat, f64 lng, i64 epochMs big-endian = 24 B. */
    @Test public void positionIs24BytesBigEndian() {
        byte[] p = Locations.position(-23.5, -46.625, 1_700_000_000_123L);
        assertEquals(24, p.length);
        assertEquals((byte) 0xc0, p[0]); // sinal de -23,5 no f64
        double[] d = Locations.position(p);
        assertEquals(-23.5, d[0], 0);
        assertEquals(-46.625, d[1], 0);
        assertEquals(1_700_000_000_123L, (long) d[2]);
    }

    @Test public void positionRejectsWrongSize() {
        assertNull(Locations.position(new byte[23]));
        assertNull(Locations.position(new byte[25]));
    }

    /** Payload do envelope 1:1: [tipo da mensagem Signal u8][ciphertext]; conteúdo: [kind u8][dados]. */
    @Test public void envelopeAndContentFraming() {
        byte[] e = Locations.frame(3, new byte[]{9, 8});
        assertArrayEquals(new byte[]{3, 9, 8}, e);
        assertEquals(3, Locations.kind(e));
        assertArrayEquals(new byte[]{9, 8}, Locations.body(e));
        assertEquals(-1, Locations.kind(new byte[0]));
    }

    /** Uma distribuição por remetente ("loc:{id}"), igual nos dois processos e em quem recebe. */
    @Test public void distributionIdIsStablePerUser() {
        assertEquals(Locations.distribution("u1"), Locations.distribution("u1"));
        assertNotEquals(Locations.distribution("u1"), Locations.distribution("u2"));
    }

    /**
     * Ack só depois de processar ou se for ilegível para sempre; falha transitória (lock, Keystore, banco) ou kind que
     * este processo não trata → sem Ack, o servidor entrega de novo.
     */
    @Test public void ackCriteria() {
        assertTrue(Locations.ack(null)); // SKDM aplicada, ou duplicado já visto (dedup)
        assertTrue(Locations.ack(new java.io.IOException("signal",
                new org.signal.libsignal.protocol.InvalidMessageException("corrompida"))));
        assertTrue(Locations.ack(new java.io.IOException("signal",
                new org.signal.libsignal.protocol.DuplicateMessageException("já decifrada"))));
        assertTrue(Locations.ack(new java.io.IOException("signal",
                new org.signal.libsignal.protocol.NoSessionException("sem sessão"))));
        assertFalse(Locations.ack(new java.io.IOException("keystore", new java.security.GeneralSecurityException())));
        assertFalse(Locations.ack(new java.io.IOException("signal",
                new IllegalStateException("vault", new java.security.GeneralSecurityException()))));
        assertFalse(Locations.ack(new java.io.IOException("lock")));
        // transitório que embrulha uma exceção da libsignal: o tipo próprio vale antes da causa
        assertFalse(Locations.ack(new Locations.Transient("sender key ainda não chegou",
                new org.signal.libsignal.protocol.NoSessionException("x"))));
        // payload malformado (vazio/curto) de um cliente alterado: ilegível para sempre, senão 50 deles travam a fila
        assertTrue(Locations.ack(new java.io.IOException("signal", new IllegalArgumentException("vazio"))));
    }

    @Test public void sendsOnlyAfterMovingMoreThan5m() {
        assertTrue(LocationSharer.moved(Double.NaN, Double.NaN, -23.5, -46.6));     // nada enviado ainda
        assertFalse(LocationSharer.moved(-23.5, -46.6, -23.50003, -46.6));          // ~3,3 m
        assertTrue(LocationSharer.moved(-23.5, -46.6, -23.50006, -46.6));           // ~6,7 m
    }
}
