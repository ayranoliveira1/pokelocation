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

/** Frames na "pl-chat-ws", nunca no Io: banco sob o atomic, rede fora dele. */
public final class ChatSocket {
    private static final String TAG = "ChatSocket";
    private static final long ACK_TIMEOUT_MS = 30_000, RETRY_MS = 5_000;
    private static final Set<Chat.Listener> LISTENERS = new CopyOnWriteArraySet<>();

    /** Sem o Ack S→C (o servidor gravou) em 30 s, ou se o WS cair, falhou. */
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
    private static volatile boolean meChecked;

    private ChatSocket() {}

    static synchronized void listen(Context c, Chat.Listener l) {
        LISTENERS.add(l);
        if (thread != null) return;
        Context app = c.getApplicationContext();
        sApp = app;
        Notify.chatVisible(app, true);
        meChecked = false;
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

    static volatile String viewing;
    private static volatile boolean wsWanted;
    private static volatile long panelClosedAt;

    static void panel(Chat.Listener l, boolean opened) {
        if (opened) LISTENERS.add(l);
        else LISTENERS.remove(l);
        if (LISTENERS.isEmpty()) panelClosedAt = System.currentTimeMillis();
    }

    public static boolean overlayWsNeeded(Context app) {
        if (!wsWanted) return false;
        boolean panelOpen = !LISTENERS.isEmpty();
        int d = Rules.overlayOutbox(true, panelOpen, !panelOpen && Chat.hasOutbox(app), System.currentTimeMillis() - panelClosedAt);
        if (d == Rules.OUT_KEEP) return true;
        if (d == Rules.OUT_GIVE_UP) Chat.failOutbox(app);
        wsWanted = false;
        return false;
    }

    /** Abrir e ler o painel não paga libsignal/TLS: só o envio pede o WS do :overlay (até a outbox sair). */
    static void wantWs() {
        if (LISTENERS.isEmpty()) panelClosedAt = System.currentTimeMillis();
        wsWanted = true;
    }

    public static boolean wsWanted() { return wsWanted; }

    /** :overlay: o chat usa o WS do LocationSharer enquanto ele estiver aberto; h = a thread dele. */
    public static synchronized void bind(Context app, WsClient w, Handler h) {
        ws = w;
        work = h;
        open = true;
        h.post(() -> {
            Chat.resendOutbox(app);
            Inbox.sendPendingResets(app, w::send);
        });
    }

    public static void unbind(WsClient w) {
        synchronized (ChatSocket.class) {
            if (ws != w) return;
            ws = null;
            work = null;
            open = false;
        }
        failAll();
    }

    public static void acked(String clientMsgId) {
        Pending p = PENDING.remove(clientMsgId);
        if (p != null && p.onAck != null) p.onAck.run();
    }

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

    static boolean sendMessage(Context app, byte[] frame, Chat.Msg m) {
        return send(frame, m.clientMsgId, () -> setState(app, m.id, Chat.SENT), () -> Chat.noAck(app, m.id));
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
        String user = null;
        try {
            if (SignalLoader.loaded()) {
                user = Session.username(app);
                if (user == null && !meChecked) { // @nome que não ficou gravado: o /v1/me grava de novo
                    meChecked = true;
                    user = Api.me(app).username;
                }
            }
        } catch (IOException ignored) {
        }
        int step = Rules.wsStep(SignalLoader.loaded(), SignalLoader.unavailable(), user);
        if (step == Rules.WS_GIVE_UP) return;
        if (step != Rules.WS_OPEN) {
            if (step == Rules.WS_LOAD_LIB) SignalLoader.ensure(app);
            Handler h = work;
            if (h != null) h.postDelayed(() -> start(app), RETRY_MS);
            return;
        }
        synchronized (ChatSocket.class) {
            if (thread == null) return;
            WsClient[] self = new WsClient[1];
            self[0] = new WsClient(BuildConfig.SOCIAL_API.replaceFirst("^https", "wss") + "/v1/ws", () -> Api.wsToken(app),
                    new WsClient.Listener() {
                        @Override public void onOpen() { post(() -> opened(app, self[0])); }

                        @Override public void onFrame(Frames.Frame f) { post(() -> frame(app, self[0], f)); }

                        @Override public void onClosed(IOException why) {
                            post(() -> {
                                if (ws == self[0]) open = false;
                                failAll();
                                if ("unauthorized".equals(why.getMessage())) stopSocket(self[0]); // refresh revogado: sem laço
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
        Chat.resendOutbox(app);
        Groups.leaveMissing(app);
        Groups.distributeLives(app);
        Inbox.sendPendingResets(app, from::send);
        try {
            Locations.drain(app); // uma página: o servidor também empurra o backlog pelo WS depois do Auth
        } catch (IOException e) {
            Log.w(TAG, "fila: " + e.getClass().getSimpleName());
        }
    }

    private static void frame(Context app, WsClient from, Frames.Frame f) {
        if (ws != from) return;
        try {
            switch (f.type) {
                case Frames.ACK:
                    acked(f.msgId);
                    break;
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
                    if (Locations.ack(fail)) from.send(Frames.ack(f.peer, f.msgId));
                    else Log.w(TAG, "envelope sem ack: " + Locations.rootName(fail));
                    if (got != null) Inbox.deliver(app, got, from::send);
                    break;
                }
                case Frames.GROUP_CHANGED:
                    Groups.sync(app, f.groupId);
                    Groups.distributeLives(app);
                    dispatch(null);
                    break;
                case Frames.ERROR: {
                    int a = Frames.onError(f.code);
                    if (a == Frames.ERR_RENEW) Api.renewNow(app); // refresh revogado → 401 → para sem laço
                    else if (a == Frames.ERR_PEER) Chat.failNotFriends(app);
                    else if (a != Frames.ERR_IGNORE) stopSocket(from);
                    if (a == Frames.ERR_LOGOUT) Session.ended(app);
                    break;
                }
                default:
                    break;
            }
        } catch (Api.Error e) {
            if (e.endsSession()) stopSocket(from); // sem laço de reconexão; quem limpa a sessão é o principal
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
