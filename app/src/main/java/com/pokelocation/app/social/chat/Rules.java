package com.pokelocation.app.social.chat;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Regras puras do motor do chat (testadas na JVM). */
final class Rules {
    static final int APPLY = 1, SYNC = 2, DROP = 3, MAX_TRIES = 3, MAX_PENDING_PER_SENDER = 200;
    static final long PENDING_MS = 7 * 24 * 3600_000L, RESET_EVERY_MS = 10 * 60_000L;

    private Rules() {}

    /** A SKDM do remetente pode chegar depois da mensagem do grupo: ela espera na fila até 7 dias. */
    static boolean pendingExpired(long at, long now) { return now - at > PENDING_MS; }

    /**
     * GROUP_INFO só dentro do GroupEnvelope do próprio grupo (o servidor conferiu a membresia na hora do envio): o id do
     * conteúdo tem de ser o group_id do frame e um UUID (nunca "d:…", o 1:1). Remetente que este aparelho ainda não
     * tem como membro → SYNC (GET do grupo antes de aceitar).
     */
    static int groupInfo(String frameGroupId, String contentGroupId, boolean senderIsMember) {
        if (frameGroupId == null || !Content.isGroupId(contentGroupId) || !contentGroupId.equals(frameGroupId)) return DROP;
        return senderIsMember ? APPLY : SYNC;
    }

    /** tries = envios já feitos sem Ack; o mesmo client_msg_id é reenviado (o servidor deduplica) até MAX_TRIES. */
    static boolean resend(int tries) { return tries < MAX_TRIES; }

    /** Um reset por par a cada 10 min: dois aparelhos quebrados não entram em laço de bundles. */
    static final class ResetLimiter {
        private final Map<String, Long> last = new ConcurrentHashMap<>();

        boolean allow(String peer, long now) {
            Long t = last.get(peer);
            if (t != null && now - t < RESET_EVERY_MS) return false;
            last.put(peer, now);
            return true;
        }
    }
}
