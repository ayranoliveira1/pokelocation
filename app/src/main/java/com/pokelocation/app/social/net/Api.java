package com.pokelocation.app.social.net;

import android.content.Context;
import android.net.Uri;

import com.pokelocation.app.BuildConfig;
import com.pokelocation.app.social.Session;
import com.pokelocation.app.social.crypto.SignalLoader;
import com.pokelocation.app.social.store.SocialDb;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * REST do pokelocation-server (padrão de net/Elevation), conforme pokelocation-server/docs/api.md. BLOQUEANTE: fora da
 * main (Io para ações do usuário; rede periódica numa thread do social).
 * Único lugar que conhece o JSON do servidor (parse em métodos estáticos puros, testados em ApiTest).
 * 401 numa chamada autenticada → renova o access token uma vez e repete; refresh morto → limpa a sessão e lança
 * Error(401, "unauthorized").
 */
public final class Api {
    private static final int TIMEOUT_MS = 15_000, REFRESH_TIMEOUT_MS = 10_000, MAX_BODY = 65_536;
    private static final Object TOKEN_LOCK = new Object();
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

    /** Pacote de chaves públicas de um amigo (para abrir sessão 1:1). preKey = one-time EC, null se esgotadas. */
    public static final class Bundle { public int registrationId; public byte[] identity; public PreKey signed, kyber, preKey; }
    /** Envelope 1:1 pendente na fila do servidor. */
    public static final class Envelope { public String id, from, clientMsgId; public byte[] payload; }
    /** Última posição cifrada de um amigo (sender key loc:{userId}). */
    public static final class Location { public String userId, friendCode; public byte[] payload; }

    // ---- localização e envelopes (A8). JSON PROVISÓRIO (plano S6/S7, sem api.md): só esta classe muda. ----

    public static Bundle keyBundle(Context c, String userId) throws IOException {
        try {
            return parseBundle(call(c, "GET", "/v1/keys/" + Uri.encode(userId), null));
        } catch (JSONException e) {
            throw new IOException("bad response");
        }
    }

    /** Fila de envelopes 1:1 pendentes (1ª página; o resto vem nas próximas chamadas, depois do ack). */
    public static List<Envelope> envelopes(Context c) throws IOException {
        try {
            return parseEnvelopes(call(c, "GET", "/v1/envelopes", null));
        } catch (JSONException e) {
            throw new IOException("bad response");
        }
    }

    /** Apaga do servidor os envelopes já processados. */
    public static void ack(Context c, List<String> ids) throws IOException {
        if (ids.isEmpty()) return;
        try {
            call(c, "POST", "/v1/envelopes/ack", new JSONObject().put("ids", new JSONArray(ids)));
        } catch (JSONException e) {
            throw new IOException("bad request");
        }
    }

    /** Última posição cifrada dos amigos online (MGET no Redis; some em 60 s). */
    public static List<Location> locations(Context c) throws IOException {
        try {
            return parseLocations(call(c, "GET", "/v1/locations", null));
        } catch (JSONException e) {
            throw new IOException("bad response");
        }
    }

    static Bundle parseBundle(String body) throws JSONException {
        JSONObject o = new JSONObject(body);
        Bundle b = new Bundle();
        b.registrationId = o.getInt("registration_id");
        b.identity = unb64(o.getString("identity_key"));
        b.signed = parsePreKey(o.getJSONObject("signed_prekey"));
        b.kyber = parsePreKey(o.getJSONObject("kyber_prekey"));
        JSONObject ot = o.optJSONObject("one_time_prekey");
        b.preKey = ot == null ? null : parsePreKey(ot);
        return b;
    }

    private static PreKey parsePreKey(JSONObject o) throws JSONException {
        PreKey k = new PreKey();
        k.id = o.getInt("key_id");
        k.pub = unb64(o.getString("public_key"));
        k.sig = o.isNull("signature") ? null : unb64(o.getString("signature"));
        return k;
    }

