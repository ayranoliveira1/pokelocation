package com.pokelocation.app.pogo;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.hardware.display.DisplayManager;
import android.os.PowerManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.Display;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.pokelocation.app.Io;
import com.pokelocation.app.R;
import com.pokelocation.app.geo.Cooldown;
import com.pokelocation.app.geo.CoordParser;
import com.pokelocation.app.geo.Geo;
import com.pokelocation.app.ui.panel.PanelHost;
import com.pokelocation.app.util.Ui;
import com.pokelocation.app.util.WindowDrag;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Coluna de bolhas sobre o jogo (:overlay): a logo no topo e um ícone por Pokémon (ou Rare Candy) perto da posição
 * atual. A logo arrasta a coluna e abre, para o lado de dentro da tela, atualizar, filtros, a quantidade e o modo; a
 * bolha abre o PogoCard ao lado.
 * Janelas próprias, fora do PanelHost: abrir o Teleport ou o chat (closeAll) não a fecha.
 */
public final class PogoBubbles {
    public interface Host {
        double lat();
        double lng();
        void teleport(String text, String title);
        /** {x, y, largura, altura} da janela do joystick à vista, ou null: a coluna nasce sem cobri-lo. */
        int[] stick();
        void go(double lat, double lng, boolean walk);
    }

    private static final String TAG = "PogoBubbles", POS = "overlay_panels", COUNT = "pogo_col_n", MODE = "pogo_col_mode",
            CANDY_N = "pogo_candy_n", CANDY_KM = "pogo_candy_km", CANDY_CAP = "pogo_candy_cap";
    /** O servidor aceita 10 buscas/min por conta e o 429 vem sem retry_after: a busca manual espera este tanto. */
    private static final long GAP_MS = 6_000;
    private static final int FEW = 5, MANY = 10, CAP = 10;
    static final int[] CANDY_COUNTS = {5, 10, 25, 50, 75, 100};
    /** Opacidade do card sobre o jogo, para não atrapalhar. */
    private static final float ALPHA = 0.8f;

    private final Context ctx, app;
    private final Host host;
    private final boolean classic, secure;
    private final WindowManager wm;
    private final Display display;
    private final Point scr = new Point();
    private final SharedPreferences pos;
    private final String key;
    private final int size, gap, margin;
    private final LinearLayout root, side, bubbles;
    private final ScrollView scroll;
    private final ImageView mode;
    private final TextView count;
    private final ObjectAnimator spin;
    private final WindowManager.LayoutParams lp, sideLp;
    private final ImageView logo;
    private final Drawable icon;
    private final List<Pogo.Mon> mons = new ArrayList<>();
    private int n, candyN, candyCap, downX, downY;   // a coluna no DOWN da logo: o arraste conta a partir daqui
    private boolean open = true, visible, shown, destroyed, candyMode;
    private final Consumer<Bitmap> onIcon = b -> { if (b != null && !destroyed && !candyMode) icons(); };
    private final Runnable auto = this::auto, retry = this::retry, prune = this::render, user = () -> load(true);
    private final View.OnLayoutChangeListener cardFit = (v, l, t, r, b, ol, ot, or, ob) -> { if (b - t != ob - ot) fitCard(v); };
    private long lastAt, candyAt;
    private Io.Task task;
    private PogoCard card;
    private WindowManager.LayoutParams cardLp;
    private Pogo.Mon cardMon;
    private PogoFilterSheet sheet;
    private PanelHost sheetHost, candySheet;
    private Drawable spinner, ballIcon, candyIcon, grayIcon;
    private List<Pogo.Candy> candies;
    private Map<String, Long> visited;
    private PogoCandyCard candyCard;
    private Pogo.Candy cardCandy;

