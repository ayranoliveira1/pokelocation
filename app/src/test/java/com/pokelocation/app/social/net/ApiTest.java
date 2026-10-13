package com.pokelocation.app.social.net;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;


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
