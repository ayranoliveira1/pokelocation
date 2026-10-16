package com.pokelocation.app.engine;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.pokelocation.app.data.Prefs;
import com.pokelocation.app.geo.Geo;
import com.pokelocation.app.util.Perms;

/**
 * Motor de mock (10 §3.1 + 11 §5). Roda na main thread do :overlay, num único Handler reagendado por
 * postAtTime(alvo += intervalo). Estado em primitivos: tick e setStick não alocam.
 * Não é final só para o teste JVM sobrescrever os ganchos package-private (relógio, agenda, saída).
 */
public class MockEngineImpl implements MockEngine {
    private static final long SAVE_LIVE_MS = 5000;
    private static final long MOCK_CHECK_MS = 5000;

    private final EngineConfig cfg;
    private final Context app;                 // null no teste JVM
    private final LocationInjector injector;   // null no teste JVM
    private final Handler handler;             // null no teste JVM
    private final Runnable loop = this::onLoop;
    private final RoutePlayer route = new RoutePlayer();
    private final double[] tmp = new double[2];

    private Listener listener;
    private SuspendedPublisher publisher;
    private Runnable onMockLost;
    private boolean started, playing, autoSuspendActive, registered;
    private long target, lastTick, lastSave, lastMockCheck;
    private int accUpd, accIdle, freezeTicks, countdown;

    private double lat, lng, alt = Double.NaN;            // posição lógica
    private double repLat, repLng, repAlt = Double.NaN;   // posição publicada
    private double reachedLat, reachedLng, reachedAlt;    // último waypoint alcançado (base da interpolação de alt)
    private boolean hasReached;

    private float angle, mag, speedKmh, speedMs, accuracy, bearing;
    private boolean hasSpeed, hasBearing;

    public MockEngineImpl(Context c, EngineConfig cfg) {
        this(cfg, c.getApplicationContext(),
                new LocationInjector(c.getApplicationContext(), cfg.indirect, cfg.agpsReset, cfg.privacyModeEnabled),
                new Handler(Looper.getMainLooper()));
    }

    /** Teste JVM: sem Context, injetor nem Handler; o teste sobrescreve os ganchos. */
    MockEngineImpl(EngineConfig cfg) {
        this(cfg, null, null, null);
    }

    private MockEngineImpl(EngineConfig cfg, Context app, LocationInjector injector, Handler handler) {
        this.cfg = cfg;
        this.app = app;
        this.injector = injector;
        this.handler = handler;
        speedKmh = cfg.walkKmh;
        accuracy = cfg.minIdleAcc;
        autoSuspendActive = cfg.autoSuspend;
    }

    // --- ganchos (sobrescritos no teste JVM) ---
    long uptime() { return SystemClock.uptimeMillis(); }
    long wallTime() { return System.currentTimeMillis(); }
    void scheduleAt(long uptimeMs) { handler.postAtTime(loop, uptimeMs); }
    void cancelSchedule() { handler.removeCallbacks(loop); }
    boolean registerProviders() { return injector.register(); }
    boolean mockAllowed() { return Perms.isMockApp(app); }
    void unregisterProviders() { injector.unregister(); }
    void saveLive(double la, double ln, double al) { Prefs.saveLive(app, la, ln, al); }
    void publish(double la, double ln, double al, float sp, boolean hs, float acc, float br, boolean hb) {
        injector.publish(la, ln, al, sp, hs, acc, br, hb);
    }

    /** Ticks sem speed após teleporte (10 §3.1 fato 1). */
    static int freezeTicks(int processMs) {
        if (processMs < 1000) return (1000 / processMs) * 3;
        if (processMs < 3000) return 3 - processMs / 1000;
        return 0;
    }

    // --- ciclo de vida ---
    @Override public void setListener(Listener l) { listener = l; }
    @Override public void setSuspendedPublisher(SuspendedPublisher p) { publisher = p; }

