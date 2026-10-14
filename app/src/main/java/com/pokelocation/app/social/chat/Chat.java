package com.pokelocation.app.social.chat;

import android.content.Context;

import com.pokelocation.app.social.net.Api;
import com.pokelocation.app.social.store.SocialDb;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Motor do chat para as telas. Tudo bloqueante: fora da main. Nada de rede dentro do SocialDb.atomic. */
public final class Chat {
    public static final int TEXT = 1, LOC = 2, LIVE_START = 3, LIVE_STOP = 4, UNREADABLE = 5, IDENTITY_CHANGED = 6;
    public static final int SENDING = 1, SENT = 2, FAILED = 3;

    /** myLiveUntil: minha live ativa nesta conversa (0 = nenhuma); liveUntil: a mais longa ativa de outro membro. */
    public static final class Conv {
        public String id, title; public boolean group; public String preview; public long lastTs; public int unread;
        public long myLiveUntil, liveUntil;
        public int previewType;   // tipo da última mensagem (TEXT, LOC…): preview só tem texto em TEXT
    }
    public static final class Msg {
        public long id; public String clientMsgId, convId, from;   // from == null: minha
        public int type, state; public String text; public double lat, lng; public long liveUntil, ts;
    }
    public interface Listener { void onMessage(Msg m); void onConversationsChanged(); }   // entregue na main

    private Chat() {}

    public static List<Conv> conversations(Context c) { return db(c, () -> ChatDb.conversations(c), new ArrayList<>()); }

    /** null se não existe. */
    public static Conv conversation(Context c, String convId) { return db(c, () -> ChatDb.conversation(c, convId), null); }

    /** Keyset: primeira página com beforeTs = Long.MAX_VALUE, beforeId = Long.MAX_VALUE; ordem ts DESC, id DESC. */
    public static List<Msg> page(Context c, String convId, long beforeTs, long beforeId, int limit) {
        return db(c, () -> ChatDb.page(c, convId, beforeTs, beforeId, limit), new ArrayList<>());
    }

    /** Só banco: cria o 1:1 se não existe; label = o que a tela mostra do amigo (@nome ou código). */
    public static String openDirect(Context c, String friendUserId, String label) {
        String id = ChatDb.directId(friendUserId);
        return db(c, () -> {
            ChatDb.ensureConversation(c, id, false, friendUserId, label);
            return id;
        }, null);
    }

    public static Msg sendText(Context c, String convId, String text) throws IOException { throw new UnsupportedOperationException(); }

    public static Msg sendLocation(Context c, String convId, double lat, double lng) throws IOException {
        throw new UnsupportedOperationException();
    }

    /** durationMs: 15 min, 1 h ou 8 h. */
    public static Msg startLive(Context c, String convId, long durationMs) throws IOException {
        throw new UnsupportedOperationException();
    }

    public static void stopLive(Context c, String convId) throws IOException { throw new UnsupportedOperationException(); }

    /** FAILED → reenviar. */
    public static Msg retry(Context c, long msgId) throws IOException { throw new UnsupportedOperationException(); }

    public static String createGroup(Context c, String name, List<String> memberUserIds) throws IOException {
        throw new UnsupportedOperationException();
    }

    public static void addMember(Context c, String convId, String userId) throws IOException { throw new UnsupportedOperationException(); }

    public static void removeMember(Context c, String convId, String userId) throws IOException {
        throw new UnsupportedOperationException();
    }

    public static void leaveGroup(Context c, String convId) throws IOException { throw new UnsupportedOperationException(); }

    public static List<Api.Friend> members(Context c, String convId) { return db(c, () -> ChatDb.members(c, convId), new ArrayList<>()); }

    /** Código de segurança (60 dígitos, como o Signal) do 1:1 com esse amigo. */
    public static String safetyNumber(Context c, String friendUserId) throws IOException { throw new UnsupportedOperationException(); }

    public static void markRead(Context c, String convId) {
        db(c, () -> {
            ChatDb.markRead(c, convId);
            return null;
        }, null);
    }

    /** Tela de chat aberta: liga o WsClient do processo principal; remove ao fechar. */
    public static void listen(Context c, Listener l) { throw new UnsupportedOperationException(); }

    public static void unlisten(Listener l) { throw new UnsupportedOperationException(); }

    /** Banco sob o lock; logout no outro processo (sem arquivo) → none. */
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
