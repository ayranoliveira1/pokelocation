package com.pokelocation.app.social.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class RulesTest {
    private static final String G = "11111111-2222-3333-4444-555555555555", H = "99999999-2222-3333-4444-555555555555";

    /** Mensagem de grupo sem a sender key espera a SKDM por 7 dias na fila; depois vira ilegível. */
    @Test public void pendingExpiresAfter7Days() {
        long day = 24 * 3600_000L;
        assertFalse(Rules.pendingExpired(0, 7 * day));
        assertTrue(Rules.pendingExpired(0, 7 * day + 1));
    }

    /** GROUP_INFO só dentro do GroupEnvelope do próprio grupo; membro desconhecido → sync antes; o resto é descartado. */
    @Test public void groupInfoOnlyFromItsOwnGroupEnvelope() {
        assertEquals(Rules.APPLY, Rules.groupInfo(G, G, true));
        assertEquals(Rules.SYNC, Rules.groupInfo(G, G, false));
        assertEquals(Rules.DROP, Rules.groupInfo(null, G, true));        // veio por 1:1
        assertEquals(Rules.DROP, Rules.groupInfo(G, H, true));           // id do conteúdo ≠ group_id do frame
        assertEquals(Rules.DROP, Rules.groupInfo("d:" + G, "d:" + G, true)); // nunca uma conversa 1:1
    }

    /** No máximo um reset por par a cada 10 min: sem laço de resets entre dois aparelhos quebrados. */
    @Test public void resetLimitedPerPeer() {
        Rules.Limiter l = new Rules.Limiter();
        assertTrue(l.allow("a", 0));
        assertFalse(l.allow("a", 9 * 60_000L));
        assertTrue(l.allow("b", 9 * 60_000L));
        assertTrue(l.allow("a", 10 * 60_000L));
    }

    /** Outbox: sem Ack, reenvia o mesmo client_msg_id até 3 tentativas; depois FAILED ("reenviar"). */
    @Test public void outboxRetriesThenFails() {
        assertTrue(Rules.resend(1));
        assertTrue(Rules.resend(2));
        assertFalse(Rules.resend(3));
    }

    /** Ping do FCM deslogado: não busca a fila. */
    @Test public void pingLoggedOutFetchesNothing() throws Exception {
        int[] calls = {0};
        assertEquals(0, Rules.drain(false, () -> { calls[0]++; return true; }));
        assertEquals(0, calls[0]);
    }

    /** Fila vazia: uma busca e para. */
    @Test public void pingEmptyQueueIsOneFetch() throws Exception {
        int[] calls = {0};
        assertEquals(1, Rules.drain(true, () -> { calls[0]++; return false; }));
        assertEquals(1, calls[0]);
    }

    /** Fila cheia: no máximo PING_PAGES páginas por ping (o tempo do FCM é curto); o resto sai no próximo. */
    @Test public void pingStopsAtPageCeiling() throws Exception {
        int[] calls = {0};
        assertEquals(Rules.PING_PAGES, Rules.drain(true, () -> { calls[0]++; return true; }));
        assertEquals(Rules.PING_PAGES, calls[0]);
    }

    /** Notificação só para o que a pessoa mandou (e o ilegível dela); fim de live e aviso de código ficam no chat. */
    @Test public void notifiesOnlyMessagesFromPeople() {
        assertTrue(Rules.notifies(Chat.TEXT));
        assertTrue(Rules.notifies(Chat.LOC));
        assertTrue(Rules.notifies(Chat.LIVE_START));
        assertTrue(Rules.notifies(Chat.UNREADABLE));
        assertFalse(Rules.notifies(Chat.LIVE_STOP));
        assertFalse(Rules.notifies(Chat.IDENTITY_CHANGED));
    }

    /** Live nunca enviada sai já; parada não reenvia; andou > 5 m sai de novo. */
    @Test public void liveDueWhenNewOrMoved() {
        Map<String, double[]> sent = new HashMap<>();
        assertEquals(Arrays.asList("a"), Rules.liveDue(Arrays.asList("a"), sent, -23.5, -46.6, 2));
        sent.put("a", new double[]{-23.5, -46.6, 1000});
        assertTrue(Rules.liveDue(Arrays.asList("a"), sent, -23.5, -46.6, 2).isEmpty());
        assertEquals(Arrays.asList("a"), Rules.liveDue(Arrays.asList("a"), sent, -23.5001, -46.6, 2)); // ~11 m
    }

    /** Teto por tick (limite de 60 mensagens/min do servidor): primeiro a nunca enviada, depois a há mais tempo sem envio. */
    @Test public void liveDueOldestFirstUpToMax() {
        Map<String, double[]> sent = new HashMap<>();
        sent.put("a", new double[]{0, 0, 3000});
        sent.put("b", new double[]{0, 0, 1000});
        assertEquals(Arrays.asList("c", "b"), Rules.liveDue(Arrays.asList("a", "b", "c"), sent, 1, 1, 2));
    }

    /** Fila de grupo descartada (teto ou 7 dias): um aviso por remetente, grupo e dia, não um por mensagem. */
    @Test public void lostNoticeOncePerGroupPerDay() {
        long day = 24 * 3600_000L;
        assertEquals(Rules.lostId(G, 5 * day + 1), Rules.lostId(G, 6 * day - 1));
        assertFalse(Rules.lostId(G, 5 * day).equals(Rules.lostId(G, 6 * day)));
        assertFalse(Rules.lostId(G, 5 * day).equals(Rules.lostId(H, 5 * day)));
    }

    /** Atualização de posição da live: marcada no client_msg_id (quem recebe sabe antes de decifrar), ≤ 64 bytes. */
    @Test public void liveUpdateIdIsEphemeral() {
        String id = Rules.liveId();
        assertTrue(Rules.ephemeral(id));
        assertTrue(id.length() <= 64);
        assertFalse(Rules.ephemeral(java.util.UUID.randomUUID().toString()));
        assertFalse(Rules.ephemeral(Rules.lostId(G, 0)));
    }
}
