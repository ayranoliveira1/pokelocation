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

import org.signal.libsignal.protocol.InvalidMessageException;
import org.signal.libsignal.protocol.NoSessionException;
import org.signal.libsignal.protocol.ReusedBaseKeyException;
import org.signal.libsignal.protocol.SessionCipher;
import org.signal.libsignal.protocol.SignalProtocolAddress;
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
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Recebimento: o mesmo caminho no principal e no :overlay (com algum WS aberto o servidor não manda ping FCM, então
 * quem recebe grava). receive roda dentro do SocialDb.atomic; exceção = transitório (rollback, sem Ack); retorno = Ack
 * (só depois de gravar). O mesmo envelope pode vir pelos dois processos e pelo REST: dedup por (from, client_msg_id).
 */
public final class Inbox {
    /**
     * msgs: gravadas ou alteradas (as fresh são novas de outra pessoa: notificam). resetPeer: sessão 1:1 quebrada.
     * groupInfo: {gid, nome, from} de remetente que este aparelho ainda não tem como membro (confere no servidor).
     */
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

    private static final Rules.ResetLimiter RESETS = new Rules.ResetLimiter();

    private Inbox() {}

    public static Result receive(Context c, String from, String groupId, String msgId, byte[] payload) throws Exception {
        Result r = new Result();
        ContentValues seen = new ContentValues(3);
        seen.put("sender", from);
        seen.put("msg_id", msgId);
        seen.put("at", System.currentTimeMillis());
        if (SocialDb.get(c).insertWithOnConflict("seen_envelopes", null, seen, SQLiteDatabase.CONFLICT_IGNORE) == -1) {
            r.add(ChatDb.byClientId(c, from, msgId), false); // o outro processo já gravou: só reentrega à tela
            return r;
        }
        // malformado (o servidor não tem tamanho mínimo): marcado e confirmado; sem Ack, travaria a fila por 30 dias
        if (payload.length < (groupId == null ? 2 : 1) || (groupId != null && !Content.isGroupId(groupId))) return r;
        SignalStore st = new SignalStore(c);
        SignalProtocolAddress a = new SignalProtocolAddress(from, 1);
        byte[] plain;
        try {
            plain = groupId != null ? new GroupCipher(st, a).decrypt(payload) : decrypt1to1(st, a, payload);
        } catch (NoSessionException e) {
            if (groupId == null) return unreadable(c, st, a, null, from, msgId, e, r);
            // a SKDM dele vem num Envelope 1:1 separado e pode chegar depois: gravada na fila, o Ack é seguro.
            // Teto por remetente (um membro que nunca manda a SKDM não enche o disco): a mais antiga vira ilegível.
            if (ChatDb.pendingCount(c, from) >= Rules.MAX_PENDING_PER_SENDER) {
                String[] old = ChatDb.oldestPending(c, from);
                ChatDb.dropPending(c, from, old[0]);
                r.add(system(c, old[1], true, from, old[0], Chat.UNREADABLE), true);
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
                else Log.w("Inbox", "group info descartado"); // sem conteúdo no log
                return;
            }
            case Content.MSG: {
                Chat.Msg m = Content.msg(plain);
                if (m != null && Content.acceptable(m)) store(c, m, groupId, from, msgId, r);
                return;
            }
            default:
                // RESET (só fez a sessão nova) ou kind de uma versão mais nova: confirma, senão trava a fila
        }
    }

    /** A SKDM de quem mandou acabou de chegar: decifra o que estava esperando por ela. */
    private static void retryPending(Context c, SignalStore st, SignalProtocolAddress a, Result r) throws Exception {
        String from = a.getName();
        boolean progress = true;
        while (progress) { // em lotes: mais de um lote do mesmo remetente sai todo agora, não só na próxima SKDM
            progress = false;
            List<Object[]> batch = ChatDb.pendingFrom(c, from);
            for (Object[] p : batch) {
                String mid = (String) p[0], gid = (String) p[1];
                byte[] plain;
                try {
                    plain = new GroupCipher(st, a).decrypt((byte[]) p[2]);
                } catch (NoSessionException e) {
                    continue; // é de outro grupo: segue esperando a SKDM daquele
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

    /** Dentro do atomic: fila de grupo com mais de 7 dias (a SKDM nunca veio) vira "não foi possível ler". */
    public static Result prune(Context c) throws Exception {
        Result r = new Result();
        List<String[]> batch;
        do {
            batch = ChatDb.takeExpiredPending(c, System.currentTimeMillis() - Rules.PENDING_MS);
            for (String[] p : batch) r.add(system(c, p[2], true, p[0], p[1], Chat.UNREADABLE), true);
        } while (batch.size() == 50);
        return r;
    }

    private static byte[] decrypt1to1(SignalStore st, SignalProtocolAddress a, byte[] payload) throws Exception {
        SessionCipher sc = new SessionCipher(st, a);
        byte[] ct = Arrays.copyOfRange(payload, 1, payload.length);
        return payload[0] == CiphertextMessage.PREKEY_TYPE ? sc.decrypt(new PreKeySignalMessage(ct)) : sc.decrypt(new SignalMessage(ct));
    }

    /**
     * Ilegível para sempre: avisa na conversa e confirma. No 1:1, só sessão quebrada (NoSession, ou InvalidMessage que
     * não é replay) é apagada e pede um reset; Duplicate/Legacy/versão não indicam estado quebrado.
     */
    private static Result unreadable(Context c, SignalStore st, SignalProtocolAddress a, String groupId, String from,
                                     String msgId, Exception e, Result r) throws Exception {
        String convId = groupId != null ? groupId : ChatDb.directId(from);
        r.add(system(c, convId, groupId != null, from, msgId, Chat.UNREADABLE), true);
        boolean broken = groupId == null && (e instanceof NoSessionException
                || (e instanceof InvalidMessageException && !(e instanceof ReusedBaseKeyException)));
        if (broken) {
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
        if (update || m.type == Chat.LIVE_STOP) { // posição nova ou fim de uma live: muda a mensagem original (text = id dela)
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

    /** Dentro do atomic: "o código de segurança mudou" no 1:1 com ele (descoberto ao receber ou ao enviar). */
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

    /**
     * Fora do atomic, depois do Ack (pode usar rede): entrega à tela aberta, confere no servidor o membro de um grupo que
     * mandou o nome e reinicia uma sessão quebrada. send = o WS deste processo (null: o do chat, se aberto).
     */
    public static void deliver(Context c, Result r, Predicate<byte[]> send) {
        for (Chat.Msg m : r.msgs) ChatSocket.dispatch(m);
        if (r.groupInfo != null) acceptGroupInfo(c, r.groupInfo);
        // sem por onde mandar, não busca bundle (consumiria uma one-time dele à toa): o meu próximo envio já sai como PreKey
        if (r.resetPeer != null && (send != null || ChatSocket.isOpen()) && RESETS.allow(r.resetPeer, System.currentTimeMillis())) {
            sendReset(c, r.resetPeer, send);
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

    /** Sessão nova pelo bundle + controle cifrado nela (PreKeySignalMessage): o outro lado arquiva a antiga. */
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
