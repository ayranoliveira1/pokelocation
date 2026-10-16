package com.pokelocation.app.social.net;

import android.content.Context;
import android.net.Uri;

import com.pokelocation.app.App;
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
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * REST do pokelocation-server (pokelocation-server/docs/api.md); único lugar que conhece o JSON. Bloqueante: nunca na
 * main. 401 numa chamada autenticada → renova o access uma vez e repete; refresh morto → limpa a sessão.
 */
public final class Api {
    private static final int TIMEOUT_MS = 15_000, REFRESH_TIMEOUT_MS = 10_000;
    /** 64 KiB em geral; GET /v1/locations até 256 KiB (o servidor corta em 192 KiB). */
    static final int MAX_BODY = 64 * 1024, LOCATIONS_MAX_BODY = 256 * 1024, GROUPS_MAX_BODY = 192 * 1024;
    private static final Object TOKEN_LOCK = new Object();
    private static final String CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final Pattern USERNAME = Pattern.compile("[a-z][a-z0-9_.]{2,19}");
    private static final char[] B64URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray();
    private static final char[] B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
    public static final String REAUTH = "reauth";

    /** code = campo "error" do servidor ("unknown" se não veio o JSON esperado); body = o corpo cru (envelope do /v1/pogo). */
    public static final class Error extends IOException {
        public final int http;
        public final String code, body;

        public Error(int http, String code) { this(http, code, null); }

        Error(int http, String code, String body) {
            super(http + " " + code);
            this.http = http;
            this.code = code;
            this.body = body;
        }

        public boolean endsSession() { return sessionDead(this) || accountGone(this); }
    }

    public static final class Me { public String id, email, friendCode, username; public boolean hasPassword; }
    public static final class Friend { public String userId, friendCode, username, label; }
    public static final class Request { public String id, userId, friendCode, username; public long createdAt; }
    public static final class Friends { public List<Friend> accepted; public List<Request> incoming, outgoing; }

    /** sig = assinatura pela identity; null nas one-time EC. */
    public static final class PreKey { public int id; public byte[] pub, sig; }
    /** ec/kyber = one-time ainda não enviadas. */
    public static final class KeyBundle {
        public int registrationId;
        public byte[] identity;
        public PreKey signed, lastResort;
        public List<PreKey> ec, kyber;
    }

    private Api() {}

    public static void register(Context c, String email, String password, String username) throws IOException {
        store(c, http(c, "POST", "/v1/auth/register", null, obj("email", email, "password", password, "username", username)));
        if (username == null) return;
        Session.username(c, normalizeUsername(username));
        SignalLoader.kick(c); // as chaves e o FCM levaram 403 até aqui
    }

    public static void register(Context c, String email, String password) throws IOException { register(c, email, password, null); }

    public static void setUsername(Context c, String username) throws IOException {
        call(c, "PUT", "/v1/me/username", obj("username", username));
        Session.username(c, normalizeUsername(username));
        SignalLoader.kick(c); // as chaves e o FCM levaram 403 até aqui
    }

    public static void login(Context c, String email, String password) throws IOException {
        store(c, http(c, "POST", "/v1/auth/login", null, obj("email", email, "password", password)));
    }

    /** PKCE (S256) e state vão selados pelo Vault (Keystore), por isso bloqueia: fora da main. Devolve a URL do navegador. */
    public static Uri googleStart(Context c, String returnTo) throws IOException {
        return googleStart(c, returnTo, false);
    }

    public static Uri googleReauthStart(Context c) throws IOException { return googleStart(c, REAUTH, true); }

    private static Uri googleStart(Context c, String returnTo, boolean reauth) throws IOException {
        String verifier = random(32), state = random(16);
        Session.savePkce(c, verifier, state, returnTo);
        Uri.Builder b = Uri.parse(BuildConfig.SOCIAL_API + "/v1/auth/google/start").buildUpon()
                .appendQueryParameter("code_challenge", challenge(verifier))
                .appendQueryParameter("code_challenge_method", "S256")
                .appendQueryParameter("state", state);
        if (reauth) b.appendQueryParameter("reauth", "1");
        return b.build();
    }

    public static void googleFinish(Context c, Uri callback) throws IOException {
        String[] cv = googleCode(c, callback);
        store(c, http(c, "POST", "/v1/auth/google/exchange", null, obj("code", cv[0], "code_verifier", cv[1])));
    }

