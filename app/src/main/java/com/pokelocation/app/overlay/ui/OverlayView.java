package com.pokelocation.app.overlay.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.view.Display;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.BounceInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.pokelocation.app.R;
import com.pokelocation.app.data.Prefs;
import com.pokelocation.app.engine.EngineConfig;
import com.pokelocation.app.engine.MockEngine;
import com.pokelocation.app.geo.CoordParser;
import com.pokelocation.app.util.Ui;

/**
 * Janelas do joystick no :overlay (02 §2.1–2.3, 09 §2.3, 11 §2.6). API combinada em docs/team/W1c-api.md.
 * Só framework, janelas sem aceleração de hardware, opacidade por LayoutParams.alpha.
 * Fala direto com o motor em setStick/setSpeedKmh/injectNow/setAutoSuspendActive; o resto vai pelo {@link Host}.
 */
public final class OverlayView implements View.OnClickListener {

    /** Callbacks para o OverlayService (efeitos de sessão: rota, gravação, teleporte, mapa). */
    public interface Host {
        void onGo(double lat, double lng, double alt, boolean walk);   // alt NaN = desconhecida
        void onGoBack(boolean walk);
        boolean hasPreviousPosition();
        void onOpenMap();
        void onRouteStop();
        void onRoutePlayPause();
        void onRoutePrev();
        void onRouteNext();
        void onRecordStart();
        void onRecordAdd();
        void onRecordDiscard();
        void onRecordOpenMap();
        void onRecordSubmit();
    }

    private static final String TYPE_CLASSIC = "classic";
    private static final int CLICK_SLOP_PX = 20;     // 02 §3.13
    private static final int DRAWER_DEFAULT_Y = 200;
    private static final long DROP_MS = 500;

    final Context ctx;
    final MockEngine engine;
    final Host host;
    private final EngineConfig cfg;
    private final WindowManager wm;
    private final OverlayFlows flows;
    private final boolean classic, secure;
    private final int sizePct;
    private final float alpha;
    private final Display display;           // DisplayInfo ao vivo: acompanha a rotação (o ctx temático não)
    private final Point scr = new Point();   // tela real, relida a cada place/arrasto

    // Sessão (vai no putSession; last_mocked_time fica só em memória, o serviço grava o seu no Stop)
    private int stickX, stickY, drawerX, drawerY;
    boolean walkMode;
    long lastMockedTime;

    // Janelas
    private View stickWin, drawerWin, dropWin;
    private WindowManager.LayoutParams stickLp, drawerLp;
    private boolean stickShown, drawerShown;
    private boolean visible, stickHidden;   // stickHidden: drawer_hide_joystick + olho da gaveta (só sessão)
    private boolean destroyed;

    // Views (as do tipo que não existem ficam null)
    private JoystickView stick;
    private View column, routePill, recPill, speedPill, routeTop, routeRow, asBox, suspend, recMap, recSave;
    private ImageView map, pause, rStop, eye, lock, speed, spWalk, spJog, spRun;
    private TextView progress, asNum;

    // Estado vindo do serviço
    private boolean hasRoute, playing, recording, autoSuspendActive, asRed;
    private int recCount;

