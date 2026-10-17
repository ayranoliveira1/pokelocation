package com.pokelocation.app.pogo;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.os.PowerManager;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
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
import java.util.List;
import java.util.function.Consumer;

/**
 * Coluna de bolhas sobre o jogo (:overlay): a logo no topo e um ícone por Pokémon perto da posição atual. A logo arrasta
 * a coluna e abre, para o lado de dentro da tela, atualizar, filtros e a quantidade; a bolha abre o PogoCard ao lado.
 * Janelas próprias, fora do PanelHost: abrir o Teleport ou o chat (closeAll) não a fecha.
 */
public final class PogoBubbles {
    public interface Host {
        double lat();
        double lng();
        void teleport(String text, String title);
    }

    private static final String TAG = "PogoBubbles", POS = "overlay_panels", COUNT = "pogo_col_n";
    /** O servidor aceita 10 buscas/min por conta e o 429 vem sem retry_after: a busca manual espera este tanto. */
    private static final long GAP_MS = 6_000;
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
    private final LinearLayout root, top, side, bubbles;
    private final ImageView refresh;
    private final TextView count;
    private final ObjectAnimator spin;
    private final WindowManager.LayoutParams lp;
    private final ImageView logo;
    private final List<Pogo.Mon> mons = new ArrayList<>();
    private int n;
    private boolean open = true, visible, shown, left = true, destroyed;
    private final Consumer<Bitmap> onIcon = b -> { if (b != null && !destroyed) icons(); };
    private final Runnable auto = this::auto, retry = this::retry, prune = this::render, user = () -> load(true);
    private long lastAt;
    private Io.Task task;
    private PogoCard card;
    private WindowManager.LayoutParams cardLp;
    private Pogo.Mon cardMon;
    private PogoFilterSheet sheet;
    private PanelHost sheetHost;

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
        key = classic ? "pogo_col_classic" : "pogo_col_drawer";
        n = pos.getInt(COUNT, 5) == 10 ? 10 : 5;
        size = c.getResources().getDimensionPixelSize(R.dimen.ov_btn);   // o botão da gaveta
        gap = Ui.dp(c, 6);
        margin = Ui.dp(c, 8);
        int primary = c.getColor(R.color.primary);

