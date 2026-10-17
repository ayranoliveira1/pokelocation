package com.pokelocation.app.pogo;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.PowerManager;
import android.os.Process;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

import com.pokelocation.app.App;
import com.pokelocation.app.Io;
import com.pokelocation.app.R;
import com.pokelocation.app.geo.Cooldown;
import com.pokelocation.app.geo.Geo;
import com.pokelocation.app.social.Session;
import com.pokelocation.app.util.Ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Lista de Pokémon: perto do centro (lookup numa caixa, recortado no raio e ordenado por distância) ou o mundo inteiro
 * (/search, sem coordenada), pelo escopo do filtro. Toque → PogoCard; "Filtros" troca a lista pelo PogoFilterForm na
 * mesma janela. Só framework: o conteúdo da PogoListActivity e da aba Notificações (sobre o jogo, a coluna é o PogoBubbles).
 */
public final class PogoPanel {
    public interface Host {
        void go(double lat, double lng, String title);
        void close();
        /** A folha de filtros por cima de tudo (v null = some); onBack = o BACK enquanto ela está aberta. */
        default void cover(View v, Runnable onBack) {}
    }

    public interface History {
        void more();
        void reload();
        /** Tocou na lixeira (ou na ação de acessibilidade): o host confirma e chama removeFixed. */
        void delete(Pogo.Mon m);
    }

