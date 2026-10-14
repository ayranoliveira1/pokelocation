package com.pokelocation.app.social.chat;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import com.pokelocation.app.social.loc.Locations;
import com.pokelocation.app.social.net.Api;
import com.pokelocation.app.social.store.SignalStore;
import com.pokelocation.app.social.store.SocialDb;
import com.pokelocation.app.social.ws.Frames;

import org.signal.libsignal.protocol.NoSessionException;
import org.signal.libsignal.protocol.SessionCipher;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.state.SignalProtocolStore;
import org.signal.libsignal.protocol.groups.GroupCipher;
import org.signal.libsignal.protocol.groups.GroupSessionBuilder;
import org.signal.libsignal.protocol.message.CiphertextMessage;
import org.signal.libsignal.protocol.message.PreKeySignalMessage;
import org.signal.libsignal.protocol.message.SenderKeyDistributionMessage;
import org.signal.libsignal.protocol.message.SignalMessage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * receive roda no SocialDb.atomic: exceção = transitório (rollback, sem Ack); retorno = Ack, só depois de gravar. O mesmo
 * envelope pode vir pelos dois processos e pelo REST: dedup por (from, client_msg_id).
 */
public final class Inbox {
    public static final class Result {
        final List<Chat.Msg> msgs = new ArrayList<>(1);
        final List<Chat.Msg> fresh = new ArrayList<>(1);
        String resetPeer;
        String[] groupInfo;

        void add(Chat.Msg m, boolean isFresh) {
            if (m == null) return;
            msgs.add(m);
            if (isFresh) fresh.add(m);
        }
    }

    private static final Rules.Limiter RESETS = new Rules.Limiter();
    /** ponytail: em memória; um processo novo só reseta na próxima mensagem ilegível dele. */
    private static final Set<String> RESET_LATER = ConcurrentHashMap.newKeySet();

    private Inbox() {}

    public static Result receive(Context c, String from, String groupId, String msgId, byte[] payload) throws Exception {
        Result r = new Result();
        boolean eph = Rules.ephemeral(msgId);
        ContentValues seen = new ContentValues(3);
        seen.put("sender", from);
        seen.put("msg_id", msgId);
        seen.put("at", System.currentTimeMillis());
        // efêmera (24/min) fora do dedup, que guarda 31 dias: a repetida cai em DuplicateMessage
        if (!eph && SocialDb.get(c).insertWithOnConflict("seen_envelopes", null, seen, SQLiteDatabase.CONFLICT_IGNORE) == -1) {
            r.add(ChatDb.byClientId(c, from, msgId), false);
            return r;
        }
        // malformado: confirmado, senão trava a fila por 30 dias (o servidor não tem tamanho mínimo)
        if (payload.length < (groupId == null ? 2 : 1) || (groupId != null && !Content.isGroupId(groupId))) return r;
        SignalStore st = new SignalStore(c);
        SignalProtocolAddress a = new SignalProtocolAddress(from, 1);
        byte[] plain;
        try {
            plain = groupId != null ? new GroupCipher(st, a).decrypt(payload) : decrypt1to1(st, a, payload);
        } catch (NoSessionException e) {
            if (groupId == null) return unreadable(c, st, a, null, from, msgId, e, r);
            if (eph) return r;
            // a SKDM vem num 1:1 separado e pode chegar depois: na fila o Ack é seguro; teto por remetente (quem nunca a
            // manda não enche o disco)
            if (ChatDb.pendingCount(c, from) >= Rules.MAX_PENDING_PER_SENDER) {
                String[] old = ChatDb.oldestPending(c, from);
                ChatDb.dropPending(c, from, old[0]);
                r.add(lost(c, old[1], from), false);
            }
            ChatDb.addPending(c, from, msgId, groupId, payload);
            return r;
        } catch (Exception e) {
            if (!Locations.ack(e)) throw e;
            return unreadable(c, st, a, groupId, from, msgId, e, r);
        }
        handle(c, st, a, groupId, msgId, plain, r);
        return r;
    }

