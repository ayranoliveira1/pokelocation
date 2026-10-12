package com.pokelocation.app.ui.panel;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
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

import com.pokelocation.app.App;
import com.pokelocation.app.Io;
import com.pokelocation.app.R;
import com.pokelocation.app.data.Db;
import com.pokelocation.app.data.Prefs;
import com.pokelocation.app.util.Ui;

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
    private Runnable onDismiss;
    private boolean showing;

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
            public boolean dispatchKeyEvent(KeyEvent e) {
                if (handleBack && e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                    if (e.getAction() == KeyEvent.ACTION_UP) dismiss();
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
        }
        wm = null;
        window = null;
        Runnable r = onDismiss;
        onDismiss = null;
        if (r != null) r.run();
    }

    public boolean isShowing() { return showing; }

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
