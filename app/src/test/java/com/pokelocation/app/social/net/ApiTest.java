package com.pokelocation.app.social.net;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONException;
import org.junit.Test;

public class ApiTest {
    @Test public void normalizeCode_crockfordAliases() {
        assertEquals("AB1C0DE1", Api.normalizeCode(" ab-iC o-de l "));
    }

    @Test public void normalizeCode_rejectsWrongLengthOrLetterU() {
        assertNull(Api.normalizeCode("ABCDEFG"));
        assertNull(Api.normalizeCode("ABCDEFGHJ"));
        assertNull(Api.normalizeCode("ABCDEFGU"));
        assertNull(Api.normalizeCode("ABCD!FGH"));
        assertNull(Api.normalizeCode(null));
    }

    /** RFC 7636, apêndice B. */
    @Test public void pkce_s256_rfc7636() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
                Api.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
    }

    @Test public void b64url_noPadding() {
        assertEquals("", Api.b64url(new byte[0]));
        assertEquals("-_8", Api.b64url(new byte[]{(byte) 0xfb, (byte) 0xff}));
        assertEquals("Zm9vYg", Api.b64url("foob".getBytes()));
    }

    /** social.db falso: a linha única de tokens, compartilhada pelos dois "processos". */
    private static final class Db implements Api.Tokens {
        String access = "a0", refresh = "r0";

        @Override public synchronized String[] read() { return access == null ? null : new String[]{access, refresh}; }

        @Override public synchronized void save(String a, String r) {
            access = a;
            refresh = r;
        }
    }

    /** Dois processos com o mesmo access vencido → exatamente UM /refresh (reuso revoga a família). */
    @Test public void concurrentRenewCallsRefreshOnce() throws Exception {
        java.io.File lock = java.io.File.createTempFile("social", ".lock");
        lock.deleteOnExit();
        Db db = new Db();
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        Api.Refresher server = rt -> {
            int n = calls.incrementAndGet();
            assertEquals("r0", rt); // nunca o refresh já usado
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                throw new java.io.IOException(e);
            }
            return new String[]{"a" + n, "r" + n};
        };
        String[] got = new String[2];
        Thread[] ts = new Thread[2];
        for (int i = 0; i < 2; i++) {
            int k = i;
            ts[i] = new Thread(() -> {
                try {
                    got[k] = Api.tokenLocked(lock, () -> Api.renewLocked(db, "a0", server));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            ts[i].start();
        }
        for (Thread t : ts) t.join();
        assertEquals(1, calls.get());
        assertEquals("a1", got[0]);
        assertEquals("a1", got[1]);
        assertEquals("r1", db.refresh);
    }

    @Test public void otherProcessAlreadyRenewed() throws Exception {
        Db db = new Db();
        db.access = "a5";
        assertEquals("a5", Api.renewLocked(db, "a0", rt -> { throw new AssertionError("não pode chamar /refresh"); }));
    }

    @Test public void deadRefreshIsNullButRateLimitIsTransient() throws Exception {
        assertNull(Api.renewLocked(new Db(), "a0", rt -> { throw new Api.Error(401, "unauthorized"); }));
        assertNull(Api.renewLocked(new Db(), "a0", rt -> { throw new Api.Error(400, "invalid"); }));
        try {
            Api.renewLocked(new Db(), "a0", rt -> { throw new Api.Error(429, "rate_limited"); });
            org.junit.Assert.fail();
        } catch (Api.Error e) {
            assertEquals(429, e.http);
        }
        Db none = new Db();
        none.access = null;
        assertNull(Api.renewLocked(none, "a0", rt -> { throw new AssertionError(); }));
    }

    /** O limite de leitura é parâmetro por chamada; só o GET /v1/locations lê até 256 KiB (500 amigos ≈ 128 KB). */
    @Test public void bodyLimitPerCall() throws java.io.IOException {
        assertEquals(256 * 1024, Api.LOCATIONS_MAX_BODY);
        assertEquals(64 * 1024, Api.MAX_BODY);
        byte[] big = new byte[200 * 1024];
        assertEquals(big.length, Api.read(new java.io.ByteArrayInputStream(big), Api.LOCATIONS_MAX_BODY).length());
        try {
            Api.read(new java.io.ByteArrayInputStream(big), Api.MAX_BODY);
            org.junit.Assert.fail();
        } catch (java.io.IOException expected) {
        }
    }

    @Test public void unb64RoundTrip() {
        for (int n = 0; n < 7; n++) {
            byte[] b = new byte[n];
            for (int i = 0; i < n; i++) b[i] = (byte) (i * 77 - 3);
            assertArrayEquals(b, Api.unb64(Api.b64(b, false)));
        }
        assertArrayEquals("foob".getBytes(), Api.unb64("Zm9vYg=="));
    }

    @Test(expected = IllegalArgumentException.class) public void unb64RejectsGarbage() { Api.unb64("Zm9v!g=="); }

    @Test public void bundle() throws JSONException {
        Api.Bundle b = Api.parseBundle("{\"registration_id\":7,\"identity_key\":\"aWQ=\","
                + "\"signed_prekey\":{\"key_id\":1,\"public_key\":\"c3A=\",\"signature\":\"c3M=\"},"
                + "\"kyber_prekey\":{\"key_id\":5,\"public_key\":\"a3A=\",\"signature\":\"a3M=\"},"
                + "\"one_time_prekey\":{\"key_id\":9,\"public_key\":\"ZXA=\"}}");
        assertEquals(7, b.registrationId);
        assertArrayEquals("id".getBytes(), b.identity);
        assertEquals(1, b.signed.id);
        assertArrayEquals("ss".getBytes(), b.signed.sig);
        assertEquals(5, b.kyber.id);
        assertEquals(9, b.preKey.id);
        assertNull(b.preKey.sig);
        assertNull(Api.parseBundle("{\"registration_id\":7,\"identity_key\":\"aWQ=\","
                + "\"signed_prekey\":{\"key_id\":1,\"public_key\":\"c3A=\",\"signature\":\"c3M=\"},"
                + "\"kyber_prekey\":{\"key_id\":5,\"public_key\":\"a3A=\",\"signature\":\"a3M=\"}}").preKey);
    }

    @Test public void envelopesAndLocations() throws JSONException {
        java.util.List<Api.Envelope> es = Api.parseEnvelopes("{\"envelopes\":[{\"id\":\"e1\",\"from\":\"u2\","
                + "\"client_msg_id\":\"m\",\"payload\":\"AwkI\"}]}");
        assertEquals(1, es.size());
        assertEquals("e1", es.get(0).id);
        assertEquals("u2", es.get(0).from);
        assertEquals("m", es.get(0).clientMsgId); // api.md §5: dedup por (from, client_msg_id)
        assertArrayEquals(new byte[]{3, 9, 8}, es.get(0).payload);
        assertEquals(0, Api.parseEnvelopes("{}").size());
        java.util.List<Api.Location> ls = Api.parseLocations("{\"locations\":[{\"user_id\":\"u2\",\"friend_code\":\"AB12CD34\","
                + "\"payload\":\"AwkI\"},{\"user_id\":\"u3\",\"payload\":\"AA==\"}]}");
        assertEquals(2, ls.size());
        assertEquals("AB12CD34", ls.get(0).friendCode);
        assertNull(ls.get(1).friendCode);
        assertArrayEquals(new byte[]{0}, ls.get(1).payload);
    }

    @Test public void b64std_padded() {
        assertEquals("", Api.b64(new byte[0], false));
        assertEquals("+/8=", Api.b64(new byte[]{(byte) 0xfb, (byte) 0xff}, false));
        assertEquals("Zm9vYg==", Api.b64("foob".getBytes(), false));
        assertEquals("Zm9vYmFy", Api.b64("foobar".getBytes(), false));
    }

    private static Api.PreKey pk(int id, String pub, String sig) {
        Api.PreKey k = new Api.PreKey();
        k.id = id;
        k.pub = pub.getBytes();
        k.sig = sig == null ? null : sig.getBytes();
        return k;
    }

    @Test public void keysJson() throws JSONException {
        Api.KeyBundle b = new Api.KeyBundle();
        b.registrationId = 42;
        b.identity = "id".getBytes();
        b.signed = pk(1, "sp", "ss");
        b.lastResort = pk(1, "lp", "ls");
        b.ec = java.util.Collections.singletonList(pk(7, "ep", null));
        b.kyber = java.util.Collections.singletonList(pk(8, "kp", "ks"));
        org.json.JSONObject o = new org.json.JSONObject(Api.keysJson(b).toString());
        assertEquals(42, o.getInt("registration_id"));
        assertEquals("aWQ=", o.getString("identity_key"));
        assertEquals(1, o.getJSONObject("signed_prekey").getInt("key_id"));
        assertEquals("c3A=", o.getJSONObject("signed_prekey").getString("public_key"));
        assertEquals("c3M=", o.getJSONObject("signed_prekey").getString("signature"));
        assertEquals("bHA=", o.getJSONObject("kyber_last_resort").getString("public_key"));
        org.json.JSONObject ec = o.getJSONArray("one_time_prekeys").getJSONObject(0);
        assertEquals(7, ec.getInt("key_id"));
        assertEquals("ZXA=", ec.getString("public_key"));
        assertEquals(false, ec.has("signature"));
        org.json.JSONObject ky = o.getJSONArray("kyber_prekeys").getJSONObject(0);
        assertEquals(8, ky.getInt("key_id"));
        assertEquals("a3M=", ky.getString("signature"));
    }

    /** api.md §4: até 100 por lista por PUT (acima → 400 bad_request no servidor): o app nunca monta um maior. */
    @Test(expected = IllegalArgumentException.class) public void keysJsonRejectsMoreThan100PerList() throws JSONException {
        Api.KeyBundle b = new Api.KeyBundle();
        b.identity = new byte[33];
        b.signed = pk(1, "s", "s");
        b.lastResort = pk(1, "l", "l");
        b.ec = new java.util.ArrayList<>();
        for (int i = 0; i < 101; i++) b.ec.add(pk(i, "e", null));
        b.kyber = java.util.Collections.emptyList();
        Api.keysJson(b);
    }

    /** Logout manda {"token": null}: o put(k, null) do org.json apagaria a chave e o servidor leria corpo sem token. */
    @Test public void fcmJsonKeepsExplicitNull() throws JSONException {
        assertEquals("abc", new org.json.JSONObject(Api.fcmJson("abc").toString()).getString("token"));
        org.json.JSONObject off = new org.json.JSONObject(Api.fcmJson(null).toString());
        assertTrue(off.has("token"));
        assertTrue(off.isNull("token"));
    }

    /** api.md §8: 3–20 [a-z0-9_.], começa com letra, sem "..", sem "." no fim; o app baixa maiúsculas e tira o "@". */
    @Test public void normalizeUsername() {
        assertEquals("ana.b_9", Api.normalizeUsername("  @Ana.B_9 "));
        assertEquals("abc", Api.normalizeUsername("abc"));
        assertEquals("a2345678901234567890", Api.normalizeUsername("a2345678901234567890"));
        assertNull(Api.normalizeUsername("ab"));
        assertNull(Api.normalizeUsername("a23456789012345678901"));
        assertNull(Api.normalizeUsername("1abc"));
        assertNull(Api.normalizeUsername("_abc"));
        assertNull(Api.normalizeUsername("ab..c"));
        assertNull(Api.normalizeUsername("abc."));
        assertNull(Api.normalizeUsername("joão"));
        assertNull(Api.normalizeUsername("ab-c"));
        assertNull(Api.normalizeUsername("@"));
        assertNull(Api.normalizeUsername(null));
    }

    /** "@" → só nome; código válido → código e, se também for um @nome válido, o nome depois; senão → só nome. */
    @Test public void friendRequestCandidates() throws JSONException {
        assertEquals(java.util.Collections.singletonList("{\"username\":\"ana\"}"), bodies(Api.friendRequests("@Ana")));
        assertEquals(java.util.Arrays.asList("{\"code\":\"AB1C0DE1\"}"), bodies(Api.friendRequests("ab-iC o-de l")));
        assertEquals(java.util.Arrays.asList("{\"code\":\"ABCDEFGH\"}", "{\"username\":\"abcdefgh\"}"),
                bodies(Api.friendRequests("abcdefgh")));
        assertEquals(java.util.Collections.singletonList("{\"username\":\"maria.silva\"}"),
                bodies(Api.friendRequests("Maria.Silva")));
        assertEquals(0, Api.friendRequests("??").size());
    }

    private static java.util.List<String> bodies(java.util.List<org.json.JSONObject> l) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (org.json.JSONObject o : l) out.add(o.toString());
        return out;
    }

    @Test public void usernameInModels() throws JSONException {
        assertEquals("ana", Api.parseMe("{\"id\":\"u\",\"email\":null,\"friend_code\":\"C\",\"username\":\"ana\"}").username);
        assertNull(Api.parseMe("{\"id\":\"u\",\"email\":null,\"friend_code\":\"C\",\"username\":null}").username);
        Api.Friends f = Api.parseFriends("{\"friends\":[{\"user_id\":\"u2\",\"friend_code\":\"C2\",\"username\":\"bia\"}],"
                + "\"incoming\":[{\"id\":\"r\",\"user_id\":\"u3\",\"friend_code\":\"C3\",\"username\":\"caio\",\"created_at\":1}],"
                + "\"outgoing\":[]}");
        assertEquals("bia", f.accepted.get(0).username);
        assertEquals("caio", f.incoming.get(0).username);
        assertEquals("bia", Api.parseLocations("{\"locations\":[{\"user_id\":\"u2\",\"friend_code\":\"C2\",\"username\":\"bia\","
                + "\"payload\":\"AA==\"}]}").get(0).username);
    }

    @Test public void keyCount() throws JSONException {
        assertArrayEquals(new int[]{19, 100}, Api.parseKeyCount("{\"ec\":19,\"kyber\":100}"));
    }

    @Test public void tokens() throws JSONException {
        assertArrayEquals(new String[]{"a", "r"},
                Api.tokens("{\"access_token\":\"a\",\"refresh_token\":\"r\",\"expires_in\":900}"));
    }

    @Test(expected = JSONException.class) public void tokens_missingField() throws JSONException {
        Api.tokens("{\"access_token\":\"a\"}");
    }

    @Test public void me() throws JSONException {
        Api.Me m = Api.parseMe("{\"id\":\"u1\",\"email\":\"a@b.c\",\"friend_code\":\"AB12CD34\"}");
        assertEquals("u1", m.id);
        assertEquals("a@b.c", m.email);
        assertEquals("AB12CD34", m.friendCode);
    }

    @Test public void me_nullEmail() throws JSONException {
        assertNull(Api.parseMe("{\"id\":\"u1\",\"email\":null,\"friend_code\":\"AB12CD34\"}").email);
        assertNull(Api.parseMe("{\"id\":\"u1\",\"friend_code\":\"AB12CD34\"}").email);
    }

    @Test public void friends() throws JSONException {
        Api.Friends f = Api.parseFriends("{\"friends\":[{\"user_id\":\"u2\",\"friend_code\":\"C2\"}],"
                + "\"incoming\":[{\"id\":\"r1\",\"user_id\":\"u3\",\"friend_code\":\"C3\",\"created_at\":1700000000000}],"
                + "\"outgoing\":[]}");
        assertEquals(1, f.accepted.size());
        assertEquals("u2", f.accepted.get(0).userId);
        assertEquals("C2", f.accepted.get(0).friendCode);
        assertEquals(1, f.incoming.size());
        Api.Request r = f.incoming.get(0);
        assertEquals("r1", r.id);
        assertEquals("u3", r.userId);
        assertEquals("C3", r.friendCode);
        assertEquals(1700000000000L, r.createdAt);
        assertEquals(0, f.outgoing.size());
    }

    @Test public void friends_missingListsAreEmpty() throws JSONException {
        Api.Friends f = Api.parseFriends("{}");
        assertEquals(0, f.accepted.size());
        assertEquals(0, f.incoming.size());
        assertEquals(0, f.outgoing.size());
    }

    @Test public void errorCode() {
        assertEquals("email_taken", Api.errorCode("{\"error\":\"email_taken\"}"));
        assertEquals("unknown", Api.errorCode("<html>502</html>"));
        assertEquals("unknown", Api.errorCode(""));
    }
}
