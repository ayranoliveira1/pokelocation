package com.pokelocation.app.social.chat;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.util.Log;

import com.pokelocation.app.BuildConfig;
import com.pokelocation.app.Io;
import com.pokelocation.app.social.Session;
import com.pokelocation.app.social.crypto.SignalLoader;
import com.pokelocation.app.social.loc.Locations;
import com.pokelocation.app.social.net.Api;
import com.pokelocation.app.social.ws.Frames;
import com.pokelocation.app.social.ws.WsClient;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * WS do chat no processo principal, só com a tela de chat aberta (Chat.listen). Frames tratados na thread "pl-chat-ws"
 * (banco sob o atomic, rede fora dele, nunca o Io); a tela recebe na main.
 */
final class ChatSocket {
    private static final String TAG = "ChatSocket";
    private static final long ACK_TIMEOUT_MS = 30_000, RETRY_MS = 5_000;
    private static final Set<Chat.Listener> LISTENERS = new CopyOnWriteArraySet<>();

    /** Enviado e esperando o Ack S→C (o servidor gravou): sem ele em 30 s, ou se o WS cair, falhou. */
    private static final class Pending {
        final Runnable onAck, onFail;

        Pending(Runnable onAck, Runnable onFail) {
            this.onAck = onAck;
            this.onFail = onFail;
        }
    }

    private static final Map<String, Pending> PENDING = new ConcurrentHashMap<>();
    private static Context sApp;
    private static WsClient ws;
    private static HandlerThread thread;
    private static Handler work;
    private static volatile boolean open;

    private ChatSocket() {}

