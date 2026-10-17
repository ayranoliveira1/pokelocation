package com.pokelocation.app.pogo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.RectF;
import android.os.Process;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.pokelocation.app.Io;
import com.pokelocation.app.R;
import com.pokelocation.app.geo.Geo;
import com.pokelocation.app.ui.map.MapPoiLayer;
import com.pokelocation.app.util.Ui;

import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.Projection;
import org.osmdroid.views.overlay.Overlay;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Pokémon do PoGo Coordinates no mapa (main e :map), no molde do FriendLayer: um Overlay desenha todos, sem Marker
 * (pastilha na cor do tema sob o ícone do PogoIcons; sem ícone, um ponto neutro). O toque abre o PogoCard logo
 * abaixo (ou acima) do Pokémon. Busca no resume e quando a caixa (4 casas) muda; o tick de 60 s rebusca a mesma caixa. Criada
 * pelo RouteMapView só com a camada ligada.
 */
public final class PogoLayer extends Overlay implements MapPoiLayer {

    private static final String TAG = "PogoLayer";
    private static final long POLL_MS = 60_000;
    private static final double MAX_DIAGONAL_M = 12_500_000;
    /** O lookup de 500 itens leva 1–2 s: fora do Io para não segurar rotas e favoritos. */
    private static final ThreadPoolExecutor NET = new ThreadPoolExecutor(1, 1, 6, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(), r -> new Thread(() -> {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                r.run();
            }, "pl-pogo-map"));

    static {
        NET.allowCoreThreadTimeOut(true);
    }