    public OverlayView(Context ctx, MockEngine engine, EngineConfig cfg, Host host) {
        this.ctx = ctx;
        this.engine = engine;
        this.cfg = cfg;
        this.host = host;
        wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        display = ((DisplayManager) ctx.getApplicationContext().getSystemService(Context.DISPLAY_SERVICE))
                .getDisplay(Display.DEFAULT_DISPLAY);
        classic = TYPE_CLASSIC.equals(Prefs.getString(ctx, Prefs.SETTINGS_JOYSTICK_TYPE));
        secure = Prefs.getBool(ctx, Prefs.PREVENT_SCREENSHOTS);
        sizePct = clamp(Prefs.getInt(ctx, Prefs.SETTINGS_JOYSTICK_SIZE), 75, 200);
        alpha = clamp(Prefs.getInt(ctx, Prefs.SETTINGS_JOYSTICK_OPACITY), 10, 100) / 100f;
        stickX = Prefs.getInt(ctx, Prefs.JOYSTICK_X);
        stickY = Prefs.getInt(ctx, Prefs.JOYSTICK_Y);
        drawerX = Prefs.getInt(ctx, Prefs.DRAWER_X);
        drawerY = Prefs.getInt(ctx, Prefs.DRAWER_Y);
        stickHidden = !classic && Prefs.getBool(ctx, Prefs.DRAWER_HIDE_JOYSTICK);
        walkMode = Prefs.getBool(ctx, Prefs.TELEPORT_WALK_MODE);
        lastMockedTime = Prefs.getLong(ctx, Prefs.LAST_MOCKED_TIME);
        autoSuspendActive = cfg.autoSuspend;
        flows = new OverlayFlows(this, Prefs.getBool(ctx, Prefs.DISPLAY_DISTANCE_INFO));
    }

    // ------------------------------------------------------------------ ciclo de vida

    /** 10 §2.5: stick ⇔ v && !drawer_hide_joystick (e olho); gaveta ⇔ v. A 1ª chamada cria as janelas. */
    public void setVisible(boolean v) {
        if (destroyed) return;
        visible = v;
        if (stickWin == null) inflate();
        stickShown = show(stickWin, stickLp, stickShown, v && !stickHidden, true);
        if (drawerWin != null) drawerShown = show(drawerWin, drawerLp, drawerShown, v, false);
    }

    public boolean hasVisibleWindow() {
        return stickShown || drawerShown;
    }

    public void putSession(Bundle b) {
        b.putInt(Prefs.JOYSTICK_X, stickX);
        b.putInt(Prefs.JOYSTICK_Y, stickY);
        b.putInt(Prefs.DRAWER_X, drawerX);
        b.putInt(Prefs.DRAWER_Y, drawerY);
        b.putBoolean(Prefs.TELEPORT_WALK_MODE, walkMode);
    }

    /** Valor em memória usado no cooldown dos painéis (prefs no início; = agora ao abrir o Teleport). */
    public long lastMockedTime() {
        return lastMockedTime;
    }

    public void closePanels() {
        flows.close();
    }

    /** Remove tudo e solta as referências. Idempotente. */
    public void destroy() {
        if (destroyed) return;
        destroyed = true;
        flows.close();
        if (stick != null) stick.shutdown();
        removeDrop();
        if (stickShown) remove(stickWin);
        if (drawerShown) remove(drawerWin);
        stickShown = drawerShown = false;
        stickWin = drawerWin = null;
        stickLp = drawerLp = null;
        stick = null;
        column = routePill = recPill = speedPill = routeTop = routeRow = asBox = suspend = recMap = recSave = null;
        map = pause = rStop = eye = lock = speed = spWalk = spJog = spRun = null;
        progress = asNum = null;
    }

    // ------------------------------------------------------------------ estado vindo do serviço

    public void setRouteState(boolean hasRoute, boolean playing) {
        boolean started = hasRoute && !this.hasRoute;
        this.hasRoute = hasRoute;
        this.playing = playing;
        if (destroyed || stickWin == null) return;
        if (pause != null) pause.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        if (classic) {
            setShown(routeTop, hasRoute);
            setShown(routeRow, hasRoute || cfg.suspended);
            setShown(rStop, hasRoute);
            setShown(pause, hasRoute);
            refit();
        } else {
            if (!hasRoute) setShown(routePill, false);
            else if (started) expand(routePill);
            tintMap();
        }
    }

    public void setRouteProgress(int index1Based, int total) {
        if (destroyed || progress == null) return;
        progress.setText(ctx.getString(R.string.ov_route_progress, index1Based, total));
    }