    /** Chamado uma vez, na main thread, se o registro falhar ou a permissão de localização simulada sumir. */
    public void setOnMockLost(Runnable r) { onMockLost = r; }

    @Override
    public void start(double lat, double lng, double alt) {
        if (started) return;
        started = true;
        this.lat = repLat = lat;
        this.lng = repLng = lng;
        this.alt = repAlt = alt;
        registered = registerProviders();
        target = lastMockCheck = uptime();
        if (cfg.suspended) {
            // 11 §2.3: sem injeção imediata; a primeira vem do primeiro gatilho.
            countdown = suspendInterval();
            notifyCountdown();
            target += 1000;
        }
        scheduleAt(target);
    }

    @Override
    public void shutdown() {
        if (!started) return;
        started = false;
        cancelSchedule();
        unregisterProviders();
    }

    void onLoop() {
        if (!started) return;
        long now = uptime();
        if (onMockLost != null && (!registered || now - lastMockCheck >= MOCK_CHECK_MS)) {
            lastMockCheck = now;
            if (!registered || !mockAllowed()) {
                Runnable r = onMockLost;
                onMockLost = null;
                r.run();
                return;
            }
        }
        if (cfg.suspended) countdownStep(); else tick();
        if (!started) return;   // um callback pode ter chamado shutdown()
        long step = cfg.suspended ? 1000 : cfg.processMs;
        target += step;
        if (target <= now) target = now + step;   // aparelho dormiu: realinha sem rajada de ticks
        scheduleAt(target);
    }

    /** 11 §2.4: passo de 1 s da contagem "Auto suspend in N s". */
    private void countdownStep() {
        boolean moving = mag != 0f || (playing && route.active());
        if (--countdown > 0) {
            notifyCountdown();
            if (moving) tick();
        } else {
            // fim: injeção automática; com o stick em uso o original também injeta no reinício (onTick inicial).
            if (autoSuspendActive || moving) tick();
            countdown = suspendInterval();
            notifyCountdown();
        }
    }

    private int suspendInterval() {
        return Math.max(5, Math.min(3600, cfg.autoSuspendIntervalS));
    }

    private void notifyCountdown() {
        if (autoSuspendActive && listener != null) listener.onAutoSuspendCountdown(countdown);
    }

    // --- tick (10 §3.1 com as correções do PLAN W1 tarefa 2) ---
    private void tick() {
        final int a = cfg.processMs, b = cfg.updateMs, c = cfg.idleMs;
        if (accUpd >= b) accUpd = 0;
        if (accIdle >= c) accIdle = 0;
        accUpd += a;
        accIdle += a;
        final boolean due = accUpd >= b;
        final boolean routing = playing && route.active();

        if (!routing) {
            if (mag == 0f) {
                hasSpeed = true;
                speedMs = 0f;
                if (accIdle >= c) {
                    double d = cfg.idleOffsetM;
                    Geo.offset(lat, lng, Geo.random(-d, d), Geo.random(0, 360), tmp);
                    repLat = tmp[0];
                    repLng = tmp[1];
                    repAlt = altOr0(alt) + noise(cfg.altOffsetM);
                }
                if (due) accuracy = (float) Geo.random(cfg.minIdleAcc, cfg.maxIdleAcc);
            } else {
                hasBearing = true;
                if (due) step(mag, Geo.stickToCompass(angle), false);
            }
        } else {
            hasBearing = true;
            if (!hasReached) markReached();
            int ni = route.nextIndex();
            double tl = route.lat(ni), tg = route.lng(ni);
            if (Geo.distance(lat, lng, tl, tg) - (b / 1000.0) * (speedKmh / 3.6) > 0) {
                if (due) step(1f, Geo.bearing(lat, lng, tl, tg), true);
            } else if (due) {
                arrive(false);   // snap no waypoint
            }
        }

        if (freezeTicks > 0) {
            hasSpeed = false;
            freezeTicks--;
        }
        lastTick = wallTime();
        if (cfg.suspended) {
            if (publisher != null) publisher.publishSuspended(repLat, repLng, altOr0(repAlt), speedMs, accuracy, bearing);
        } else {
            publish(repLat, repLng, altOr0(repAlt), speedMs, hasSpeed, accuracy, bearing, hasBearing);
        }
        if (cfg.saveLocation && due && (routing || mag != 0f) && lastTick - lastSave >= SAVE_LIVE_MS) {
            lastSave = lastTick;
            saveLive(lat, lng, alt);
        }
    }