    /** secure: o mesmo FLAG_SECURE das janelas do joystick. */
    public PogoBubbles(Context c, boolean classic, boolean secure, Host host) {
        ctx = c;
        app = c.getApplicationContext();
        this.host = host;
        this.classic = classic;
        this.secure = secure;
        wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        display = ((DisplayManager) app.getSystemService(Context.DISPLAY_SERVICE)).getDisplay(Display.DEFAULT_DISPLAY);
        pos = c.getSharedPreferences(POS, Context.MODE_PRIVATE);   // só o :overlay grava
        key = classic ? "pogo_col_classic" : "pogo_col_drawer_top";   // o padrão da gaveta saiu de baixo: a salva antiga não vale
        n = pos.getInt(COUNT, FEW) == MANY ? MANY : FEW;
        candyMode = pos.getInt(MODE, 0) == 1;
        candyN = pos.getInt(CANDY_N, FEW);
        candyCap = pos.getInt(CANDY_CAP, CAP);
        size = c.getResources().getDimensionPixelSize(R.dimen.ov_btn);   // o botão da gaveta
        gap = Ui.dp(c, 6);
        margin = Ui.dp(c, 8);
        int primary = c.getColor(R.color.primary);

        logo = new ImageView(c);
        icon = c.getDrawable(R.mipmap.ic_launcher_round);
        logo.setImageDrawable(icon);
        logo.setContentDescription(c.getString(R.string.pogo_bubbles_logo));
        side = new LinearLayout(c);
        button(PogoChips.refresh(c, primary), R.string.pogo_refresh, v -> refreshNow());
        button(PogoChips.lines(c, primary), R.string.pogo_panel_filters, v -> openSheet());
        count = new TextView(c);
        count.setGravity(Gravity.CENTER);
        count.setTextSize(15);
        count.setTypeface(PogoChips.MEDIUM);
        count.setTextColor(primary);
        count.setBackground(round());
        count.setOnClickListener(v -> cycle());
        side.addView(count, sideLp());
        mode = button(null, R.string.pogo_mode_to_candy, v -> switchMode());
        showMode();
        bubbles = new LinearLayout(c);
        bubbles.setOrientation(LinearLayout.VERTICAL);
        scroll = new ScrollView(c) {
            @Override protected void onMeasure(int w, int h) {
                readScreen();
                int max = Math.min(MeasureSpec.getSize(h), Math.max(0, scr.y - lp.y - size));
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST));
            }
        };
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setOnScrollChangeListener((v, x, y, ox, oy) -> closeCard());   // o card deixaria de apontar para a bolha
        scroll.addView(bubbles);
        root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(logo, new LinearLayout.LayoutParams(size, size));
        root.addView(scroll);
        View.OnLayoutChangeListener resized = (v, l, t, r, b, ol, ot, or, ob) -> { if (b - t != ob - ot || r - l != or - ol) fit(); };
        root.addOnLayoutChangeListener(resized);
        bubbles.addOnLayoutChangeListener(resized);

        spin = ObjectAnimator.ofFloat(logo, View.ROTATION, 0f, 360f);
        spin.setDuration(900);
        spin.setRepeatCount(ValueAnimator.INFINITE);
        spin.setInterpolator(new LinearInterpolator());

        lp = params(WindowManager.LayoutParams.WRAP_CONTENT);
        lp.x = pos.getInt(key + "_x", -1);
        lp.y = pos.getInt(key + "_y", -1);
        sideLp = params(WindowManager.LayoutParams.WRAP_CONTENT);
        logo.setOnClickListener(v -> toggleSide());
        WindowDrag drag = new WindowDrag(wm, root, lp, display, true, (x, y) -> moved());
        logo.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                downX = lp.x;
                downY = lp.y;
            }
            boolean r = drag.onTouch(v, e);
            if (e.getActionMasked() == MotionEvent.ACTION_MOVE && WindowDrag.dragged(lp.x - downX, lp.y - downY)) {
                closeSide();   // ficariam para trás
            }
            return r;
        });
    }

    /** O joystick apareceu ou sumiu (oculto, Stop): a coluna vai junto. */
    public void setVisible(boolean v) {
        visible = v;
        sync();
    }

    /** O botão PoGo do joystick. */
    public void toggle() {
        open = !open;
        sync();
    }

    public void destroy() {
        if (destroyed) return;
        destroyed = true;
        if (task != null) task.cancel();
        task = null;
        sync();
        loading(false);
        if (card != null) card.destroy();
        card = null;
        if (candyCard != null) candyCard.destroy();
        candyCard = null;
        if (sheetHost != null) sheetHost.dismiss();
        if (candySheet != null) candySheet.dismiss();
        mons.clear();
        bubbles.removeAllViews();
        PogoIcons.trim();
    }

    private void sync() {
        boolean want = open && visible && !destroyed;
        if (want == shown) return;
        if (!want) {
            closeSide();
            closeCard();
            loading(false);
            Io.main().removeCallbacks(auto);
            Io.main().removeCallbacks(retry);
            Io.main().removeCallbacks(prune);
            Io.main().removeCallbacks(user);
            remove(root);
            shown = false;
            return;
        }
        readScreen();
        int k = shown();
        int[] p = place(classic, lp.x, lp.y, size, size + k * (size + gap), size + Math.max(MANY, k) * (size + gap), scr.x,
                scr.y, margin, size, host.stick());
        lp.x = p[0];
        lp.y = p[1];
        try {
            wm.addView(root, lp);
        } catch (RuntimeException e) {
            return;   // sem permissão de overlay
        }
        shown = true;
        loading(task != null);
        render();
        schedule();
    }

    private WindowManager.LayoutParams params(int width) {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(width, WindowManager.LayoutParams.WRAP_CONTENT,
                Ui.overlayWindowType(), WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | (secure ? WindowManager.LayoutParams.FLAG_SECURE : 0), PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.LEFT;
        return p;
    }

    /**
     * Sem posição salva, ou com o x fora da tela (girou, outra tela): clássico no canto superior esquerdo, gaveta no
     * superior direito, descendo. Se a coluna cheia (full, no mínimo 10 bolhas) chegaria ao joystick, desce ao lado
     * dele, por dentro. A salva só sobe o que for preciso para caber (de 5 para 10 bolhas).
     */
    static int[] place(boolean classic, int x, int y, int w, int h, int full, int scrW, int scrH, int margin, int top,
                       int[] stick) {
        if (x >= 0 && y >= 0 && x <= scrW - w) return new int[]{x, WindowDrag.clamp(y, 0, Math.max(0, scrH - h))};
        x = classic ? margin : scrW - w - margin;
        if (stick != null && x < stick[0] + stick[2] && stick[0] < x + w && top < stick[1] + stick[3] && stick[1] < top + full) {
            x = stick[0] - w - margin;
            if (x < 0) x = Math.min(stick[0] + stick[2] + margin, scrW - w);
        }
        return new int[]{x, top};
    }

    static boolean onLeft(int x, int w, int scrW) {
        return x + w / 2 < scrW / 2;
    }

    /** Soltou a logo: só um arraste de verdade grava a posição (o toque curto deixa o padrão livre). */
    private void moved() {
        closeCard();
        if (!WindowDrag.dragged(lp.x - downX, lp.y - downY)) return;
        pos.edit().putInt(key + "_x", lp.x).putInt(key + "_y", lp.y).apply();
    }

    /**
     * A coluna mudou de tamanho (quantidade, busca, botões): sobe o que for preciso para caber inteira e só rola o que
     * não cabe nem no topo. Ficar no lugar rolando travaria o arraste para baixo (o WindowDrag prende pela altura da
     * janela, que já chegaria ao fundo).
     */
    private void fit() {
        if (!shown) return;
        readScreen();
        int x = WindowDrag.clamp(lp.x, 0, Math.max(0, scr.x - root.getWidth()));
        int y = WindowDrag.clamp(lp.y, 0, Math.max(0, scr.y - size - bubbles.getHeight()));
        if (x == lp.x && y == lp.y) return;
        lp.x = x;
        lp.y = y;
        update(root, lp);
        scroll.requestLayout();
    }

    @SuppressWarnings("deprecation")
    private void readScreen() {
        display.getRealSize(scr);
    }

    private void update(View v, WindowManager.LayoutParams p) {
        try {
            wm.updateViewLayout(v, p);
        } catch (RuntimeException ignored) {
        }
    }

    private void remove(View v) {
        if (v.getParent() == null) return;
        try {
            wm.removeViewImmediate(v);
        } catch (RuntimeException ignored) {
        }
    }

    /**
     * Os botões numa janela própria, ao lado da logo e para o lado de dentro da tela: na janela da coluna eles a
     * alargavam, e a área vazia embaixo deles engolia o toque do jogo (janela de overlay não deixa o toque atravessar).
     */
    private void toggleSide() {
        if (side.getParent() != null) {
            closeSide();
            return;
        }
        readScreen();
        side.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int w = side.getMeasuredWidth();
        int x = onLeft(lp.x, size, scr.x) ? lp.x + size : lp.x - w;
        sideLp.x = WindowDrag.clamp(x, 0, Math.max(0, scr.x - w));
        sideLp.y = lp.y + (size - side.getMeasuredHeight()) / 2;
        try {
            wm.addView(side, sideLp);
        } catch (RuntimeException ignored) {
        }
    }

    private void closeSide() {
        remove(side);
    }

    private ImageView button(Drawable icon, int desc, View.OnClickListener click) {
        ImageView b = new ImageView(ctx);
        b.setImageDrawable(icon);
        b.setScaleType(ImageView.ScaleType.CENTER);
        b.setBackground(round());
        b.setContentDescription(ctx.getString(desc));
        b.setOnClickListener(click);
        side.addView(b, sideLp());
        return b;
    }

    private LinearLayout.LayoutParams sideLp() {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(size, size);
        l.gravity = Gravity.CENTER_VERTICAL;
        l.setMarginStart(gap);
        l.setMarginEnd(gap);
        return l;
    }

    private GradientDrawable round() {
        GradientDrawable g = PogoChips.round(ctx, 20, ctx.getColor(R.color.surface), ctx.getColor(R.color.outline));
        g.setShape(GradientDrawable.OVAL);
        return g;
    }

    static int nextCount(int n) {
        return n == FEW ? MANY : FEW;
    }

    static int nextCandyCount(int n, int cap) {
        for (int c : CANDY_COUNTS) if (c > n && c <= Math.max(cap, FEW)) return c;
        return FEW;
    }

    /** O cap muda entre buscas (rodízio de tokens): a escolha salva fica, e volta quando o cap subir. */
    static int candyCount(int saved, int cap) {
        if (Arrays.binarySearch(CANDY_COUNTS, saved) < 0) return FEW;
        int k = FEW;
        for (int c : CANDY_COUNTS) if (c <= saved && c <= cap) k = c;
        return k;
    }

    /** Um spawn novo no mesmo lugar tem outro expiresMs e não conta como visitado. */
    static boolean isVisited(Map<String, Long> visited, Pogo.Candy c) {
        if (visited == null) return false;
        Long e = visited.get(c.coords);
        return e != null && e == c.expiresMs;
    }

    /** Só na lista nova: até ela chegar, a visitada continua na coluna, cinza. */
    static void dropVisited(List<Pogo.Candy> list, Map<String, Long> visited) {
        if (visited != null) list.removeIf(c -> isVisited(visited, c));
    }

    static void pruneVisited(Map<String, Long> visited, long now) {
        visited.values().removeIf(e -> PogoText.gone(e, now));
    }

    private int shown() {
        return candyMode ? candyCount(candyN, candyCap) : n;
    }

    private void cycle() {
        if (candyMode) {
            candyN = nextCandyCount(shown(), candyCap);
            pos.edit().putInt(CANDY_N, candyN).apply();
        } else {
            n = nextCount(n);
            pos.edit().putInt(COUNT, n).apply();
        }
        showCount();
        render();
    }

    private void showCount() {
        int k = shown();
        count.setText(String.valueOf(k));
        count.setContentDescription(ctx.getString(candyMode ? R.string.pogo_candy_count : R.string.pogo_bubbles_count, k));
    }

    private void showMode() {
        if (candyIcon == null) candyIcon = PogoChips.candy(ctx);
        if (candyMode && ballIcon == null) ballIcon = PogoChips.pokeball(ctx, ctx.getColor(R.color.primary));
        mode.setImageDrawable(candyMode ? ballIcon : candyIcon);
        mode.setContentDescription(ctx.getString(candyMode ? R.string.pogo_mode_to_pokemon : R.string.pogo_mode_to_candy));
        showCount();
    }

    private void switchMode() {
        if (task != null) task.cancel();
        task = null;
        Io.main().removeCallbacks(auto);
        Io.main().removeCallbacks(retry);
        closeCard();
        closeSide();
        candyMode = !candyMode;
        pos.edit().putInt(MODE, candyMode ? 1 : 0).apply();
        showMode();
        bubbles.removeAllViews();
        render();
        loadSoon();
    }

    /** O estado vem da logo, não do animador: com as animações do sistema desligadas ele termina sozinho. */
    private void loading(boolean on) {
        if (on == (logo.getDrawable() == spinner)) return;
        if (!on) {
            spin.cancel();
            logo.setRotation(0);
            logo.setImageDrawable(icon);
            logo.setContentDescription(ctx.getString(R.string.pogo_bubbles_logo));
            return;
        }
        if (spinner == null) {
            LayerDrawable d = new LayerDrawable(new Drawable[]{round(), PogoChips.refresh(ctx, ctx.getColor(R.color.primary))});
            d.setLayerSize(0, size, size);
            d.setLayerGravity(1, Gravity.CENTER);
            spinner = d;
        }
        logo.setImageDrawable(spinner);
        logo.setContentDescription(ctx.getString(R.string.pogo_bubbles_loading));
        spin.start();
    }

    private void refreshNow() {
        closeSide();
        if (task == null) loadSoon();
    }

    /** Atualizar e filtro: antes dos GAP_MS da última busca do modo, espera carregando em vez de levar o 429. */
    private void loadSoon() {
        Io.main().removeCallbacks(user);
        long wait = (candyMode ? candyAt : lastAt) + GAP_MS - System.currentTimeMillis();
        if (wait <= 0) {
            load(true);
            return;
        }
        loading(true);
        Io.main().postDelayed(user, wait);
    }

    private void schedule() {
        Io.main().removeCallbacks(auto);
        if (shown && task == null) {
            Io.main().postDelayed(auto, Math.max(0, (candyMode ? candyAt : lastAt) + PogoPanel.FRESH_MS - System.currentTimeMillis()));
        }
    }

    private void auto() {
        if (!shown || destroyed) return;
        if (((PowerManager) app.getSystemService(Context.POWER_SERVICE)).isInteractive()) load(false);
        else Io.main().postDelayed(auto, PogoPanel.FRESH_MS);
    }

    private void retry() {
        if (shown && !destroyed) load(false);
    }

    /** O centro é a posição de agora; user: o atualizar e o filtro (mostram o erro e o vazio); o automático fica quieto. */
    private void load(boolean user) {
        if (task != null) task.cancel();
        Io.Task t = task = new Io.Task();
        Io.main().removeCallbacks(auto);
        Io.main().removeCallbacks(retry);
        loading(true);
        double lat = host.lat(), lng = host.lng();
        if (candyMode) {
            loadCandies(t, user, lat, lng);
            return;
        }
        PogoPanel.NET.execute(() -> {
            if (t.isCancelled()) return;
            PogoFilter f = PogoText.asSent(app, PogoFilter.load(app));
            List<Pogo.Mon> got = null;
            Exception err = null;
            try {
                got = PogoPanel.fetch(app, f, f.km, lat, lng);
            } catch (Exception e) {
                err = e;
                Log.w(TAG, "load: " + (e instanceof Pogo.Error ? ((Pogo.Error) e).http : e.getClass().getSimpleName()));
            }
            List<Pogo.Mon> l = got;
            Exception e = err;
            Io.main().post(() -> {
                if (!done(t, e, user)) return;
                mons.clear();
                mons.addAll(l);
                if (user && l.isEmpty()) Ui.toast(app, PogoText.count(app, 0, f, f.km == PogoPanel.WORLD));
                render();
                schedule();
            });
        });
    }

    private void loadCandies(Io.Task t, boolean user, double lat, double lng) {
        int km = pos.getInt(CANDY_KM, PogoCandySheet.DEFAULT_KM);
        PogoPanel.NET.execute(() -> {
            if (t.isCancelled()) return;
            Pogo.Candies got = null;
            Exception err = null;
            try {
                got = Pogo.rareCandy(app, lat, lng, km);
            } catch (Exception e) {
                err = e;
                Log.w(TAG, "candy: " + (e instanceof Pogo.Error ? ((Pogo.Error) e).http : e.getClass().getSimpleName()));
            }
            Pogo.Candies cs = got;
            Exception e = err;
            Io.main().post(() -> {
                if (!done(t, e, user)) return;
                candies = new ArrayList<>(cs.list);
                dropVisited(candies, visited);
                candyCap = cs.cap;
                pos.edit().putInt(CANDY_CAP, candyCap).apply();
                showCount();
                if (user && candies.isEmpty()) Ui.toast(app, app.getString(R.string.pogo_candy_none, km));
                render();
                schedule();
            });
        });
    }

    /**
     * Fim da busca t; false se ela foi descartada ou deu erro (já tratado). A descartada não mexe na logo: quem a
     * cancelou já ligou outra busca (load, switchMode) ou desligou a logo (destroy).
     */
    private boolean done(Io.Task t, Exception e, boolean user) {
        if (t.isCancelled() || destroyed) return false;
        task = null;
        if (candyMode) candyAt = System.currentTimeMillis();
        else lastAt = System.currentTimeMillis();
        loading(false);
        if (e == null) return true;
        if (user) Ui.toast(app, PogoText.error(app, e));
        if (Pogo.slow(e) && shown) Io.main().postDelayed(retry, PogoPanel.RETRY_MS);
        else schedule();   // 429 incluso: só na próxima rodada
        return false;
    }

    /** Tira os vencidos, mostra os n primeiros (os mais perto) e agenda o próximo vencimento; sem tick de 1 s. */
    private void render() {
        long now = System.currentTimeMillis();
        long at = candyMode ? renderCandies(now) : renderMons(now);
        Io.main().removeCallbacks(prune);
        if (shown && at > 0) Io.main().postDelayed(prune, at - now);
    }

    private long renderMons(long now) {
        mons.removeIf(m -> PogoText.gone(m.despawnMs, now));
        int k = Math.min(n, mons.size());
        while (bubbles.getChildCount() > k) bubbles.removeViewAt(k);
        for (int i = 0; i < k; i++) {
            ImageView b = i < bubbles.getChildCount() ? (ImageView) bubbles.getChildAt(i) : bubble();
            Pogo.Mon m = mons.get(i);
            b.setTag(m);
            b.setContentDescription(m.name);
            Bitmap bmp = PogoIcons.cached(m);
            if (bmp != null) b.setImageBitmap(bmp);
            else b.setImageResource(R.drawable.pogo_ic_pokemon);
            if (bmp == null && m.id > 0) PogoIcons.load(app, m, onIcon);
        }
        return nextDespawn(mons, k, now);
    }

    private long renderCandies(long now) {
        if (candies == null) return 0;
        candies.removeIf(c -> PogoText.gone(c.expiresMs, now));
        if (visited != null) pruneVisited(visited, now);
        int k = Math.min(shown(), candies.size());
        while (bubbles.getChildCount() > k) bubbles.removeViewAt(k);
        long at = 0;
        for (int i = 0; i < k; i++) {
            ImageView b = i < bubbles.getChildCount() ? (ImageView) bubbles.getChildAt(i) : bubble();
            Pogo.Candy c = candies.get(i);
            b.setTag(c);
            b.setContentDescription(TextUtils.isEmpty(c.location) ? ctx.getString(R.string.pogo_candy_title) : c.location);
            b.setImageDrawable(isVisited(visited, c) ? grayIcon : candyIcon);
            if (c.expiresMs > now && (at == 0 || c.expiresMs < at)) at = c.expiresMs;
        }
        return at;
    }

    private void icons() {
        for (int i = 0; i < bubbles.getChildCount(); i++) {
            ImageView b = (ImageView) bubbles.getChildAt(i);
            Bitmap bmp = PogoIcons.cached((Pogo.Mon) b.getTag());
            if (bmp != null) b.setImageBitmap(bmp);
        }
    }

    /** O menor despawn ainda no futuro entre os k primeiros; 0 = nada a agendar. */
    static long nextDespawn(List<Pogo.Mon> l, int k, long now) {
        long at = 0;
        for (int i = 0; i < Math.min(k, l.size()); i++) {
            long d = l.get(i).despawnMs;
            if (d > now && (at == 0 || d < at)) at = d;
        }
        return at;
    }

    private ImageView bubble() {
        ImageView b = new ImageView(ctx);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(ctx.getColor(R.color.surface));
        d.setStroke(Ui.dp(ctx, 1), ctx.getColor(R.color.outline));
        b.setBackground(d);
        int p = Ui.dp(ctx, 4);
        b.setPadding(p, p, p, p);
        if (candyMode) gestures(b);
        else b.setOnClickListener(this::tap);
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(size, size);
        l.topMargin = gap;
        bubbles.addView(b, l);
        return b;
    }

    private void gestures(ImageView b) {
        b.setOnClickListener(v -> go((Pogo.Candy) v.getTag(), true));
        b.setOnLongClickListener(v -> {
            hold(v, (Pogo.Candy) v.getTag());
            return true;
        });
        GestureDetector g = new GestureDetector(ctx, new GestureDetector.SimpleOnGestureListener() {
            private Pogo.Candy at;
            private boolean held;

            @Override public boolean onDown(MotionEvent e) {
                at = (Pogo.Candy) b.getTag();   // uma busca chegando antes do tempo do duplo troca a tag da bolha
                held = false;
                return true;
            }

            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                go(at, true);
                return true;
            }

            /** No UP: a 2ª descida que vira rolagem recebe CANCEL; tocar e segurar só abre o card. */
            @Override public boolean onDoubleTapEvent(MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_UP && !held) go(at, false);
                return true;
            }

            @Override public void onLongPress(MotionEvent e) {
                held = true;
                hold(b, at);
            }
        });
        b.setOnTouchListener((v, e) -> {
            g.onTouchEvent(e);
            return true;
        });
    }

    private void go(Pogo.Candy c, boolean walk) {
        if (!shown) return;
        closeCard();
        if (visited == null) {
            visited = new HashMap<>();
            ColorMatrix m = new ColorMatrix();
            m.setSaturation(0);
            grayIcon = PogoChips.candy(ctx);
            grayIcon.setColorFilter(new ColorMatrixColorFilter(m));
        }
        visited.put(c.coords, c.expiresMs);
        host.go(c.lat, c.lng, walk);
        render();
    }

    private void hold(View b, Pogo.Candy c) {
        if (!shown) return;
        if (cardCandy != null && cardCandy.coords.equals(c.coords)) {
            closeCard();
            return;
        }
        closeSide();
        if (candyCard == null) {
            candyCard = new PogoCandyCard(ctx, new PogoCandyCard.Actions() {
                @Override public void walk(Pogo.Candy x) { go(x, true); }

                @Override public void teleport(Pogo.Candy x) { go(x, false); }

                @Override public void close() { closeCard(); }
            });
            candyCard.view.addOnLayoutChangeListener(cardFit);
        }
        cardCandy = c;
        candyCard.bind(c, Cooldown.formatDistance(Geo.distance(host.lat(), host.lng(), c.lat, c.lng)));
        if (!beside(candyCard.view, b)) cardCandy = null;
    }

    /** O card de hoje ao lado da bolha, do lado de dentro da tela; tocar de novo na mesma bolha fecha. */
    private void tap(View b) {
        Pogo.Mon m = (Pogo.Mon) b.getTag();
        if (m == cardMon) {
            closeCard();
            return;
        }
        closeSide();
        if (card == null) {
            card = new PogoCard(ctx, R.string.teleport, (la, ln, title) -> {
                closeCard();
                host.teleport(CoordParser.text(la, ln), title);
            }, this::closeCard);
            card.view.addOnLayoutChangeListener(cardFit);
        }
        cardMon = m;
        card.bind(m, Double.isNaN(m.lat) ? null : Cooldown.formatDistance(Geo.distance(host.lat(), host.lng(), m.lat, m.lng)));
        if (!beside(card.view, b)) cardMon = null;
    }

    /** false: a janela do card não abriu (sem permissão de overlay). */
    private boolean beside(View v, View b) {
        if (cardLp == null) {
            cardLp = params(0);
            cardLp.alpha = ALPHA;
        }
        int colX = lp.x + scroll.getLeft();
        readScreen();
        cardLp.width = Math.min(Ui.dp(ctx, 300), scr.x - size - 3 * margin);
        cardLp.x = onLeft(colX, size, scr.x) ? colX + size + margin : colX - margin - cardLp.width;
        cardLp.y = lp.y + scroll.getTop() + b.getTop() - scroll.getScrollY();
        if (v.getParent() != null) {
            update(v, cardLp);
            return true;
        }
        try {
            wm.addView(v, cardLp);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void fitCard(View v) {
        if (v.getParent() == null) return;
        int y = WindowDrag.clamp(cardLp.y, 0, Math.max(0, scr.y - v.getHeight()));
        if (y == cardLp.y) return;
        cardLp.y = y;
        update(v, cardLp);
    }

    private void closeCard() {
        if (cardMon != null) {
            cardMon = null;
            card.destroy();
            remove(card.view);
        }
        if (cardCandy != null) {
            cardCandy = null;
            candyCard.destroy();
            remove(candyCard.view);
        }
    }

    /** O mesmo modal de filtros, em tela cheia; aplicar salva (no arquivo do :overlay) e busca de novo. */
    private void openSheet() {
        closeSide();
        closeCard();
        if (candyMode) {
            if (candySheet != null) return;
            candySheet = PogoCandySheet.show(ctx, pos.getInt(CANDY_KM, PogoCandySheet.DEFAULT_KM), km -> {
                pos.edit().putInt(CANDY_KM, km).apply();
                loadSoon();
            });
            candySheet.setOnDismiss(() -> candySheet = null);
            return;
        }
        if (sheet != null) return;
        PogoFilterSheet s = new PogoFilterSheet(ctx, PogoFilter.load(app), f -> {
            f.save(app);
            sheet.close();
            loadSoon();
        }, () -> {
            sheet = null;
            if (sheetHost != null) sheetHost.dismiss();
        }, null, null);
        sheet = s;
        sheetHost = PanelHost.sheet(ctx, s.view(), s::back);
        sheetHost.setOnDismiss(() -> {
            if (sheet != null) sheet.destroy();   // fechou por fora (Teleport, Stop): sem a animação
            sheet = null;
            sheetHost = null;
        });
    }
}
