package com.pokelocation.app.pogo;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Process;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.pokelocation.app.App;
import com.pokelocation.app.Io;
import com.pokelocation.app.R;
import com.pokelocation.app.data.Db;
import com.pokelocation.app.data.FavoriteDao;
import com.pokelocation.app.geo.CoordParser;
import com.pokelocation.app.util.Ui;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Card de um Pokémon, o mesmo no mapa principal, no mini-mapa e no detalhe do painel. "Ver coordenadas" chama o
 * /reveal; só com a coordenada exata aparecem o botão de ir (teleportar ou definir destino) e o de favoritar.
 */
final class PogoCard {
    interface Go { void go(double lat, double lng, String title); }

    private static final String TAG = "PogoCard";
    private static final ThreadPoolExecutor NET = new ThreadPoolExecutor(1, 1, 6, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(), r -> new Thread(() -> {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                r.run();
            }, "pl-pogo-card"));

    static {
        NET.allowCoreThreadTimeOut(true);
    }

    final View view;
    private final Context ctx, app;
    private final ImageView icon;
    private final LinearLayout chips;
    private final TextView title, sub, pvp, moves, place, despawn, coords, reveal, go, fav;
    private Pogo.Mon m, extra;   // extra: golpes/local que o enrich trouxe
    private String dist;
    private String[] despawnT;
    private final StringBuilder tickText = new StringBuilder(32);
    private double[] ll;
    private Io.Task task, enrichTask;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            showDespawn();
            Io.main().postDelayed(this, 1000);
        }
    };

    PogoCard(Context c, int goLabel, Go onGo, Runnable onClose) {
        ctx = c;
        app = c.getApplicationContext();
        view = LayoutInflater.from(c).inflate(R.layout.pogo_card, null);
        icon = view.findViewById(R.id.pc_icon);
        chips = view.findViewById(R.id.pc_chips);
        title = view.findViewById(R.id.pc_title);
        sub = view.findViewById(R.id.pc_sub);
        pvp = view.findViewById(R.id.pc_pvp);
        moves = view.findViewById(R.id.pc_moves);
        place = view.findViewById(R.id.pc_place);
        despawn = view.findViewById(R.id.pc_despawn);
        coords = view.findViewById(R.id.pc_coords);
        reveal = view.findViewById(R.id.pc_reveal);
        go = view.findViewById(R.id.pc_go);
        fav = view.findViewById(R.id.pc_fav);
        go.setText(goLabel);
        view.findViewById(R.id.pc_close).setOnClickListener(v -> onClose.run());
        reveal.setOnClickListener(v -> reveal());
        go.setOnClickListener(v -> { if (ll != null) onGo.go(ll[0], ll[1], m.name); });
        fav.setOnClickListener(v -> favorite());
        coords.setOnClickListener(v -> { if (ll != null) Ui.copy(ctx, CoordParser.text(ll[0], ll[1]), true); });
        view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View x) { Io.main().post(tick); }
            @Override public void onViewDetachedFromWindow(View x) { Io.main().removeCallbacks(tick); }
        });
    }

    /** distance: a do "Perto de mim", ou null. Golpes e local vêm do /search; sem eles, enrich e depois o Geocoder. */
    void bind(Pogo.Mon mon, String distance) {
        cancel();
        m = mon;
        extra = null;
        dist = distance;
        ll = null;
        Bitmap b = PogoIcons.cached(mon);
        icon.setImageBitmap(b);
        if (b == null && mon.id > 0) PogoIcons.load(app, mon, bmp -> { if (m == mon && bmp != null) icon.setImageBitmap(bmp); });
        title.setText(PogoText.title(mon));
        sub.setText(PogoText.sub(mon));
        chips.removeAllViews();
        if (mon.cp > 0) chip(ctx.getString(R.string.pogo_map_cp, mon.cp), 0);
        if (mon.level > 0) chip(ctx.getString(R.string.pogo_map_level, PogoText.level(mon.level)), 0);
        if (mon.iv >= 0) {
            String iv = ctx.getString(R.string.pogo_map_iv, (int) mon.iv);
            chip(mon.rawIv != null ? iv + " (" + mon.rawIv + ")" : iv, PogoText.glow(mon));
        }
        line(pvp, PogoText.pvp(ctx, mon));
        showMovesPlace();
        enrich(mon);
        showDespawn();
        coords.setText(ctx.getString(R.string.pogo_card_coords, ctx.getString(R.string.pogo_card_coords_hint)));
        reveal.setVisibility(View.VISIBLE);
        reveal.setEnabled(true);
        go.setVisibility(View.GONE);
        fav.setVisibility(View.GONE);
        fav.setEnabled(true);
    }

    void destroy() {
        cancel();
        Io.main().removeCallbacks(tick);
        icon.setImageBitmap(null);   // o bitmap é do LRU do PogoIcons: o card não o segura depois de fechar
        m = null;
        ll = null;
    }

    private void cancel() {
        if (task != null) task.cancel();
        if (enrichTask != null) enrichTask.cancel();
        task = enrichTask = null;
    }

    /** Só mostra o que já chegou: a linha aparece quando o dado vem, nunca some depois (sem piscar). */
    private void showMovesPlace() {
        Pogo.Mon e = extra;
        String fast = m.fastMove != null ? m.fastMove : e != null ? e.fastMove : null;
        String charge = m.chargeMove != null ? m.chargeMove : e != null ? e.chargeMove : null;
        line(moves, fast == null && charge == null ? null
                : ctx.getString(R.string.pogo_card_moves, (fast != null ? fast : "—") + " / " + (charge != null ? charge : "—")));
        String loc = location();
        String p = TextUtils.isEmpty(loc) ? dist : dist == null ? loc : loc + " · " + dist;
        line(place, p == null ? null : ctx.getString(R.string.pogo_card_place, p));
    }

    private String location() {
        if (m.location != null) return m.location;
        if (extra != null && extra.location != null) return extra.location;
        return Double.isNaN(m.lat) ? null : PogoPlaces.cached(m.lat, m.lng);
    }

    /** Uma consulta por card aberto (o Pogo guarda por encounter); depois, se ainda faltar local, o Geocoder. */
    private void enrich(Pogo.Mon mon) {
        if (mon.fastMove != null || mon.location != null) return;
        if (mon.encounter == null || Double.isNaN(mon.lat)) {
            place(mon);
            return;
        }
        Io.Task t = enrichTask = new Io.Task();
        PogoPlaces.run(() -> {
            if (t.isCancelled()) return;
            Pogo.Mon r = null;
            try {
                r = Pogo.enrich(app, mon);
            } catch (Exception e) {   // 429/rede: as linhas ficam escondidas
                Log.w(TAG, "enrich: " + (e instanceof Pogo.Error ? ((Pogo.Error) e).http : e.getClass().getSimpleName()));
            }
            Pogo.Mon res = r;
            Io.main().post(() -> {
                if (t.isCancelled() || m != mon) return;
                enrichTask = null;
                extra = res;
                showMovesPlace();
                if (res == null || res.location == null) place(mon);
            });
        });
    }

    private void place(Pogo.Mon mon) {
        if (!Double.isNaN(mon.lat) && PogoPlaces.cached(mon.lat, mon.lng) == null)
            PogoPlaces.request(ctx, mon.lat, mon.lng, () -> { if (m == mon) showMovesPlace(); });
    }

    private void chip(String text, int color) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(12);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setTextColor(color != 0 ? color : ctx.getColor(R.color.on_surface));
        t.setBackgroundResource(R.drawable.map_bg_card);
        int h = Ui.dp(ctx, 8), v = Ui.dp(ctx, 3);
        t.setPadding(h, v, h, v);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(Ui.dp(ctx, 6));
        chips.addView(t, lp);
    }

    private static void line(TextView t, String s) {
        t.setText(s);
        t.setVisibility(s == null ? View.GONE : View.VISIBLE);
    }

    private void showDespawn() {
        if (m == null) return;
        long now = System.currentTimeMillis();
        if (m.despawnMs <= 0) {
            line(despawn, null);
            return;
        }
        if (despawnT == null) despawnT = PogoText.around(ctx, R.string.pogo_card_despawn);
        StringBuilder sb = tickText;   // roda a cada 1 s com o card aberto: sem String.format nem concatenação
        sb.setLength(0);
        PogoText.clock(sb.append(despawnT[0]), m.despawnMs - now);
        if (!m.verified) sb.append(" ⚠️");
        despawn.setText(sb.append(despawnT[1]));
        despawn.setVisibility(View.VISIBLE);
        view.setAlpha(PogoText.gone(m.despawnMs, now) ? 0.6f : 1f);
    }

    private void reveal() {
        cancel();
        Io.Task t = task = new Io.Task();
        Pogo.Mon mon = m;
        reveal.setEnabled(false);
        NET.execute(() -> {
            if (t.isCancelled()) return;
            double[] r = null;
            Exception err = null;
            try {
                r = Pogo.reveal(app, mon.reveal);
            } catch (Exception e) {
                err = e;
                Log.w(TAG, "reveal: " + (e instanceof Pogo.Error ? ((Pogo.Error) e).http : e.getClass().getSimpleName()));
            }
            double[] res = r;
            Exception e = err;
            Io.main().post(() -> {
                if (t.isCancelled()) return;
                task = null;
                if (res == null) {
                    coords.setText(ctx.getString(R.string.pogo_card_coords, PogoText.error(app, e)));
                    reveal.setEnabled(true);
                    return;
                }
                ll = res;
                coords.setText(ctx.getString(R.string.pogo_card_coords, CoordParser.text(res[0], res[1])));
                reveal.setVisibility(View.GONE);
                go.setVisibility(View.VISIBLE);
                fav.setVisibility(View.VISIBLE);
            });
        });
    }

    private void favorite() {
        if (ll == null) return;
        fav.setEnabled(false);
        double lat = ll[0], lng = ll[1];
        String name = favName(m, ctx.getString(R.string.pogo_map_cp, m.cp)), address = m.location != null ? m.location : "";
        Io.run(() -> {
            FavoriteDao.insert(app, name, address, lat, lng, 0, null);
            return Boolean.TRUE;
        }, new Io.Callback<Boolean>() {
            @Override public void onResult(Boolean ok) {
                Ui.toast(app, R.string.item_saved);
                if (App.process() == App.PROC_OVERLAY) Io.run(Db::close);   // o :overlay vive horas: o banco só fica aberto em uso
            }

            @Override public void onError(Throwable t) {
                Ui.toast(app, R.string.unknown_error);
                fav.setEnabled(true);
            }
        });
    }

    /** "Blitzle 100% PC 605" (sem IV ou sem PC, a parte some). */
    static String favName(Pogo.Mon m, String cp) {
        StringBuilder sb = new StringBuilder(m.name != null ? m.name : "#" + m.id);
        if (m.iv >= 0) sb.append(' ').append((int) m.iv).append('%');
        if (m.cp > 0) sb.append(' ').append(cp);
        return sb.toString();
    }
}
