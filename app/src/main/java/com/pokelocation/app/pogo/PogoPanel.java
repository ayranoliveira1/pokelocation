package com.pokelocation.app.pogo;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Process;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.pokelocation.app.App;
import com.pokelocation.app.Io;
import com.pokelocation.app.R;
import com.pokelocation.app.geo.Cooldown;
import com.pokelocation.app.geo.CoordParser;
import com.pokelocation.app.geo.Geo;
import com.pokelocation.app.ui.panel.PanelHost;
import com.pokelocation.app.util.Ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Lista de Pokémon: perto do centro (lookup numa caixa, recortado no raio e ordenado por distância) ou o mundo inteiro
 * (/search, sem coordenada), pelo escopo do filtro. Toque → PogoCard; "Filtros" troca a lista pelo PogoFilterForm na
 * mesma janela. Só framework: é o painel sobre o jogo (show) e o conteúdo da PogoListActivity.
 */
public final class PogoPanel {
    public interface Host {
        void go(double lat, double lng, String title);
        void close();
        default void focusable(boolean on) {}
        /** A folha de filtros por cima de tudo (v null = some); onBack = o BACK enquanto ela está aberta. */
        default void cover(View v, Runnable onBack) {}
    }

    private static final String TAG = "PogoPanel";
    private static final int WORLD = 0;
    private static final double M_PER_DEG = 111_320;
    /** Rede do painel fora do Io (single-thread do app); morre ociosa. */
    private static final ThreadPoolExecutor NET = new ThreadPoolExecutor(1, 1, 6, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(), r -> new Thread(() -> {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                r.run();
            }, "pl-pogo-panel"));

    static {
        NET.allowCoreThreadTimeOut(true);
    }

    public final View view;
    private final Context ctx, app;
    private final double lat, lng;
    private final int goLabel;
    private final boolean fullScreen;
    private final Host host;
    private final List<Pogo.Mon> mons = new ArrayList<>();
    private final ListView list;
    private final ViewGroup detail;
    private final View frame, progress;
    private final TextView status;
    private final Button close;
    private final LinearLayout chips;          // faixa de resumo: uma linha, rola na horizontal
    private final LinearLayout filtersBtn;
    private final TextView filtersText, badge;
    private final BaseAdapter adapter;
    private final int iconPx;
    private final Consumer<Bitmap> onIcon;
    // Memo por Pokémon da busca atual (o tick de 1 s não refaz título, números nem distância); limpo a cada busca.
    private final IdentityHashMap<Pogo.Mon, Row> rows = new IdentityHashMap<>();
    private final StringBuilder line = new StringBuilder(96);
    private String[] despawn;

    private static final class Row {
        final String title, fixed, dist;
        Drawable icon;

        Row(String title, String fixed, String dist) {
            this.title = title;
            this.fixed = fixed;
            this.dist = dist;
        }
    }

    private static final class Holder {
        final TextView title, sub;
        Pogo.Mon mon;