    static List<Envelope> parseEnvelopes(String body) throws JSONException {
        JSONArray a = new JSONObject(body).optJSONArray("envelopes");
        int n = a == null ? 0 : a.length();
        List<Envelope> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            JSONObject j = a.getJSONObject(i);
            Envelope e = new Envelope();
            e.id = j.getString("id");
            e.from = j.getString("from");
            e.clientMsgId = j.getString("client_msg_id");
            e.payload = unb64(j.getString("payload"));
            out.add(e);
        }
        return out;
    }

    static List<Location> parseLocations(String body) throws JSONException {
        JSONArray a = new JSONObject(body).optJSONArray("locations");
        int n = a == null ? 0 : a.length();
        List<Location> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            JSONObject j = a.getJSONObject(i);
            Location l = new Location();
            l.userId = j.getString("user_id");
            l.friendCode = j.isNull("friend_code") ? null : j.getString("friend_code");
            l.payload = unb64(j.getString("payload"));
            out.add(l);
        }
        return out;
    }

    /** Base64 padrão (com ou sem '='); IllegalArgumentException se tiver caractere fora do alfabeto. */
    static byte[] unb64(String s) {
        int len = s.length();
        while (len > 0 && s.charAt(len - 1) == '=') len--;
        byte[] out = new byte[len * 3 / 4];
        int acc = 0, bits = 0, o = 0;
        for (int i = 0; i < len; i++) {
            char ch = s.charAt(i);
            int v = ch >= 'A' && ch <= 'Z' ? ch - 'A' : ch >= 'a' && ch <= 'z' ? ch - 'a' + 26
                    : ch >= '0' && ch <= '9' ? ch - '0' + 52 : ch == '+' ? 62 : ch == '/' ? 63 : -1;
            if (v < 0) throw new IllegalArgumentException("base64");
            acc = acc << 6 | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out[o++] = (byte) (acc >> bits);
            }
        }
        return out;
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

    /** Linha de tokens no social.db (sob o lock). */
    interface Tokens {
        String[] read() throws Exception;
        void save(String access, String refresh) throws Exception;
    }

    /** POST /v1/auth/refresh → {access, refresh}. */
    interface Refresher { String[] refresh(String refreshToken) throws IOException; }

    /**
     * Puro (ApiTest), já com o lock entre processos: RELÊ do banco; se outro processo já renovou (access diferente do
     * velho), usa o novo sem chamar /refresh, porque reusar o refresh revoga a família. null = sessão morta.
     */
    static String renewLocked(Tokens t, String stale, Refresher r) throws Exception {
        String[] cur = t.read();
        if (cur == null) return null;
        if (!cur[0].equals(stale)) return cur[0];
        String[] fresh;
        try {
            fresh = r.refresh(cur[1]);
        } catch (Error e) {
            // 4xx = refresh revogado/expirado; 429 e 5xx não matam a sessão
            if (e.http >= 400 && e.http < 500 && e.http != 429) return null;
            throw e;
        }
        t.save(fresh[0], fresh[1]);
        return fresh[0];
    }

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

    /**
     * Renovação sob files/token.lock (decisão do maestro), SEPARADO do social.lock: um /refresh lento (até 10 s) nunca
     * segura o estado Signal. Principal e :overlay nunca gastam o mesmo refresh (renewLocked relê do banco). Antes de
     * reler, confere a geração no social.lock (relance curto): sem isso, um processo com o arquivo velho aberto leria
     * tokens fantasmas e um 401 deles limparia a sessão nova. Ordem sempre token → social (nada pega o token.lock
     * dentro do social.lock). Sessão morta → limpa FORA dos locks (o clear apaga o social.db).
     */
    private static String renew(Context c, String stale) throws IOException {
        String fresh;
        try {
            fresh = tokenLocked(new File(c.getFilesDir(), "token.lock"), () -> {
                SocialDb.locked(c, () -> null); // geração: esquece caches/arquivo velhos; logout lá → IllegalStateException
                return renewLocked(new Tokens() {
                    @Override public String[] read() throws IOException { return Session.readTokens(c); }

                    @Override public void save(String access, String refresh) throws IOException {
                        Session.writeTokens(c, access, refresh);
                    }
                }, stale, rt -> {
                    try {
                        return tokens(http(c, "POST", "/v1/auth/refresh", null, obj("refresh_token", rt), REFRESH_TIMEOUT_MS));
                    } catch (JSONException e) {
                        throw new IOException("bad response"); // sem causa: o corpo tem os tokens
                    }
                });
            });
        } catch (IOException e) {
            throw e;
        } catch (IllegalStateException e) {
            fresh = null; // o outro processo fez logout (SocialDb sem o arquivo)
        } catch (Exception e) {
            throw new IOException("renew", e);
        }
        if (fresh == null) throw unauthorized(c);
        SignalLoader.kick(c); // refresh = checagem periódica das prekeys (A3)
        return fresh;
    }

    /** files/token.lock: exclusivo entre processos (FileLock) e entre threads daqui (monitor). Não reentrante. */
    static <T> T tokenLocked(File lock, SocialDb.Op<T> op) throws Exception {
        synchronized (TOKEN_LOCK) {
            try (RandomAccessFile f = new RandomAccessFile(lock, "rw"); FileLock ignored = f.getChannel().lock()) {
                return op.run();
            }
        }
    }

    private static Error unauthorized(Context c) {
        Session.clear(c);
        return new Error(401, "unauthorized");
    }

    /** Login: grava os tokens da resposta. */
    private static void store(Context c, String body) throws IOException {
        String[] t;
        try {
            t = tokens(body);
        } catch (JSONException e) {
            throw new IOException("bad response"); // sem causa: o corpo tem os tokens e o Io.run loga a stack
        }
        Session.save(c, t[0], t[1]);
    }

    /** Uma ida ao servidor; status ≥ 400 vira Error. Devolve o corpo ("" se vazio). */
    private static String http(Context c, String method, String path, String token, JSONObject body) throws IOException {
        return http(c, method, path, token, body, TIMEOUT_MS);
    }

    private static String http(Context c, String method, String path, String token, JSONObject body, int timeoutMs)
            throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(BuildConfig.SOCIAL_API + path).openConnection();
        // sem disconnect() no caminho feliz: corpo lido até o fim devolve a conexão ao pool (keep-alive, sem novo TLS)
        try {
            con.setConnectTimeout(timeoutMs);
            con.setReadTimeout(timeoutMs);
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
    public static String b64(byte[] b, boolean url) {
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

    /** api.md §4: até 100 por lista por PUT /v1/keys. */
    public static final int MAX_KEYS_PER_PUT = 100;

    static JSONObject keysJson(KeyBundle k) throws JSONException {
        if (k.ec.size() > MAX_KEYS_PER_PUT || k.kyber.size() > MAX_KEYS_PER_PUT) throw new IllegalArgumentException("> 100");
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
