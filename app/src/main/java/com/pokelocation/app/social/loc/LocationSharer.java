package com.pokelocation.app.social.loc;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.util.Log;

import com.pokelocation.app.BuildConfig;
import com.pokelocation.app.data.Prefs;
import com.pokelocation.app.engine.MockEngine;
import com.pokelocation.app.geo.Geo;
import com.pokelocation.app.social.Session;
import com.pokelocation.app.social.crypto.SignalLoader;
import com.pokelocation.app.social.net.Api;
import com.pokelocation.app.social.store.SocialDb;
import com.pokelocation.app.social.ws.Frames;
import com.pokelocation.app.social.ws.WsClient;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * A8: compartilha minha posição com os amigos, no :overlay (criado/destruído pelo OverlayService; o motor não muda).
 * A cada 5 s a main só lê engine.lat()/lng() e entrega à thread "pl-loc", que decide: social.db existe (senão nada
 * social é carregado) → "Compartilhar" ligado (senão LocationOff na transição e fecha o WS) → sessão viva no banco
 * (Session.access: geração nova/logout → para tudo; o :overlay nunca limpa sessão) → libsignal carregada (só aqui,
 * com login + compartilhar) → WS aberto → moveu > 5 m → cifra com a sender key loc:{meuId} sob o SocialDb.atomic e
 * envia. A SKDM vai por envelope 1:1 aos amigos que ainda não a receberam nesta sessão do joystick. Rede sempre fora
 * do lock; nada disto roda no Io.
 */
public final class LocationSharer {
    private static final String TAG = "LocationSharer";
    private static final long TICK_MS = 5000;
    private static final double MIN_MOVE_M = 5;
    private static final int FRIENDS_EVERY = 60; // ticks (5 min): amigos novos recebem a SKDM

    private final Context app;
    private final MockEngine engine;
    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread thread;
    private Handler work;

    // só na "pl-loc":
    private WsClient ws;
    private String myId;
    private boolean sharing, open;
    private double lastLat = Double.NaN, lastLng = Double.NaN;
    private int ticks;
    // ponytail: SKDM 1 vez por amigo por conexão do WS (em memória); quem recebe processa repetida sem problema.
    // ponytail: a sender key loc:{meuId} não rotaciona ao remover/bloquear um amigo (ele guarda a chave; hoje só o
    // servidor deixa de entregar). Rotacionar = nova distribuição + SKDM aos que ficam (o chat/grupos do subprojeto 3).
    private final Set<String> skdmSent = new HashSet<>();

    public LocationSharer(Context c, MockEngine engine) {
        app = c.getApplicationContext();
        this.engine = engine;
    }

    /** Main. */
    public void start() {
        thread = new HandlerThread("pl-loc", Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        work = new Handler(thread.getLooper());
        main.post(tick);
    }

    /** Main (onDestroy do OverlayService): fecha o WS e a thread; a posição some sozinha no servidor em 60 s. */
    public void stop() {
        main.removeCallbacks(tick);
        HandlerThread t = thread;
        if (t == null) return;
        thread = null;
        work.post(() -> {
            halt();
            t.quitSafely();
        });
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            double lat = engine.lat(), lng = engine.lng(); // lidos na main, como o resto do OverlayService
            work.post(() -> step(lat, lng));
            main.postDelayed(this, TICK_MS);
        }
    };

