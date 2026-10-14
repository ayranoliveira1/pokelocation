package com.pokelocation.app.social.chat;

import android.content.Context;
import android.util.Log;

import com.pokelocation.app.data.Prefs;
import com.pokelocation.app.model.Coord;
import com.pokelocation.app.social.crypto.SignalLoader;
import com.pokelocation.app.social.loc.Locations;
import com.pokelocation.app.social.ws.Frames;

import com.pokelocation.app.social.net.Api;
import com.pokelocation.app.social.store.SocialDb;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Motor do chat para as telas. Tudo bloqueante: fora da main. Nada de rede dentro do SocialDb.atomic. */
public final class Chat {
    public static final int TEXT = 1, LOC = 2, LIVE_START = 3, LIVE_STOP = 4, UNREADABLE = 5, IDENTITY_CHANGED = 6;
    public static final int SENDING = 1, SENT = 2, FAILED = 3;

    /** myLiveUntil: minha live ativa nesta conversa (0 = nenhuma); liveUntil: a mais longa ativa de outro membro. */
    public static final class Conv {
        public String id, title; public boolean group; public String preview; public long lastTs; public int unread;
        public long myLiveUntil, liveUntil;
        public boolean left;   // grupo do qual saí ou fui removido: só leitura
        public int previewType;   // tipo da última mensagem (TEXT, LOC…): preview só tem texto em TEXT
    }
    public static final class Msg {
        public long id; public String clientMsgId, convId, from;   // from == null: minha
        public int type, state; public String text; public double lat, lng; public long liveUntil, ts;
    }
    public interface Listener { void onMessage(Msg m); void onConversationsChanged(); }   // entregue na main

    private Chat() {}

    public static List<Conv> conversations(Context c) { return db(c, () -> ChatDb.conversations(c), new ArrayList<>()); }

    public static Conv conversation(Context c, String convId) { return db(c, () -> ChatDb.conversation(c, convId), null); }

    /** Keyset: primeira página com beforeTs = Long.MAX_VALUE, beforeId = Long.MAX_VALUE; ordem ts DESC, id DESC. */
    public static List<Msg> page(Context c, String convId, long beforeTs, long beforeId, int limit) {
        return db(c, () -> ChatDb.page(c, convId, beforeTs, beforeId, limit), new ArrayList<>());
    }

    public static String openDirect(Context c, String friendUserId, String label) {
        String id = ChatDb.directId(friendUserId);
        return db(c, () -> {
            ChatDb.ensureConversation(c, id, false, friendUserId, label);
            return id;
        }, null);
    }

    public static Msg sendText(Context c, String convId, String text) throws IOException {
        return send(c, convId, msg(TEXT, text, 0, 0, 0));
    }

    public static Msg sendLocation(Context c, String convId, double lat, double lng) throws IOException {
        return send(c, convId, msg(LOC, "", lat, lng, 0));
    }

    public static Msg startLive(Context c, String convId, long durationMs) throws IOException {
        Coord p = Prefs.lastPosition(c);
        long until = System.currentTimeMillis() + durationMs;
        Msg m = send(c, convId, msg(LIVE_START, "", p == null ? 0 : p.lat, p == null ? 0 : p.lng, until));
        Locations.atomic(c, () -> {
            ChatDb.setLive(c, convId, true, until);
            return null;
        });
        Live.hint(c);
        ChatSocket.dispatch(null);
        return m;
    }

    public static void stopLive(Context c, String convId) throws IOException {
        long now = System.currentTimeMillis();
        Conv conv = conversation(c, convId);
        if (conv == null || conv.myLiveUntil <= now) return;
        String start = Locations.atomic(c, () -> ChatDb.myLiveStart(c, convId));
        if (start == null) return;
        Coord p = Prefs.lastPosition(c);
        send(c, convId, msg(LIVE_STOP, start, p == null ? 0 : p.lat, p == null ? 0 : p.lng, now));
        Msg ended = Locations.atomic(c, () -> {
            ChatDb.setLive(c, convId, true, 0);
            Msg s = ChatDb.byClientId(c, null, start);
            return s == null ? null : ChatDb.updateLive(c, convId, null, start, s.lat, s.lng, now);
        });
        Live.hint(c);
        ChatSocket.dispatch(ended);
    }

    public static Msg retry(Context c, long msgId) throws IOException {
        Msg m = Locations.atomic(c, () -> {
            ChatDb.setState(c, msgId, SENDING);
            return ChatDb.byId(c, msgId);
        });
        if (m != null) transmit(c, m);
        return m;
    }