    public static String googleReauthFinish(Context c, Uri callback) throws IOException {
        String[] cv = googleCode(c, callback);
        try {
            return new JSONObject(call(c, "POST", "/v1/auth/google/exchange",
                    new JSONObject().put("code", cv[0]).put("code_verifier", cv[1]).put("reauth", true))).getString("reauth_token");
        } catch (JSONException e) {
            throw new IOException("bad response");
        }
    }

    private static String[] googleCode(Context c, Uri callback) throws IOException {
        String err = callback.getQueryParameter("error"), code = callback.getQueryParameter("code");
        // state antes de consumir: um callback forjado não aborta o login pendente
        String verifier = Session.takePkce(c, callback.getQueryParameter("state"));
        if (verifier == null) throw new Error(400, "invalid_state");
        if (REAUTH.equals(Session.pendingReturnTo(c))) Session.takeReturnTo(c);
        if (err != null) throw new Error(400, err);
        if (code == null) throw new Error(400, "invalid_state");
        return new String[]{code, verifier};
    }

    public static void deleteAccount(Context c, String password, String reauthToken) throws IOException {
        JSONObject body = password != null ? obj("password", password) : obj("reauth_token", reauthToken);
        deleteAccount(() -> call(c, "DELETE", "/v1/me", body, MAX_BODY, () -> {}), () -> Session.clear(c), () -> Session.ended(c));
    }

    interface Call { void run() throws IOException; }

    static void deleteAccount(Call delete, Runnable clear, Runnable ended) throws IOException {
        try {
            delete.run();
        } catch (Error e) {
            if (!accountGone(e)) {
                if (e.endsSession()) ended.run();
                throw e;
            }
        }
        clear.run();
    }

    static boolean accountGone(Error e) { return e.http == 410 && "account_deleted".equals(e.code); }

