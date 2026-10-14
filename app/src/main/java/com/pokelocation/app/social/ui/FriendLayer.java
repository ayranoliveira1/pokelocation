package com.pokelocation.app.social.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.Typeface;
import android.os.Process;
import android.util.Log;
import android.view.MotionEvent;

import com.google.android.material.snackbar.Snackbar;
import com.pokelocation.app.Io;
import com.pokelocation.app.R;
import com.pokelocation.app.data.Prefs;
import com.pokelocation.app.geo.Cooldown;
import com.pokelocation.app.geo.Geo;
import com.pokelocation.app.model.Coord;
import com.pokelocation.app.social.Session;
import com.pokelocation.app.social.crypto.SignalLoader;
import com.pokelocation.app.social.loc.Locations;
import com.pokelocation.app.social.loc.Locations.FriendLoc;
import com.pokelocation.app.social.ui.chat.ChatActivity;
import com.pokelocation.app.util.Ui;

import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.Projection;
import org.osmdroid.views.overlay.Overlay;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Amigos no mapa do principal: um único Overlay desenha todos (círculo + 4 primeiros chars do código, um Paint, sem
 * Marker nem bitmap por amigo). Polling de Locations.fetch a cada 5 s, na thread NET, só com o mapa visível. Criada
 * pelo RouteMapView só com login; sem a libsignal carregada não busca nada.
 */
public final class FriendLayer extends Overlay implements SignalLoader.Listener {
    private static final long POLL_MS = 5_000, MAX_AGE_MS = 60_000, STALE_S = 15;
    /**
     * Rede periódica do social nunca no Io (single-thread do app: rotas/favoritos não esperam a rede). Thread própria,
     * só enquanto o mapa faz polling: morre 6 s depois do último fetch (> POLL_MS, então não recria entre ticks).
     */
    private static final ThreadPoolExecutor NET = new ThreadPoolExecutor(1, 1, 6, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(), r -> new Thread(() -> {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                r.run();
            }, "pl-social-map"));

    static {
        NET.allowCoreThreadTimeOut(true);
    }

    private final Context app;
    private MapView mv;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Point px = new Point();
    private final GeoPoint gp = new GeoPoint(0.0, 0.0);
    private final float radius;
    private List<FriendLoc> locs = Collections.emptyList();
    private String[] labels = new String[0];   // initials de locs, calculadas 1x por fetch (o draw roda a 60 fps)
    private Io.Task task;
    private boolean resumed;
    private final Runnable tick = this::poll;

    public FriendLayer(Context c, MapView mv) {
        app = c.getApplicationContext();
        this.mv = mv;
        radius = Ui.dp(c, 18);
        fill.setColor(c.getColor(R.color.accent));
        text.setColor(Color.WHITE);
        text.setTextSize(Ui.dp(c, 11));
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        mv.getOverlays().add(this);
    }

    public void resume() {
        resumed = true;
        if (SignalLoader.loaded()) poll();
        else SignalLoader.start(app, this);   // com a lib baixada: só hash + load (~20 ms); onReady → poll
    }

    public void pause() {
        resumed = false;
        stop();
        SignalLoader.detach(this);
    }

    public void destroy() {
        pause();
        if (mv != null) mv.getOverlays().remove(this);
        mv = null;
        locs = Collections.emptyList();
        labels = new String[0];
    }

    private void stop() {
        Io.main().removeCallbacks(tick);
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void poll() {
        stop();
        if (!resumed || mv == null) return;
        if (!Session.loggedIn(app)) {   // logout (ou 401: a Api limpou a sessão): some e para
            locs = Collections.emptyList();
            labels = new String[0];
            mv.invalidate();
            return;
        }
        Io.Task t = task = new Io.Task();
        NET.execute(() -> {
            List<FriendLoc> got = null;
            boolean noName = false;
            try {
                got = Locations.fetch(app);
            } catch (Exception e) {   // rede/servidor: as últimas posições ficam e somem por idade
                noName = Errors.usernameRequired(e);
                Log.w("FriendLayer", "fetch: " + e.getClass().getSimpleName());
            }
            List<FriendLoc> l = got;
            boolean pause = noName;
            Io.main().post(() -> {
                if (t.isCancelled()) return;
                task = null;
                if (l != null) apply(l);
                if (pause) { if (mv != null) mv.invalidate(); }   // conta sem @nome: tenta de novo só no próximo resume
                else next();
            });
        });
    }

    private void apply(List<FriendLoc> l) {
        locs = l;
        labels = new String[l.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = marker(l.get(i).username, l.get(i).friendCode);
    }

    private void next() {
        if (mv == null) return;
        mv.invalidate();
        if (resumed) Io.main().postDelayed(tick, POLL_MS);
    }

    @Override public void onReady() { if (resumed && SignalLoader.loaded()) poll(); }
    @Override public void onProgress(long done, long total) {}
    @Override public void onError(java.io.IOException e) {}   // sem lib: sem camada (o Amigos mostra o erro)
    @Override public void onUnavailable() {}

    @Override
    public void draw(Canvas c, Projection pj) {
        if (locs.isEmpty()) return;
        long now = System.currentTimeMillis();
        float dy = -(text.ascent() + text.descent()) / 2;
        for (int i = 0; i < labels.length; i++) {
            FriendLoc f = locs.get(i);
            if (!visible(f.at, now)) continue;
            toPixels(pj, f);
            fill.setAlpha(ageSeconds(f.at, now) > STALE_S ? 140 : 255);   // sem atualização há 3 ticks: esmaecido
            c.drawCircle(px.x, px.y, radius, fill);
            if (labels[i] != null) c.drawText(labels[i], px.x, px.y + dy, text);
        }
    }

    @Override
    public boolean onSingleTapConfirmed(MotionEvent e, MapView map) {
        long now = System.currentTimeMillis();
        FriendLoc hit = null;
        Projection pj = map.getProjection();
        for (FriendLoc f : locs) {   // o último desenhado fica por cima
            if (!visible(f.at, now)) continue;
            toPixels(pj, f);
            float dx = e.getX() - px.x, dy = e.getY() - px.y;
            if (dx * dx + dy * dy <= radius * radius) hit = f;
        }
        if (hit == null) return false;
        String code = FriendsActivity.label(hit.username, hit.friendCode);
        long age = ageSeconds(hit.at, now);
        Coord me = Prefs.lastPosition(app);
        String info = me == null ? app.getString(R.string.soc_friend_tap_nodist, code, age)
                : app.getString(R.string.soc_friend_tap, code,
                        Cooldown.formatDistance(Geo.distance(me.lat, me.lng, hit.lat, hit.lng)), age);
        FriendLoc f = hit;
        Snackbar.make(map, info, Snackbar.LENGTH_LONG)
                .setAction(R.string.soc_chat_open, v -> v.getContext().startActivity(ChatActivity.friend(v.getContext(), f.userId, code)))
                .show();
        return true;
    }

    private void toPixels(Projection pj, FriendLoc f) {
        gp.setCoords(f.lat, f.lng);
        pj.toPixels(gp, px);
    }

    /** Some com mais de 60 s. at no futuro (relógio do amigo adiantado) conta como agora. */
    static boolean visible(long at, long now) {
        return now - at <= MAX_AGE_MS;
    }

    static long ageSeconds(long at, long now) {
        return Math.max(0, now - at) / 1000;
    }

    static String marker(String username, String code) {
        return username == null ? initials(code) : username.length() <= 4 ? username : username.substring(0, 4);
    }

    static String initials(String code) {
        return code == null || code.length() <= 4 ? code : code.substring(0, 4);
    }
}
