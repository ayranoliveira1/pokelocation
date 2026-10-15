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

/** A live é escolha explícita no chat: segue com o Compartilhar do mapa desligado. */
public final class Live {
    /** No social_share: o :overlay sem Compartilhar e sem live não abre o social.db a cada tick. */
    private static final String UNTIL = "my_live_until";
    /** 24/min no máximo: o resto do limite de 60/min do servidor fica para o chat. */
    private static final int PER_TICK = 2;

    private Live() {}

    @SuppressWarnings("deprecation")
    public static boolean maybeActive(Context c, long now) {
        return c.getSharedPreferences(Prefs.SHARE_FILE, Context.MODE_MULTI_PROCESS).getLong(UNTIL, 0) > now;
    }

    @SuppressWarnings("deprecation")
    public static void forget(Context c) {
        c.getSharedPreferences(Prefs.SHARE_FILE, Context.MODE_MULTI_PROCESS).edit().remove(UNTIL).apply();
    }

    @SuppressWarnings("deprecation")
    static void hint(Context c) throws IOException {
        long until = Locations.atomic(c, () -> ChatDb.maxMyLive(c));
        c.getSharedPreferences(Prefs.SHARE_FILE, Context.MODE_MULTI_PROCESS).edit().putLong(UNTIL, until).apply();
    }

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
            m.text = start;
            m.lat = lat;
            m.lng = lng;
            m.ts = now;
            m.liveUntil = (long) l[4];
            byte[] plain = Content.msg(m);
            String id = Rules.liveId();
            try {
                byte[] frame;
                if ((boolean) l[1]) {
                    // ponytail: o :overlay ignora o GroupChanged; com o chat fechado, quem entrou não vê a live e a rotação
                    // por saída espera a próxima abertura do chat (o fan-out do servidor já exclui quem saiu: degradação,
                    // não vazamento). Melhorar = o :overlay avisar o principal para sincronizar.
                    byte[] ct = Locations.atomic(c, () -> ChatDb.keyOk(c, convId) ? Groups.encrypt(c, me, convId, plain, false) : null);
                    if (ct == null) continue;
                    frame = Frames.groupEnvelope(convId, id, ct);
                } else {
                    frame = Frames.envelope((String) l[2], id, Groups.seal1to1(c, (String) l[2], plain, null));
                }
                if (!ws.test(frame)) return;
                sent.put(convId, new double[]{lat, lng, now});
                Locations.atomic(c, () -> ChatDb.updateLive(c, convId, null, start, lat, lng, m.liveUntil));
            } catch (Api.Error e) {
                if (e.endsSession()) throw e;
                Log.w("Live", "update: " + e.code);
            } catch (IOException e) {
                Log.w("Live", "update: " + e.getClass().getSimpleName());
            }
        }
    }
}
