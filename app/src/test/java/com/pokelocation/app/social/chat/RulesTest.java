package com.pokelocation.app.social.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

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
        Rules.ResetLimiter l = new Rules.ResetLimiter();
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
}
