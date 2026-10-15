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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
        boolean changed;

        void add(Chat.Msg m, boolean isFresh) {
            if (m == null) return;
            msgs.add(m);
            if (isFresh) fresh.add(m);
        }
    }

    private static final Rules.Limiter RESETS = new Rules.Limiter();
    /** ponytail: em memória; um processo novo só reseta na próxima mensagem ilegível dele. */
    private static final Set<String> RESET_LATER = ConcurrentHashMap.newKeySet();
    private static final Map<String, List<String>> RESEND_LATER = new ConcurrentHashMap<>();

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
            if (groupId == null) return unreadable(c, null, from, msgId, e, r);
            if (eph || ChatDb.gone(c, groupId)) return r; // cópia gravada antes de eu sair: a chave já foi apagada
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
            return unreadable(c, groupId, from, msgId, e, r);
        }
        handle(c, st, a, groupId, msgId, plain, r);
        return r;
    }

    private static void handle(Context c, SignalStore st, SignalProtocolAddress a, String groupId, String msgId, byte[] plain,
                               Result r) throws Exception {
        String from = a.getName();
        if (st.replaced(from)) {
            r.add(notice(c, from, "identity:" + msgId), true);
            if (groupId == null) ChatDb.setResendRequest(c, from, new ArrayList<>(), 0); // identity nova: o pedido velho não vale
        }
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
            case Content.RESET: {
                long since = System.currentTimeMillis() - Rules.RESEND_WINDOW_MS;
                onReset(plain, groupId, new Requests() {
                    @Override public boolean mine(String id) throws Exception { return ChatDb.resendable(c, from, id, since) != null; }

                    @Override public void set(List<String> ids) { ChatDb.setResendRequest(c, from, ids, System.currentTimeMillis()); }
                }, r);
                return;
            }
            case Content.RESEND: {
                Chat.Msg m = groupId == null ? Content.resend(plain) : null;
                if (m == null || !Content.acceptable(m)) return;
                r.add(ChatDb.replaceUnreadable(c, from, m), false); // a ilegível já tinha avisado: não notifica de novo
                return;
            }
            default:
                // kind de versão mais nova: confirma, senão trava a fila
        }
    }

    interface Requests {
        boolean mine(String clientMsgId) throws Exception;

        void set(List<String> clientMsgIds) throws Exception;
    }

    /** Só guarda o pedido (substitui o anterior): o reenvio sai apenas quando o usuário toca 'Reenviar N' (Chat.resend). */
    static void onReset(byte[] plain, String groupId, Requests q, Result r) throws Exception {
        if (groupId != null) return;
        List<String> ok = new ArrayList<>();
        for (String id : new LinkedHashSet<>(Content.resetIds(plain))) if (q.mine(id)) ok.add(id);
        q.set(ok);
        r.changed = true;
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
                    unreadable(c, gid, from, mid, e, r);
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

    /**
     * Sem acesso ao store: só pede o reset (ele abre sessão nova pelo bundle e arquiva a atual); apagar a sessão deixaria
     * uma mensagem velha atrasada derrubar a nova que o reset acabou de abrir.
     */
    private static Result unreadable(Context c, String groupId, String from, String msgId, Exception e, Result r)
            throws Exception {
        String convId = groupId != null ? groupId : ChatDb.directId(from);
        if (!Rules.ephemeral(msgId)) r.add(system(c, convId, groupId != null, from, msgId, Chat.UNREADABLE), true);
        if (groupId == null && Rules.brokenSession(e)) r.resetPeer = from;
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
        nameNew(c, r.fresh);
        for (Chat.Msg m : r.msgs) ChatSocket.dispatch(m);
        String seen = ChatSocket.viewing;
        List<Chat.Msg> notify = new ArrayList<>(r.fresh.size());
        for (Chat.Msg m : r.fresh) if (!m.convId.equals(seen)) notify.add(m);
        Notify.fresh(c, notify);
        if (notify.size() < r.fresh.size()) Chat.markRead(c, seen);
        else if (!r.fresh.isEmpty()) Chat.broadcastUnread(c);
        if (r.groupInfo != null) acceptGroupInfo(c, r.groupInfo);
        if (r.changed) ChatSocket.dispatch(null);
        if (r.resetPeer == null) return;
        // sem por onde mandar, não busca bundle (consumiria uma one-time dele à toa): sai quando o WS do chat abrir
        if (send == null && !ChatSocket.isOpen()) RESET_LATER.add(r.resetPeer);
        else if (RESETS.allow(r.resetPeer, System.currentTimeMillis())) sendReset(c, r.resetPeer, send);
    }

    /** WS do chat acabou de abrir: os resets que chegaram pela fila REST. */
    static void sendPendingResets(Context c, Predicate<byte[]> send) {
        for (String peer : new ArrayList<>(RESET_LATER)) { // cópia: o reset que não sai volta para o RESET_LATER
            RESET_LATER.remove(peer);
            if (RESETS.allow(peer, System.currentTimeMillis())) sendReset(c, peer, send);
        }
        for (String peer : RESEND_LATER.keySet()) {
            List<String> ids = RESEND_LATER.remove(peer);
            if (ids != null) resend(c, peer, ids);
        }
    }

    static void resendApproved(Context c, String peer, List<String> ids) {
        if (ChatSocket.isOpen()) resend(c, peer, ids);
        else RESEND_LATER.put(peer, ids);
    }

    static boolean resendLater() { return !RESEND_LATER.isEmpty(); }

    /**
     * Só o que eu mandei para ele, neste 1:1, nas últimas 24 h (o SQL do ChatDb recusa o resto), pela sessão nova, com
     * client_msg_id novo e o original dentro do cifrado. Sem o Ack de alguma, o pedido fica e o usuário toca de novo.
     */
    private static void resend(Context c, String peer, List<String> ids) {
        long since = System.currentTimeMillis() - Rules.RESEND_WINDOW_MS;
        String convId = ChatDb.directId(peer);
        Rules.Batch batch = new Rules.Batch(() -> resent(c, convId, ids));
        for (String id : new LinkedHashSet<>(ids)) {
            try {
                Chat.Msg m = Locations.atomic(c, () -> ChatDb.resendable(c, peer, id, since));
                if (m == null) continue;
                String cmid = UUID.randomUUID().toString();
                byte[] frame = Frames.envelope(peer, cmid, Groups.seal1to1(c, peer, Content.resend(id, m), null));
                batch.add();
                if (!ChatSocket.send(frame, cmid, batch::ack, batch::fail)) {
                    batch.fail();
                    break;
                }
            } catch (IOException e) {
                batch.fail();
                Log.w("Inbox", "resend: " + e.getClass().getSimpleName());
            }
        }
        batch.ack();
    }

    /** Só apaga o pedido que foi reenviado: um RESET novo do amigo nesse meio-tempo fica. */
    private static void resent(Context c, String convId, List<String> ids) {
        try {
            ChatSocket.dispatch(Locations.atomic(c, () -> {
                if (ChatDb.resendRequest(c, convId, 0).equals(ids)) ChatDb.clearResendRequest(c, convId);
                return ChatDb.latestNotice(c, convId);
            }));
        } catch (IOException e) {
            Log.w("Inbox", "resent: " + e.getClass().getSimpleName());
        }
    }

    /** O envelope só traz o id: o 1:1 ganha o @nome pela lista de amigos; o membro de grupo, pelo GET do grupo. */
    private static void nameNew(Context c, List<Chat.Msg> fresh) {
        Set<String> synced = new HashSet<>();
        for (Chat.Msg m : fresh) {
            if (m.from == null) continue;
            if (Content.isGroupId(m.convId)) {
                if (!synced.add(m.convId) || known(c, m.convId, m.from)) continue;
                try {
                    Groups.sync(c, m.convId);
                } catch (IOException e) {
                    Log.w("Inbox", "members: " + e.getClass().getSimpleName());
                }
                continue;
            }
            Chat.Conv conv = Chat.conversation(c, m.convId);
            if (conv != null && conv.title == null) nameDirect(c, m.convId, m.from);
        }
    }

    private static boolean known(Context c, String groupId, String userId) {
        try {
            return Locations.atomic(c, () -> ChatDb.isMember(c, groupId, userId));
        } catch (IOException e) {
            return true;
        }
    }

    private static String nameDirect(Context c, String convId, String userId) {
        try {
            for (Api.Friend f : Api.friends(c).accepted) {
                if (!f.userId.equals(userId)) continue;
                String label = f.username != null ? "@" + f.username : f.friendCode;
                Locations.atomic(c, () -> {
                    ChatDb.ensureConversation(c, convId, false, userId, label);
                    return null;
                });
                return label;
            }
        } catch (IOException e) {
            Log.w("Inbox", "name: " + e.getClass().getSimpleName());
        }
        return null;
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
            List<String> unread = Locations.atomic(c, () -> ChatDb.unreadableIds(c, peer, System.currentTimeMillis() - Rules.RESEND_WINDOW_MS));
            Api.Bundle b = Api.keyBundle(c, peer);
            byte[] frame = Frames.envelope(peer, UUID.randomUUID().toString(), Groups.seal1to1(c, peer, Content.reset(unread), b));
            if (send != null ? send.test(frame) : ChatSocket.send(frame, null, null, null)) return;
            RESETS.forget(peer); // o WS caiu: sai na próxima abertura, não só na próxima ilegível
            RESET_LATER.add(peer);
        } catch (IOException e) {
            Log.w("Inbox", "reset: " + e.getClass().getSimpleName());
        }
    }
}
