package com.pokelocation.app.social.chat;

import android.util.Log;

import com.pokelocation.app.geo.Geo;

import org.signal.libsignal.protocol.InvalidMessageException;
import org.signal.libsignal.protocol.NoSessionException;
import org.signal.libsignal.protocol.ReusedBaseKeyException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class Rules {
    static final int APPLY = 1, SYNC = 2, DROP = 3, MAX_TRIES = 3, MAX_PENDING_PER_SENDER = 200, PING_PAGES = 10;
    static final long PENDING_MS = 7 * 24 * 3600_000L, LIMIT_EVERY_MS = 10 * 60_000L;

    private Rules() {}

    static boolean pendingExpired(long at, long now) { return now - at > PENDING_MS; }

    /** GROUP_INFO só vale no GroupEnvelope do próprio grupo (o servidor conferiu a membresia) e nunca renomeia um 1:1. */
    static int groupInfo(String frameGroupId, String contentGroupId, boolean senderIsMember) {
        if (frameGroupId == null || !Content.isGroupId(contentGroupId) || !contentGroupId.equals(frameGroupId)) return DROP;
        return senderIsMember ? APPLY : SYNC;
    }

    /** Reenvia o mesmo client_msg_id: o servidor deduplica. */
    static boolean resend(int tries) { return tries < MAX_TRIES; }

    interface Send {
        void run() throws Exception;
    }

    /** Nenhuma exceção deixa a mensagem em SENDING para sempre: vira FAILED (o usuário reenvia); no log só a classe. */
    static void sendOrFail(Send send, Runnable fail) {
        try {
            send.run();
        } catch (Exception | LinkageError e) {
            Log.w("Chat", "send: " + e.getClass().getSimpleName());
            fail.run();
        }
    }

    interface Page {
        boolean next() throws IOException;
    }

    /** O processo acordado pelo FCM tem poucos segundos: o resto sai no próximo ping ou WS. */
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

    /** Em claro para o servidor, que já vê a frequência: quem recebe sabe que é efêmera antes de decifrar. */
    static String liveId() { return LIVE + UUID.randomUUID(); }

    static boolean ephemeral(String clientMsgId) { return clientMsgId.startsWith(LIVE); }

    /** O dedup por (remetente, id) deixa um aviso por grupo e dia. */
    static String lostId(String groupId, long now) { return "lost:" + groupId + ":" + now / (24 * 3600_000L); }

    /** Só sessão quebrada pede reset; replay (ReusedBaseKey), Duplicate, Legacy e versão não. */
    static boolean brokenSession(Exception e) {
        return e instanceof NoSessionException || (e instanceof InvalidMessageException && !(e instanceof ReusedBaseKeyException));
    }

    /** Dois aparelhos quebrados não entram em laço de resets; "não foi possível ler" não vira enxurrada. */
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