    private static void handle(Context c, SignalStore st, SignalProtocolAddress a, String groupId, String msgId, byte[] plain,
                               Result r) throws Exception {
        String from = a.getName();
        if (st.replaced(from)) r.add(notice(c, from, "identity:" + msgId), true);
        if (plain.length == 0) return;
        switch (plain[0]) {
            case Content.SKDM:
                new GroupSessionBuilder(st).process(a, new SenderKeyDistributionMessage(Arrays.copyOfRange(plain, 1, plain.length)));
                retryPending(c, st, a, r);
                return;
            case Content.GROUP_INFO: {
                String[] g = Content.groupInfo(plain);
                String gid = g == null ? null : g[0];
                int action = Rules.groupInfo(groupId, gid, groupId != null && ChatDb.isMember(c, groupId, from));
                if (action == Rules.APPLY) ChatDb.ensureConversation(c, gid, true, null, g[1]);
                else if (action == Rules.SYNC) r.groupInfo = new String[]{gid, g[1], from};
                else Log.w("Inbox", "group info descartado");
                return;
            }
            case Content.MSG: {
                Chat.Msg m = Content.msg(plain);
                if (m == null || !Content.acceptable(m)) return;
                if (Rules.ephemeral(msgId) && (m.type != Chat.LIVE_START || m.text.isEmpty())) return;
                store(c, m, groupId, from, msgId, r);
                return;
            }
            default:
                // RESET ou kind de versão mais nova: confirma, senão trava a fila
        }
    }

    private static void retryPending(Context c, SignalStore st, SignalProtocolAddress a, Result r) throws Exception {
        String from = a.getName();
        boolean progress = true;
        while (progress) {
            progress = false;
            List<Object[]> batch = ChatDb.pendingFrom(c, from);
            for (Object[] p : batch) {
                String mid = (String) p[0], gid = (String) p[1];
                byte[] plain;
                try {
                    plain = new GroupCipher(st, a).decrypt((byte[]) p[2]);
                } catch (NoSessionException e) {
                    continue;
                } catch (Exception e) {
                    if (!Locations.ack(e)) throw e;
                    ChatDb.dropPending(c, from, mid);
                    unreadable(c, st, a, gid, from, mid, e, r);
                    progress = true;
                    continue;
                }
                ChatDb.dropPending(c, from, mid);
                handle(c, st, a, gid, mid, plain, r);
                progress = true;
            }
            if (batch.size() < 50) break;
        }
    }

    public static Result prune(Context c) throws Exception {
        Result r = new Result();
        List<String[]> batch;
        do {
            batch = ChatDb.takeExpiredPending(c, System.currentTimeMillis() - Rules.PENDING_MS);
            for (String[] p : batch) r.add(lost(c, p[2], p[0]), false);
        } while (batch.size() == 50);
        return r;
    }

    private static Chat.Msg lost(Context c, String groupId, String from) throws Exception {
        return system(c, groupId, true, from, Rules.lostId(groupId, System.currentTimeMillis()), Chat.UNREADABLE);
    }

    static byte[] decrypt1to1(SignalProtocolStore st, SignalProtocolAddress a, byte[] payload) throws Exception {
        SessionCipher sc = new SessionCipher(st, a);
        byte[] ct = Arrays.copyOfRange(payload, 1, payload.length);
        return payload[0] == CiphertextMessage.PREKEY_TYPE ? sc.decrypt(new PreKeySignalMessage(ct)) : sc.decrypt(new SignalMessage(ct));
    }

    private static Result unreadable(Context c, SignalStore st, SignalProtocolAddress a, String groupId, String from,
                                     String msgId, Exception e, Result r) throws Exception {
        String convId = groupId != null ? groupId : ChatDb.directId(from);
        if (!Rules.ephemeral(msgId)) r.add(system(c, convId, groupId != null, from, msgId, Chat.UNREADABLE), true);
        if (groupId == null && Rules.brokenSession(e)) {
            st.deleteSession(a);
            r.resetPeer = from;
        }
        return r;
    }

