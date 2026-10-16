package com.pokelocation.app.ui.panel;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Point;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.view.Display;
import android.graphics.PixelFormat;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import com.pokelocation.app.App;
import com.pokelocation.app.Io;
import com.pokelocation.app.R;
import com.pokelocation.app.data.Db;
import com.pokelocation.app.data.Prefs;
import com.pokelocation.app.util.Ui;
import com.pokelocation.app.util.WindowDrag;

import java.util.ArrayList;

/**
 * Hospeda um painel: Activity → Dialog translúcido; senão → janela WindowManager overlay MATCH_PARENT (sem aceleração
 * de hardware). Véu scrim + card centralizado (margem 16dp). Back e toque no véu fecham. Abrir um painel fecha os outros.
 * Só widgets do framework: funciona no main, :overlay e :map.
 */
public final class PanelHost {
    private static final ArrayList<PanelHost> sOpen = new ArrayList<>();

    private Dialog dialog;
    private WindowManager wm;
    private View window;
    private WindowManager.LayoutParams lp;
    private Runnable onDismiss, coverBack;
    private View cover;
    private boolean showing;
    // overGame: janela arrastável do tamanho do card, posição salva por painel (key)
    private static final String POS_FILE = "overlay_panels";
    private String key;
    private Display display;
    private WindowDrag drag;
    private final Point scr = new Point();
    private final View.OnLayoutChangeListener refit = (v, l, t, r, b, ol, ot, or, ob) -> { if (b - t != ob - ot) fit(); };

    private PanelHost() {}

    public static PanelHost show(Context ctx, View content, boolean wantsKeyboard) {
        closeAll();
        PanelHost h = new PanelHost();
        Activity act = activityOf(ctx);
        FrameLayout root = h.buildRoot(ctx, content, act == null);
        boolean secure = Prefs.getBool(ctx, Prefs.PREVENT_SCREENSHOTS);
        if (act != null) {
            Dialog d = new Dialog(act, android.R.style.Theme_Translucent_NoTitleBar);
            d.setContentView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            d.setCancelable(true);
            d.setOnDismissListener(x -> h.dismiss());
            Window w = d.getWindow();
            if (w != null) {
                w.setBackgroundDrawable(new ColorDrawable(0));
                w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                w.setSoftInputMode(wantsKeyboard ? WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                        : WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
                if (secure) w.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            }
            h.dialog = d;
            d.show();
        } else {
            // Focável para receber Back; sem teclado → ALT_FOCUSABLE_IM (o IME nunca abre).
            int flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
            if (!wantsKeyboard) flags |= WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
            if (secure) flags |= WindowManager.LayoutParams.FLAG_SECURE;
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.overlayWindowType(), flags, PixelFormat.TRANSLUCENT);
            lp.softInputMode = wantsKeyboard ? WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    : WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN;
            h.wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            try {
                h.wm.addView(root, lp);
            } catch (Exception e) {
                h.wm = null;
                return h;   // sem permissão de overlay: painel não aparece
            }
            h.window = root;
            h.lp = lp;
            root.setFocusableInTouchMode(true);
            root.requestFocus();
        }
        h.showing = true;
        sOpen.add(h);
        return h;
    }

