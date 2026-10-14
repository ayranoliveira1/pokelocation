package com.pokelocation.app.social.chat;

import com.pokelocation.app.geo.Geo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Regras puras do motor do chat (testadas na JVM). */
final class Rules {
    static final int APPLY = 1, SYNC = 2, DROP = 3, MAX_TRIES = 3, MAX_PENDING_PER_SENDER = 200, PING_PAGES = 10;
    static final long PENDING_MS = 7 * 24 * 3600_000L, LIMIT_EVERY_MS = 10 * 60_000L;

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

    interface Page {
        /** false = a fila veio vazia. */
        boolean next() throws IOException;
    }

    /** Páginas de 50 que um ping do FCM busca: o processo acordado tem poucos segundos; o resto sai no próximo ping ou WS. */
    static int drain(boolean loggedIn, Page page) throws IOException {
        int n = 0;
        while (loggedIn && n < PING_PAGES) {
            n++;
            if (!page.next()) break;
        }
        return n;
    }

    static boolean notifies(int type) {
        return type == Chat.TEXT || type == Chat.LOC || type == Chat.LIVE_START || type == Chat.UNREADABLE;
    }

    /**
     * Lives (convId) a enviar neste tick: nunca enviada ou andou > 5 m desde o último envio; no máximo max, a nunca
     * enviada e depois a há mais tempo sem envio primeiro. sent: convId → {lat, lng, quando}.
     */
    static List<String> liveDue(List<String> active, Map<String, double[]> sent, double lat, double lng, int max) {
        List<String> due = new ArrayList<>();
        for (String id : active) {
            double[] s = sent.get(id);
            if (s == null || Geo.distance(s[0], s[1], lat, lng) > 5) due.add(id);
        }
        due.sort((x, y) -> Double.compare(at(sent, x), at(sent, y)));
        return due.size() > max ? due.subList(0, max) : due;
    }

    private static double at(Map<String, double[]> sent, String id) {
        double[] s = sent.get(id);
        return s == null ? -1 : s[2];
    }

    private static final String LIVE = "live:";

    /** client_msg_id de uma atualização de posição da live (em claro para o servidor, como a frequência já é). */
    static String liveId() { return LIVE + UUID.randomUUID(); }

    /** Efêmera: quem recebe e não decifra descarta em silêncio (nem fila de grupo, nem aviso, nem notificação). */
    static boolean ephemeral(String clientMsgId) { return clientMsgId.startsWith(LIVE); }

    /** clientMsgId do aviso de fila de grupo perdida: o dedup por (remetente, id) deixa um por grupo e dia. */
    static String lostId(String groupId, long now) { return "lost:" + groupId + ":" + now / (24 * 3600_000L); }

    /**
     * Uma vez por chave a cada 10 min: reset por par (dois aparelhos quebrados não entram em laço de bundles) e
     * notificação de "não foi possível ler" por conversa.
     */
    static final class Limiter {
        private final Map<String, Long> last = new ConcurrentHashMap<>();

        boolean allow(String peer, long now) {
            Long t = last.get(peer);
            if (t != null && now - t < LIMIT_EVERY_MS) return false;
            last.put(peer, now);
            return true;
        }
    }
}
