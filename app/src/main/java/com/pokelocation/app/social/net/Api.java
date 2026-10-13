package com.pokelocation.app.social.net;

import android.content.Context;
import android.net.Uri;

import com.pokelocation.app.BuildConfig;
import com.pokelocation.app.social.Session;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * REST do pokelocation-server (padrão de net/Elevation). BLOQUEANTE: chamar só dentro de Io.run.
 * Único lugar que conhece o JSON do servidor (parse em métodos estáticos puros, testados em ApiTest).
 * 401 numa chamada autenticada → renova o access token uma vez e repete; refresh morto → limpa a sessão e lança
 * Error(401, "unauthorized").
 */
public final class Api {
    private static final int TIMEOUT_MS = 15_000, MAX_BODY = 65_536;
    private static final String CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final char[] B64URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray();
    private static final char[] B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    /** Erro do servidor. code = campo "error" do JSON ("unknown" se não houver); http = status. */
    public static final class Error extends IOException {
        public final int http;
        public final String code;

        public Error(int http, String code) {
            super(http + " " + code);
            this.http = http;
            this.code = code;
        }
    }

    public static final class Me { public String id, email, friendCode; }
    public static final class Friend { public String userId, friendCode; }
    public static final class Request { public String id, userId, friendCode; public long createdAt; /* epoch ms */ }
    public static final class Friends { public List<Friend> accepted; public List<Request> incoming, outgoing; }

    /** Chave pública do Signal para o servidor. sig = assinatura pela identity (null nas one-time EC). */
    public static final class PreKey { public int id; public byte[] pub, sig; }
    /** Pacote de chaves públicas do aparelho (A3). ec/kyber = one-time ainda não enviadas. */
    public static final class KeyBundle {
        public int registrationId;
        public byte[] identity;
        public PreKey signed, lastResort;
        public List<PreKey> ec, kyber;
    }

    private Api() {}

    // ---- conta ----

    public static void register(Context c, String email, String password) throws IOException {
        store(c, http(c, "POST", "/v1/auth/register", null, obj("email", email, "password", password)));
    }

    public static void login(Context c, String email, String password) throws IOException {
        store(c, http(c, "POST", "/v1/auth/login", null, obj("email", email, "password", password)));
    }

    /** Gera PKCE (verifier S256) + state, guarda, devolve a URL para abrir no navegador (ACTION_VIEW). Bloqueia (Keystore): chamar no Io. */
    public static Uri googleStart(Context c) throws IOException {
        String verifier = random(32), state = random(16);
        Session.savePkce(c, verifier, state);
        return Uri.parse(BuildConfig.SOCIAL_API + "/v1/auth/google/start").buildUpon()
                .appendQueryParameter("code_challenge", challenge(verifier))
                .appendQueryParameter("code_challenge_method", "S256")
                .appendQueryParameter("state", state)
                .build();
    }

    /** Recebe o Uri pokelocationauth://callback?code=…&state=…; confere state, troca o code, grava a sessão. */
    public static void googleFinish(Context c, Uri callback) throws IOException {
        String err = callback.getQueryParameter("error"), code = callback.getQueryParameter("code");
        // state antes de consumir: um callback forjado não aborta o login pendente
        String verifier = Session.takePkce(c, callback.getQueryParameter("state"));
        if (verifier == null) throw new Error(400, "invalid_state");
        if (err != null) throw new Error(400, err);
        if (code == null) throw new Error(400, "invalid_state");
        store(c, http(c, "POST", "/v1/auth/google/exchange", null, obj("code", code, "code_verifier", verifier)));
    }

    /** Revoga no servidor (melhor esforço) e SEMPRE limpa a sessão local. */
    public static void logout(Context c) {
        try {
            String r = Session.refresh(c);
            if (r != null) http(c, "POST", "/v1/auth/logout", null, obj("refresh_token", r));
        } catch (Exception ignored) {
        } finally {
            Session.clear(c);
        }
    }

    public static Me me(Context c) throws IOException {
        try {
            return parseMe(call(c, "GET", "/v1/me", null));
        } catch (JSONException e) {
            throw new IOException("bad response"); // sem causa: a mensagem do JSONTokener traz o corpo
        }
    }

    // ---- amigos ----

    public static Friends friends(Context c) throws IOException {
        try {
            return parseFriends(call(c, "GET", "/v1/friends", null));
        } catch (JSONException e) {
            throw new IOException("bad response");
        }
    }

    /** code já normalizado (normalizeCode). */
    public static void requestFriend(Context c, String code) throws IOException {
        call(c, "POST", "/v1/friends/requests", obj("code", code));
    }

    public static void accept(Context c, String requestId) throws IOException {
        call(c, "POST", "/v1/friends/requests/" + Uri.encode(requestId) + "/accept", null);
    }

    public static void decline(Context c, String requestId) throws IOException {
        call(c, "POST", "/v1/friends/requests/" + Uri.encode(requestId) + "/decline", null);
    }