    /** Um passo de movimento (só em tick due). */
    private void step(float k, double baseBearing, boolean toTarget) {
        double dt = cfg.updateMs / 1000.0;
        double noiseM = Geo.random(-cfg.speedOffsetKmh, cfg.speedOffsetKmh) / 3.6 * dt;
        double br = baseBearing + noise(cfg.headingOffsetDeg);
        Geo.offset(lat, lng, dt * (speedKmh / 3.6) * k + noiseM, br, tmp);
        double nl = tmp[0], ng = tmp[1];

        boolean altFromTarget = false;
        if (toTarget) {
            int ni = route.nextIndex();
            double ta = route.alt(ni);
            if (!Double.isNaN(ta)) {
                altFromTarget = true;
                if (hasReached && !Double.isNaN(reachedAlt) && !Double.isNaN(alt)) {
                    double tl = route.lat(ni), tg = route.lng(ni);
                    double full = Geo.distance(reachedLat, reachedLng, tl, tg);
                    double f = full > 0 ? Math.min(1.0, Geo.distance(nl, ng, tl, tg) / full) : 0;
                    alt = ta + (reachedAlt - ta) * f + noise(cfg.altOffsetM);
                } else {
                    alt = ta + noise(cfg.altOffsetM);
                }
            }
        }
        lat = repLat = nl;
        lng = repLng = ng;
        // Correção do drift (10 §3.1 fato 3): ruído sobre pos.alt, nunca sobre a alt publicada anterior.
        repAlt = altFromTarget ? alt : altOr0(alt) + noise(cfg.altOffsetM);

        hasSpeed = true;
        speedMs = (float) Math.max(0, noiseM / dt + (speedKmh / 3.6) * k);
        br %= 360;
        bearing = (float) (br < 0 ? br + 360 : br);
        accuracy = (float) Geo.random(cfg.minMovingAcc, cfg.maxMovingAcc);
    }

    /** Snap em next e avança (04 §3.4 arrive). push = injetar já (fast forward). */
    private void arrive(boolean push) {
        snapTo(route.nextIndex());
        if (route.arrive()) {
            markReached();
            if (listener != null) listener.onRouteProgress(route.progress1(), route.total());
        } else {
            endRoute();
        }
        if (push) tick();
    }

    private void snapTo(int i) {
        lat = repLat = route.lat(i);
        lng = repLng = route.lng(i);
        double a = route.alt(i);
        if (!Double.isNaN(a)) alt = a;
        repAlt = alt;
    }

    private void markReached() {
        reachedLat = lat;
        reachedLng = lng;
        reachedAlt = alt;
        hasReached = true;
    }

    private void endRoute() {
        route.clear();
        playing = false;
        hasReached = false;
        saveLive(lat, lng, alt);
        if (listener != null) listener.onRouteStateChanged(false, false);
    }

    private void notifyState() {
        if (listener != null) listener.onRouteStateChanged(route.active(), playing && route.active());
    }

    /** Teleporte interno: freeze de speed, pos = rep = destino, tick imediato em suspended. */
    private void setTeleport(double la, double ln, double al) {
        freezeTicks = freezeTicks(cfg.processMs);
        lat = repLat = la;
        lng = repLng = ln;
        if (!Double.isNaN(al)) alt = al;
        repAlt = alt;
        saveLive(lat, lng, alt);   // eventos discretos gravam mesmo sem save_location: é por este arquivo que o main vê a posição
        if (cfg.suspended && started) tick();
    }