    private FrameLayout buildRoot(Context ctx, View content, boolean handleBack) {
        FrameLayout root = new FrameLayout(ctx) {
            @Override
            protected void onConfigurationChanged(Configuration c) {
                super.onConfigurationChanged(c);
                post(PanelHost.this::fit);   // girou: largura e posição de novo dentro da tela
            }

            @Override
            public boolean dispatchKeyEvent(KeyEvent e) {
                if (handleBack && e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                    if (e.getAction() == KeyEvent.ACTION_UP) {
                        if (coverBack != null) coverBack.run();
                        else dismiss();
                    }
                    return true;
                }
                return super.dispatchKeyEvent(e);
            }
        };
        root.setBackgroundColor(ctx.getColor(R.color.scrim));
        root.setOnClickListener(v -> dismiss());
        final int maxW = ctx.getResources().getDimensionPixelSize(R.dimen.panel_max_width);
        FrameLayout card = new FrameLayout(ctx) {
            @Override
            protected void onMeasure(int wSpec, int hSpec) {
                int w = MeasureSpec.getSize(wSpec);
                if (w > maxW) wSpec = MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.getMode(wSpec));
                super.onMeasure(wSpec, hSpec);
            }
        };
        card.setBackgroundResource(R.drawable.bg_panel);
        card.setClickable(true);
        card.setClipToOutline(true);
        int m = ctx.getResources().getDimensionPixelSize(R.dimen.panel_margin);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        clp.setMargins(m, m, m, m);
        card.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(card, clp);
        // edge-to-edge / teclado: padding com system bars + IME
        root.setOnApplyWindowInsetsListener((v, in) -> {
            int l, t, r, b;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                l = i.left; t = i.top; r = i.right; b = i.bottom;
            } else {
                l = in.getSystemWindowInsetLeft(); t = in.getSystemWindowInsetTop();
                r = in.getSystemWindowInsetRight(); b = in.getSystemWindowInsetBottom();
            }
            v.setPadding(l, t, r, b);
            return in;
        });
        return root;
    }

    private static Activity activityOf(Context c) {
        while (c instanceof ContextWrapper) {
            if (c instanceof Activity) return (Activity) c;
            c = ((ContextWrapper) c).getBaseContext();
        }
        return null;
    }

    /** Idempotente; removeView em try/catch; anula referências. */
    public void dismiss() {
        if (!showing && dialog == null && window == null) return;
        showing = false;
        sOpen.remove(this);
        Dialog d = dialog;
        dialog = null;
        if (d != null) {
            d.setOnDismissListener(null);
            try {
                d.dismiss();
            } catch (Exception ignored) {
            }
        }
        if (wm != null && window != null) {
            try {
                wm.removeView(window);
            } catch (Exception ignored) {
            }
            // O sistema (IME, ViewRootImpl) às vezes segura a raiz de uma janela de overlay removida: sem filhos,
            // ela não arrasta o painel, as listas e os bitmaps junto.
            ((ViewGroup) window).removeAllViews();
        }
        if (window != null) window.removeOnLayoutChangeListener(refit);
        wm = null;
        window = null;
        lp = null;
        cover = null;
        coverBack = null;
        drag = null;
        display = null;
        Runnable r = onDismiss;
        onDismiss = null;
        if (r != null) r.run();
    }

    public boolean isShowing() { return showing; }

    /**
     * Painel sobre o jogo (só :overlay): janela do tamanho do card, sem scrim, arrastável pela alça do topo como o
     * joystick; o toque fora vai para o jogo, que mantém o foco até setFocusable(true). FLAG_SECURE: a conversa não
     * sai em print nem gravação.
     */
    public void overGame() { overGame(true, "chat"); }

    /**
     * secure false: só o prevent_screenshots do app (já aplicado no show) decide o FLAG_SECURE. key: cada painel guarda
     * a sua posição (prefs próprio, só o :overlay grava); sem posição salva, abre embaixo e centralizado.
     */
    public void overGame(boolean secure, String key) {
        if (wm == null || window == null) return;
        Context c = window.getContext();
        this.key = key;
        display = ((DisplayManager) c.getApplicationContext().getSystemService(Context.DISPLAY_SERVICE)).getDisplay(Display.DEFAULT_DISPLAY);
        FrameLayout root = (FrameLayout) window;
        root.setBackground(null);
        root.setOnClickListener(null);
        root.setClickable(false);
        ViewGroup card = (ViewGroup) root.getChildAt(0);
        FrameLayout.LayoutParams clp = (FrameLayout.LayoutParams) card.getLayoutParams();
        clp.setMargins(0, 0, 0, 0);
        clp.gravity = Gravity.TOP | Gravity.START;
        View content = card.getChildAt(0);
        card.removeView(content);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        View grip = grip(c);
        col.addView(grip, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 24)));
        col.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        drag = new WindowDrag(wm, root, lp, display, false, this::savePos);
        grip.setOnTouchListener(drag);
        readScreen();
        SharedPreferences p = c.getSharedPreferences(POS_FILE, Context.MODE_PRIVATE);
        lp.width = width(c);
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = p.getInt(key + "_x", (scr.x - lp.width) / 2);
        lp.y = p.getInt(key + "_y", scr.y);   // sem posição: embaixo (o fit prende quando a altura é conhecida)
        lp.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        if (secure) lp.flags |= WindowManager.LayoutParams.FLAG_SECURE;
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        root.addOnLayoutChangeListener(refit);
        update();
    }

    /** Alça visível de 32×4dp numa faixa de 24dp (o alvo do arraste); o resto do conteúdo rola normalmente. */
    private static View grip(Context c) {
        FrameLayout strip = new FrameLayout(c);
        View bar = new View(c);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(Ui.dp(c, 2));
        g.setColor(c.getColor(R.color.outline));
        bar.setBackground(g);
        strip.addView(bar, new FrameLayout.LayoutParams(Ui.dp(c, 32), Ui.dp(c, 4), Gravity.CENTER));
        return strip;
    }

    private int width(Context c) {
        return Math.min(Ui.dp(c, 360), scr.x - Ui.dp(c, 16));
    }

    @SuppressWarnings("deprecation")
    private void readScreen() {
        display.getRealSize(scr);
    }

    private void savePos(int x, int y) {
        if (window == null || App.process() != App.PROC_OVERLAY) return;
        window.getContext().getSharedPreferences(POS_FILE, Context.MODE_PRIVATE).edit()
                .putInt(key + "_x", x).putInt(key + "_y", y).apply();
    }

    /** Mantém o painel inteiro na tela quando a altura muda (lista carregou, card abriu) ou a tela gira. */
    private void fit() {
        if (wm == null || window == null || display == null || cover != null) return;
        readScreen();
        int w = width(window.getContext());
        int x = WindowDrag.clamp(lp.x, 0, Math.max(0, scr.x - w));
        int y = WindowDrag.clamp(lp.y, 0, Math.max(0, scr.y - window.getHeight()));
        if (x == lp.x && y == lp.y && w == lp.width) return;
        lp.x = x;
        lp.y = y;
        lp.width = w;
        update();
    }

    /**
     * Folha por cima do painel sobre o jogo (filtros do PogoPanel): com v a janela cobre a tela, o arraste desliga, o
     * card fica onde estava e o BACK chama onBack; com null a folha sai e a janela volta ao card na posição de antes.
     * O dismiss também solta tudo.
     */
    public void cover(View v, Runnable onBack) {
        if (wm == null || window == null) return;
        FrameLayout root = (FrameLayout) window;
        if (cover != null) root.removeView(cover);
        boolean was = cover != null;
        cover = v;
        coverBack = v != null ? onBack : null;
        FrameLayout.LayoutParams clp = (FrameLayout.LayoutParams) root.getChildAt(0).getLayoutParams();
        if (v != null) {
            root.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            if (!was) {
                clp.width = lp.width;
                clp.leftMargin = lp.x;
                clp.topMargin = lp.y;
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
                lp.x = lp.y = 0;
            }
        } else if (was) {
            lp.x = clp.leftMargin;
            lp.y = clp.topMargin;
            lp.width = clp.width;
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            clp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            clp.setMargins(0, 0, 0, 0);
        }
        if (drag != null) drag.setEnabled(v == null);
        root.getChildAt(0).setLayoutParams(clp);
        update();
    }

    public void setFocusable(boolean focusable) {
        if (wm == null || window == null) return;
        if (focusable) lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        else lp.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        update();
    }

    private void update() {
        try {
            wm.updateViewLayout(window, lp);
        } catch (Exception ignored) {
        }
    }

    /** Chamado uma vez, em qualquer forma de fechar. */
    public void setOnDismiss(Runnable r) { onDismiss = r; }

    /**
     * :overlay (vive horas): o banco só é aberto por painéis. Ao fechar um painel que o usou, fecha a conexão no Io
     * (mesma fila dos DAOs: roda depois das leituras/escritas pendentes; o próximo uso reabre). No main não faz nada.
     */
    static void releaseDbIfOverlay() {
        if (App.process() == App.PROC_OVERLAY) Io.run(Db::close);
    }

    public static void closeAll() {
        for (Object h : sOpen.toArray()) ((PanelHost) h).dismiss();
    }
}