    public static void removeFriend(Context c, String userId) throws IOException {
        call(c, "DELETE", "/v1/friends/" + Uri.encode(userId), null);
    }

    public static void block(Context c, String userId) throws IOException {
        call(c, "POST", "/v1/blocks/" + Uri.encode(userId), null);
    }

    public static void unblock(Context c, String userId) throws IOException {
        call(c, "DELETE", "/v1/blocks/" + Uri.encode(userId), null);
    }

    // ---- chaves do Signal (A3). JSON provisório até o docs/api.md do servidor; só esta classe muda. ----

    public static void putKeys(Context c, KeyBundle k) throws IOException {
        try {
            call(c, "PUT", "/v1/keys", keysJson(k));
        } catch (JSONException e) {
            throw new IOException("bad request");
        }
    }

    /** {ec, kyber}: one-time prekeys que o servidor ainda tem deste aparelho. */
    public static int[] keyCount(Context c) throws IOException {
        try {
            return parseKeyCount(call(c, "GET", "/v1/keys/count", null));
        } catch (JSONException e) {
            throw new IOException("bad response");
        }
    }

    /** Crockford base32, 8 chars: maiúsculas, I/L→1, O→0, remove espaços e '-'. null se inválido. Puro, sem rede. */
    public static String normalizeCode(String raw) {
        if (raw == null) return null;
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < raw.length(); i++) {
            char ch = Character.toUpperCase(raw.charAt(i));
            if (ch == '-' || Character.isWhitespace(ch)) continue;
            if (ch == 'I' || ch == 'L') ch = '1';
            else if (ch == 'O') ch = '0';
            if (CROCKFORD.indexOf(ch) < 0 || sb.length() == 8) return null;
            sb.append(ch);
        }
        return sb.length() == 8 ? sb.toString() : null;
    }

    // ---- transporte ----

    /** Chamada autenticada com renovação única em 401. */
    private static String call(Context c, String method, String path, JSONObject body) throws IOException {
        String token = Session.access(c);
        if (token == null) throw unauthorized(c);
        try {
            return http(c, method, path, token, body);
        } catch (Error e) {
            if (e.http != 401) throw e;
        }
        try {
            return http(c, method, path, renew(c, token), body);
        } catch (Error e) {
            if (e.http == 401) throw unauthorized(c);
            throw e;
        }
    }

    /** synchronized + compara com o token velho: duas threads com 401 não gastam o refresh duas vezes (reuso revoga a família). */
    private static synchronized String renew(Context c, String stale) throws IOException {
        String cur = Session.access(c);
        if (cur != null && !cur.equals(stale)) return cur;
        String refresh = Session.refresh(c);
        if (refresh == null) throw unauthorized(c);
        try {
            return store(c, http(c, "POST", "/v1/auth/refresh", null, obj("refresh_token", refresh)));
        } catch (Error e) {
            // 4xx = refresh revogado/expirado; 429 e 5xx não matam a sessão
            if (e.http >= 400 && e.http < 500 && e.http != 429) throw unauthorized(c);
            throw e;
        }
    }

    private static Error unauthorized(Context c) {
        Session.clear(c);
        return new Error(401, "unauthorized");
    }

    /** Grava os tokens da resposta; devolve o access token. */
    private static String store(Context c, String body) throws IOException {
        try {
            String[] t = tokens(body);
            Session.save(c, t[0], t[1]);
            return t[0];
        } catch (JSONException e) {
            throw new IOException("bad response"); // sem causa: o corpo tem os tokens e o Io.run loga a stack
        } catch (GeneralSecurityException e) {
            throw new IOException("keystore", e);
        }
    }

    /** Uma ida ao servidor; status ≥ 400 vira Error. Devolve o corpo ("" se vazio). */
    private static String http(Context c, String method, String path, String token, JSONObject body) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(BuildConfig.SOCIAL_API + path).openConnection();
        // sem disconnect() no caminho feliz: corpo lido até o fim devolve a conexão ao pool (keep-alive, sem novo TLS)
        try {
            con.setConnectTimeout(TIMEOUT_MS);
            con.setReadTimeout(TIMEOUT_MS);
            con.setInstanceFollowRedirects(false);
            con.setUseCaches(false);
            con.setRequestMethod(method);
            con.setRequestProperty("User-Agent", c.getPackageName() + "/" + BuildConfig.VERSION_NAME);
            con.setRequestProperty("Accept", "application/json");
            if (token != null) con.setRequestProperty("Authorization", "Bearer " + token);
            if (body != null || "POST".equals(method)) {
                byte[] b = body == null ? new byte[0] : body.toString().getBytes(StandardCharsets.UTF_8);
                con.setDoOutput(true);
                con.setFixedLengthStreamingMode(b.length);
                if (body != null) con.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = con.getOutputStream()) {
                    out.write(b);
                }
            }
            int status = con.getResponseCode();
            String text = read(status < 400 ? con.getInputStream() : con.getErrorStream());
            if (status >= 400) throw new Error(status, errorCode(text));
            return text;
        } catch (Error e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            con.disconnect();
            throw e;
        }
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream bo = new ByteArrayOutputStream(256);
        try (InputStream i = in) {
            byte[] b = new byte[1024];
            int r;
            while ((r = i.read(b)) > 0) {
                if (bo.size() + r > MAX_BODY) throw new IOException("response too large");
                bo.write(b, 0, r);
            }
        }
        return bo.toString("UTF-8");
    }

    private static JSONObject obj(String... kv) {
        try {
            JSONObject o = new JSONObject();
            for (int i = 0; i < kv.length; i += 2) o.put(kv[i], kv[i + 1]);
            return o;
        } catch (JSONException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static String random(int bytes) {
        byte[] b = new byte[bytes];
        new SecureRandom().nextBytes(b);
        return b64url(b);
    }

    // ---- puros (ApiTest) ----

    /** PKCE S256: base64url(SHA-256(ASCII(verifier))), sem padding. */
    static String challenge(String verifier) {
        try {
            return b64url(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String b64url(byte[] b) { return b64(b, true); }

    /** RFC 4648: url = §5 sem padding (PKCE); senão §4 com padding ([]byte do Go). Manual: java.util.Base64 é API 26
     *  e android.util.Base64 não roda na JVM. */
    static String b64(byte[] b, boolean url) {
        char[] abc = url ? B64URL : B64;
        StringBuilder sb = new StringBuilder((b.length + 2) / 3 * 4);
        for (int i = 0; i < b.length; i += 3) {
            int left = Math.min(3, b.length - i);
            int n = (b[i] & 0xff) << 16 | (left > 1 ? (b[i + 1] & 0xff) << 8 : 0) | (left > 2 ? b[i + 2] & 0xff : 0);
            for (int j = 0; j <= left; j++) sb.append(abc[n >> (18 - 6 * j) & 63]);
            if (!url) for (int j = left; j < 3; j++) sb.append('=');
        }
        return sb.toString();
    }

    static JSONObject keysJson(KeyBundle k) throws JSONException {
        JSONArray ec = new JSONArray(), ky = new JSONArray();
        for (PreKey p : k.ec) ec.put(preKey(p));
        for (PreKey p : k.kyber) ky.put(preKey(p));
        return new JSONObject()
                .put("registration_id", k.registrationId)
                .put("identity_key", b64(k.identity, false))
                .put("signed_prekey", preKey(k.signed))
                .put("kyber_last_resort", preKey(k.lastResort))
                .put("one_time_prekeys", ec)
                .put("kyber_prekeys", ky);
    }

    private static JSONObject preKey(PreKey p) throws JSONException {
        JSONObject o = new JSONObject().put("key_id", p.id).put("public_key", b64(p.pub, false));
        return p.sig == null ? o : o.put("signature", b64(p.sig, false));
    }

    static int[] parseKeyCount(String body) throws JSONException {
        JSONObject o = new JSONObject(body);
        return new int[]{o.getInt("ec"), o.getInt("kyber")};
    }

    /** {access_token, refresh_token}; JSONException se faltar. */
    static String[] tokens(String body) throws JSONException {
        JSONObject o = new JSONObject(body);
        return new String[]{o.getString("access_token"), o.getString("refresh_token")};
    }

    static Me parseMe(String body) throws JSONException {
        JSONObject o = new JSONObject(body);
        Me m = new Me();
        m.id = o.getString("id");
        m.email = o.isNull("email") ? null : o.getString("email"); // optString(k, null) do Android devolve "null"
        m.friendCode = o.getString("friend_code");
        return m;
    }

    static Friends parseFriends(String body) throws JSONException {
        JSONObject o = new JSONObject(body);
        Friends f = new Friends();
        JSONArray a = o.optJSONArray("friends");
        int n = a == null ? 0 : a.length();
        f.accepted = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            JSONObject j = a.getJSONObject(i);
            Friend x = new Friend();
            x.userId = j.getString("user_id");
            x.friendCode = j.getString("friend_code");
            f.accepted.add(x);
        }
        f.incoming = requests(o.optJSONArray("incoming"));
        f.outgoing = requests(o.optJSONArray("outgoing"));
        return f;
    }

    private static List<Request> requests(JSONArray a) throws JSONException {
        int n = a == null ? 0 : a.length();
        List<Request> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            JSONObject j = a.getJSONObject(i);
            Request r = new Request();
            r.id = j.getString("id");
            r.userId = j.getString("user_id");
            r.friendCode = j.getString("friend_code");
            r.createdAt = j.optLong("created_at");
            out.add(r);
        }
        return out;
    }

    /** Campo "error" do corpo; "unknown" se o corpo não for o JSON esperado. */
    static String errorCode(String body) {
        try {
            return new JSONObject(body).getString("error");
        } catch (JSONException e) {
            return "unknown";
        }
    }
}