    /** 1×/s em Suspended com auto suspend ativo. setTextColor só ao cruzar N < 6 (11 §2.6). */
    public void setAutoSuspendCountdown(int secondsLeft) {
        if (destroyed || asBox == null || !cfg.suspended || !autoSuspendActive) return;
        setShown(asBox, true);
        asNum.setText(ctx.getString(R.string.ov_seconds, secondsLeft));
        boolean red = secondsLeft < 6;
        if (red != asRed) {
            asRed = red;
            asNum.setTextColor(red ? ctx.getColor(R.color.danger) : 0xFFFFFFFF);
        }
    }

    /** (true,n) abre o layout de gravação venha de onde vier; (false,0) fecha. Submit e mapa só com n ≥ 2. */
    public void setRecording(boolean recording, int pointCount) {
        boolean started = recording && !this.recording;
        this.recording = recording;
        recCount = recording ? pointCount : 0;
        if (destroyed || recPill == null) return;
        showRecording(started || classic);
    }

    private void showRecording(boolean expandPill) {
        if (!recording) setShown(recPill, false);
        else if (expandPill) expand(recPill);
        setShown(recMap, recording && recCount >= 2);
        setShown(recSave, recording && recCount >= 2);
        refit();
    }

    /** Pino cai de −100 px até 0 em 500 ms (bounce) numa janela não tocável, removida 500 ms depois. */
    public void playRecordPointAnimation() {
        if (destroyed) return;
        removeDrop();
        FrameLayout root = new FrameLayout(ctx);
        ImageView pin = new ImageView(ctx);
        int s = ctx.getResources().getDimensionPixelSize(R.dimen.ov_drop);
        pin.setImageResource(R.drawable.ic_pin);
        pin.setImageTintList(ColorStateList.valueOf(ctx.getColor(R.color.success)));
        root.addView(pin, new FrameLayout.LayoutParams(s, s, Gravity.CENTER));
        WindowManager.LayoutParams lp = params(ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        lp.gravity = Gravity.CENTER;
        try {
            wm.addView(root, lp);
        } catch (RuntimeException e) {
            return;
        }
        dropWin = root;
        pin.setTranslationY(-100f);
        pin.animate().translationY(0f).setDuration(DROP_MS).setInterpolator(new BounceInterpolator())
                .withEndAction(() -> root.postDelayed(() -> { if (dropWin == root) removeDrop(); }, DROP_MS));
    }

    /** Velocidade trocada por intent (SPEED): só o ícone. */
    public void onSpeedChanged(float kmh) {
        if (destroyed || stickWin == null) return;
        showSpeed(kmh);
    }

    // ------------------------------------------------------------------ abrir fluxos

    /** INT_TELEPORT_UI: NaN,NaN = campo vazio. */
    public void openTeleport(double lat, double lng) {
        if (destroyed) return;
        flows.openTeleport(Double.isNaN(lat) || Double.isNaN(lng) ? "" : CoordParser.text(lat, lng));
    }

    /** INT_SAVE_LOCATION: seletor "Current Location". */
    public void openSave() {
        if (destroyed) return;
        flows.openSave();
    }

    boolean isDestroyed() {
        return destroyed;
    }

    // ------------------------------------------------------------------ cliques

    @Override
    public void onClick(View v) {
        if (destroyed) return;
        int id = v.getId();
        if (id == R.id.ov_handle) {
            setShown(column, column.getVisibility() != View.VISIBLE);
            refit();
        } else if (id == R.id.ov_map) {
            if (!classic && hasRoute) toggle(routePill);
            else host.onOpenMap();
        } else if (id == R.id.ov_r_stop) {
            host.onRouteStop();
        } else if (id == R.id.ov_r_pause) {
            host.onRoutePlayPause();
        } else if (id == R.id.ov_r_prev) {
            host.onRoutePrev();
        } else if (id == R.id.ov_r_next) {
            host.onRouteNext();
        } else if (id == R.id.ov_record) {
            if (recording) toggle(recPill);
            else host.onRecordStart();
        } else if (id == R.id.ov_rec_stop) {
            host.onRecordDiscard();
        } else if (id == R.id.ov_rec_add) {
            host.onRecordAdd();
        } else if (id == R.id.ov_rec_map) {
            host.onRecordOpenMap();
        } else if (id == R.id.ov_rec_save) {
            host.onRecordSubmit();
        } else if (id == R.id.ov_teleport) {
            flows.openTeleport("");
        } else if (id == R.id.ov_save) {
            flows.openSave();
        } else if (id == R.id.ov_share) {
            Ui.share(ctx, CoordParser.snippet(engine.lat(), engine.lng()));
        } else if (id == R.id.ov_eye) {
            stickHidden = !stickHidden;
            eye.setImageResource(stickHidden ? R.drawable.ic_visibility_off : R.drawable.ic_visibility);
            stickShown = show(stickWin, stickLp, stickShown, visible && !stickHidden, true);
        } else if (id == R.id.ov_lock) {
            stick.setLocked(!stick.isLocked());
            lock.setImageResource(stick.isLocked() ? R.drawable.ic_lock : R.drawable.ic_lock_open);
        } else if (id == R.id.ov_speed) {
            toggle(speedPill);
        } else if (id == R.id.ov_sp_walk) {
            pickSpeed(cfg.walkKmh);
        } else if (id == R.id.ov_sp_jog) {
            pickSpeed(cfg.jogKmh);
        } else if (id == R.id.ov_sp_run) {
            pickSpeed(cfg.runKmh);
        } else if (id == R.id.ov_suspend) {
            engine.injectNow();
        } else if (id == R.id.ov_as_close) {
            autoSuspendActive = false;
            engine.setAutoSuspendActive(false);
            setShown(asBox, false);
        }
    }

    private void pickSpeed(float kmh) {
        engine.setSpeedKmh(kmh);
        showSpeed(kmh);
        setShown(speedPill, false);
    }

    private void showSpeed(float kmh) {
        if (speed != null) {
            speed.setImageResource(kmh == cfg.runKmh ? R.drawable.ic_run
                    : kmh == cfg.jogKmh ? R.drawable.ic_jog : R.drawable.ic_walk);
        }
        if (spWalk != null) {
            spWalk.setSelected(kmh == cfg.walkKmh);
            spJog.setSelected(kmh == cfg.jogKmh && kmh != cfg.walkKmh);
            spRun.setSelected(kmh == cfg.runKmh && kmh != cfg.walkKmh && kmh != cfg.jogKmh);
        }
    }

    // ------------------------------------------------------------------ janelas

    private void inflate() {
        LayoutInflater inf = LayoutInflater.from(ctx);
        stickWin = inf.inflate(classic ? R.layout.ov_classic : R.layout.ov_stick, null);
        bind(stickWin);
        stick = stickWin.findViewById(R.id.ov_stick);
        stick.setup(engine, sizePct, classic);
        stickLp = params(ViewGroup.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        stickWin.findViewById(R.id.ov_move).setOnTouchListener(new Drag(stickWin, stickLp, true, false));
        if (!classic) {
            drawerWin = inf.inflate(R.layout.ov_drawer, null);
            bind(drawerWin);
            drawerLp = params(ViewGroup.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            drawerWin.findViewById(R.id.ov_handle).setOnTouchListener(new Drag(drawerWin, drawerLp, false, true));
            if (eye != null && stickHidden) eye.setImageResource(R.drawable.ic_visibility_off);
        }
        setShown(suspend, cfg.suspended);
        setShown(asBox, cfg.suspended && autoSuspendActive);
        showSpeed(engine.getSpeedKmh());
        setRouteState(hasRoute, playing);
        if (hasRoute && !classic) expand(routePill);
        showRecording(true);
    }

    /** Liga cliques e guarda as Views que existirem nesta janela. */
    private void bind(View w) {
        int[] ids = {R.id.ov_handle, R.id.ov_map, R.id.ov_r_stop, R.id.ov_r_pause, R.id.ov_r_prev, R.id.ov_r_next,
                R.id.ov_record, R.id.ov_rec_stop, R.id.ov_rec_add, R.id.ov_rec_map, R.id.ov_rec_save,
                R.id.ov_teleport, R.id.ov_save, R.id.ov_share, R.id.ov_eye, R.id.ov_lock, R.id.ov_speed,
                R.id.ov_sp_walk, R.id.ov_sp_jog, R.id.ov_sp_run, R.id.ov_suspend, R.id.ov_as_close};
        for (int id : ids) {
            View v = w.findViewById(id);
            if (v != null) v.setOnClickListener(this);
        }
        column = pick(w, R.id.ov_column, column);
        routePill = pick(w, R.id.ov_route_pill, routePill);
        recPill = pick(w, R.id.ov_rec_pill, recPill);
        speedPill = pick(w, R.id.ov_speed_pill, speedPill);
        routeTop = pick(w, R.id.ov_route_top, routeTop);
        routeRow = pick(w, R.id.ov_route_row, routeRow);
        asBox = pick(w, R.id.ov_as_box, asBox);
        suspend = pick(w, R.id.ov_suspend, suspend);
        recMap = pick(w, R.id.ov_rec_map, recMap);
        recSave = pick(w, R.id.ov_rec_save, recSave);
        map = pick(w, R.id.ov_map, map);
        pause = pick(w, R.id.ov_r_pause, pause);
        rStop = pick(w, R.id.ov_r_stop, rStop);
        eye = pick(w, R.id.ov_eye, eye);
        lock = pick(w, R.id.ov_lock, lock);
        speed = pick(w, R.id.ov_speed, speed);
        spWalk = pick(w, R.id.ov_sp_walk, spWalk);
        spJog = pick(w, R.id.ov_sp_jog, spJog);
        spRun = pick(w, R.id.ov_sp_run, spRun);
        progress = pick(w, R.id.ov_r_progress, progress);
        asNum = pick(w, R.id.ov_as_num, asNum);
    }

    @SuppressWarnings("unchecked")
    private static <T extends View> T pick(View w, int id, T current) {
        View v = w.findViewById(id);
        return v != null ? (T) v : current;
    }

    private WindowManager.LayoutParams params(int size, int flags) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(size, size, Ui.overlayWindowType(),
                flags | (secure ? WindowManager.LayoutParams.FLAG_SECURE : 0), PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.alpha = alpha;   // nunca View.setAlpha na raiz (1.4.2); sem FLAG_HARDWARE_ACCELERATED
        return lp;
    }

    /** Adiciona/remove a janela (escondida = fora do WindowManager, sem custo de desenho). */
    private boolean show(View win, WindowManager.LayoutParams lp, boolean shown, boolean want, boolean isStick) {
        if (win == null || shown == want) return shown;
        if (!want) {
            remove(win);
            return false;
        }
        place(win, lp, isStick);
        try {
            wm.addView(win, lp);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Posição salva, ou default medido (02 §3.13): fora da tela volta ao default. */
    private void place(View win, WindowManager.LayoutParams lp, boolean isStick) {
        win.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        readScreen();
        int maxX = Math.max(0, scr.x - win.getMeasuredWidth());
        int maxY = Math.max(0, scr.y - win.getMeasuredHeight());
        int x = isStick ? stickX : drawerX, y = isStick ? stickY : drawerY;
        if (x < 0 || x > maxX || y < 0 || y > maxY) {
            x = isStick ? maxX : 0;
            y = isStick ? Math.min(scr.y / 2, maxY) : Math.min(DRAWER_DEFAULT_Y, maxY);
        }
        lp.x = x;
        lp.y = y;
        savePos(isStick, x, y);
    }

    private void savePos(boolean isStick, int x, int y) {
        if (isStick) {
            stickX = x;
            stickY = y;
        } else {
            drawerX = x;
            drawerY = y;
        }
    }

    /** Tamanho real da tela agora (orientação atual). Não usa o wm/ctx temático: a Configuration dele fica congelada. */
    @SuppressWarnings("deprecation")
    private void readScreen() {
        display.getRealSize(scr);
    }

    /** Janela cresceu (pílula/linha nova): puxa de volta para dentro da tela. */
    private void refit() {
        if (stickShown) fit(stickWin, stickLp, true);
        if (drawerShown) fit(drawerWin, drawerLp, false);
    }

    private void fit(View win, WindowManager.LayoutParams lp, boolean isStick) {
        win.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        readScreen();
        int x = clamp(lp.x, 0, Math.max(0, scr.x - win.getMeasuredWidth()));
        int y = clamp(lp.y, 0, Math.max(0, scr.y - win.getMeasuredHeight()));
        if (x == lp.x && y == lp.y) return;
        lp.x = x;
        lp.y = y;
        savePos(isStick, x, y);
        try {
            wm.updateViewLayout(win, lp);
        } catch (RuntimeException ignored) {
        }
    }

    private void remove(View v) {
        if (v == null) return;
        try {
            wm.removeViewImmediate(v);
        } catch (RuntimeException ignored) {
        }
    }

    private void removeDrop() {
        View d = dropWin;
        dropWin = null;
        if (d != null) {
            d.animate().cancel();
            remove(d);
        }
    }

    /** Arrasto de janela (02 §3.13) com clamp; toque curto na alça clicável = performClick. */
    private final class Drag implements View.OnTouchListener {
        private final View win;
        private final WindowManager.LayoutParams lp;
        private final boolean isStick, clickable;
        private int x0, y0;
        private float rx0, ry0;

        Drag(View win, WindowManager.LayoutParams lp, boolean isStick, boolean clickable) {
            this.win = win;
            this.lp = lp;
            this.isStick = isStick;
            this.clickable = clickable;
        }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            if (destroyed) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    readScreen();   // uma vez por arrasto: MOVE sem alocação
                    x0 = lp.x;
                    y0 = lp.y;
                    rx0 = e.getRawX();
                    ry0 = e.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    lp.x = clamp(x0 + Math.round(e.getRawX() - rx0), 0, Math.max(0, scr.x - win.getWidth()));
                    lp.y = clamp(y0 + Math.round(e.getRawY() - ry0), 0, Math.max(0, scr.y - win.getHeight()));
                    try {
                        wm.updateViewLayout(win, lp);
                    } catch (RuntimeException ignored) {
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    savePos(isStick, lp.x, lp.y);   // já clampado (corrige o original)
                    if (clickable && Math.abs(lp.x - x0) < CLICK_SLOP_PX && Math.abs(lp.y - y0) < CLICK_SLOP_PX) {
                        v.performClick();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    savePos(isStick, lp.x, lp.y);
                    return true;
                default:
                    return false;
            }
        }
    }

    // ------------------------------------------------------------------ pílulas

    /** Só uma pílula aberta por vez na gaveta. */
    private void expand(View pill) {
        if (pill == null) return;
        if (!classic) {
            if (pill != routePill) setShown(routePill, false);
            if (pill != recPill) setShown(recPill, false);
            if (pill != speedPill) setShown(speedPill, false);
            setShown(column, true);
        }
        setShown(pill, true);
        refit();
    }

    private void toggle(View pill) {
        if (pill == null) return;
        if (pill.getVisibility() == View.VISIBLE) setShown(pill, false);
        else expand(pill);
    }

    /** Botão de mapa da gaveta: warning pausado, success andando, normal sem rota. */
    private void tintMap() {
        if (map == null) return;
        if (!hasRoute) {
            map.setBackgroundTintList(null);
            map.setImageTintList(ColorStateList.valueOf(ctx.getColor(R.color.primary)));
        } else {
            map.setBackgroundTintList(ColorStateList.valueOf(ctx.getColor(playing ? R.color.success : R.color.warning)));
            map.setImageTintList(ColorStateList.valueOf(0xFFFFFFFF));
        }
    }

    private static void setShown(View v, boolean shown) {
        if (v == null) return;
        int vis = shown ? View.VISIBLE : View.GONE;
        if (v.getVisibility() != vis) v.setVisibility(vis);
    }

    private static int clamp(int v, int min, int max) {
        return v < min ? min : Math.min(v, max);
    }
}