    private void step(double lat, double lng) {
        try {
            if (!SocialDb.exists(app)) { // sem login: só um stat, nada social carregado
                halt();
                return;
            }
            if (!Prefs.shareLocation(app)) { // MULTI_PROCESS: lido aqui, fora da main
                off();
                return;
            }
            if (Session.access(app) == null) { // logout/geração nova sem sessão: para em 1 tick
                halt();
                return;
            }
            if (!SignalLoader.loaded()) {
                SignalLoader.start(app, null); // carrega na thread dele (login + compartilhar); segue no próximo tick
                return;
            }
            if (myId == null) myId = Api.me(app).id;
            if (ws == null) startWs();
            sharing = true;
            if (!open) return;
            if (ticks++ % FRIENDS_EVERY == 0) distribute();
            if (!moved(lastLat, lastLng, lat, lng)) return;
            String me = myId;
            byte[] p = Locations.atomic(app, () -> Locations.encrypt(app, me, lat, lng, System.currentTimeMillis()));
            if (ws.send(Frames.location(p))) {
                lastLat = lat;
                lastLng = lng;
            }
        } catch (Api.Error e) {
            if (e.http == 401) halt(); // sessão morta: para; quem limpa é o principal
            else Log.w(TAG, "tick: " + e.code);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "tick: " + e.getClass().getSimpleName()); // nada de corpo/token no log
        }
    }

    /**
     * Compartilhar desligado: avisa os amigos uma vez (LocationOff sai antes do close) e fecha o WS. Desligado durante
     * uma reconexão (WS fechado), o aviso não sai: a última posição some sozinha no servidor em até 60 s (TTL).
     */
    private void off() {
        if (sharing && ws != null && open) ws.send(Frames.locationOff());
        sharing = false;
        stopWs();
    }

    /** Sem sessão (ou stop): fecha tudo e esquece o desta sessão. A libsignal fica carregada (não descarrega). */
    private void halt() {
        sharing = false;
        stopWs();
        myId = null;
        skdmSent.clear();
    }

    private void stopWs() {
        if (ws != null) ws.stop();
        ws = null;
        open = false;
        lastLat = lastLng = Double.NaN;
        ticks = 0;
    }

    private void startWs() {
        WsClient[] self = new WsClient[1];
        self[0] = new WsClient(BuildConfig.SOCIAL_API.replaceFirst("^https", "wss") + "/v1/ws", () -> Session.access(app),
                new WsClient.Listener() {
                    @Override public void onOpen() {
                        work.post(() -> {
                            if (ws != self[0]) return;
                            open = true;
                            ticks = 0; // distribui a SKDM já no próximo tick
                            // send() true é só "enfileirado": se a conexão caiu antes de escrever, a SKDM se perdeu
                            skdmSent.clear();
                        });
                    }

                    @Override public void onFrame(Frames.Frame f) { work.post(() -> frame(self[0], f)); }

                    @Override public void onClosed(IOException why) {
                        work.post(() -> {
                            if (ws == self[0]) open = false;
                        });
                    }
                });
        ws = self[0];
        ws.start();
    }

    private void frame(WsClient from, Frames.Frame f) {
        if (ws != from) return;
        try {
            if (f.type == Frames.ENVELOPE) { // SKDM de um amigo online: guarda a sender key dele e confirma
                try {
                    Locations.atomic(app, () -> {
                        Locations.receive(app, f.peer, f.payload);
                        return null;
                    });
                } catch (Api.Error e) {
                    throw e; // 401: sem ack, o principal processa da fila
                } catch (IOException e) {
                    // ponytail: confirma mesmo assim (como o drain do principal): ilegível não melhora e o remetente
                    // reenvia a SKDM a cada sessão do joystick
                    Log.w(TAG, "envelope: " + e.getClass().getSimpleName());
                }
                ws.send(Frames.ack(f.peer, f.msgId));
            } else if (f.type == Frames.ERROR && "unauthorized".equals(f.code)) {
                Api.me(app); // REST: o 401 renova o access (token.lock); a reconexão do WS usa o novo
            }
            // LocationUpdate: quem desenha é o mapa no principal (Locations.fetch); aqui só se envia
        } catch (Api.Error e) {
            if (e.http == 401) halt();
        } catch (Exception e) {
            Log.w(TAG, "frame " + f.type + ": " + e.getClass().getSimpleName());
        }
    }

    /** SKDM 1:1 aos amigos que ainda não a receberam: rede (amigos, bundle) fora do lock, cripto dentro. */
    private void distribute() throws IOException {
        String me = myId;
        for (Api.Friend f : Api.friends(app).accepted) {
            if (skdmSent.contains(f.userId)) continue;
            String to = f.userId;
            try {
                byte[] env = Locations.atomic(app, () -> Locations.skdmFor(app, me, to, null));
                if (env == null) {
                    Api.Bundle b = Api.keyBundle(app, to);
                    env = Locations.atomic(app, () -> Locations.skdmFor(app, me, to, b));
                }
                if (ws != null && ws.send(Frames.envelope(to, UUID.randomUUID().toString(), env))) skdmSent.add(to);
            } catch (Api.Error e) {
                if (e.http == 401) throw e;
                Log.w(TAG, "skdm: " + e.code); // um amigo sem chaves no servidor não segura os outros
            }
        }
    }

    /** Pura (LocationsTest): manda se nada foi enviado ainda ou se andou mais de 5 m. */
    static boolean moved(double lat0, double lng0, double lat, double lng) {
        return Double.isNaN(lat0) || Geo.distance(lat0, lng0, lat, lng) > MIN_MOVE_M;
    }
}