    private final Context ctx, app;
    private MapView mv;
    private final View status, busy;
    private final TextView info;
    private final int goLabel;
    private final MapPoiLayer.OnGo onReveal;
    private final Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG),
            dot = new Paint(Paint.ANTI_ALIAS_FLAG), halo = new Paint(Paint.ANTI_ALIAS_FLAG), bmp = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Point px = new Point();
    private final GeoPoint gp = new GeoPoint(0.0, 0.0);
    private final RectF dst = new RectF();
    private final float iconR, haloR, picR, dotR;
    private final FrameLayout cardHost;   // mesma área do mapa, por cima dos controles
    private PogoCard card;
    private int tapX, tapY;
    private final View.OnLayoutChangeListener relayout = (v, l, t, r, b, ol, ot, or, ob) -> {
        if (b - t != ob - ot) v.post(this::placeCard);   // o reveal acrescenta botões: o card cresce e é preso de novo
    };
    private List<Pogo.Mon> mons = Collections.emptyList();
    private double[] lastBox;
    private long lastAt, blockedUntil;
    private long askAt;   // próxima rodada de pedidos de ícone no draw
    private boolean resumed, zoomHint, taps = true;
    private Io.Task task;
    private final Runnable tick = this::poll;
    private final Consumer<Bitmap> onIcon = b -> { if (b != null && mv != null) mv.invalidate(); };

    /** status: o map_pogo_box (indicador + texto), escondido pelo toque; goLabel: botão de ir do card. */
    public PogoLayer(Context c, MapView mv, FrameLayout cardHost, View status, int goLabel, MapPoiLayer.OnGo onReveal) {
        ctx = c;
        this.cardHost = cardHost;
        app = c.getApplicationContext();
        this.mv = mv;
        this.status = status;
        busy = status.findViewById(R.id.map_pogo_busy);
        info = status.findViewById(R.id.map_pogo_info);
        this.goLabel = goLabel;
        this.onReveal = onReveal;
        iconR = Ui.dp(c, 18);
        haloR = iconR + Ui.dp(c, 4);
        picR = iconR - Ui.dp(c, 2);
        dotR = Ui.dp(c, 4);
        // c é o contexto do mapa (Activity no main, overlayContext no :map): as cores já seguem o claro/escuro do app.
        disc.setColor(c.getColor(R.color.overlay_button_bg));
        ring.setColor(c.getColor(R.color.outline));
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(Ui.dp(c, 1));
        dot.setColor(c.getColor(R.color.outline));
        status.setOnClickListener(v -> v.setVisibility(View.GONE));
        mv.getOverlays().add(this);
    }

    /** Busca já (a volta da tela de filtros não espera o tick); o retry_after continua valendo. */
    @Override
    public void resume() {
        resumed = true;
        lastBox = null;
        poll();
    }

    @Override
    public void pause() {
        resumed = false;
        Io.main().removeCallbacks(tick);
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    @Override
    public void destroy() {
        pause();
        closeCard();
        PogoIcons.trim();
        if (mv != null) {
            mv.getOverlays().remove(this);
            mv.invalidate();
        }
        mv = null;
        mons = Collections.emptyList();
        status.setOnClickListener(null);
        status.setVisibility(View.GONE);
    }

    /** Desligado no modo mover ponto do RouteMapView: o toque cai no mapa, não abre o card. */
    @Override
    public void setTapsEnabled(boolean on) { taps = on; }

    /** onIdle do mapa (scroll/zoom) e 1º layout. */
    @Override
    public void boxChanged() { poll(); }

    private void poll() {
        Io.main().removeCallbacks(tick);
        if (!resumed || mv == null || mv.getHeight() == 0 || task != null) return;   // em voo: o fim chama poll()
        BoundingBox b = mv.getBoundingBox();
        double[] box = box(b.getLatNorth(), b.getLonWest(), b.getLatSouth(), b.getLonEast());
        if (box == null || tooBig(box)) {
            lastBox = null;
            if (box != null) {
                zoomHint = true;
                show(app.getString(R.string.pogo_map_zoom_in));
            }
            return;
        }
        if (zoomHint) {
            zoomHint = false;
            status.setVisibility(View.GONE);
        }
        long wait = wait(Arrays.equals(box, lastBox), lastAt, blockedUntil, System.currentTimeMillis());
        if (wait > 0) Io.main().postDelayed(tick, wait);
        else fetch(box);
    }

    private void fetch(double[] b) {
        Io.Task t = task = new Io.Task();
        info.setText(R.string.pogo_map_loading);
        busy.setVisibility(View.VISIBLE);
        status.setVisibility(View.VISIBLE);
        NET.execute(() -> {
            List<Pogo.Mon> got = null;
            Exception err = null;
            PogoFilter f = null;
            try {
                f = PogoText.asSent(app, PogoFilter.load(app));
                f.km = 0;   // o mapa busca a caixa visível: o escopo do painel não vale aqui (nem conta como filtro)
                got = Pogo.lookup(app, b[0], b[1], b[2], b[3], f);
            } catch (Exception e) {   // os últimos ficam e somem pelo despawn
                err = e;
                Log.w(TAG, "lookup: " + (e instanceof Pogo.Error ? ((Pogo.Error) e).http : e.getClass().getSimpleName()));
            }
            List<Pogo.Mon> l = got;
            Exception e = err;
            PogoFilter filter = f;
            Io.main().post(() -> {
                if (t.isCancelled()) return;
                task = null;
                long now = System.currentTimeMillis();
                lastBox = b;
                lastAt = now;
                if (e instanceof Pogo.Error && ((Pogo.Error) e).http == 429) {
                    long r = ((Pogo.Error) e).retryAfterS;
                    blockedUntil = now + (r > 0 ? r * 1000 : POLL_MS);
                }
                if (l != null) apply(l);
                if (e == null) show(PogoText.count(app, l.size(), filter, false));
                else show(alive(now) > 0 ? PogoText.stale(app, e) : PogoText.error(app, e));   // os da última busca ficam (T29)
                if (mv != null) mv.invalidate();
                poll();
            });
        });
    }

    private int alive(long now) {
        int n = 0;
        for (Pogo.Mon m : mons) if (!PogoText.gone(m.despawnMs, now)) n++;
        return n;
    }

    private void apply(List<Pogo.Mon> l) {
        mons = l;
    }

    private void show(String s) {
        busy.setVisibility(View.GONE);
        info.setText(s);
        status.setVisibility(View.VISIBLE);
    }

    @Override
    public void draw(Canvas c, Projection pj) {
        if (mons.isEmpty()) return;
        long now = System.currentTimeMillis();
        boolean ask = now >= askAt, asked = false;
        for (Pogo.Mon m : mons) {
            if (PogoText.gone(m.despawnMs, now)) continue;
            toPixels(pj, m);
            int g = PogoText.glow(m);
            if (g != 0) {
                halo.setColor(g);
                halo.setAlpha(110);
                c.drawCircle(px.x, px.y, haloR, halo);
            }
            c.drawCircle(px.x, px.y, iconR, disc);
            c.drawCircle(px.x, px.y, iconR, ring);
            Bitmap b = PogoIcons.cached(m);
            if (b == null) {
                // Pede de novo os visíveis (o LRU expulsa; o VIP traz 500): no máximo 1 rodada a cada 2 s, não por frame.
                if (ask && onScreen()) {
                    PogoIcons.load(app, m, onIcon);
                    asked = true;
                }
                c.drawCircle(px.x, px.y, dotR, dot);
                continue;
            }
            dst.set(px.x - picR, px.y - picR, px.x + picR, px.y + picR);
            c.drawBitmap(b, null, dst, bmp);
        }
        if (asked) askAt = now + 2000;
    }

    private boolean onScreen() {
        return mv != null && px.x > -iconR && px.y > -iconR && px.x < mv.getWidth() + iconR && px.y < mv.getHeight() + iconR;
    }

    @Override
    public boolean onSingleTapConfirmed(MotionEvent e, MapView map) {
        if (!taps) return false;
        long now = System.currentTimeMillis();
        Pogo.Mon hit = null;
        Projection pj = map.getProjection();
        for (Pogo.Mon m : mons) {   // o último desenhado fica por cima
            if (PogoText.gone(m.despawnMs, now)) continue;
            toPixels(pj, m);
            float dx = e.getX() - px.x, dy = e.getY() - px.y;
            if (dx * dx + dy * dy <= iconR * iconR) hit = m;
        }
        if (hit == null) return false;
        if (card == null) card = new PogoCard(ctx, goLabel, (lat, lng, t) -> onReveal.at(lat, lng), this::closeCard);
        card.bind(hit, null);
        toPixels(pj, hit);
        tapX = px.x;
        tapY = px.y;
        placeCard();
        return true;
    }

    /**
     * Fixo na tela, não no GeoPoint: com o mapa seguindo o pino (mock/caminhada) um card ancorado andaria e o toque
     * cairia fora do botão. Preso aos limites do mapa; abaixo do Pokémon, ou acima se não couber.
     */
    private void placeCard() {
        if (card == null) return;
        View v = card.view;
        int hw = cardHost.getWidth(), hh = cardHost.getHeight(), m = Ui.dp(ctx, 8);
        int w = Math.min(Ui.dp(ctx, 280), hw - 2 * m);
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(hh, View.MeasureSpec.AT_MOST));
        int[] p = place(tapX, tapY, (int) iconR, w, v.getMeasuredHeight(), hw, hh, m);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = p[0];
        lp.topMargin = p[1];
        if (v.getParent() == null) {
            cardHost.addView(v, lp);
            v.addOnLayoutChangeListener(relayout);
        } else {
            v.setLayoutParams(lp);
        }
    }

    /** {left, top} do card w×h para o Pokémon em (x, y) num mapa hw×hh, com margem m e folga gap do ícone. */
    static int[] place(int x, int y, int gap, int w, int h, int hw, int hh, int m) {
        int left = clamp(x - w / 2, m, hw - w - m);
        int top = y + gap;
        if (top + h > hh - m) top = y - gap - h;
        return new int[]{left, clamp(top, m, hh - h - m)};
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(v, hi));
    }

    private void closeCard() {
        if (card == null) return;
        card.view.removeOnLayoutChangeListener(relayout);
        cardHost.removeView(card.view);
        card.destroy();
        card = null;
    }

    private void toPixels(Projection pj, Pogo.Mon m) {
        gp.setCoords(m.lat, m.lng);
        pj.toPixels(gp, px);
    }

    static double round4(double v) {
        return Math.round(v * 1e4) / 1e4;
    }

    /** {n,w,s,e} arredondada como o front; null no antimeridiano (w > e), que não buscamos. */
    static double[] box(double n, double w, double s, double e) {
        return w > e ? null : new double[]{round4(n), round4(w), round4(s), round4(e)};
    }

    static boolean tooBig(double[] box) {
        return Geo.distance(box[0], box[1], box[2], box[3]) > MAX_DIAGONAL_M;
    }

    /** ms até a próxima busca (≤ 0 = agora): caixa nova busca já, a mesma espera o tick; o 429 adia as duas. */
    static long wait(boolean sameBox, long lastAt, long blockedUntil, long now) {
        return Math.max(blockedUntil - now, sameBox ? lastAt + POLL_MS - now : 0);
    }
}
