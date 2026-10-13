package com.pokelocation.app.social;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.util.Base64;

import com.pokelocation.app.social.store.SocialDb;
import com.pokelocation.app.social.store.Vault;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.UnrecoverableKeyException;

import javax.crypto.AEADBadTagException;

/**
 * Estado de login (arquivo de prefs "social", fora do "prefs" do app). Só loggedIn é API das telas;
 * o resto é público só porque Api fica em social.net. Escritas com commit(): roda no Io, e perder o refresh
 * token rotacionado num kill derrubaria a sessão (reuso revoga a família).
 */
public final class Session {
    private static final String FILE = "social", IN = "in", ACCESS = "a", REFRESH = "r", VERIFIER = "pv", STATE = "ps";
    private static volatile String sAccess;

    private Session() {}

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences(FILE, Context.MODE_PRIVATE); }

    /** Barato e seguro na main: só lê um flag em prefs, não toca Vault/Keystore/Db. */
    public static boolean loggedIn(Context c) { return sp(c).getBoolean(IN, false); }

    public static void save(Context c, String access, String refresh) throws GeneralSecurityException {
        boolean ok = sp(c).edit().putString(ACCESS, seal(access)).putString(REFRESH, seal(refresh)).putBoolean(IN, true).commit();
        if (!ok) throw new GeneralSecurityException("prefs");
        sAccess = access;
    }

    /**
     * null = sem sessão (token ausente ou ilegível para sempre: chave do Vault perdida).
     * IOException = Keystore falhou agora (transitório): quem chama NÃO deve limpar a sessão.
     */
    public static String access(Context c) throws IOException {
        String a = sAccess;
        if (a == null) sAccess = a = open(sp(c).getString(ACCESS, null));
        return a;
    }

    public static String refresh(Context c) throws IOException { return open(sp(c).getString(REFRESH, null)); }

    /** Limpa tokens, PKCE pendente, social.db (chaves do Signal) e a chave do Vault. Só em logout/sessão morta. */
    public static void clear(Context c) {
        sAccess = null;
        sp(c).edit().clear().commit();
        SocialDb.delete(c);
        Vault.wipe();
    }

    public static void savePkce(Context c, String verifier, String state) {
        sp(c).edit().putString(VERIFIER, verifier).putString(STATE, state).apply(); // main (clique do login)
    }

    /** Verifier do PKCE pendente, consumido (uso único) só se state bater; null se não houver ou não bater. */
    public static String takePkce(Context c, String state) {
        SharedPreferences p = sp(c);
        String v = p.getString(VERIFIER, null);
        if (v == null || state == null || !state.equals(p.getString(STATE, null))) return null;
        p.edit().remove(VERIFIER).remove(STATE).commit();
        return v;
    }

    private static String seal(String s) throws GeneralSecurityException {
        return Base64.encodeToString(Vault.seal(s.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
    }

    private static String open(String b64) throws IOException {
        if (b64 == null) return null;
        try {
            return new String(Vault.open(Base64.decode(b64, Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (AEADBadTagException | KeyPermanentlyInvalidatedException | UnrecoverableKeyException
                 | IllegalArgumentException e) {
            return null; // chave perdida/invalidada ou blob corrompido: nunca mais abre
        } catch (GeneralSecurityException e) {
            throw new IOException("keystore", e);
        }
    }
}
