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
