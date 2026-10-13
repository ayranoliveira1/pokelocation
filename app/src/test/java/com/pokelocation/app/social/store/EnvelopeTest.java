package com.pokelocation.app.social.store;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.security.GeneralSecurityException;
import java.util.Arrays;

public class EnvelopeTest {
    private static final byte[] KEY = new byte[32], PLAIN = "registro do signal".getBytes();

    static {
        for (int i = 0; i < KEY.length; i++) KEY[i] = (byte) i;
    }

    @Test public void roundTrip() throws GeneralSecurityException {
        assertArrayEquals(PLAIN, Envelope.open(KEY, Envelope.seal(KEY, PLAIN, "s|u1|1"), "s|u1|1"));
    }

    @Test public void freshIvEachSeal() throws GeneralSecurityException {
        assertFalse(Arrays.equals(Envelope.seal(KEY, PLAIN, "a"), Envelope.seal(KEY, PLAIN, "a")));
    }

    @Test public void tamperedBlobFails() throws GeneralSecurityException {
        byte[] b = Envelope.seal(KEY, PLAIN, "a");
        b[b.length - 1] ^= 1;
        assertOpenFails(b, "a", KEY);
    }

    /** AAD = endereço da linha: um blob copiado para outra linha (outro amigo) não abre. */
    @Test public void otherAddressFails() throws GeneralSecurityException {
        assertOpenFails(Envelope.seal(KEY, PLAIN, "s|u1|1"), "s|u2|1", KEY);
    }

    @Test public void otherKeyFails() throws GeneralSecurityException {
        byte[] other = KEY.clone();
        other[0] ^= 1;
        assertOpenFails(Envelope.seal(KEY, PLAIN, "a"), "a", other);
    }

    @Test public void shortBlobFails() {
        assertOpenFails(new byte[5], "a", KEY);
    }

    private static void assertOpenFails(byte[] blob, String aad, byte[] key) {
        try {
            Envelope.open(key, blob, aad);
            fail();
        } catch (GeneralSecurityException expected) {
            assertTrue(true);
        }
    }
}