        Holder(TextView title, TextView sub) {
            this.title = title;
            this.sub = sub;
        }
    }
    private final Runnable onPlace;
    private PogoCard card;
    private PogoFilterSheet sheet;
    private PogoFilter base, filter;   // base: o último carregado; filter: o enviado (asSent), que os chips mostram
    private Io.Task task;
    private int km;
    private boolean destroyed, loaded;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            long now = System.currentTimeMillis();
            if (task == null && mons.removeIf(m -> PogoText.gone(m.despawnMs, now))) showStatus(PogoText.count(app, mons.size(), filter, km == WORLD));
            adapter.notifyDataSetChanged();
            Io.main().postDelayed(this, 1000);
        }
    };

    private static PanelHost sHost;   // o painel aberto sobre o jogo (só um por vez)

    /** Logout com o painel aberto: o botão segue a conta PokeLocation, o painel também. */
    public static void dismissOpen() {
        if (sHost != null) sHost.dismiss();
    }

    /** Sobre o jogo (:overlay); onTeleport(texto "lat, lng", título) abre o TeleportPanel. */
    public static void show(Context c, double lat, double lng, BiConsumer<String, String> onTeleport) {
        PanelHost[] h = new PanelHost[1];
        PogoPanel p = new PogoPanel(c, lat, lng, R.string.teleport, false, new Host() {
            @Override public void go(double la, double ln, String title) {
                h[0].dismiss();
                onTeleport.accept(CoordParser.text(la, ln), title);
            }

            @Override public void close() { h[0].dismiss(); }

            @Override public void focusable(boolean on) { h[0].setFocusable(on); }

            @Override public void cover(View v, Runnable onBack) { h[0].cover(v, onBack); }
        });
        p.start(null);
        h[0] = sHost = PanelHost.show(c, p.view, true);
        h[0].overGame(false, "pogo");
        h[0].setOnDismiss(() -> {
            if (sHost == h[0]) sHost = null;
            p.destroy();
            PogoIcons.trim();
        });
    }

    /** lat/lng NaN = sem centro: busca o mundo inteiro. fullScreen: a lista ocupa a Activity. */
    public PogoPanel(Context c, double lat, double lng, int goLabel, boolean fullScreen, Host host) {
        ctx = c;
        app = c.getApplicationContext();
        this.lat = lat;
        this.lng = lng;
        this.goLabel = goLabel;
        this.fullScreen = fullScreen;
        this.host = host;
        view = LayoutInflater.from(c).inflate(R.layout.pogo_panel, null);
        list = view.findViewById(R.id.pogo_list);
        detail = view.findViewById(R.id.pogo_detail);
        frame = view.findViewById(R.id.pogo_frame);
        status = view.findViewById(R.id.pogo_status);
        progress = view.findViewById(R.id.pogo_progress);
        close = view.findViewById(R.id.pogo_close);
        iconPx = Ui.dp(c, 48);
        if (fullScreen) {
            view.findViewById(R.id.pogo_title).setVisibility(View.GONE);   // a toolbar já diz
            frame.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        }
        adapter = new BaseAdapter() {
            @Override public int getCount() { return mons.size(); }
            @Override public Object getItem(int i) { return mons.get(i); }
            @Override public long getItemId(int i) { return i; }

            /** O tick de 1 s rebinda as linhas visíveis: só o texto de baixo (tempo/local) é refeito. */
            @Override public View getView(int i, View row, ViewGroup parent) {
                Holder h;
                if (row == null) {
                    row = LayoutInflater.from(ctx).inflate(R.layout.panel_item_row, parent, false);
                    row.setTag(h = new Holder(row.findViewById(R.id.row_title), row.findViewById(R.id.row_subtitle)));
                } else {
                    h = (Holder) row.getTag();
                }
                Pogo.Mon m = mons.get(i);
                Row r = rows.get(m);
                if (r == null) rows.put(m, r = new Row(PogoText.title(m), PogoText.fixedStats(ctx, m), km == WORLD ? null : distance(m)));
                if (h.mon != m) {
                    h.mon = m;
                    h.title.setText(r.title);
                    h.title.setCompoundDrawablesRelative(icon(m, r), null, null, null);
                } else if (r.icon == null && icon(m, r) != null) {
                    h.title.setCompoundDrawablesRelative(r.icon, null, null, null);
                }
                StringBuilder s = line;
                s.setLength(0);
                s.append(r.fixed);
                long now = System.currentTimeMillis();
                if (m.despawnMs > now) {
                    if (despawn == null) despawn = PogoText.around(ctx, R.string.pogo_map_despawn);
                    PogoText.clock(PogoText.dot(s).append(despawn[0]), m.despawnMs - now).append(despawn[1]);
                }
                rowPlace(s, m, r);
                h.sub.setText(s);
                h.sub.setVisibility(s.length() == 0 ? View.GONE : View.VISIBLE);
                return row;
            }
        };
        list.setAdapter(adapter);
        onIcon = b -> { if (b != null && !destroyed) adapter.notifyDataSetChanged(); };
        onPlace = () -> { if (!destroyed) adapter.notifyDataSetChanged(); };
        list.setOnItemClickListener((parent, row, pos, id) -> openDetail(mons.get(pos)));

        HorizontalScrollView strip = new HorizontalScrollView(c);
        strip.setHorizontalScrollBarEnabled(false);
        strip.setFillViewport(false);
        chips = new LinearLayout(c);
        chips.setGravity(android.view.Gravity.CENTER_VERTICAL);
        strip.addView(chips);
        ((ViewGroup) view.findViewById(R.id.pogo_chips)).addView(strip);
        // Botão "Filtros": 44dp visíveis (alvo 48), badge on_primary com o número em primary, dentro dele
        filtersBtn = new LinearLayout(c);
        filtersBtn.setGravity(android.view.Gravity.CENTER_VERTICAL);
        filtersBtn.setMinimumHeight(Ui.dp(c, 48));
        int ph = Ui.dp(c, 16);
        filtersBtn.setPadding(ph, 0, Ui.dp(c, 12), 0);
        filtersText = new TextView(c);
        filtersText.setText(R.string.pogo_panel_filters);
        filtersText.setTextSize(15);
        filtersText.setTypeface(PogoChips.MEDIUM);
        filtersBtn.addView(filtersText);
        badge = new TextView(c);
        badge.setTextSize(12);
        badge.setTypeface(PogoChips.MEDIUM);
        badge.setGravity(android.view.Gravity.CENTER);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(c.getColor(R.color.on_primary));
        badge.setBackground(dot);
        badge.setTextColor(c.getColor(R.color.primary));
        int bd = Ui.dp(c, 20);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(bd, bd);
        blp.setMarginStart(Ui.dp(c, 8));
        filtersBtn.addView(badge, blp);
        filtersBtn.setOnClickListener(x -> openSheet());
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.setMarginEnd(Ui.dp(c, 8));
        chips.addView(filtersBtn, flp);
        renderChips();
        close.setOnClickListener(x -> {
            if (detail.getVisibility() == View.VISIBLE) closeDetail();
            else host.close();
        });
        view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View x) { Io.main().postDelayed(tick, 1000); }
            @Override public void onViewDetachedFromWindow(View x) { Io.main().removeCallbacks(tick); }
        });
    }

    /** alert: o card do push por cima e a lista só carrega quando ele fecha (sem gastar o /search à toa). */
    public void start(Pogo.Mon alert) {
        if (alert != null) showMon(alert);
        else load();
    }

    public void destroy() {
        destroyed = true;
        cancel(task);
        task = null;
        Io.main().removeCallbacks(tick);
        if (card != null) card.destroy();
        if (sheet != null) sheet.destroy();
        sheet = null;
        mons.clear();
        rows.clear();
        list.setAdapter(null);   // solta os scraps do ListView (linhas com BitmapDrawable)
    }

    /** Na volta da conta PoGo (vincular treinador) o sheet aberto remonta o shiny. */
    public void onResume() {
        if (sheet != null) sheet.render();
    }

    private void load() {
        loaded = true;
        closeDetail();
        cancel(task);
        Io.Task t = task = new Io.Task();
        mons.clear();
        rows.clear();
        adapter.notifyDataSetChanged();
        status.setText(R.string.pogo_map_loading);
        progress.setVisibility(View.VISIBLE);
        NET.execute(() -> {
            if (t.isCancelled()) return;
            List<Pogo.Mon> got = null;
            Exception err = null;
            PogoFilter raw = PogoFilter.load(app), f = PogoText.asSent(app, raw.copy());
            int k = Double.isNaN(lat) ? WORLD : f.km;
            try {
                if (k == WORLD) {
                    got = Pogo.search(app, f);
                } else {
                    double[] b = around(lat, lng, k * 1000.0);
                    got = near(Pogo.lookup(app, b[0], b[1], b[2], b[3], f), lat, lng, k * 1000.0);
                }
            } catch (Exception e) {
                err = e;
                Log.w(TAG, "load: " + (e instanceof Pogo.Error ? ((Pogo.Error) e).http : e.getClass().getSimpleName()));
            }
            List<Pogo.Mon> l = got;
            Exception e = err;
            Io.main().post(() -> {
                if (t.isCancelled()) return;
                task = null;
                base = raw;
                filter = f;
                km = k;
                renderChips();
                if (e != null) {
                    showStatus(PogoText.error(app, e));
                    return;
                }
                mons.addAll(l);
                adapter.notifyDataSetChanged();
                showStatus(PogoText.count(app, mons.size(), filter, km == WORLD));
            });
        });
    }

    /** O sheet edita uma cópia: só "Mostrar resultados" salva (no :overlay, no arquivo dele) e busca de novo. */
    private void openSheet() {
        if (sheet != null || destroyed) return;
        closeDetail();
        Runnable link = App.process() == App.PROC_MAIN ? () -> ctx.startActivity(new Intent(ctx, PogoActivity.class)) : null;
        sheet = new PogoFilterSheet(ctx, base != null ? base : PogoFilter.load(app), f -> {
            f.save(app);
            sheet.close();
            load();
        }, () -> {
            host.cover(null, null);
            host.focusable(false);
            sheet = null;
        }, host::focusable, link);
        host.cover(sheet.view(), () -> { if (sheet != null) sheet.back(); });
    }

    /** Botão "Filtros" com o nº de ativos e um chip por filtro ativo (toque tira só aquele e busca de novo). */
    private void renderChips() {
        while (chips.getChildCount() > 1) chips.removeViewAt(1);
        PogoFilter f = filter;
        int n = f == null ? 0 : f.activeCount();
        badge.setText(String.valueOf(n));
        badge.setVisibility(n > 0 ? View.VISIBLE : View.GONE);
        // com filtro ativo, pílula primary; sem filtro, só a borda (e sem badge)
        int v = Ui.dp(ctx, 2);
        filtersBtn.setBackground(new InsetDrawable(n > 0 ? PogoChips.round(ctx, 22, ctx.getColor(R.color.primary), 0)
                : PogoChips.round(ctx, 22, 0, ctx.getColor(R.color.outline)), 0, v, 0, v));
        filtersText.setTextColor(ctx.getColor(n > 0 ? R.color.on_primary : R.color.on_surface));
        if (f == null) return;
        chip(f.km == WORLD || Double.isNaN(lat) ? ctx.getString(R.string.pogo_panel_world) : ctx.getString(R.string.pogo_chip_near, f.km), null);
        int species = f.pokemonIds.size() + f.speciesForms.size();
        if (species > 0) chip(ctx.getString(R.string.pogo_chip_species, species), x -> {
            x.pokemonIds.clear();
            x.speciesForms.clear();
            x.labels.clear();
        });
        if (f.ivMin != null || f.ivMax != null) chip(range("IV", f.ivMin, f.ivMax), x -> x.ivMin = x.ivMax = null);
        if (f.cpMin != null || f.cpMax != null) chip(range(ctx.getString(R.string.pogo_f_cp), f.cpMin, f.cpMax), x -> x.cpMin = x.cpMax = null);
        if (f.levelMin != null || f.levelMax != null) chip(range(ctx.getString(R.string.pogo_f_level), f.levelMin, f.levelMax), x -> x.levelMin = x.levelMax = null);
        if (f.gender != null) chip("Male".equals(f.gender) ? "♂" : "Female".equals(f.gender) ? "♀" : "⚲", x -> x.gender = null);
        if (!f.sizes.isEmpty()) chip(TextUtils.join(" ", f.sizes), x -> x.sizes.clear());
        if (f.boosted != null) chip(ctx.getString(f.boosted ? R.string.pogo_chip_boost : R.string.pogo_chip_no_boost), x -> x.boosted = null);
        if (f.pvpMode) chip("PvP", x -> x.pvpMode = false);
        if (f.shinyOnly) chip(ctx.getString(R.string.pogo_f_shiny_only), x -> x.shinyOnly = false);
        if (f.backgroundOnly) chip(ctx.getString(R.string.pogo_f_background), x -> x.backgroundOnly = false);
    }

    /** remove null = o escopo: abre o sheet em vez de sumir. */
    /** Tocar no texto abre o sheet; o "×" (alvo próprio) tira só este filtro e busca de novo. remove null = o escopo. */
    private void chip(String label, Consumer<PogoFilter> remove) {
        View t = PogoChips.summaryChip(ctx, label, x -> openSheet(), remove == null ? null : x -> {
            if (base == null) return;
            PogoFilter f = base.copy();
            remove.accept(f);
            f.save(app);
            load();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(Ui.dp(ctx, 8));
        chips.addView(t, lp);
    }

    static String range(String name, Number min, Number max) {
        if (max == null) return name + " ≥ " + num(min);
        if (min == null) return name + " ≤ " + num(max);
        return name + " " + num(min) + "–" + num(max);
    }

    private static String num(Number n) {
        double d = n.doubleValue();
        return d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    /** O card de um Pokémon de fora da lista (push de alerta): sem distância; fechar volta à lista. */
    public void showMon(Pogo.Mon m) {
        openDetail(m, null);
    }

    private void openDetail(Pogo.Mon m) {
        openDetail(m, km == WORLD ? null : distance(m));
    }

    private void openDetail(Pogo.Mon m, String where) {
        if (card == null) {
            card = new PogoCard(ctx, goLabel, host::go, this::closeDetail);
            detail.addView(card.view);
        }
        card.bind(m, where);
        list.setVisibility(View.GONE);
        detail.setVisibility(View.VISIBLE);
        close.setText(R.string.cancel);
    }

    private void closeDetail() {
        detail.setVisibility(View.GONE);
        list.setVisibility(View.VISIBLE);
        close.setText(R.string.close);
        if (!loaded && !destroyed) load();
    }

    /** Ícone de 48dp da linha, criado uma vez; sem ele (ainda não veio ou o LRU expulsou), pede e redesenha ao chegar. */
    private Drawable icon(Pogo.Mon m, Row r) {
        if (r.icon != null) return r.icon;
        Bitmap b = PogoIcons.cached(m);
        if (b == null) {
            PogoIcons.load(app, m, onIcon);
            return null;
        }
        BitmapDrawable d = new BitmapDrawable(ctx.getResources(), b);
        d.setBounds(0, 0, iconPx, iconPx);
        return r.icon = d;
    }

    private String distance(Pogo.Mon m) {
        return Cooldown.formatDistance(Geo.distance(lat, lng, m.lat, m.lng));
    }

    /** Mundo: o local do /search. Perto: bairro/cidade pelo Geocoder (só das linhas desenhadas, ou seja, visíveis) + distância. */
    private void rowPlace(StringBuilder s, Pogo.Mon m, Row r) {
        String loc = m.location;
        if (loc == null && r.dist != null) {
            loc = PogoPlaces.cached(m.lat, m.lng);
            if (loc == null) PogoPlaces.request(ctx, m.lat, m.lng, onPlace);
        }
        if (loc != null && !loc.isEmpty()) PogoText.dot(s).append(loc);
        if (r.dist != null) PogoText.dot(s).append(r.dist);
    }

    private void showStatus(String s) {
        progress.setVisibility(View.GONE);
        status.setText(s);
    }

    private static void cancel(Io.Task t) { if (t != null) t.cancel(); }

    /** Caixa {n,w,s,e} que contém o círculo de raio m. */
    static double[] around(double lat, double lng, double m) {
        double dLat = m / M_PER_DEG, dLng = m / (M_PER_DEG * Math.max(0.01, Math.cos(Math.toRadians(lat))));
        return new double[]{lat + dLat, lng - dLng, lat - dLat, lng + dLng};
    }

    /** Só os de dentro do raio (a caixa sobra nos cantos), do mais perto ao mais longe. */
    static List<Pogo.Mon> near(List<Pogo.Mon> l, double lat, double lng, double m) {
        List<Pogo.Mon> out = new ArrayList<>(l.size());
        for (Pogo.Mon x : l) if (Geo.distance(lat, lng, x.lat, x.lng) <= m) out.add(x);
        out.sort(Comparator.comparingDouble(x -> Geo.distance(lat, lng, x.lat, x.lng)));
        return out;
    }
}
