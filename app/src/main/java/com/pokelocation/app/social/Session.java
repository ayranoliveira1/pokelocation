package com.pokelocation.app.social;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.util.Base64;

import com.pokelocation.app.App;
import com.pokelocation.app.social.chat.Chat;
import com.pokelocation.app.social.chat.Live;
import com.pokelocation.app.social.chat.Notify;
import com.pokelocation.app.social.chat.Push;
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
 * session, selados pelo Vault), que principal e :overlay leem; a renovação roda sob o token.lock (Api.renew).
 * Só loggedIn é API das telas; o resto é público só porque Api fica em social.net.
 */
public final class Session {
    private static final String FILE = "social", IN = "in", VERIFIER = "pv", STATE = "ps", RETURN_TO = "pr";
    /** Último access lido/gravado no banco por este processo; num 401 a Api relê do banco sob o lock. */
    private static volatile String sAccess;
    /** O @nome troca mas nunca volta a null: em memória até a geração mudar, sem Keystore a cada leitura. */
    private static volatile String sUsername;

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
                    SocialDb.advance(); // login e logout abrem geração nova
                    SocialDb.get(c).delete("session", null, null); // login por cima de outra conta: nada dela fica
                    sUsername = null;
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

    public static String refresh(Context c) throws IOException {
        String[] t = locked(c, () -> readTokens(c));
        return t == null ? null : t[1];
    }

    /** SocialDb.sync: a geração mudou, o access em memória é da conta/sessão velha. */
    public static void forget() {
        sAccess = null;
        sUsername = null;
    }

    /** {access, refresh} do banco (null sem arquivo/linha), sob o social.lock ou o token.lock. Atualiza o access em memória. */
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

    public static String username(Context c) throws IOException {
        String u = sUsername;
        if (u != null) return u;
        return locked(c, () -> {
            if (!SocialDb.exists(c)) return null;
            try (Cursor q = SocialDb.get(c).rawQuery("SELECT username FROM session", null)) {
                return sUsername = q.moveToFirst() ? open(q.getBlob(0)) : null;
            }
        });
    }

    public static void username(Context c, String username) throws IOException {
        if (App.process() != App.PROC_MAIN || username == null) return;
        locked(c, () -> {
            if (!SocialDb.exists(c)) return null;
            ContentValues v = new ContentValues(1);
            try {
                v.put("username", Vault.seal(username.getBytes(StandardCharsets.UTF_8)));
            } catch (GeneralSecurityException e) {
                throw new IOException("keystore", e);
            }
            SocialDb.get(c).update("session", v, null, null);
            sUsername = username;
            return null;
        });
    }

    public static String fcmSent(Context c) throws IOException {
        return locked(c, () -> {
            if (!SocialDb.exists(c)) return null;
            try (Cursor q = SocialDb.get(c).rawQuery("SELECT fcm FROM session", null)) {
                return q.moveToFirst() ? open(q.getBlob(0)) : null;
            }
        });
    }

    public static void fcmSent(Context c, String token) throws IOException {
        locked(c, () -> {
            if (!SocialDb.exists(c)) return null;
            ContentValues v = new ContentValues(1);
            try {
                v.put("fcm", Vault.seal(token.getBytes(StandardCharsets.UTF_8)));
            } catch (GeneralSecurityException e) {
                throw new IOException("keystore", e);
            }
            SocialDb.get(c).update("session", v, null, null);
            return null;
        });
    }

    // REPLACE apagaria a linha inteira, com o @nome e o token do FCM
    static final String TOKENS_UPDATE = "UPDATE session SET access = ?, refresh = ?";
    static final String TOKENS_INSERT = "INSERT INTO session(id, access, refresh) VALUES (0, ?, ?)";

    /** Grava a linha única, sob o social.lock (login) ou o token.lock (renovação). */
    public static void writeTokens(Context c, String access, String refresh) throws IOException {
        byte[] a, r;
        try {
            a = Vault.seal(access.getBytes(StandardCharsets.UTF_8));
            r = Vault.seal(refresh.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IOException("keystore", e);
        }
        SQLiteDatabase db = SocialDb.get(c);
        int n;
        try (SQLiteStatement u = tokens(db, TOKENS_UPDATE, a, r)) {
            n = u.executeUpdateDelete();
        }
        if (n == 0) {
            try (SQLiteStatement i = tokens(db, TOKENS_INSERT, a, r)) {
                i.executeInsert();
            }
        }
        sAccess = access;
    }

    private static SQLiteStatement tokens(SQLiteDatabase db, String sql, byte[] access, byte[] refresh) {
        SQLiteStatement s = db.compileStatement(sql);
        s.bindBlob(1, access);
        s.bindBlob(2, refresh);
        return s;
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
        if (loggedIn(c)) Push.stop(c); // sem login, o Firebase nem deve subir
        Notify.cancelAll(c);
        Live.forget(c);
        sAccess = null;
        sp(c).edit().clear().commit();
        SocialDb.delete(c);
        Vault.wipe();
        Chat.broadcastLoggedOut(c);
    }

    /** Verifier e state do PKCE cifrados pelo Vault (Io: o Keystore pode bloquear). IOException = Keystore falhou. */
    public static void savePkce(Context c, String verifier, String state, String returnTo) throws IOException {
        try {
            // sem gravar, a volta do navegador daria invalid_state sem explicação
            // returnTo (origem do Login) não é sensível: vai em claro
            if (!sp(c).edit().putString(VERIFIER, seal(verifier)).putString(STATE, seal(state)).putString(RETURN_TO, returnTo)
                    .commit()) {
                throw new IOException("prefs");
            }
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

    /** Origem do Login guardada no googleStart; lida (e apagada) ao fim do googleFinish. null = Main. */
    public static String takeReturnTo(Context c) {
        SharedPreferences p = sp(c);
        String r = p.getString(RETURN_TO, null);
        p.edit().remove(RETURN_TO).apply();
        return r;
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