    private static void store(Context c, Chat.Msg m, String groupId, String from, String msgId, Result r) throws Exception {
        String convId = groupId != null ? groupId : ChatDb.directId(from);
        m.from = from;
        m.clientMsgId = msgId;
        m.convId = convId;
        m.state = Chat.SENT;
        ChatDb.ensureConversation(c, convId, groupId != null, groupId != null ? null : from, null);
        boolean update = m.type == Chat.LIVE_START && !m.text.isEmpty();
        if (update || m.type == Chat.LIVE_STOP) {
            Chat.Msg start = ChatDb.updateLive(c, convId, from, m.text, m.lat, m.lng, update ? m.liveUntil : m.ts);
            ChatDb.recomputeLive(c, convId);
            if (update) {
                r.add(start, false);
                return;
            }
        }
        if (ChatDb.insert(c, m) == -1) {
            r.add(ChatDb.byClientId(c, from, msgId), false);
            return;
        }
        if (m.type == Chat.LIVE_START) ChatDb.recomputeLive(c, convId);
        r.add(m, true);
    }

    static Chat.Msg notice(Context c, String peer, String id) throws Exception {
        return system(c, ChatDb.directId(peer), false, peer, id, Chat.IDENTITY_CHANGED);
    }

    private static Chat.Msg system(Context c, String convId, boolean group, String from, String id, int type) throws Exception {
        ChatDb.ensureConversation(c, convId, group, group ? null : from, null);
        Chat.Msg m = new Chat.Msg();
        m.convId = convId;
        m.from = from;
        m.clientMsgId = id;
        m.type = type;
        m.state = Chat.SENT;
        m.ts = System.currentTimeMillis();
        m.text = "";
        return ChatDb.insert(c, m) == -1 ? null : m;
    }

    /** Fora do atomic, depois do Ack: pode usar rede. send = o WS deste processo (null: o do chat, se aberto). */
    public static void deliver(Context c, Result r, Predicate<byte[]> send) {
        for (Chat.Msg m : r.msgs) ChatSocket.dispatch(m);
        Notify.fresh(c, r.fresh);
        if (r.groupInfo != null) acceptGroupInfo(c, r.groupInfo);
        if (r.resetPeer == null) return;
        // sem por onde mandar, não busca bundle (consumiria uma one-time dele à toa): sai quando o WS do chat abrir
        if (send == null && !ChatSocket.isOpen()) RESET_LATER.add(r.resetPeer);
        else if (RESETS.allow(r.resetPeer, System.currentTimeMillis())) sendReset(c, r.resetPeer, send);
    }

    /** WS do chat acabou de abrir: os resets que chegaram pela fila REST. */
    static void sendPendingResets(Context c, Predicate<byte[]> send) {
        for (String peer : RESET_LATER) {
            RESET_LATER.remove(peer);
            if (RESETS.allow(peer, System.currentTimeMillis())) sendReset(c, peer, send);
        }
    }

    private static void acceptGroupInfo(Context c, String[] g) {
        try {
            Groups.sync(c, g[0]);
            Locations.atomic(c, () -> {
                if (Rules.groupInfo(g[0], g[0], ChatDb.isMember(c, g[0], g[2])) == Rules.APPLY) {
                    ChatDb.ensureConversation(c, g[0], true, null, g[1]);
                }
                return null;
            });
            ChatSocket.dispatch(null);
        } catch (IOException e) {
            Log.w("Inbox", "group info: " + e.getClass().getSimpleName());
        }
    }

    private static void sendReset(Context c, String peer, Predicate<byte[]> send) {
        try {
            Api.Bundle b = Api.keyBundle(c, peer);
            byte[] frame = Frames.envelope(peer, UUID.randomUUID().toString(), Groups.seal1to1(c, peer, new byte[]{Content.RESET}, b));
            if (send != null) send.test(frame);
            else ChatSocket.send(frame, null, null, null);
        } catch (IOException e) {
            Log.w("Inbox", "reset: " + e.getClass().getSimpleName());
        }
    }
}
