package com.pokelocation.app.social.store;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.security.UnrecoverableKeyException;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * AES-256-GCM com chave não exportável do AndroidKeyStore. Blob = IV(12) || cifrado+tag(16).
 * Só usar com login (cria a chave na 1ª chamada). wipe() no logout torna todo blob antigo ilegível.
 */
public final class Vault {
    private static final String ALIAS = "pl_social", KS = "AndroidKeyStore", AES = "AES/GCM/NoPadding";
    private static final int IV = 12, TAG_BITS = 128;
    /** Cache: sem ele cada seal/open faria 2 IPCs a mais ao keystore (getInstance+load+getKey). */
    private static volatile SecretKey sKey;

    private Vault() {}

    public static byte[] seal(byte[] plain) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance(AES);
        c.init(Cipher.ENCRYPT_MODE, key(true));
        byte[] iv = c.getIV(), ct = c.doFinal(plain), out = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(ct, 0, out, iv.length, ct.length);
        return out;
    }

    public static byte[] open(byte[] blob) throws GeneralSecurityException {
        if (blob == null || blob.length < IV + TAG_BITS / 8) throw new GeneralSecurityException("blob");
        Cipher c = Cipher.getInstance(AES);
        c.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(TAG_BITS, blob, 0, IV));
        return c.doFinal(blob, IV, blob.length - IV);
    }

    /** Apaga a chave: blobs antigos viram lixo. Melhor esforço. */
    public static synchronized void wipe() {
        sKey = null;
        try {
            KeyStore ks = KeyStore.getInstance(KS);
            ks.load(null);
            ks.deleteEntry(ALIAS);
        } catch (Exception ignored) {
        }
    }

    /** create só no seal: open sem chave (após wipe) não deixa chave órfã no Keystore. */
    private static synchronized SecretKey key(boolean create) throws GeneralSecurityException {
        if (sKey != null) return sKey;
        try {
            KeyStore ks = KeyStore.getInstance(KS);
            ks.load(null);
            Key k = ks.getKey(ALIAS, null); // chave ilegível → UnrecoverableKeyException (permanente para o Session)
            if (k instanceof SecretKey) return sKey = (SecretKey) k;
        } catch (java.io.IOException e) {
            throw new GeneralSecurityException(e);
        }
        if (!create) throw new UnrecoverableKeyException("no key"); // permanente para o Session
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KS);
        g.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return sKey = g.generateKey();
    }
}
