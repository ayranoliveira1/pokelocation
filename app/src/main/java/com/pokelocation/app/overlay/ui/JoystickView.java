package com.pokelocation.app.overlay.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import com.pokelocation.app.R;
import com.pokelocation.app.engine.MockEngine;

/**
 * Stick 360° desenhado em software (base + knob). Cada toque vai direto para {@link MockEngine#setStick}
 * com ângulo atan2(−dy,dx) em graus (0 = leste, anti-horário), magnitude |d|/raio em 0..1.
 * Hot path sem alocação: só primitivos e Paints criados no construtor.
 */
public final class JoystickView extends View {
    private static final long RETURN_MS = 100;

    private final Paint basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int primary, accent, primaryDark;
    private final float ringW;
    private static final int SLOP_PX = 10;   // touch slop do classic

    private MockEngine engine;
    private int basePx, knobPx;
    private float cx, cy, radius;
    private float kx, ky;                 // deslocamento atual do knob em px
    private float downX, downY;
    private boolean dragged, downInKnob;
    private boolean locked;
    private boolean tapToggles;           // classic: toque curto no centro trava/destrava
    private ValueAnimator back;
    private float backX, backY;

    public JoystickView(Context c, AttributeSet a) {
        super(c, a);
        basePx = c.getResources().getDimensionPixelSize(R.dimen.ov_stick_base);
        knobPx = c.getResources().getDimensionPixelSize(R.dimen.ov_stick_knob);
        primary = c.getColor(R.color.primary);
        accent = c.getColor(R.color.accent);
        primaryDark = c.getColor(R.color.primary_dark);
        ringW = 2 * c.getResources().getDisplayMetrics().density;
        basePaint.setColor(c.getColor(R.color.overlay_button_bg));
        ringPaint.setStyle(Paint.Style.STROKE);
        knobPaint.setColor(primary);
    }

    /** Liga ao motor. sizePct 75..200 escala a base (75dp) e o knob (30dp). */
    void setup(MockEngine engine, int sizePct, boolean tapToggles) {
        this.engine = engine;
        this.tapToggles = tapToggles;
        basePx = basePx * sizePct / 100;
        knobPx = knobPx * sizePct / 100;
        requestLayout();
    }

    boolean isLocked() {
        return locked;
    }

    /** Lock ("cruise control"): travado, soltar não zera o stick. Destravar devolve ao centro. */
    void setLocked(boolean l) {
        locked = l;
        if (!l) release();
        invalidate();
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        setMeasuredDimension(basePx, basePx);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        cx = w / 2f;
        cy = h / 2f;
        radius = Math.max(1f, Math.min(w, h) / 2f - knobPx / 2f);
    }

    @Override
    protected void onDraw(Canvas c) {
        float r = Math.min(cx, cy);
        c.drawCircle(cx, cy, r, basePaint);
        ringPaint.setColor(locked ? accent : primary);
        ringPaint.setStrokeWidth(locked ? ringW * 2 : ringW);
        c.drawCircle(cx, cy, r - ringPaint.getStrokeWidth() / 2f, ringPaint);
        knobPaint.setColor(locked ? primaryDark : primary);
        c.drawCircle(cx + kx, cy + ky, knobPx / 2f, knobPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (engine == null) return false;
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (back != null) back.cancel();
                downX = x;
                downY = y;
                dragged = false;
                downInKnob = Math.hypot(x - cx, y - cy) <= knobPx / 2f;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                if (!(tapToggles && downInKnob)) move(x, y);   // startOnFirstTouch, exceto o toque no toggle
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!dragged && Math.hypot(x - downX, y - downY) > SLOP_PX) dragged = true;
                if (dragged || !(tapToggles && downInKnob)) move(x, y);
                return true;
            case MotionEvent.ACTION_UP:
                if (tapToggles && downInKnob && !dragged) {
                    performClick();
                    setLocked(!locked);
                    return true;
                }
                if (!locked) release();
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (!locked) release();
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private void move(float x, float y) {
        float dx = x - cx, dy = y - cy;
        float d = (float) Math.hypot(dx, dy);
        if (d > radius) {
            dx = dx * radius / d;
            dy = dy * radius / d;
        }
        kx = dx;
        ky = dy;
        engine.setStick(angleDeg(dx, dy), magnitude(dx, dy, radius));
        invalidate();
    }

    /** Solta o stick: motor parado já, knob volta ao centro em 100 ms. */
    private void release() {
        if (engine != null) engine.setStick(0f, 0f);
        if (kx == 0f && ky == 0f) return;
        if (back == null) {
            back = ValueAnimator.ofFloat(1f, 0f);
            back.setDuration(RETURN_MS);
            back.setInterpolator(new DecelerateInterpolator());
            back.addUpdateListener(a -> {
                float f = (float) a.getAnimatedValue();
                kx = backX * f;
                ky = backY * f;
                invalidate();
            });
        }
        backX = kx;
        backY = ky;
        back.start();
    }

    /** Cancela a animação e solta referências (OverlayView.destroy). */
    void shutdown() {
        if (back != null) {
            back.cancel();
            back.removeAllUpdateListeners();
            back = null;
        }
        engine = null;
    }

    /** Ângulo matemático em graus: 0 = leste, 90 = norte (dy de tela cresce para baixo). */
    static float angleDeg(float dx, float dy) {
        return (float) Math.toDegrees(Math.atan2(0f - dy, dx));   // 0f − dy evita −0.0 (esquerda = 180, não −180)
    }

    static float magnitude(float dx, float dy, float radius) {
        if (radius <= 0f) return 0f;
        return (float) Math.min(1.0, Math.hypot(dx, dy) / radius);
    }
}