        logo = new ImageView(c);
        logo.setImageResource(R.mipmap.ic_launcher_round);
        logo.setContentDescription(c.getString(R.string.pogo_bubbles_logo));
        side = new LinearLayout(c);
        side.setVisibility(View.GONE);
        refresh = button(PogoChips.refresh(c, primary), R.string.pogo_refresh, v -> refreshNow());
        button(PogoChips.lines(c, primary), R.string.pogo_panel_filters, v -> openSheet());
        count = new TextView(c);
        count.setGravity(Gravity.CENTER);
        count.setTextSize(15);
        count.setTypeface(PogoChips.MEDIUM);
        count.setTextColor(primary);
        count.setBackground(round());
        count.setOnClickListener(v -> cycle());
        side.addView(count, sideLp());
        showCount();
        top = new LinearLayout(c);
        top.addView(logo, new LinearLayout.LayoutParams(size, size));
        top.addView(side);
        bubbles = new LinearLayout(c);
        bubbles.setOrientation(LinearLayout.VERTICAL);
        root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(top);
        root.addView(bubbles);
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> { if (b - t != ob - ot || r - l != or - ol) fit(); });

        spin = ObjectAnimator.ofFloat(refresh, View.ROTATION, 0f, 360f);
        spin.setDuration(900);
        spin.setRepeatCount(ValueAnimator.INFINITE);
        spin.setInterpolator(new LinearInterpolator());

        lp = params(WindowManager.LayoutParams.WRAP_CONTENT);
        lp.x = pos.getInt(key + "_x", -1);
        lp.y = pos.getInt(key + "_y", -1);
        logo.setOnClickListener(v -> toggleSide());
        logo.setOnTouchListener(new WindowDrag(wm, root, lp, display, true, (x, y) -> moved()));
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
        if (card != null) card.destroy();
        card = null;
        if (sheetHost != null) sheetHost.dismiss();
        mons.clear();
        bubbles.removeAllViews();
        PogoIcons.trim();
    }

    // ------------------------------------------------------------------ janela

    private void sync() {
        boolean want = open && visible && !destroyed;
        if (want == shown) return;
        if (!want) {
            closeSide();
            closeCard();
            spin.cancel();
            Io.main().removeCallbacks(auto);
            Io.main().removeCallbacks(retry);
            Io.main().removeCallbacks(prune);
            Io.main().removeCallbacks(user);
            refresh.setRotation(0);
            refresh.setEnabled(true);
            remove(root);
            shown = false;
            return;
        }
        readScreen();
        int[] p = place(classic, lp.x, lp.y, size, size + n * (size + gap), scr.x, scr.y, margin, size, 2 * size);
        lp.x = p[0];
        lp.y = p[1];
        try {
            wm.addView(root, lp);
        } catch (RuntimeException e) {
            return;   // sem permissão de overlay
        }
        shown = true;
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
     * inferior direito. A salva só sobe o que for preciso para caber (de 5 para 10 bolhas).
     */
    static int[] place(boolean classic, int x, int y, int w, int h, int scrW, int scrH, int margin, int top, int bottom) {
        if (x >= 0 && y >= 0 && x <= scrW - w) return new int[]{x, WindowDrag.clamp(y, 0, Math.max(0, scrH - h))};
        return classic ? new int[]{margin, top} : new int[]{scrW - w - margin, scrH - h - bottom};
    }

    static boolean onLeft(int x, int w, int scrW) {
        return x + w / 2 < scrW / 2;
    }

    /** Soltou a logo: guarda o x da logo (com os botões abertos à esquerda dela, a janela começa antes). */
    private void moved() {
        closeCard();
        pos.edit().putInt(key + "_x", lp.x + top.getLeft() + logo.getLeft()).putInt(key + "_y", lp.y).apply();
    }

    /** A coluna mudou de tamanho (quantidade, busca, botões): continua inteira na tela. */
    private void fit() {
        if (!shown) return;
        readScreen();
        int x = WindowDrag.clamp(lp.x, 0, Math.max(0, scr.x - root.getWidth()));
        int y = WindowDrag.clamp(lp.y, 0, Math.max(0, scr.y - root.getHeight()));
        if (x == lp.x && y == lp.y) return;
        lp.x = x;
        lp.y = y;
        update(root, lp);
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

    // ------------------------------------------------------------------ botões da logo

    /** Com a coluna à direita os botões abrem à esquerda da logo: a janela cresce para lá e a logo não sai do lugar. */
    private void toggleSide() {
        if (side.getVisibility() == View.VISIBLE) {
            closeSide();
            return;
        }
        readScreen();
        left = onLeft(lp.x, size, scr.x);
        top.removeView(side);
        top.addView(side, left ? 1 : 0);
        root.setGravity(left ? Gravity.START : Gravity.END);
        side.setVisibility(View.VISIBLE);
        if (!left) shift(-sideWidth());
    }

    private void closeSide() {
        if (side.getVisibility() != View.VISIBLE) return;
        side.setVisibility(View.GONE);
        if (!left) shift(sideWidth());
    }

    private void shift(int dx) {
        if (!shown) return;
        lp.x += dx;
        update(root, lp);
    }

    private int sideWidth() {
        side.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        return side.getMeasuredWidth();
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
        return n == 5 ? 10 : 5;
    }

    private void cycle() {
        n = nextCount(n);
        pos.edit().putInt(COUNT, n).apply();
        showCount();
        render();
    }

    private void showCount() {
        count.setText(String.valueOf(n));
        count.setContentDescription(ctx.getString(R.string.pogo_bubbles_count, n));
    }

    private void refreshNow() {
        closeSide();
        if (task == null) loadSoon();
    }

    /** Atualizar e filtro: antes dos GAP_MS da última busca, espera girando em vez de levar o 429. */
    private void loadSoon() {
        Io.main().removeCallbacks(user);
        long wait = lastAt + GAP_MS - System.currentTimeMillis();
        if (wait <= 0) {
            load(true);
            return;
        }
        refresh.setEnabled(false);
        spin.start();
        Io.main().postDelayed(user, wait);
    }

    // ------------------------------------------------------------------ busca

    private void schedule() {
        Io.main().removeCallbacks(auto);
        if (shown && task == null) Io.main().postDelayed(auto, Math.max(0, lastAt + PogoPanel.FRESH_MS - System.currentTimeMillis()));
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
        refresh.setEnabled(false);
        spin.start();
        double lat = host.lat(), lng = host.lng();
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
                if (t.isCancelled() || destroyed) return;
                task = null;
                lastAt = System.currentTimeMillis();
                spin.cancel();
                refresh.setRotation(0);
                refresh.setEnabled(true);
                if (e != null) {
                    if (user) Ui.toast(app, PogoText.error(app, e));
                    if (Pogo.slow(e) && shown) Io.main().postDelayed(retry, PogoPanel.RETRY_MS);
                    else schedule();   // 429 incluso: só na próxima rodada
                    return;
                }
                mons.clear();
                mons.addAll(l);
                if (user && l.isEmpty()) Ui.toast(app, PogoText.count(app, 0, f, f.km == PogoPanel.WORLD));
                render();
                schedule();
            });
        });
    }

    // ------------------------------------------------------------------ bolhas

    /** Tira os despawnados, mostra os n primeiros (os mais perto) e agenda o próximo despawn; sem tick de 1 s. */
    private void render() {
        long now = System.currentTimeMillis();
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
        Io.main().removeCallbacks(prune);
        long at = nextDespawn(mons, k, now);
        if (shown && at > 0) Io.main().postDelayed(prune, at - now);
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
        b.setOnClickListener(this::tap);
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(size, size);
        l.topMargin = gap;
        bubbles.addView(b, l);
        return b;
    }

    // ------------------------------------------------------------------ card e filtros

    /** O card de hoje ao lado da bolha, do lado de dentro da tela; tocar de novo na mesma bolha fecha. */
    private void tap(View b) {
        Pogo.Mon m = (Pogo.Mon) b.getTag();
        if (m == cardMon) {
            closeCard();
            return;
        }
        int colX = lp.x + bubbles.getLeft(), y = lp.y + bubbles.getTop() + b.getTop();
        closeSide();
        if (card == null) {
            card = new PogoCard(ctx, R.string.teleport, (la, ln, title) -> {
                closeCard();
                host.teleport(CoordParser.text(la, ln), title);
            }, this::closeCard);
            cardLp = params(0);
            cardLp.alpha = ALPHA;
            card.view.addOnLayoutChangeListener((v, l, t, r, bo, ol, ot, or, ob) -> { if (bo - t != ob - ot) fitCard(); });
        }
        cardMon = m;
        card.bind(m, Double.isNaN(m.lat) ? null : Cooldown.formatDistance(Geo.distance(host.lat(), host.lng(), m.lat, m.lng)));
        readScreen();
        cardLp.width = Math.min(Ui.dp(ctx, 300), scr.x - size - 3 * margin);
        cardLp.x = onLeft(colX, size, scr.x) ? colX + size + margin : colX - margin - cardLp.width;
        cardLp.y = y;
        if (card.view.getParent() != null) {
            update(card.view, cardLp);
            return;
        }
        try {
            wm.addView(card.view, cardLp);
        } catch (RuntimeException e) {
            cardMon = null;
        }
    }

    private void fitCard() {
        if (cardMon == null) return;
        int y = WindowDrag.clamp(cardLp.y, 0, Math.max(0, scr.y - card.view.getHeight()));
        if (y == cardLp.y) return;
        cardLp.y = y;
        update(card.view, cardLp);
    }

    private void closeCard() {
        if (cardMon == null) return;
        cardMon = null;
        card.destroy();
        remove(card.view);
    }

    /** O mesmo modal de filtros, em tela cheia; aplicar salva (no arquivo do :overlay) e busca de novo. */
    private void openSheet() {
        closeSide();
        closeCard();
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