    private static final String TAG = "PogoPanel";
    static final int WORLD = 0;
    private static final double M_PER_DEG = 111_320;
    /** Rede do painel fora do Io (single-thread do app); morre ociosa. */
    static final ThreadPoolExecutor NET = new ThreadPoolExecutor(1, 1, 6, TimeUnit.SECONDS,
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
    private final Host host;
    private final List<Pogo.Mon> mons = new ArrayList<>();
    private final RecyclerView list;
    private final View pull;
    private final ViewGroup detail;
    private final View frame;
    private final TextView status;
    private final Button close;
    private final LinearLayout chips;          // faixa de resumo: uma linha, rola na horizontal
    private final LinearLayout filtersBtn;
    private final TextView filtersText, badge;
    private final RecyclerView.Adapter<RecyclerView.ViewHolder> adapter;
    private final Consumer<Bitmap> onIcon;
    // Memo por Pokémon da busca atual (o tick de 1 s não refaz título, números nem distância); limpo a cada busca.
    private final IdentityHashMap<Pogo.Mon, Row> rows = new IdentityHashMap<>();
    private final StringBuilder line = new StringBuilder(96);
    private String[] despawn;

    private static final class Row {
        final String title, stats, dist, iv;
        Bitmap photo;

        Row(String title, String stats, String dist, String iv) {
            this.title = title;
            this.stats = stats;
            this.dist = dist;
            this.iv = iv;
        }
    }

    /** O bloco de um Pokémon: disco com a foto, nome / PC · Nv · tempo / local, pílula de IV. */
    private static final class Holder {
        final ImageView photo;
        final TextView name, stats, time, place, iv;
        final GradientDrawable ivBg;
        Pogo.Mon mon;
        boolean warn;

        Holder(ImageView photo, TextView name, TextView stats, TextView time, TextView place, TextView iv, GradientDrawable ivBg) {
            this.photo = photo;
            this.name = name;
            this.stats = stats;
            this.time = time;
            this.place = place;
            this.iv = iv;
            this.ivBg = ivBg;
        }
    }
    private final Runnable onPlace;
    private PogoCard card;
    private PogoFilterSheet sheet;
    private PogoFilter base, filter;   // base: o último carregado; filter: o enviado (asSent), que os chips mostram
    private Io.Task task;
    private final ImageView refresh;
    private final ObjectAnimator spin;
    private boolean attached, paused;
    private long lastAt;
    static final long FRESH_MS = 5 * 60_000, MAX_AGE_MS = 30 * 60_000;
    private static String sKey;
    private static List<Pogo.Mon> sMons;
    private static long sAt;
    private static boolean sTrimHook;
    /** A cada 5 min com a lista/painel visível; com a tela desligada, só reagenda. */
    private final Runnable auto = new Runnable() {
        @Override public void run() {
            if (destroyed || !attached || paused) return;
            if (!((PowerManager) app.getSystemService(Context.POWER_SERVICE)).isInteractive()) Io.main().postDelayed(this, FRESH_MS);
            else load(false);
        }
    };
    private String stale;   // aviso "Mostrando a última busca · …" enquanto a nova não dá certo
    private int km;
    static final long RETRY_MS = 30_000;
    /** PoGo lento: tenta de novo sozinho (o mapa já tem o tick de 60 s); com o card aberto, espera ele fechar. */
    private final Runnable retry = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            load(false);
        }
    };
    private boolean destroyed, loaded;
    /** Lista dada (as notificações): sem busca, e o que despawnou fica, marcado como expirado. */
    private boolean fixed;
    private History history;
    private String empty, note;
    private boolean refreshing;
    /** O bloco arrastado que ficou aberto com a lixeira à mostra; blockClick: o toque que o fechou não abre o card. */
    private View open;
    private boolean blockClick;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            long now = System.currentTimeMillis();
            // despawnados saem a cada segundo, também com uma busca em curso ou depois de uma que falhou
            if (!fixed && mons.removeIf(m -> gone(m, now))) {
                if (task == null) showStatus(stale != null && !mons.isEmpty() ? stale : PogoText.count(app, mons.size(), filter, km == WORLD));
                adapter.notifyDataSetChanged();
            } else {
                redraw();
            }
            Io.main().postDelayed(this, 1000);
        }
    };

    /** lat/lng NaN = sem centro: busca o mundo inteiro. */
    public PogoPanel(Context c, double lat, double lng, int goLabel, Host host) {
        ctx = c;
        app = c.getApplicationContext();
        this.lat = lat;
        this.lng = lng;
        this.goLabel = goLabel;
        this.host = host;
        view = LayoutInflater.from(c).inflate(R.layout.pogo_panel, null);
        list = view.findViewById(R.id.pogo_list);
        detail = view.findViewById(R.id.pogo_detail);
        frame = view.findViewById(R.id.pogo_frame);
        status = view.findViewById(R.id.pogo_status);
        close = view.findViewById(R.id.pogo_close);
        frame.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        pull = view.findViewById(R.id.pogo_pull);
        list.setLayoutManager(new LinearLayoutManager(c));
        ((SimpleItemAnimator) list.getItemAnimator()).setSupportsChangeAnimations(false);
        adapter = new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @Override public int getItemCount() { return mons.size(); }

            @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
                View row = newRow();
                RecyclerView.ViewHolder vh = new RecyclerView.ViewHolder(row) {};
                row.setOnClickListener(x -> {
                    int at = vh.getBindingAdapterPosition();
                    if (at != RecyclerView.NO_POSITION) click(mons.get(at));
                });
                if (history != null) ViewCompat.addAccessibilityAction(row, ctx.getString(R.string.delete), (x, a) -> {
                    int at = vh.getBindingAdapterPosition();
                    if (at != RecyclerView.NO_POSITION) history.delete(mons.get(at));
                    return true;
                });
                return vh;
            }

            @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder vh, int i) { bind(vh.itemView, mons.get(i)); }

            @Override public void onViewRecycled(@NonNull RecyclerView.ViewHolder vh) {
                vh.itemView.animate().cancel();
                vh.itemView.setTranslationX(0);
                if (open == vh.itemView) open = null;
            }
        };
        list.setAdapter(adapter);
        onIcon = b -> { if (b != null && !destroyed) redraw(); };
        onPlace = () -> { if (!destroyed) redraw(); };

        HorizontalScrollView strip = new HorizontalScrollView(c);
        strip.setHorizontalScrollBarEnabled(false);
        strip.setFillViewport(false);
        chips = new LinearLayout(c);
        chips.setGravity(android.view.Gravity.CENTER_VERTICAL);
        strip.addView(chips);
        LinearLayout bar = new LinearLayout(c);
        bar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        bar.addView(strip, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        refresh = new ImageView(c);
        refresh.setImageDrawable(PogoChips.refresh(c, c.getColor(R.color.primary)));
        refresh.setScaleType(ImageView.ScaleType.CENTER);
        refresh.setBackground(PogoChips.round(c, 20, 0, c.getColor(R.color.outline)));
        refresh.setContentDescription(c.getString(R.string.pogo_refresh));
        refresh.setOnClickListener(x -> load(false));
        int r40 = Ui.dp(c, 40);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(r40, r40);
        rlp.setMarginStart(Ui.dp(c, 8));
        bar.addView(refresh, rlp);
        spin = ObjectAnimator.ofFloat(refresh, View.ROTATION, 0f, 360f);   // 1 animador por painel: nada por frame
        spin.setDuration(900);
        spin.setRepeatCount(ValueAnimator.INFINITE);
        spin.setInterpolator(new LinearInterpolator());
        ((ViewGroup) view.findViewById(R.id.pogo_chips)).addView(bar);
        // Botão "Filtros": pílula de 40dp pela largura do conteúdo (ícone + texto + badge), nunca círculo
        filtersBtn = new LinearLayout(c);
        filtersBtn.setGravity(android.view.Gravity.CENTER_VERTICAL);
        filtersText = new TextView(c);
        filtersText.setText(R.string.pogo_panel_filters);
        filtersText.setTextSize(15);
        filtersText.setTypeface(PogoChips.MEDIUM);
        filtersText.setSingleLine(true);
        filtersText.setCompoundDrawablePadding(Ui.dp(c, 8));
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
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(c, 40));
        flp.setMarginEnd(Ui.dp(c, 8));
        chips.addView(filtersBtn, flp);
        int gap = Ui.dp(c, 8);
        list.addItemDecoration(new RecyclerView.ItemDecoration() {
            @Override public void getItemOffsets(@NonNull Rect out, @NonNull View v, @NonNull RecyclerView p, @NonNull RecyclerView.State st) {
                out.top = p.getChildAdapterPosition(v) > 0 ? gap : 0;
            }
        });
        int m16 = Ui.dp(c, 16);
        list.setPadding(m16, Ui.dp(c, 8), m16, Ui.dp(c, 8));
        list.setClipToPadding(false);
        renderChips();
        close.setOnClickListener(x -> {
            if (detail.getVisibility() == View.VISIBLE) closeDetail();
            else host.close();
        });
        view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View x) {
                attached = true;
                running();
            }

            @Override public void onViewDetachedFromWindow(View x) {
                attached = false;
                running();
            }
        });
    }

    /** O último resultado do processo aparece na hora se for da mesma busca e tiver < 30 min; com > 5 min, busca também. */
    public void start() {
        PogoFilter raw = PogoFilter.load(app), f = PogoText.asSent(app, raw.copy());
        int k = Double.isNaN(lat) ? WORLD : f.km;
        long now = System.currentTimeMillis(), age = now - sAt;
        if (sMons == null || age >= MAX_AGE_MS || !key(f, k).equals(sKey)) {
            load(false);
            return;
        }
        loaded = true;
        base = raw;
        filter = f;
        km = k;
        lastAt = sAt;
        renderChips();
        for (Pogo.Mon m : sMons) if (!PogoText.gone(m.despawnMs, now)) mons.add(m);
        adapter.notifyDataSetChanged();
        showStatus(PogoText.count(app, mons.size(), filter, km == WORLD));
        if (age >= FRESH_MS) load(false);
        else running();
    }

    public void showFixed(List<Pogo.Mon> l, String empty) {
        closeOpen();
        fixed = loaded = true;
        this.empty = empty;
        view.findViewById(R.id.pogo_chips).setVisibility(View.GONE);
        mons.clear();
        rows.clear();
        mons.addAll(l);
        adapter.notifyDataSetChanged();
        fixedStatus();
        running();
    }

    /** at -1 = no fim. Entrando no topo com a lista no topo, o novo fica à vista. */
    public void addFixed(int at, List<Pogo.Mon> l) {
        if (at < 0 || at > mons.size()) at = mons.size();
        boolean top = at == 0 && !list.canScrollVertically(-1);
        mons.addAll(at, l);
        adapter.notifyItemRangeInserted(at, l.size());
        if (top) list.scrollToPosition(0);
        fixedStatus();
    }

    public void note(String s) {
        note = s;
        fixedStatus();
    }

    public void refreshing(boolean on) {
        refreshing = on;
        pull.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    private void fixedStatus() {
        String s = note != null ? note : mons.isEmpty() ? empty : "";
        showStatus(s);
        status.setVisibility(TextUtils.isEmpty(s) ? View.GONE : View.VISIBLE);
    }

    /** Só para a lista fixa; chamar antes do showFixed (a ação de acessibilidade "Excluir" entra ao criar cada bloco). */
    public void history(History h) {
        history = h;
        int bw = Ui.dp(ctx, 64), w = bw + Ui.dp(ctx, 8), reach = Ui.dp(ctx, 72), slop = ViewConfiguration.get(ctx).getScaledTouchSlop();
        int side = ctx.getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? 1 : -1;
        Drawable bin = ctx.getDrawable(R.drawable.ic_delete).mutate();
        bin.setTint(ctx.getColor(R.color.surface));
        GradientDrawable block = PogoChips.round(ctx, 16, ctx.getColor(R.color.danger), 0);
        list.addItemDecoration(new RecyclerView.ItemDecoration() {
            /** Só o bloco da lixeira, no espaço que o item deslocado deixa. */
            @Override public void onDraw(@NonNull Canvas cv, @NonNull RecyclerView rv, @NonNull RecyclerView.State st) {
                for (int i = 0; i < rv.getChildCount(); i++) {
                    View v = rv.getChildAt(i);
                    float t = v.getTranslationX();
                    if (t == 0) continue;
                    int l = t < 0 ? v.getRight() - bw : v.getLeft(), top = v.getTop() + (int) v.getTranslationY(),
                            b = v.getBottom() + (int) v.getTranslationY(), size = bin.getIntrinsicWidth();
                    cv.save();
                    cv.clipRect(t < 0 ? v.getRight() + t : v.getLeft(), top, t < 0 ? v.getRight() : v.getLeft() + t, b);
                    block.setBounds(l, top, l + bw, b);
                    block.draw(cv);
                    int x = l + (bw - size) / 2, y = (top + b - size) / 2;
                    bin.setBounds(x, y, x + size, y + size);
                    bin.draw(cv);
                    cv.restore();
                }
            }
        });
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrollStateChanged(@NonNull RecyclerView rv, int state) {
                if (state == RecyclerView.SCROLL_STATE_DRAGGING) closeOpen();
            }

            @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (dy > 0 && ((LinearLayoutManager) rv.getLayoutManager()).findLastVisibleItemPosition() >= mons.size() - 5) h.more();
            }
        });
        list.addOnItemTouchListener(new RecyclerView.OnItemTouchListener() {
            float x0, y0, base, pullY = -1;
            View row;
            boolean dragging, tapBin;

            @Override public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        x0 = e.getX();
                        y0 = e.getY();
                        dragging = tapBin = false;
                        row = rv.findChildViewUnder(x0, y0);
                        blockClick = open != null;
                        if (onOpenBin(x0, y0)) {
                            tapBin = true;
                            return true;
                        }
                        if (open != null && row != open) closeOpen();
                        base = row != null ? row.getTranslationX() : 0;
                        pullY = refreshing || rv.canScrollVertically(-1) ? -1 : y0;
                        break;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getX() - x0, dy = e.getY() - y0;
                        if (row != null && Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy)) {
                            dragging = true;
                            if (pullY >= 0) pull.setVisibility(View.GONE);
                            pullY = -1;
                            rv.getParent().requestDisallowInterceptTouchEvent(true);
                            return true;
                        }
                        if (pullY >= 0) pull.setVisibility(dy > reach ? View.VISIBLE : View.GONE);
                        break;
                    case MotionEvent.ACTION_UP:
                        if (pullY >= 0 && e.getY() - y0 > reach) h.reload();
                        else if (pullY >= 0) pull.setVisibility(View.GONE);
                        if (open != null && row == open) closeOpen();
                        pullY = -1;
                        break;
                    case MotionEvent.ACTION_CANCEL:
                        if (pullY >= 0) pull.setVisibility(View.GONE);
                        pullY = -1;
                        break;
                }
                return false;
            }

            @Override public void onTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
                int a = e.getActionMasked();
                if (tapBin) {
                    if (a == MotionEvent.ACTION_UP && onOpenBin(e.getX(), e.getY())) {
                        int at = rv.getChildAdapterPosition(open);
                        if (at != RecyclerView.NO_POSITION) h.delete(mons.get(at));
                    }
                    return;
                }
                if (!dragging || row == null) return;
                if (a == MotionEvent.ACTION_MOVE) {
                    row.setTranslationX(slide(base, e.getX() - x0, w, side));
                    rv.invalidate();
                } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                    float t = settle(row.getTranslationX(), w);
                    if (t != 0) open = row;
                    else if (open == row) open = null;
                    slideTo(row, t);
                }
            }

            @Override public void onRequestDisallowInterceptTouchEvent(boolean disallow) {}

            /** Pelo item aberto, não pelo findChildViewUnder: ele soma o translationX e o bloco da lixeira fica fora. */
            boolean onOpenBin(float x, float y) {
                if (open == null) return false;
                float ty = open.getTranslationY();
                return onBin(x, y, open.getLeft(), open.getTop() + ty, open.getRight(), open.getBottom() + ty, open.getTranslationX(), bw);
            }
        });
    }

    /**
     * O host confirmou: sai da lista (pelo historyId, se um reload trocou os objetos com o modal aberto); devolve onde
     * estava, para voltar com addFixed se o servidor recusar, ou -1 se já saiu.
     */
    public int removeFixed(Pogo.Mon m) {
        int at = mons.indexOf(m);
        for (int i = 0; at < 0 && m.historyId != null && i < mons.size(); i++) if (m.historyId.equals(mons.get(i).historyId)) at = i;
        if (at < 0) return -1;
        rows.remove(mons.remove(at));
        open = null;
        adapter.notifyItemRemoved(at);
        fixedStatus();
        return at;
    }

    private void closeOpen() {
        if (open != null) slideTo(open, 0);
        open = null;
    }

    private void slideTo(View v, float t) {
        v.animate().translationX(t).setDuration(150).setUpdateListener(a -> list.invalidate()).start();
    }

    /** Deslocamento no arrasto: só para o lado da lixeira (side -1 = esquerda), no máximo w. */
    static float slide(float base, float dx, float w, int side) {
        return side * Math.max(0, Math.min(w, (base + dx) * side));
    }

    /** Solto além da metade trava aberto; antes, volta. */
    static float settle(float t, float w) {
        return Math.abs(t) > w / 2 ? Math.signum(t) * w : 0;
    }

    /** (x, y) no bloco da lixeira (largura bw) do lado que o item deslocado (t) deixou à mostra. */
    static boolean onBin(float x, float y, int left, float top, int right, float bottom, float t, int bw) {
        if (y < top || y > bottom) return false;
        return t < 0 ? x >= right - bw && x <= right : t > 0 && x >= left && x <= left + bw;
    }

    /** Com payload o RecyclerView rebinda os mesmos blocos (sem trocar o ViewHolder): não desfaz um arrasto em curso. */
    private void redraw() {
        adapter.notifyItemRangeChanged(0, mons.size(), Boolean.TRUE);
    }

    /** true = o BACK fechou o card. */
    public boolean back() {
        if (detail.getVisibility() != View.VISIBLE) return false;
        closeDetail();
        return true;
    }

    /** Visível entre onResume e onPause. */
    public void setPaused(boolean on) {
        paused = on;
        running();
    }

    /** Logout, pouca memória: o próximo abrir busca de novo. */
    public static void clearCache() {
        sKey = null;
        sMons = null;
    }

    private String key(PogoFilter sent, int k) {
        return sent.stored() + (k == WORLD ? "|w" : "|" + Math.round(lat * 1000) + "," + Math.round(lng * 1000))
                + "|" + Session.loggedIn(app) + Pogo.loggedIn(app);   // a rota e o shiny dependem dos dois
    }

    /** O tick de 1 s e a auto-atualização de 5 min só rodam com a lista/painel visível e a tela ligada. */
    private void running() {
        Io.main().removeCallbacks(tick);
        Io.main().removeCallbacks(auto);
        spin();
        if (destroyed || !attached || paused) return;
        Io.main().postDelayed(tick, 1000);
        if (task == null && loaded && !fixed) Io.main().postDelayed(auto, Math.max(0, lastAt + FRESH_MS - System.currentTimeMillis()));
    }

    private void busy(boolean on) {
        refresh.setEnabled(!on);
        if (on) {
            spin();
        } else {
            spin.cancel();
            refresh.setRotation(0);
        }
    }

    /** O loading gira só com a busca em curso E o painel à vista: escondido, pausa (o Choreographer não gera frames). */
    private void spin() {
        if (task == null) return;
        if (!destroyed && attached && !paused) {
            if (spin.isPaused()) spin.resume();
            else if (!spin.isStarted()) spin.start();
        } else if (spin.isRunning() && !spin.isPaused()) {
            spin.pause();
        }
    }

    public void destroy() {
        destroyed = true;
        cancel(task);
        task = null;
        Io.main().removeCallbacks(tick);
        Io.main().removeCallbacks(retry);
        Io.main().removeCallbacks(auto);
        spin.cancel();
        if (card != null) card.destroy();
        if (sheet != null) sheet.destroy();
        sheet = null;
        mons.clear();
        rows.clear();
        list.setAdapter(null);   // solta os blocos guardados (com as fotos)
    }

    /** Na volta da conta PoGo (vincular treinador) o sheet aberto remonta o shiny. */
    public void onResume() {
        if (sheet != null) sheet.render();
    }

    /** user: troca de filtro/escopo pelo usuário (fecha o card); atualizar/automático não mexem no card. */
    private void load(boolean user) {
        loaded = true;
        if (user) closeDetail();
        cancel(task);
        Io.Task t = task = new Io.Task();
        Io.main().removeCallbacks(retry);   // a lista da busca anterior fica até esta dar certo
        Io.main().removeCallbacks(auto);
        busy(true);
        if (mons.isEmpty()) status.setText(R.string.pogo_map_loading);
        NET.execute(() -> {
            if (t.isCancelled()) return;
            List<Pogo.Mon> got = null;
            Exception err = null;
            PogoFilter raw = PogoFilter.load(app), f = PogoText.asSent(app, raw.copy());
            int k = Double.isNaN(lat) ? WORLD : f.km;
            try {
                got = fetch(app, f, k, lat, lng);
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
                renderChips();
                busy(false);
                if (e != null) {
                    long now = System.currentTimeMillis();
                    mons.removeIf(m -> gone(m, now));
                    adapter.notifyDataSetChanged();
                    stale = mons.isEmpty() ? null : PogoText.stale(app, e);
                    showStatus(stale != null ? stale : PogoText.error(app, e));
                    lastAt = System.currentTimeMillis();   // a próxima automática conta desta tentativa, não fica em laço
                    if (Pogo.slow(e)) Io.main().postDelayed(retry, RETRY_MS);
                    else running();
                    return;
                }
                stale = null;
                km = k;
                lastAt = System.currentTimeMillis();
                cache(key(f, k), l, k);
                mons.clear();
                rows.clear();
                mons.addAll(l);
                adapter.notifyDataSetChanged();
                showStatus(PogoText.count(app, mons.size(), filter, km == WORLD));
                running();
            });
        });
    }

    /** Um resultado por processo, só os Mon (sem bitmaps); o trim de memória o descarta. */
    private void cache(String key, List<Pogo.Mon> l, int k) {
        sKey = key;
        sMons = new ArrayList<>(l);
        sAt = lastAt;
        if (!sTrimHook) {
            sTrimHook = true;
            app.registerComponentCallbacks(new ComponentCallbacks2() {
                // UI_HIDDEN (20) fica de fora: o main o recebe toda vez que o usuário volta ao jogo
                @Override public void onTrimMemory(int level) {
                    if (level != TRIM_MEMORY_UI_HIDDEN && level >= TRIM_MEMORY_RUNNING_LOW) clearCache();
                }

                @Override public void onConfigurationChanged(android.content.res.Configuration c) {}

                @Override public void onLowMemory() { clearCache(); }
            });
        }
    }

    /** Despawnou: sai da lista e do memo junto. */
    private boolean gone(Pogo.Mon m, long now) {
        if (!PogoText.gone(m.despawnMs, now)) return false;
        rows.remove(m);
        return true;
    }

    /** O sheet edita uma cópia: só "Mostrar resultados" salva (no :overlay, no arquivo dele) e busca de novo. */
    private void openSheet() {
        if (sheet != null || destroyed) return;
        closeDetail();
        Runnable link = App.process() == App.PROC_MAIN ? () -> ctx.startActivity(new Intent(ctx, PogoActivity.class)) : null;
        sheet = new PogoFilterSheet(ctx, base != null ? base : PogoFilter.load(app), f -> {
            f.save(app);
            sheet.close();
            load(true);
        }, () -> {
            host.cover(null, null);
            sheet = null;
        }, null, link);
        host.cover(sheet.view(), () -> { if (sheet != null) sheet.back(); });
    }

    /** Botão "Filtros" com o nº de ativos e um chip por filtro ativo (toque tira só aquele e busca de novo). */
    private void renderChips() {
        while (chips.getChildCount() > 1) chips.removeViewAt(1);
        PogoFilter f = filter;
        int n = f == null ? 0 : f.activeCount();
        badge.setText(String.valueOf(n));
        badge.setVisibility(n > 0 ? View.VISIBLE : View.GONE);
        // com filtro ativo, pílula primary; sem filtro, só a borda (e sem badge). O padding vem DEPOIS do fundo: o
        // setBackground troca o padding do view pelo do drawable.
        int fg = ctx.getColor(n > 0 ? R.color.on_primary : R.color.primary);
        filtersBtn.setBackground(n > 0 ? PogoChips.round(ctx, 20, ctx.getColor(R.color.primary), 0)
                : PogoChips.round(ctx, 20, 0, ctx.getColor(R.color.outline)));
        filtersBtn.setPadding(Ui.dp(ctx, 16), 0, Ui.dp(ctx, n > 0 ? 10 : 16), 0);
        filtersText.setTextColor(fg);
        filtersText.setCompoundDrawablesRelative(PogoChips.lines(ctx, fg), null, null, null);
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

    /** Tocar no texto abre o sheet; o "×" (alvo próprio) tira só este filtro e busca de novo. remove null = o escopo. */
    private void chip(String label, Consumer<PogoFilter> remove) {
        View t = PogoChips.summaryChip(ctx, label, x -> openSheet(), remove == null ? null : x -> {
            if (base == null) return;
            PogoFilter f = base.copy();
            remove.accept(f);
            f.save(app);
            load(true);
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

    /** O card de um Pokémon de fora da lista (push de alerta); fechar volta à lista. */
    public void showMon(Pogo.Mon m) {
        openDetail(m);
    }

    private void openDetail(Pogo.Mon m) {
        if (card == null) {
            card = new PogoCard(ctx, goLabel, host::go, this::closeDetail);
            detail.addView(card.view);
        }
        card.bind(m, distance(m));
        list.setVisibility(View.GONE);
        detail.setVisibility(View.VISIBLE);
        close.setText(R.string.cancel);
    }

    private void closeDetail() {
        detail.setVisibility(View.GONE);
        list.setVisibility(View.VISIBLE);
        close.setText(R.string.close);
        if (!loaded && !destroyed) start();
    }

    /** A foto do bloco, guardada no memo; sem ela (ainda não veio ou o LRU expulsou), pede e redesenha ao chegar. */
    private Bitmap photo(Pogo.Mon m, Row r) {
        if (r.photo != null) return r.photo;
        Bitmap b = PogoIcons.cached(m);
        if (b == null) PogoIcons.load(app, m, onIcon);
        return r.photo = b;
    }

    /** O tick de 1 s rebinda os blocos visíveis: só o tempo e o local são refeitos; o resto, quando muda o Pokémon. */
    private void bind(View row, Pogo.Mon m) {
        Holder h = (Holder) row.getTag();
        Row r = rows.get(m);
        if (r == null) {
            r = new Row(PogoText.title(m), PogoText.cpLevel(ctx, m), distance(m),
                    m.iv >= 0 ? (int) m.iv + "%" : null);
            rows.put(m, r);
        }
        if (h.mon != m) {
            h.mon = m;
            h.name.setText(r.title);
            h.stats.setText(r.stats);
            h.photo.setImageBitmap(photo(m, r));
            h.iv.setVisibility(r.iv == null ? View.GONE : View.VISIBLE);
            if (r.iv != null) {
                h.iv.setText(r.iv);
                int strong = PogoText.ivPill(m);
                h.ivBg.setColor(strong != 0 ? strong : ctx.getColor(R.color.pogo_surface_variant));
                h.iv.setTextColor(strong == PogoText.PILL_RED ? 0xFFFFFFFF : strong == PogoText.PILL_ORANGE ? 0xFF1A1C1E
                        : ctx.getColor(R.color.on_surface));
            }
        } else if (r.photo == null && photo(m, r) != null) {
            h.photo.setImageBitmap(r.photo);
        }
        StringBuilder s = line;
        s.setLength(0);
        long left = m.despawnMs - System.currentTimeMillis();
        if (m.despawnMs > 0 && left > 0) PogoText.clock(s.append(r.stats.isEmpty() ? "" : " · "), left);
        else if (fixed && m.despawnMs > 0) s.append(r.stats.isEmpty() ? "" : " · ").append(ctx.getString(R.string.pogo_notified_expired));
        h.time.setText(s);
        row.setAlpha(fixed && m.despawnMs > 0 && left <= 0 ? 0.5f : 1f);
        boolean warn = left > 0 && left < 3 * 60_000;
        if (warn != h.warn) {
            h.warn = warn;
            h.time.setTextColor(ctx.getColor(warn ? R.color.pogo_warn : R.color.on_surface_variant));
        }
        s.setLength(0);
        rowPlace(s, m, r);
        h.place.setText(s);
        h.place.setVisibility(s.length() == 0 ? View.GONE : View.VISIBLE);
    }

    private void click(Pogo.Mon m) {
        if (blockClick) return;
        if (fixed && PogoText.gone(m.despawnMs, System.currentTimeMillis())) Ui.toast(ctx, R.string.pogo_notified_gone);
        else openDetail(m);
    }

    /** O bloco é montado em código (sem layout): fundo tile raio 16dp com ripple, 72dp de altura mínima, padding 12dp. */
    private View newRow() {
        Context c = ctx;
        int p = Ui.dp(c, 12);
        LinearLayout row = new LinearLayout(c);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Ui.dp(c, 72));
        int on = c.getColor(R.color.on_surface);
        row.setBackground(new RippleDrawable(ColorStateList.valueOf((on & 0x00FFFFFF) | 0x1F000000),
                PogoChips.round(c, 16, c.getColor(R.color.pogo_tile), 0), null));
        row.setPadding(p, p, p, p);
        row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout disc = new FrameLayout(c);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(c.getColor(R.color.surface));
        disc.setBackground(d);
        ImageView photo = new ImageView(c);
        int ph = Ui.dp(c, 48);
        disc.addView(photo, new FrameLayout.LayoutParams(ph, ph, android.view.Gravity.CENTER));
        int dd = Ui.dp(c, 56);
        row.addView(disc, new LinearLayout.LayoutParams(dd, dd));
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView name = rowText(16, on);
        name.setTypeface(PogoChips.MEDIUM);
        col.addView(name);
        LinearLayout l2 = new LinearLayout(c);
        TextView stats = rowText(14, c.getColor(R.color.on_surface_variant)), time = rowText(14, c.getColor(R.color.on_surface_variant));
        l2.addView(stats);
        l2.addView(time);
        col.addView(l2);
        TextView place = rowText(14, c.getColor(R.color.on_surface_variant));
        col.addView(place);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        clp.setMarginStart(p);
        clp.setMarginEnd(Ui.dp(c, 8));
        row.addView(col, clp);
        TextView iv = rowText(14, on);
        iv.setTypeface(PogoChips.MEDIUM);
        iv.setGravity(android.view.Gravity.CENTER);
        int ip = Ui.dp(c, 10);
        GradientDrawable ivBg = PogoChips.round(c, 14, c.getColor(R.color.pogo_surface_variant), 0);
        iv.setBackground(ivBg);
        iv.setPadding(ip, 0, ip, 0);
        row.addView(iv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(c, 28)));
        row.setTag(new Holder(photo, name, stats, time, place, iv, ivBg));
        return row;
    }

    private TextView rowText(int sp, int color) {
        TextView t = new TextView(ctx);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setFontFeatureSettings("tnum");   // dígitos de largura fixa: o tempo não "pula" a cada segundo
        return t;
    }

    /** null no mundo inteiro e no push sem coordenada. */
    private String distance(Pogo.Mon m) {
        if ((km == WORLD && !fixed) || Double.isNaN(lat) || Double.isNaN(m.lat)) return null;
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
        status.setText(s);
    }

    private static void cancel(Io.Task t) { if (t != null) t.cancel(); }

    /** k km em volta do centro (lookup na caixa, recortado no raio, do mais perto ao mais longe); k WORLD: /search. */
    static List<Pogo.Mon> fetch(Context app, PogoFilter f, int k, double lat, double lng) throws java.io.IOException {
        if (k == WORLD) return Pogo.search(app, f);
        double[] b = around(lat, lng, k * 1000.0);
        return near(Pogo.lookup(app, b[0], b[1], b[2], b[3], f), lat, lng, k * 1000.0);
    }

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