    /** O nome nunca vai ao servidor: viaja cifrado com a sender key. */
    public static String createGroup(Context c, String name, List<String> memberUserIds) throws IOException {
        requireLib();
        String id = Api.createGroup(c, memberUserIds);
        Api.Group g = Api.group(c, id);
        String me = Groups.myId(c);
        Locations.atomic(c, () -> {
            ChatDb.ensureConversation(c, id, true, null, name);
            ChatDb.setMembers(c, id, Groups.others(g.members, me));
            return null;
        });
        if (ChatSocket.isOpen()) Groups.distribute(c, id);
        ChatSocket.dispatch(null);
        return id;
    }

    public static void addMember(Context c, String convId, String userId) throws IOException {
        Api.addGroupMember(c, convId, userId);
        Groups.sync(c, convId);
        ChatSocket.dispatch(null);
    }

    public static void removeMember(Context c, String convId, String userId) throws IOException {
        Api.removeGroupMember(c, convId, userId);
        Groups.sync(c, convId);
        ChatSocket.dispatch(null);
    }

    public static void leaveGroup(Context c, String convId) throws IOException {
        Api.removeGroupMember(c, convId, Groups.myId(c));
        Groups.leave(c, convId);
        ChatSocket.dispatch(null);
    }

    public static List<Api.Friend> members(Context c, String convId) { return db(c, () -> ChatDb.members(c, convId), new ArrayList<>()); }

    public static String safetyNumber(Context c, String friendUserId) throws IOException {
        requireLib();
        return Groups.safetyNumber(c, friendUserId);
    }

    public static void markRead(Context c, String convId) {
        db(c, () -> {
            ChatDb.markRead(c, convId);
            return null;
        }, null);
        Notify.cancel(c, convId);
    }

    public static void listen(Context c, Listener l) { ChatSocket.listen(c, l); }

    public static void unlisten(Listener l) { ChatSocket.unlisten(l); }

    private static void requireLib() throws IOException {
        if (!SignalLoader.loaded()) throw new IOException("signal not loaded");
    }

    private static Msg msg(int type, String text, double lat, double lng, long liveUntil) {
        Msg m = new Msg();
        m.type = type;
        m.text = text;
        m.lat = lat;
        m.lng = lng;
        m.liveUntil = liveUntil;
        return m;
    }

    private static Msg send(Context c, String convId, Msg m) throws IOException {
        m.clientMsgId = UUID.randomUUID().toString();
        m.convId = convId;
        m.state = SENDING;
        m.ts = System.currentTimeMillis();
        Locations.atomic(c, () -> ChatDb.insert(c, m));
        transmit(c, m);
        return m;
    }

    private static void transmit(Context c, Msg m) {
        Rules.sendOrFail(() -> {
            requireLib();
            if (!ChatSocket.isOpen()) return;
            byte[] plain = Content.msg(m);
            byte[] frame;
            if (Locations.atomic(c, () -> ChatDb.isGroup(c, m.convId))) {
                if (Locations.atomic(c, () -> ChatDb.gone(c, m.convId))) throw new IOException("left");
                Groups.distribute(c, m.convId);
                String me = Groups.myId(c);
                frame = Frames.groupEnvelope(m.convId, m.clientMsgId, Locations.atomic(c, () -> Groups.encrypt(c, me, m.convId, plain, true)));
            } else {
                String peer = Locations.atomic(c, () -> ChatDb.peer(c, m.convId));
                frame = Frames.envelope(peer, m.clientMsgId, Groups.seal1to1(c, peer, plain, null));
            }
            if (ChatSocket.sendMessage(c, frame, m)) {
                Locations.atomic(c, () -> ChatDb.incTries(c, m.id));
            }
        }, () -> fail(c, m.id));
    }

    static void noAck(Context c, long id) {
        Rules.sendOrFail(() -> {
            Object[] st = Locations.atomic(c, () -> new Object[]{ChatDb.byId(c, id), ChatDb.tries(c, id)});
            Msg m = (Msg) st[0];
            if (m == null || m.state != SENDING) return;
            if (Rules.resend((Integer) st[1])) transmit(c, m);
            else fail(c, id);
        }, () -> fail(c, id));
    }

    static void resendOutbox(Context c) {
        List<Long> ids;
        try {
            ids = Locations.atomic(c, () -> ChatDb.sendingIds(c));
        } catch (IOException | RuntimeException e) {
            Log.w("Chat", "outbox: " + e.getClass().getSimpleName());
            return;
        }
        for (long id : ids) noAck(c, id);
    }

    private static void fail(Context c, long id) { ChatSocket.setState(c, id, FAILED); }

    private static <T> T db(Context c, SocialDb.Op<T> op, T none) {
        try {
            return SocialDb.atomic(c, op);
        } catch (IllegalStateException e) {
            if ("logged out".equals(e.getMessage())) return none;
            throw e;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("chat db", e);
        }
    }
}
