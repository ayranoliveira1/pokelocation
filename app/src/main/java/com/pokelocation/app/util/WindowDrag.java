package com.pokelocation.app.util;

import android.graphics.Point;
import android.view.Display;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

/**
 * Arrasta uma janela do WindowManager pela alça (joystick, gaveta e painéis sobre o jogo), presa na tela. O MOVE não
 * aloca: o tamanho da tela é lido uma vez por arrasto e o mesmo LayoutParams vai no updateViewLayout.
 */
public final class WindowDrag implements View.OnTouchListener {
    /** Ao soltar, com a posição já presa na tela. */
    public interface Listener { void onMoved(int x, int y); }

    private static final int CLICK_SLOP_PX = 20;   // 02 §3.13

    private final WindowManager wm;
    private final View win;
    private final WindowManager.LayoutParams lp;
    private final Display display;   // DisplayInfo ao vivo: acompanha a rotação (o ctx temático não)
    private final boolean clickable;
    private final Listener done;
    private final Point scr = new Point();
    private int x0, y0;
    private float rx0, ry0;
    private boolean enabled = true;

    public WindowDrag(WindowManager wm, View win, WindowManager.LayoutParams lp, Display display, boolean clickable, Listener done) {
        this.wm = wm;
        this.win = win;
        this.lp = lp;
        this.display = display;
        this.clickable = clickable;
        this.done = done;
    }

    public void setEnabled(boolean on) { enabled = on; }

    @Override
    @SuppressWarnings("deprecation")
    public boolean onTouch(View v, MotionEvent e) {
        if (!enabled || !win.isAttachedToWindow()) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                display.getRealSize(scr);   // uma vez por arrasto: MOVE sem alocação
                x0 = lp.x;
                y0 = lp.y;
                rx0 = e.getRawX();
                ry0 = e.getRawY();
                return true;
            case MotionEvent.ACTION_MOVE:
                lp.x = clamp(x0 + Math.round(e.getRawX() - rx0), 0, Math.max(0, scr.x - win.getWidth()));
                lp.y = clamp(y0 + Math.round(e.getRawY() - ry0), 0, Math.max(0, scr.y - win.getHeight()));
                try {
                    wm.updateViewLayout(win, lp);
                } catch (RuntimeException ignored) {
                }
                return true;
            case MotionEvent.ACTION_UP:
                done.onMoved(lp.x, lp.y);   // já preso na tela (corrige o original)
                if (clickable && !dragged(lp.x - x0, lp.y - y0)) v.performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                done.onMoved(lp.x, lp.y);
                return true;
            default:
                return false;
        }
    }

    /** Passou da tolerância do clique: o tremor de um toque curto (o MOVE já soma o delta cru) não conta como arraste. */
    public static boolean dragged(int dx, int dy) {
        return Math.abs(dx) >= CLICK_SLOP_PX || Math.abs(dy) >= CLICK_SLOP_PX;
    }

    public static int clamp(int v, int min, int max) {
        return v < min ? min : Math.min(v, max);
    }
}