    /** Revoga no servidor (melhor esforço) e SEMPRE limpa a sessão local. */
    public static void logout(Context c) {
        try {
            putFcmToken(c, null); // enquanto o access vale: este aparelho para de receber pings desta conta
        } catch (Exception ignored) {
        }
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
            Me m = parseMe(call(c, "GET", "/v1/me", null));
            boolean had = Session.username(c) != null;
            Session.username(c, m.username);
            if (!had && m.username != null) SignalLoader.kick(c); // as chaves esperavam o @nome
            return m;
        } catch (JSONException e) {
            throw new IOException("bad response"); // sem causa: a mensagem do JSONTokener traz o corpo
        }
    }

    public static Friends friends(Context c) throws IOException {
        try {
            return parseFriends(call(c, "GET", "/v1/friends", null));
        } catch (JSONException e) {
            throw new IOException("bad response");
        }
    }

    public static void requestFriend(Context c, String usernameOrCode) throws IOException {
        List<JSONObject> tries;
        try {
            tries = friendRequests(usernameOrCode);
        } catch (JSONException e) {
            throw new IOException("bad request");
        }
        if (tries.isEmpty()) throw new Error(404, "code_not_found");
        for (int i = 0; ; i++) {
            try {
                call(c, "POST", "/v1/friends/requests", tries.get(i));
                return;
            } catch (Error e) {
                if (!"code_not_found".equals(e.code) || i == tries.size() - 1) throw e;
            }
        }
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

    /** friendCode fica null: o GET /v1/blocks não o devolve. */
    public static List<Friend> blocks(Context c) throws IOException {
        try {
            return parseBlocks(call(c, "GET", "/v1/blocks", null));
        } catch (JSONException e) {
            throw new IOException("bad response");
        }
    }

    public static void unblock(Context c, String userId) throws IOException {
        call(c, "DELETE", "/v1/blocks/" + Uri.encode(userId), null);
    }

    /** preKey = one-time EC; null quando acabaram no servidor. */
    public static final class Bundle { public int registrationId; public byte[] identity; public PreKey signed, kyber, preKey; }
    public static final class Envelope { public String id, from, clientMsgId, groupId; public byte[] payload; }
    public static final class Group { public String id, ownerId; public List<Friend> members; public long createdAt; }

    public static String createGroup(Context c, List<String> members) throws IOException {
        try {
            return new JSONObject(call(c, "POST", "/v1/groups", new JSONObject().put("members", new JSONArray(members))))
                    .getString("id");
        } catch (JSONException e) {
            throw new IOException("bad response");
        }
    }

    public static List<Group> groups(Context c) throws IOException {
        try {
            return parseGroups(call(c, "GET", "/v1/groups", null, GROUPS_MAX_BODY));
        } catch (JSONException e) {
            throw new IOException("bad response");
        }
    }

    public static Group group(Context c, String groupId) throws IOException {
        try {
            return parseGroup(new JSONObject(call(c, "GET", "/v1/groups/" + Uri.encode(groupId), null)));
        } catch (JSONException e) {
            throw new IOException("bad response");
        }
    }

    public static void addGroupMember(Context c, String groupId, String userId) throws IOException {
        call(c, "POST", "/v1/groups/" + Uri.encode(groupId) + "/members", obj("user_id", userId));
    }

    public static void removeGroupMember(Context c, String groupId, String userId) throws IOException {
        call(c, "DELETE", "/v1/groups/" + Uri.encode(groupId) + "/members/" + Uri.encode(userId), null);
    }

    static List<Group> parseGroups(String body) throws JSONException {
        JSONArray a = new JSONObject(body).optJSONArray("groups");
        int n = a == null ? 0 : a.length();
        List<Group> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(parseGroup(a.getJSONObject(i)));
        return out;
    }

    private static Group parseGroup(JSONObject o) throws JSONException {
        Group g = new Group();
        g.id = o.getString("id");
        g.ownerId = optString(o, "owner_id");
        g.createdAt = o.optLong("created_at");
        JSONArray m = o.optJSONArray("members");
        int n = m == null ? 0 : m.length();
        g.members = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            JSONObject j = m.getJSONObject(i);
            Friend f = new Friend();
            f.userId = j.getString("user_id");
            f.username = optString(j, "username");
            g.members.add(f);
        }
        return g;
    }
    public static final class Location { public String userId, friendCode, username; public byte[] payload; }

    public static void putFcmToken(Context c, String token) throws IOException {
        try {
            call(c, "PUT", "/v1/devices/fcm", fcmJson(token));
        } catch (JSONException e) {
            throw new IOException("bad request");
        }
    }

    /** null = parar de receber pings; vai como null explícito (o put(k, null) do org.json apagaria a chave). */
    static JSONObject fcmJson(String token) throws JSONException {
        return new JSONObject().put("token", token == null ? JSONObject.NULL : token);
    }

    public static Bundle keyBundle(Context c, String userId) throws IOException {
        try {
            return parseBundle(call(c, "GET", "/v1/keys/" + Uri.encode(userId), null));
        } catch (JSONException e) {
            throw new IOException("bad response");
        }
    }

    /** Uma página; a próxima vem depois do ack. */
    public static List<Envelope> envelopes(Context c) throws IOException {
        try {
            return parseEnvelopes(call(c, "GET", "/v1/envelopes", null));
        } catch (JSONException e) {
            throw new IOException("bad response");
        }
    }

    public static void ack(Context c, List<String> ids) throws IOException {
        if (ids.isEmpty()) return;
        try {
            call(c, "POST", "/v1/envelopes/ack", new JSONObject().put("ids", new JSONArray(ids)));
        } catch (JSONException e) {
            throw new IOException("bad request");
        }
    }

    public static List<Location> locations(Context c) throws IOException {
        try {
            return parseLocations(call(c, "GET", "/v1/locations", null, LOCATIONS_MAX_BODY));
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
            e.groupId = optString(j, "group_id");
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
            l.friendCode = optString(j, "friend_code");
            l.username = optString(j, "username");
            l.payload = unb64(j.getString("payload"));
            out.add(l);
        }
        return out;
    }

    /** "sub" do access token (o meu id), sem verificar a assinatura: só identifica, quem valida é o servidor. */
    public static String jwtSubject(String jwt) {
        if (jwt == null) return null;
        String[] parts = jwt.split("\\.");
        if (parts.length != 3) return null;
        try {
            byte[] json = unb64(parts[1].replace('-', '+').replace('_', '/'));
            return optString(new JSONObject(new String(json, StandardCharsets.UTF_8)), "sub");
        } catch (JSONException | IllegalArgumentException e) {
            return null;
        }
    }

    /** Ausente ou null → null (o optString(k, null) do Android devolve "null"). */
    private static String optString(JSONObject o, String k) throws JSONException { return o.isNull(k) ? null : o.getString(k); }

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

    public static void putKeys(Context c, KeyBundle k) throws IOException {
        try {
            call(c, "PUT", "/v1/keys", keysJson(k));
        } catch (JSONException e) {
            throw new IOException("bad request");
        }
    }

    /** {ec, kyber}: one-time que o servidor ainda tem deste aparelho. */
    public static int[] keyCount(Context c) throws IOException {
        try {
            return parseKeyCount(call(c, "GET", "/v1/keys/count", null));
        } catch (JSONException e) {
            throw new IOException("bad response");
        }
    }

    public static String normalizeUsername(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.startsWith("@")) s = s.substring(1);
        s = s.toLowerCase(Locale.ROOT);
        return USERNAME.matcher(s).matches() && !s.contains("..") && !s.endsWith(".") ? s : null;
    }

    static List<JSONObject> friendRequests(String raw) throws JSONException {
        List<JSONObject> out = new ArrayList<>(2);
        if (raw == null) return out;
        String t = raw.trim();
        String code = t.startsWith("@") ? null : normalizeCode(t), user = normalizeUsername(t);
        if (code != null) out.add(new JSONObject().put("code", code));
        if (user != null) out.add(new JSONObject().put("username", user));
        return out;
    }

    /** Crockford base32, 8 chars: maiúsculas, I/L→1, O→0, sem espaços e '-'. null se inválido. */
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

    /**
     * O WS só apresenta o token no Auth: vencido, o servidor fecha (1008) e a reconexão repetiria o mesmo. null = sessão
     * morta (refresh revogado).
     */
    public static String wsToken(Context c) throws IOException {
        String a = Session.access(c);
        if (a == null || !expiresSoon(a, System.currentTimeMillis())) return a;
        try {
            return renew(c, a);
        } catch (Error e) {
            if (e.http == 401) return null;
            throw e;
        }
    }

    static boolean expiresSoon(String jwt, long now) {
        String[] parts = jwt.split("\\.");
        if (parts.length != 3) return false;
        try {
            JSONObject o = new JSONObject(new String(unb64(parts[1].replace('-', '+').replace('_', '/')), StandardCharsets.UTF_8));
            return o.has("exp") && o.getLong("exp") * 1000 - now < 60_000;
        } catch (JSONException | IllegalArgumentException e) {
            return false;
        }
    }

    /** Linha de tokens no social.db; usar com o token.lock. */
    interface Tokens {
        String[] read() throws Exception;
        void save(String access, String refresh) throws Exception;
    }

    interface Refresher { String[] refresh(String refreshToken) throws IOException; }

    /**
     * Já com o token.lock: relê do banco; se outro processo já renovou (access diferente do velho), usa o novo sem
     * chamar /refresh, porque reusar o refresh revoga a família. null = sessão morta.
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

    private static String call(Context c, String method, String path, JSONObject body) throws IOException {
        return call(c, method, path, body, MAX_BODY);
    }

    private static String call(Context c, String method, String path, JSONObject body, int maxBody) throws IOException {
        return call(c, method, path, body, maxBody, () -> Session.ended(c));
    }

    private static String call(Context c, String method, String path, JSONObject body, int maxBody, Runnable ended)
            throws IOException {
        String token = Session.access(c);
        if (token == null) throw unauthorized(c);
        return authed(token, t -> http(c, method, path, t, body, TIMEOUT_MS, maxBody), t -> renew(c, t), ended);
    }

    /** /v1/pogo/* com o nosso Bearer (renovação entre processos como no resto); o pogo lê o envelope no Error.body. */
    public static String pogo(Context c, String method, String path, JSONObject body, int maxBody, int timeoutMs)
            throws IOException {
        String token = Session.access(c);
        if (token == null) throw unauthorized(c);
        return authed(token, t -> http(c, method, path, t, body, timeoutMs, maxBody), t -> renew(c, t), () -> Session.ended(c));
    }

    interface Attempt { String run(String token) throws IOException; }

    static String authed(String token, Attempt http, Attempt renew, Runnable clear) throws IOException {
        try {
            try {
                return http.run(token);
            } catch (Error e) {
                if (!sessionDead(e)) throw e;
            }
            return http.run(renew.run(token));
        } catch (Error e) {
            if (e.endsSession()) clear.run();
            throw e;
        }
    }

    static boolean sessionDead(Error e) { return e.http == 401 && "unauthorized".equals(e.code); }

    /**
     * Sob files/token.lock, separado do social.lock: um /refresh lento (até 10 s) não segura o estado Signal. Antes de
     * reler os tokens passa pelo social.lock para conferir a geração: um processo com o arquivo velho aberto leria
     * tokens fantasmas, e o 401 deles limparia a sessão nova. Ordem sempre token → social. Sessão morta → limpa fora
     * dos locks (o clear apaga o social.db).
     */
    private static String renew(Context c, String stale) throws IOException {
        String fresh;
        try {
            fresh = tokenLocked(new File(c.getFilesDir(), "token.lock"), () -> {
                SocialDb.locked(c, () -> null); // geração; logout no outro processo → IllegalStateException
                return renewLocked(new Tokens() {
                    @Override public String[] read() throws IOException { return Session.readTokens(c); }

                    @Override public void save(String access, String refresh) throws IOException {
                        Session.writeTokens(c, access, refresh);
                    }
                }, stale, rt -> {
                    try {
                        return tokens(http(c, "POST", "/v1/auth/refresh", null, obj("refresh_token", rt), REFRESH_TIMEOUT_MS,
                                MAX_BODY));
                    } catch (JSONException e) {
                        throw new IOException("bad response"); // sem causa: o corpo tem os tokens
                    }
                });
            });
        } catch (IOException e) {
            throw e;
        } catch (IllegalStateException e) {
            fresh = null; // logout no outro processo
        } catch (Exception e) {
            throw new IOException("renew", e);
        }
        if (fresh == null) throw unauthorized(c);
        SignalLoader.kick(c); // cada refresh também confere se faltam prekeys
        return fresh;
    }

    /** Exclusivo entre processos (FileLock) e entre threads (monitor); não reentrante. */
    static <T> T tokenLocked(File lock, SocialDb.Op<T> op) throws Exception {
        synchronized (TOKEN_LOCK) {
            try (RandomAccessFile f = new RandomAccessFile(lock, "rw"); FileLock ignored = f.getChannel().lock()) {
                return op.run();
            }
        }
    }

    /**
     * Renova mesmo com o access ainda válido: o JWT vale até 15 min depois de logout/revogação. Refresh revogado → 401,
     * e quem chama para em vez de reconectar em laço.
     */
    public static void renewNow(Context c) throws IOException {
        String a = Session.access(c);
        if (a == null) throw unauthorized(c);
        renew(c, a);
    }

    private static Error unauthorized(Context c) {
        Session.ended(c);
        return new Error(401, "unauthorized");
    }

    private static void store(Context c, String body) throws IOException {
        String[] t;
        try {
            t = tokens(body);
        } catch (JSONException e) {
            throw new IOException("bad response"); // sem causa: o corpo tem os tokens e o Io.run loga a stack
        }
        Session.save(c, t[0], t[1]);
    }

    private static String http(Context c, String method, String path, String token, JSONObject body) throws IOException {
        return http(c, method, path, token, body, TIMEOUT_MS, MAX_BODY);
    }

    private static String http(Context c, String method, String path, String token, JSONObject body, int timeoutMs,
                               int maxBody) throws IOException {
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
            // :overlay: sem conexão parada no pool (TLS + buffers) depois de uma chamada avulsa
            if (App.process() != App.PROC_MAIN) con.setRequestProperty("Connection", "close");
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
            String text = read(status < 400 ? con.getInputStream() : con.getErrorStream(), maxBody);
            if (status >= 400) throw new Error(status, errorCode(text), text);
            return text;
        } catch (Error e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            con.disconnect();
            throw e;
        }
    }

    static String read(InputStream in, int max) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream bo = new ByteArrayOutputStream(256);
        try (InputStream i = in) {
            byte[] b = new byte[1024];
            int r;
            while ((r = i.read(b)) > 0) {
                if (bo.size() + r > max) throw new IOException("response too large");
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

    /** api.md §4: até 100 por lista em cada PUT /v1/keys. */
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
        m.username = optString(o, "username");
        m.hasPassword = o.optBoolean("has_password");
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
            x.username = optString(j, "username");
            f.accepted.add(x);
        }
        f.incoming = requests(o.optJSONArray("incoming"));
        f.outgoing = requests(o.optJSONArray("outgoing"));
        return f;
    }

    static List<Friend> parseBlocks(String body) throws JSONException {
        JSONArray a = new JSONObject(body).optJSONArray("blocks");
        int n = a == null ? 0 : a.length();
        List<Friend> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            JSONObject j = a.getJSONObject(i);
            Friend x = new Friend();
            x.userId = j.getString("user_id");
            x.username = optString(j, "username");
            out.add(x);
        }
        return out;
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
            r.username = optString(j, "username");
            r.createdAt = j.optLong("created_at");
            out.add(r);
        }
        return out;
    }

    static String errorCode(String body) {
        try {
            return new JSONObject(body).getString("error");
        } catch (JSONException e) {
            return "unknown";
        }
    }
}