    private static double noise(double range) { return Geo.random(-range, range); }
    private static double altOr0(double a) { return Double.isNaN(a) ? 0 : a; }

    // --- entrada ---
    @Override
    public void setStick(float angleDeg, float magnitude) {
        boolean wasMoving = mag != 0f;
        angle = angleDeg;
        mag = !(magnitude > 0f) ? 0f : Math.min(magnitude, 1f);
        if (wasMoving && mag == 0f) saveLive(lat, lng, alt);
    }

    @Override public void setSpeedKmh(float kmh) { if (kmh > 0) speedKmh = kmh; }
    @Override public float getSpeedKmh() { return speedKmh; }

    @Override
    public void teleport(double lat, double lng, double alt) {
        if (playing && route.active()) {
            playing = false;
            notifyState();
        }
        setTeleport(lat, lng, alt);
    }

    @Override
    public void setAltitude(double a) {
        if (Double.isNaN(a)) return;
        alt = repAlt = a;
    }

    // --- rota ---
    @Override
    public void startRoute(double[] pts, int routeMode, boolean teleportToStart) {
        if (pts == null || pts.length < 3) return;
        route.start(pts, routeMode);
        playing = true;
        hasReached = false;
        notifyState();
        if (listener != null) listener.onRouteProgress(1, route.total());
        if (teleportToStart) setTeleport(pts[0], pts[1], pts[2]);
    }

    @Override
    public void resumeRoute(double[] pts, int routeMode, boolean reversed, int nextIndex) {
        if (pts == null || pts.length < 3) return;
        route.resume(pts, routeMode, reversed, nextIndex);
        playing = true;
        hasReached = false;
        notifyState();
        if (listener != null) listener.onRouteProgress(route.progress1(), route.total());
    }

    @Override
    public void setRoutePlaying(boolean p) {
        if (!route.active()) return;
        playing = p;
        notifyState();
        if (!p) saveLive(lat, lng, alt);
    }

    @Override
    public boolean next() {
        if (!route.active()) return false;
        resumeIfPaused();
        arrive(true);
        return true;
    }

    @Override
    public boolean previous() {
        if (!route.active()) return false;
        int c = route.cur();
        boolean atCur = c >= 0 && lat == route.lat(c) && lng == route.lng(c);
        snapTo(route.previous(atCur));
        markReached();
        resumeIfPaused();
        if (listener != null) listener.onRouteProgress(route.progress1(), route.total());
        tick();
        return true;
    }

    private void resumeIfPaused() {
        if (playing) return;
        playing = true;
        notifyState();
    }

    @Override
    public void stopRoute() {
        endRoute();
    }

    @Override public boolean hasRoute() { return route.active(); }
    @Override public boolean isRoutePlaying() { return playing && route.active(); }
    @Override public int routeIndex() { return route.active() ? route.cur() : -1; }
    @Override public int routeTotal() { return route.total(); }
    @Override public int routeRemaining() { return route.remaining(); }
    @Override public int routeMode() { return route.mode(); }
    @Override public boolean isReversed() { return route.reversed(); }
    @Override public int nextIndex() { return route.active() ? route.nextIndex() : -1; }
    @Override public double[] routePoints() { return route.points(); }

    // --- leitura ---
    @Override public double lat() { return lat; }
    @Override public double lng() { return lng; }
    @Override public double alt() { return alt; }
    @Override public float bearing() { return bearing; }
    @Override public float speedMs() { return speedMs; }
    @Override public float accuracy() { return accuracy; }
    @Override public long lastTickTime() { return lastTick; }

    // --- suspended (sem efeito fora dele) ---
    @Override
    public void injectNow() {
        if (cfg.suspended && started) tick();
    }

    @Override
    public void setAutoSuspendActive(boolean active) {
        autoSuspendActive = active;
    }
}
