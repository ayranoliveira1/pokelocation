package com.pokelocation.app.social.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.pokelocation.app.social.net.Api;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class RulesTest {
    private static final String G = "11111111-2222-3333-4444-555555555555", H = "99999999-2222-3333-4444-555555555555";

    @Test public void pendingExpiresAfter7Days() {
        long day = 24 * 3600_000L;
        assertFalse(Rules.pendingExpired(0, 7 * day));
        assertTrue(Rules.pendingExpired(0, 7 * day + 1));
    }

    @Test public void groupInfoOnlyFromItsOwnGroupEnvelope() {
        assertEquals(Rules.APPLY, Rules.groupInfo(G, G, true));
        assertEquals(Rules.SYNC, Rules.groupInfo(G, G, false));
        assertEquals(Rules.DROP, Rules.groupInfo(null, G, true));
        assertEquals(Rules.DROP, Rules.groupInfo(G, H, true));
        assertEquals(Rules.DROP, Rules.groupInfo("d:" + G, "d:" + G, true));
    }

    @Test public void resetLimitedPerPeer() {
        Rules.Limiter l = new Rules.Limiter();
        assertTrue(l.allow("a", 0));
        assertFalse(l.allow("a", 9 * 60_000L));
        assertTrue(l.allow("b", 9 * 60_000L));
        assertTrue(l.allow("a", 10 * 60_000L));
    }

    @Test public void outboxRetriesThenFails() {
        assertTrue(Rules.resend(1));
        assertTrue(Rules.resend(2));
        assertFalse(Rules.resend(3));
    }

    @Test public void pingLoggedOutFetchesNothing() throws Exception {
        int[] calls = {0};
        assertEquals(0, Rules.drain(false, () -> { calls[0]++; return true; }));
        assertEquals(0, calls[0]);
    }

    @Test public void pingEmptyQueueIsOneFetch() throws Exception {
        int[] calls = {0};
        assertEquals(1, Rules.drain(true, () -> { calls[0]++; return false; }));
        assertEquals(1, calls[0]);
    }

    @Test public void pingStopsAtPageCeiling() throws Exception {
        int[] calls = {0};
        assertEquals(Rules.PING_PAGES, Rules.drain(true, () -> { calls[0]++; return true; }));
        assertEquals(Rules.PING_PAGES, calls[0]);
    }

    @Test public void notifiesOnlyMessagesFromPeople() {
        assertTrue(Rules.notifies(Chat.TEXT));
        assertTrue(Rules.notifies(Chat.LOC));
        assertTrue(Rules.notifies(Chat.LIVE_START));
        assertTrue(Rules.notifies(Chat.UNREADABLE));
        assertFalse(Rules.notifies(Chat.LIVE_STOP));
        assertFalse(Rules.notifies(Chat.IDENTITY_CHANGED));
    }

    @Test public void liveDueWhenNewOrMoved() {
        Map<String, double[]> sent = new HashMap<>();
        assertEquals(Arrays.asList("a"), Rules.liveDue(Arrays.asList("a"), sent, -23.5, -46.6, 2));
        sent.put("a", new double[]{-23.5, -46.6, 1000});
        assertTrue(Rules.liveDue(Arrays.asList("a"), sent, -23.5, -46.6, 2).isEmpty());
        assertEquals(Arrays.asList("a"), Rules.liveDue(Arrays.asList("a"), sent, -23.5001, -46.6, 2)); // ~11 m
    }

    @Test public void liveDueOldestFirstUpToMax() {
        Map<String, double[]> sent = new HashMap<>();
        sent.put("a", new double[]{0, 0, 3000});
        sent.put("b", new double[]{0, 0, 1000});
        assertEquals(Arrays.asList("c", "b"), Rules.liveDue(Arrays.asList("a", "b", "c"), sent, 1, 1, 2));
    }

    @Test public void lostNoticeOncePerGroupPerDay() {
        long day = 24 * 3600_000L;
        assertEquals(Rules.lostId(G, 5 * day + 1), Rules.lostId(G, 6 * day - 1));
        assertFalse(Rules.lostId(G, 5 * day).equals(Rules.lostId(G, 6 * day)));
        assertFalse(Rules.lostId(G, 5 * day).equals(Rules.lostId(H, 5 * day)));
    }

    @Test public void liveUpdateIdIsEphemeral() {
        String id = Rules.liveId();
        assertTrue(Rules.ephemeral(id));
        assertTrue(id.length() <= 64);
        assertFalse(Rules.ephemeral(java.util.UUID.randomUUID().toString()));
        assertFalse(Rules.ephemeral(Rules.lostId(G, 0)));
    }

    @Test public void sendErrorNeverLeavesMessageSending() {
        Throwable[] errors = {new RuntimeException(), new java.io.IOException(), new NoSuchMethodError(), new NoClassDefFoundError()};
        for (Throwable t : errors) {
            boolean[] failed = {false};
            Rules.sendOrFail(() -> {
                if (t instanceof Error) throw (Error) t;
                throw (Exception) t;
            }, () -> failed[0] = true);
            assertTrue(t.getClass().getSimpleName(), failed[0]);
        }
        boolean[] failed = {false};
        Rules.sendOrFail(() -> { }, () -> failed[0] = true);
        assertFalse(failed[0]);
    }

    @Test public void onlyBrokenSessionAsksReset() {
        assertTrue(Rules.brokenSession(new org.signal.libsignal.protocol.NoSessionException("x")));
        assertTrue(Rules.brokenSession(new org.signal.libsignal.protocol.InvalidMessageException("x")));
        assertFalse(Rules.brokenSession(new org.signal.libsignal.protocol.ReusedBaseKeyException("x")));
        assertFalse(Rules.brokenSession(new org.signal.libsignal.protocol.DuplicateMessageException("x")));
        assertFalse(Rules.brokenSession(new org.signal.libsignal.protocol.LegacyMessageException("x")));
    }

    @Test public void chatSocketLoadsTheLibInsteadOfWaitingForever() {
        assertEquals(Rules.WS_LOAD_LIB, Rules.wsStep(false, false, null));
        assertEquals(Rules.WS_GIVE_UP, Rules.wsStep(false, true, null));
        assertEquals(Rules.WS_NEED_NAME, Rules.wsStep(true, false, null));
        assertEquals(Rules.WS_OPEN, Rules.wsStep(true, false, "nome"));
    }

    @Test public void sendLogShowsOurReasonButNeverOutsideMessages() {
        assertEquals("Unsent left", Rules.cause(new Rules.Unsent("left")));
        String s = Rules.cause(new java.io.IOException("corpo do servidor", new javax.net.ssl.SSLException("token=abc")));
        assertEquals("IOException <- SSLException", s);
    }

    @Test public void peerWithoutKeysFailsAsGoneRightAway() {
        assertEquals("gone", sendOutcome(new Api.Error(403, "forbidden")));
        assertEquals("failed", sendOutcome(new Api.Error(404, "not_found")));
        assertEquals("failed", sendOutcome(new Api.Error(403, "username_required")));
        assertEquals("failed", sendOutcome(new Api.Error(410, "account_deleted")));
        assertEquals("failed", sendOutcome(new java.io.IOException()));
    }

    private static String sendOutcome(Exception e) {
        String[] r = {"none"};
        Rules.sendOrFail(() -> { throw e; }, () -> r[0] = "failed", () -> r[0] = "gone");
        return r[0];
    }

    @Test public void directConversationNamesItsPeer() {
        assertEquals("u1", ChatDb.directPeer(ChatDb.directId("u1")));
        assertEquals(null, ChatDb.directPeer("d:"));
        assertEquals(null, ChatDb.directPeer("2cdc86e2-0000-4000-8000-000000000000"));
    }

    @Test public void forbiddenWithOnlyGroupEnvelopesPendingDoesNotFetchFriends() throws Exception {
        assertTrue(Rules.notFriends(java.util.Collections.emptyList(), () -> { throw new AssertionError("GET /v1/friends"); }).isEmpty());
        assertEquals(Arrays.asList(2L), Rules.notFriends(Arrays.asList(new Object[]{1L, "a"}, new Object[]{2L, "b"}),
                () -> Arrays.asList("a")));
    }
}
