package com.pokelocation.app.social;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.util.Base64;

import com.pokelocation.app.App;
import com.pokelocation.app.social.crypto.SignalLoader;
import com.pokelocation.app.social.store.SocialDb;
import com.pokelocation.app.social.store.Vault;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.UnrecoverableKeyException;

import javax.crypto.AEADBadTagException;

/**
 * Estado de login. Flag "in" e PKCE no prefs "social" (fora do "prefs" do app); access/refresh no social.db (tabela
 * session, selados pelo Vault): principal e :overlay leem o mesmo arquivo, e a renovação roda sob o social.lock
 * (decisão do maestro, Api.renew). Só loggedIn é API das telas; o resto é público só porque Api fica em social.net.
 */
public final class Session {
    private static final String FILE = "social", IN = "in", VERIFIER = "pv", STATE = "ps";
    /** Último access lido/gravado no banco por este processo; num 401 a Api relê do banco sob o lock. */
    private static volatile String sAccess;

    private Session() {}

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences(FILE, Context.MODE_PRIVATE); }

    /**
     * Barato e seguro na main: só lê um flag em prefs, não toca Vault/Keystore/Db. Vale no processo principal (o único
     * que escreve o prefs). No :overlay o cache do prefs pode estar velho: lá "logado" = access(c) != null.
     */
    public static boolean loggedIn(Context c) { return sp(c).getBoolean(IN, false); }

    /** Login (Io): grava os tokens no social.db sob o lock e liga o flag. IOException("keystore", GSE) se o Vault falhar. */
    public static void save(Context c, String access, String refresh) throws IOException {
        for (int i = 0; ; i++) {
            try {
                SocialDb.atomic(c, true, () -> {
                    SocialDb.advance(); // decisão do maestro: login e logout abrem geração nova
                    writeTokens(c, access, refresh);
                    return null;
                });
                break;
            } catch (IllegalStateException e) {
                // o arquivo velho foi apagado por um logout no outro processo: o sync fechou; a 2ª volta cria o novo
                if (i > 0) throw new IOException("session", e);
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException("session", e);
            }
        }
        // commit(): sem o flag gravado, um kill agora deixaria tokens órfãos e a tela no login
        if (!sp(c).edit().putBoolean(IN, true).commit()) throw new IOException("prefs");
        SignalLoader.kick(c);
    }

    /**
     * Access token (Io, qualquer processo). Sempre sob o social.lock: se a geração mudou (logout no outro processo) o
     * access em memória já foi esquecido. null = sem sessão (sem arquivo/linha, logout no outro processo, ou ilegível
     * para sempre). IOException = Keystore falhou agora (transitório): quem chama NÃO deve limpar a sessão.
     */
    public static String access(Context c) throws IOException {
        return locked(c, () -> {
            String a = sAccess;
            if (a != null) return a;
            String[] t = readTokens(c);
            return t == null ? null : t[0];
        });
    }

    /** Refresh token (logout). null como em access(). */
    public static String refresh(Context c) throws IOException {
        String[] t = locked(c, () -> readTokens(c));
        return t == null ? null : t[1];
    }

    /** SocialDb.sync: a geração mudou, o access em memória é da conta/sessão velha. */
    public static void forget() { sAccess = null; }

    /** {access, refresh} do banco (null sem arquivo/linha), sob o social.lock. Atualiza o access em memória. */
    public static String[] readTokens(Context c) throws IOException {
        if (!SocialDb.exists(c)) return null; // não cria um social.db vazio sem login
        try (Cursor q = SocialDb.get(c).rawQuery("SELECT access, refresh FROM session", null)) {
            if (!q.moveToFirst()) return null;
            String a = open(q.getBlob(0)), r = open(q.getBlob(1));
            if (a == null || r == null) return null;
            sAccess = a;
            return new String[]{a, r};
        }
    }

    /** Grava a linha única, sob o social.lock (SocialDb.atomic). */
    public static void writeTokens(Context c, String access, String refresh) throws IOException {
        ContentValues v = new ContentValues(3);
        v.put("id", 0);
        try {
            v.put("access", Vault.seal(access.getBytes(StandardCharsets.UTF_8)));
            v.put("refresh", Vault.seal(refresh.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IOException("keystore", e);
        }
        SocialDb.get(c).insertWithOnConflict("session", null, v, SQLiteDatabase.CONFLICT_REPLACE);
        sAccess = access;
    }

    /** SocialDb.locked com os erros no padrão do Session: IOException passa; logout no outro processo = null. */
    private static <T> T locked(Context c, SocialDb.Op<T> op) throws IOException {
        try {
            return SocialDb.locked(c, op);
        } catch (IOException e) {
            throw e;
        } catch (IllegalStateException e) {
            return null; // social.db apagado pelo outro processo (logout)
        } catch (Exception e) {
            throw new IOException("session", e);
        }
    }

    /**
     * Limpa tokens, PKCE pendente, social.db (chaves do Signal) e a chave do Vault. Só em logout/sessão morta e SÓ no
     * processo principal (um só escritor do prefs/Keystore/arquivo): no :overlay é no-op e a sessão morta só para ali;
     * o principal limpa na próxima chamada dele.
     */
    public static void clear(Context c) {
        if (App.process() != App.PROC_MAIN) return;
        sAccess = null;
        sp(c).edit().clear().commit();
        SocialDb.delete(c);
        Vault.wipe();
    }

    /** Verifier e state do PKCE cifrados pelo Vault (Io: o Keystore pode bloquear). IOException = Keystore falhou. */
    public static void savePkce(Context c, String verifier, String state) throws IOException {
        try {
            // sem gravar, a volta do navegador daria invalid_state sem explicação
            if (!sp(c).edit().putString(VERIFIER, seal(verifier)).putString(STATE, seal(state)).commit()) throw new IOException("prefs");
        } catch (GeneralSecurityException e) {
            throw new IOException("keystore", e);
        }
    }

    /**
     * Verifier do PKCE pendente, consumido (uso único) só se state bater; null se não houver, não bater ou estiver
     * ilegível para sempre. IOException = Keystore falhou agora (o pendente fica para tentar de novo).
     */
    public static String takePkce(Context c, String state) throws IOException {
        SharedPreferences p = sp(c);
        String v = open(p.getString(VERIFIER, null));
        if (v == null || state == null || !state.equals(open(p.getString(STATE, null)))) return null;
        p.edit().remove(VERIFIER).remove(STATE).commit();
        return v;
    }

    private static String seal(String s) throws GeneralSecurityException {
        return Base64.encodeToString(Vault.seal(s.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
    }

    private static String open(String b64) throws IOException {
        if (b64 == null) return null;
        try {
            return open(Base64.decode(b64, Base64.NO_WRAP));
        } catch (IllegalArgumentException e) {
            return null; // base64 corrompido
        }
    }

    private static String open(byte[] blob) throws IOException {
        if (blob == null) return null;
        try {
            return new String(Vault.open(blob), StandardCharsets.UTF_8);
        } catch (AEADBadTagException | KeyPermanentlyInvalidatedException | UnrecoverableKeyException
                 | IllegalArgumentException e) {
            return null; // chave perdida/invalidada ou blob corrompido: nunca mais abre
        } catch (GeneralSecurityException e) {
            throw new IOException("keystore", e);
        }
    }
}
