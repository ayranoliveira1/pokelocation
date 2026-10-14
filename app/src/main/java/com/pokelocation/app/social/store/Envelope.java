package com.pokelocation.app.social.store;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM em software com a chave-envelope (contrato do subprojeto 2): registros do Signal no caminho quente sem IPC
 * ao Keystore. Blob = IV(12) || cifrado+tag(16). aad = endereço da linha ("s|nome|device"…): blob copiado para outra
 * linha não abre.
 */
final class Envelope {
    private static final int IV = 12, TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private Envelope() {}

    static byte[] seal(byte[] key, byte[] plain, String aad) throws GeneralSecurityException {
        byte[] iv = new byte[IV];
        RANDOM.nextBytes(iv);
        Cipher c = cipher(Cipher.ENCRYPT_MODE, key, iv, aad);
        byte[] out = new byte[IV + c.getOutputSize(plain.length)];
        System.arraycopy(iv, 0, out, 0, IV);
        c.doFinal(plain, 0, plain.length, out, IV);
        return out;
    }

    static byte[] open(byte[] key, byte[] blob, String aad) throws GeneralSecurityException {
        if (blob == null || blob.length < IV + TAG_BITS / 8) throw new GeneralSecurityException("blob");
        byte[] iv = new byte[IV];
        System.arraycopy(blob, 0, iv, 0, IV);
        return cipher(Cipher.DECRYPT_MODE, key, iv, aad).doFinal(blob, IV, blob.length - IV);
    }

    static byte[] newKey() {
        byte[] k = new byte[32];
        RANDOM.nextBytes(k);
        return k;
    }

    private static Cipher cipher(int mode, byte[] key, byte[] iv, String aad) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
        c.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        return c;
    }
}
