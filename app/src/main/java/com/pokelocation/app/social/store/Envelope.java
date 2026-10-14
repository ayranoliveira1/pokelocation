package com.pokelocation.app.social.store;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM em software com a chave-envelope: registros do Signal no caminho quente sem IPC ao Keystore.
 * Blob = IV(12) || cifrado+tag(16). aad = endereço da linha ("s|nome|device"…): blob copiado para outra linha não abre.
 */
public final class Envelope {
    private static final int IV = 12, TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static volatile byte[] sKey;

    private Envelope() {}

    /** Dentro do SocialDb.atomic: o Keystore só é tocado na 1ª vez do processo (chave-envelope selada pelo Vault). */
    public static byte[] seal(Context c, byte[] plain, String aad) throws GeneralSecurityException {
        return seal(key(c), plain, aad);
    }

    public static byte[] open(Context c, byte[] blob, String aad) throws GeneralSecurityException {
        return open(key(c), blob, aad);
    }

    static byte[] key(Context c) throws GeneralSecurityException {
        byte[] k = sKey;
        if (k != null) return k;
        if (!SocialDb.inAtomic()) throw new IllegalStateException("Envelope fora de SocialDb.atomic");
        synchronized (Envelope.class) {
            if (sKey != null) return sKey;
            SQLiteDatabase db = SocialDb.get(c);
            try (Cursor q = db.rawQuery("SELECT blob FROM envelope", null)) {
                if (q.moveToFirst()) return sKey = Vault.open(q.getBlob(0));
            }
            k = newKey();
            ContentValues v = new ContentValues(2);
            v.put("id", 0);
            v.put("blob", Vault.seal(k));
            db.insertOrThrow("envelope", null, v);
            return sKey = k;
        }
    }

    static void forget() { sKey = null; }

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
