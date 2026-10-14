package com.pokelocation.app.social.chat;

import android.content.Context;
import android.util.Log;

import com.pokelocation.app.data.Prefs;
import com.pokelocation.app.social.loc.Locations;
import com.pokelocation.app.social.net.Api;
import com.pokelocation.app.social.ws.Frames;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Posição das minhas lives, mandada pelo :overlay a cada tick. A live é escolha explícita no chat: segue com o
 * Compartilhar do mapa desligado; para quando vence, no stopLive (o banco) e no logout/geração nova (quem chama).
 */
public final class Live {
    /** Fim da live mais longa, no social_share: o :overlay sem Compartilhar e sem live não abre o social.db a cada tick. */
    private static final String UNTIL = "my_live_until";
    /** Atualizações por tick (5 s): 24/min no máximo, o resto do limite de 60/min do servidor fica para o chat. */
    private static final int PER_TICK = 2;

    private Live() {}

    @SuppressWarnings("deprecation")
    public static boolean maybeActive(Context c, long now) {
        return c.getSharedPreferences(Prefs.SHARE_FILE, Context.MODE_MULTI_PROCESS).getLong(UNTIL, 0) > now;
    }

    /** Logout (principal): o :overlay deixa de abrir o social.db a cada tick. */
    @SuppressWarnings("deprecation")
    public static void forget(Context c) {
        c.getSharedPreferences(Prefs.SHARE_FILE, Context.MODE_MULTI_PROCESS).edit().remove(UNTIL).apply();
    }

    /** Principal, depois de iniciar ou encerrar uma live. */
    @SuppressWarnings("deprecation")
    static void hint(Context c) throws IOException {
        long until = Locations.atomic(c, () -> ChatDb.maxMyLive(c));
        c.getSharedPreferences(Prefs.SHARE_FILE, Context.MODE_MULTI_PROCESS).edit().putLong(UNTIL, until).apply();
    }

    /**
     * Rede fora do lock. sent: convId → {lat, lng, quando} desta conexão (quem chama zera ao reconectar). A minha bolha
     * também anda (só no banco: a tela, no outro processo, lê ao abrir).
     */
    public static void send(Context c, String me, double lat, double lng, Map<String, double[]> sent, Predicate<byte[]> ws)
            throws IOException {
        long now = System.currentTimeMillis();
        List<Object[]> lives = Locations.atomic(c, () -> ChatDb.myLives(c, now));
        List<String> active = new ArrayList<>(lives.size());
        for (Object[] l : lives) active.add((String) l[0]);
        sent.keySet().retainAll(active);
        List<String> due = Rules.liveDue(active, sent, lat, lng, PER_TICK);
        for (Object[] l : lives) {
            String convId = (String) l[0], start = (String) l[3];
            if (!due.contains(convId)) continue;
            Chat.Msg m = new Chat.Msg();
            m.type = Chat.LIVE_START;
            m.text = start; // atualização: o outro lado move a mensagem original
            m.lat = lat;
            m.lng = lng;
            m.ts = now;
            m.liveUntil = (long) l[4];
            byte[] plain = Content.msg(m);
            String id = Rules.liveId();
            try {
                byte[] frame;
                if ((boolean) l[1]) {
                    // só com a minha chave já entregue a todos (key_ok, escrito pelo principal): rotacionada ou membro novo
                    // → pula até o principal distribuir (ao abrir o chat / no GroupChanged)
                    byte[] ct = Locations.atomic(c, () -> ChatDb.keyOk(c, convId) ? Groups.encrypt(c, me, convId, plain, false) : null);
                    if (ct == null) continue;
                    frame = Frames.groupEnvelope(convId, id, ct);
                } else {
                    frame = Frames.envelope((String) l[2], id, Groups.seal1to1(c, (String) l[2], plain, null));
                }
                if (!ws.test(frame)) return; // WS caiu: o resto no próximo tick
                sent.put(convId, new double[]{lat, lng, now});
                Locations.atomic(c, () -> ChatDb.updateLive(c, convId, null, start, lat, lng, m.liveUntil));
            } catch (Api.Error e) {
                if (e.http == 401) throw e;
                Log.w("Live", "update: " + e.code);
            } catch (IOException e) {
                Log.w("Live", "update: " + e.getClass().getSimpleName()); // uma conversa não segura as outras
            }
        }
    }
}