    static synchronized void listen(Context c, Chat.Listener l) {
        LISTENERS.add(l);
        if (thread != null) return;
        Context app = c.getApplicationContext();
        sApp = app;
        Notify.chatVisible(app, true);
        thread = new HandlerThread("pl-chat-ws", Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        work = new Handler(thread.getLooper());
        work.post(() -> start(app));
    }

    static synchronized void unlisten(Chat.Listener l) {
        LISTENERS.remove(l);
        if (!LISTENERS.isEmpty() || thread == null) return;
        Notify.chatVisible(sApp, false);
        WsClient w = ws;
        ws = null;
        open = false;
        HandlerThread t = thread;
        thread = null;
        work.post(() -> {
            if (w != null) w.stop();
            failAll();
            t.quitSafely();
        });
    }

    static boolean isOpen() { return open; }

    static boolean listening() { return !LISTENERS.isEmpty(); }

    /** Envia um frame; com cmid, espera o Ack S→C (onAck) ou falha (onFail, pode ser null). false se o WS está fechado. */
    static boolean send(byte[] frame, String cmid, Runnable onAck, Runnable onFail) {
        WsClient w = ws;
        Handler h = work;
        if (w == null || !open || h == null) return false;
        if (cmid != null) PENDING.put(cmid, new Pending(onAck, onFail));
        if (!w.send(frame)) {
            if (cmid != null) PENDING.remove(cmid);
            return false;
        }
        if (cmid != null) h.postDelayed(() -> fail(cmid), ACK_TIMEOUT_MS);
        return true;
    }

    /** Envia uma mensagem do chat: Ack → SENT; sem Ack → outbox (reenvia o mesmo client_msg_id ou FAILED). */
    static boolean sendMessage(Context app, byte[] frame, Chat.Msg m) {
        return send(frame, m.clientMsgId, () -> setState(app, m.id, Chat.SENT), () -> Chat.noAck(app, m.id));
    }

    /** Na thread do WS (rede permitida). */
    static void later(Runnable r, long delayMs) {
        Handler h = work;
        if (h != null) h.postDelayed(r, delayMs);
    }

    static void setState(Context app, long id, int state) {
        try {
            dispatch(Locations.atomic(app, () -> {
                ChatDb.setState(app, id, state);
                return ChatDb.byId(app, id);
            }));
        } catch (IOException e) {
            Log.w(TAG, "state: " + e.getClass().getSimpleName());
        }
    }

    private static void fail(String cmid) {
        Pending p = PENDING.remove(cmid);
        if (p != null && p.onFail != null) p.onFail.run();
    }

    private static void failAll() {
        List<String> ids = new ArrayList<>(PENDING.keySet());
        for (String id : ids) fail(id);
    }

    /** Na main, para cada tela aberta. Qualquer mensagem gravada/alterada muda também a lista de conversas. */
    static void dispatch(Chat.Msg m) {
        if (LISTENERS.isEmpty()) return;
        Io.main().post(() -> {
            for (Chat.Listener l : LISTENERS) {
                if (m != null) l.onMessage(m);
                l.onConversationsChanged();
            }
        });
    }

    private static void start(Context app) {
        if (SignalLoader.unavailable()) return; // social indisponível neste aparelho: sem chat em tempo real
        String user = null;
        try {
            if (SignalLoader.loaded()) user = Session.username(app);
        } catch (IOException ignored) {
        }
        if (user == null) { // lib ainda baixando, ou sem @nome (o Auth seria recusado): confere de novo com a tela aberta
            Handler h = work;
            if (h != null) h.postDelayed(() -> start(app), RETRY_MS);
            return;
        }
        synchronized (ChatSocket.class) {
            if (thread == null) return; // unlisten antes de abrir
            WsClient[] self = new WsClient[1];
            self[0] = new WsClient(BuildConfig.SOCIAL_API.replaceFirst("^https", "wss") + "/v1/ws", () -> Session.access(app),
                    new WsClient.Listener() {
                        @Override public void onOpen() { post(() -> opened(app, self[0])); }

                        @Override public void onFrame(Frames.Frame f) { post(() -> frame(app, self[0], f)); }

                        @Override public void onClosed(IOException why) {
                            post(() -> {
                                if (ws == self[0]) open = false;
                                failAll(); // sem a conexão, o Ack não chega mais
                            });
                        }
                    });
            ws = self[0];
            ws.start();
        }
    }

    private static void post(Runnable r) {
        Handler h = work;
        if (h != null) h.post(r);
    }

    private static void opened(Context app, WsClient from) {
        if (ws != from) return;
        open = true;
        try {
            Inbox.Result pruned = Locations.atomic(app, () -> Inbox.prune(app));
            Inbox.deliver(app, pruned, from::send);
        } catch (IOException e) {
            Log.w(TAG, "prune: " + e.getClass().getSimpleName());
        }
        Chat.resendOutbox(app); // o que ficou SENDING (WS caiu, tela fechou, processo novo) sai de novo
        Groups.distributeLives(app);
    }

    private static void frame(Context app, WsClient from, Frames.Frame f) {
        if (ws != from) return;
        try {
            switch (f.type) {
                case Frames.ACK: {
                    Pending p = PENDING.remove(f.msgId);
                    if (p != null && p.onAck != null) p.onAck.run();
                    break;
                }
                case Frames.ENVELOPE:
                case Frames.GROUP_ENVELOPE: {
                    Throwable fail = null;
                    Inbox.Result got = null;
                    try {
                        got = Locations.atomic(app, () -> Inbox.receive(app, f.peer, f.groupId, f.msgId, f.payload));
                    } catch (Api.Error e) {
                        throw e;
                    } catch (IOException e) {
                        fail = e;
                    }
                    if (Locations.ack(fail)) from.send(Frames.ack(f.peer, f.msgId)); // só depois de gravar
                    else Log.w(TAG, "envelope sem ack: " + Locations.rootName(fail));
                    if (got != null) Inbox.deliver(app, got, from::send);
                    break;
                }
                case Frames.GROUP_CHANGED:
                    Groups.sync(app, f.groupId);
                    Groups.distributeLives(app); // rotação ou membro novo: a live do grupo não fica parada
                    dispatch(null);
                    break;
                case Frames.ERROR:
                    if ("unauthorized".equals(f.code)) Api.renewNow(app); // refresh revogado → 401 → para abaixo
                    else if ("username_required".equals(f.code)) stopSocket(from);
                    break;
                default:
                    break; // posições: o mapa lê pelo REST
            }
        } catch (Api.Error e) {
            if (e.http == 401) stopSocket(from); // sessão morta: sem laço de reconexão; quem limpa é o principal
            else Log.w(TAG, "frame " + f.type + ": " + e.code);
        } catch (Exception e) {
            Log.w(TAG, "frame " + f.type + ": " + e.getClass().getSimpleName());
        }
    }

    private static void stopSocket(WsClient from) {
        synchronized (ChatSocket.class) {
            if (ws != from) return;
            ws = null;
            open = false;
        }
        from.stop();
        failAll();
    }
}
