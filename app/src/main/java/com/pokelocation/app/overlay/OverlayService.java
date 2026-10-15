package com.pokelocation.app.overlay;

import android.app.ActivityManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;

import com.pokelocation.app.Io;
import com.pokelocation.app.data.Db;
import com.pokelocation.app.data.Prefs;
import com.pokelocation.app.data.RouteDao;
import com.pokelocation.app.engine.EngineConfig;
import com.pokelocation.app.engine.MockEngine;
import com.pokelocation.app.engine.MockEngineImpl;
import com.pokelocation.app.social.chat.Chat;
import com.pokelocation.app.social.loc.LocationSharer;
import com.pokelocation.app.geo.CoordParser;
import com.pokelocation.app.ipc.Intents;
import com.pokelocation.app.mapoverlay.MapOverlayService;
import com.pokelocation.app.model.Coord;
import com.pokelocation.app.model.MapOptions;
import com.pokelocation.app.model.Route;
import com.pokelocation.app.net.Elevation;
import com.pokelocation.app.overlay.svc.Actions;
import com.pokelocation.app.overlay.ui.OverlayView;
import com.pokelocation.app.util.Perms;
import com.pokelocation.app.util.Themes;
import com.pokelocation.app.util.Ui;

/**
 * FGS do processo :overlay (F65, N01, N03, N14). Dono do estado da sessão: rota (via motor), gravação, hidden,
 * posição anterior, mapa e notificação. Motor, injetor e janelas só nascem no start: STATUS/STOP frios não os criam.
 */
public final class OverlayService extends Service
        implements MockEngine.Listener, MockEngine.SuspendedPublisher, OverlayView.Host {

    private EngineConfig cfg;
    private MapOptions opts;
    private OverlayNotification notif;
    private MockEngineImpl engine;
    private OverlayView ui;
    private LocationSharer sharer;
    private BroadcastReceiver unreadRx;
    private Intent mockIntent;

    private boolean fgFailed, running, hidden, mapOpen, sessionSent, destroyed;
    private boolean hasPrev;
    private double prevLat, prevLng, prevAlt;
    private boolean recording;
    private double[] rec;
    private int elevSeq;

    @Override
    public void onCreate() {
        super.onCreate();
        cfg = EngineConfig.load(this);
        opts = MapOptions.load(this);
        notif = new OverlayNotification(this);
        try {
            notif.startForeground(this);
        } catch (RuntimeException e) { // Android 14+: FGS em segundo plano recusado (N14)
            fgFailed = true;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (intent != null && !Intents.trusted(this, intent)) {
            if (!running) stopSelfResult(startId);
            return running ? START_STICKY : START_NOT_STICKY;
        }
        if (fgFailed) {
            if (action == null) {
                if (intent != null) openMain(Intents.INT_REQUEST_PERMISSIONS);
            } else {
                Actions.cold(this, intent, action);
            }
            stopSelf();
            return START_NOT_STICKY;
        }
        if (action == null) {
            start(intent);
            return START_STICKY;
        }
        if (!running) {
            // F97 sem passar pela Main (QA F102: sem o 2º fork do :overlay e ACTIVE na hora) quando nada falta;
            // senão a Main faz as checagens e reenvia (replay).
            if (Actions.wantsStart(intent, action) && Perms.canDrawOverlays(this) && Perms.hasFine(this)
                    && Perms.isMockApp(this)) {
                boolean tp = Intents.ACTION_TELEPORT.equals(action);
                start(tp ? Intents.getDouble(intent, Intents.EXTRA_LAT, Double.NaN) : Double.NaN,
                        tp ? Intents.getDouble(intent, Intents.EXTRA_LNG, Double.NaN) : Double.NaN,
                        tp ? Intents.getDouble(intent, Intents.EXTRA_ALT, Double.NaN) : Double.NaN,
                        Prefs.getBool(this, Prefs.HIDE_JOYSTICK), null, false);
                if (running) {
                    if (!tp) Actions.handle(this, intent, action);
                    return START_STICKY;
                }
            }
            Actions.cold(this, intent, action);
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        if (!handleInternal(intent, action)) Actions.handle(this, intent, action);
        return START_STICKY;
    }

    // ---------------------------------------------------------------- start / stop

    /** Start da Main (action null). intent null = restart do sistema (START_STICKY). */
    private void start(Intent i) {
        if (i == null) {
            start(Double.NaN, Double.NaN, Double.NaN, Prefs.getBool(this, Prefs.HIDE_JOYSTICK), null, false);
            return;
        }
        start(Intents.getDouble(i, Intents.EXTRA_START_LAT, Double.NaN),
                Intents.getDouble(i, Intents.EXTRA_START_LNG, Double.NaN),
                Intents.getDouble(i, Intents.EXTRA_START_ALT, Double.NaN),
                Intents.getBool(i, Intents.EXTRA_START_HIDDEN, false),
                i.getStringExtra(Intents.EXTRA_START_ROUTE_ID),
                Intents.getBool(i, Intents.EXTRA_RECORD_ROUTE, false));
    }

    /** lat/lng NaN = última posição (Prefs.lastPosition). */
    private void start(double lat, double lng, double alt, boolean hide, String routeId, boolean record) {
        if (running) return;
        if (!Perms.canDrawOverlays(this) || !Perms.hasFine(this)) {
            openMain(Intents.INT_REQUEST_PERMISSIONS);
            stopSelf();
            return;
        }
        if (Double.isNaN(lat) || Double.isNaN(lng)) {
            Coord c = Prefs.lastPosition(this);
            lat = c == null ? 0 : c.lat;
            lng = c == null ? 0 : c.lng;
            alt = c == null ? Double.NaN : c.alt;
        }
        hidden = hide;

        engine = new MockEngineImpl(this, cfg);
        engine.setListener(this);
        engine.setSuspendedPublisher(this);
        ui = new OverlayView(Themes.overlayContext(this), engine, cfg, this);
        engine.start(lat, lng, alt);
        ui.setVisible(!hidden);
        running = true;
        // Sem login nenhuma classe social carrega; login no meio só vale na próxima sessão do joystick.
        if (getDatabasePath("social.db").exists()) {
            sharer = new LocationSharer(this, engine);
            sharer.start();
            ui.setChatEnabled(true);
            listenUnread();
            Context app = getApplicationContext();
            Io.run(() -> Chat.unreadTotal(app), n -> { if (ui != null) ui.setUnread(n); });
        }
        notif.setHidden(hidden);
        Intents.broadcastSpoofState(this, true, null);
        if (Double.isNaN(alt)) fetchElevation(lat, lng);

        if (routeId != null && !routeId.isEmpty()) {
            Io.run(() -> RouteDao.get(getApplicationContext(), routeId), r -> {
                if (running && r != null && r.pts != null && Route.count(r.pts) > 0) startRouteFromList(r.pts);
            });
        } else if (record) {
            startRecording(lat, lng, alt);
        }
    }

    /** Stop (INT_STOP_JOYSTICK, ACTION_STOP, notificação): sessão → main e encerra. */
    public void stopSpoof() {
        sendSession();
        stopSelf();
    }

    private void sendSession() {
        if (sessionSent || engine == null) return;
        sessionSent = true;
        Bundle b = new Bundle();
        b.putDouble(Prefs.LAST_LATITUDE, engine.lat());
        b.putDouble(Prefs.LAST_LONGITUDE, engine.lng());
        b.putDouble(Prefs.LAST_ALTITUDE, engine.alt());
        b.putLong(Prefs.LAST_MOCKED_TIME, System.currentTimeMillis());
        ui.putSession(b);
        opts.toBundle(b);
        Intents.broadcastSpoofState(this, false, b);
    }

    /** Só enquanto vivo, e só o próprio app (permissão signature): mensagem do chat nunca sobe o serviço. */
    private void listenUnread() {
        unreadRx = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                if (ui == null) return;
                if (i.getBooleanExtra(Intents.EXTRA_LOGGED_OUT, false)) ui.setChatEnabled(false);
                else {
                    ui.setChatEnabled(true); // login de novo depois de um logout nesta sessão
                    ui.setUnread(i.getIntExtra(Intents.EXTRA_UNREAD, 0));
                    ui.chatChanged();
                }
            }
        };
        IntentFilter f = new IntentFilter(Intents.INT_CHAT_UNREAD);
        String perm = getPackageName() + ".permission.INTERNAL";
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(unreadRx, f, perm, null, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(unreadRx, f, perm, null);
    }

    @Override
    public void onDestroy() {
        if (destroyed) return;
        destroyed = true;
        sendSession(); // parada pelo sistema/stopService externo: a sessão não se perde
        running = false;
        notif.release();
        if (sharer != null) sharer.stop();
        if (unreadRx != null) {
            unregisterReceiver(unreadRx);
            unreadRx = null;
        }
        if (engine != null) engine.shutdown();
        if (ui != null) ui.destroy();
        try {
            stopService(new Intent(this, MapOverlayService.class));
        } catch (RuntimeException ignored) {
        }
        Db.close();
        super.onDestroy();
        Process.killProcess(Process.myPid());
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---------------------------------------------------------------- ações internas

    private boolean handleInternal(Intent i, String a) {
        switch (a) {
            case Intents.INT_STOP_JOYSTICK:
                stopSpoof();
                return true;
            case Intents.INT_HIDE:
                setHidden(Intents.getBool(i, Intents.EXTRA_HIDE, !hidden));
                return true;
            case Intents.INT_SHARE:
                share();
                return true;
            case Intents.INT_TELEPORT_UI:
                closeMap();
                ui.openTeleport(Intents.getDouble(i, Intents.EXTRA_LAT, Double.NaN),
                        Intents.getDouble(i, Intents.EXTRA_LNG, Double.NaN),
                        Intents.getBool(i, Intents.EXTRA_FROM_CHAT, false) ? getString(com.pokelocation.app.R.string.soc_teleport_title) : null);
                return true;
            case Intents.INT_SAVE_LOCATION:
                closeMap();
                ui.openSave();
                return true;
            case Intents.INT_ROUTE_ACTION:
                closeMap();
                switch (Intents.getInt(i, Intents.EXTRA_ROUTE_ACTION, -1)) {
                    case Intents.ROUTE_ACTION_STOP: engine.stopRoute(); break;
                    case Intents.ROUTE_ACTION_PAUSE: engine.setRoutePlaying(false); break;
                    case Intents.ROUTE_ACTION_RESUME: if (engine.hasRoute()) engine.setRoutePlaying(true); break;
                    case Intents.ROUTE_ACTION_NEXT: engine.next(); break;
                    default: break;
                }
                return true;
            case Intents.INT_OPEN_MAP:
                onOpenMap();
                return true;
            case Intents.INT_RETURN_FROM_MAP:
                returnFromMap(i);
                return true;
            default:
                return false;
        }
    }

    private void openMain(String action) {
        try {
            startActivity(Intents.auth(this, Intents.main(this).setAction(action)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)));
        } catch (RuntimeException ignored) {
        }
    }

    // ---------------------------------------------------------------- estado usado por svc/Actions

    public boolean isRunning() { return running; }
    public boolean isHidden() { return hidden; }
    public boolean isRecording() { return recording; }
    public int recordCount() { return recording ? Route.count(rec) : 0; }
    public MockEngine engine() { return engine; }
    public MapOptions mapOptions() { return opts; }

    public void setHidden(boolean h) {
        hidden = h;
        ui.setVisible(!h);
        notif.setHidden(h);
        if (!h) notif.clearInject();
    }

    public void setSpeed(float kmh) {
        engine.setSpeedKmh(kmh);
        ui.onSpeedChanged(kmh);
    }

    public void share() {
        closeMap();
        Ui.share(this, CoordParser.snippet(engine.lat(), engine.lng()));
    }

    /** TELEPORT, FAVORITES, mapa, painel. O motor pausa a rota se houver. alt NaN → elevação. */
    public void teleportTo(double lat, double lng, double alt) {
        savePrev();
        closeMap();
        engine.teleport(lat, lng, alt);
        if (Double.isNaN(alt)) fetchElevation(lat, lng);
    }

    /** ROUTE, WALK, START do mapa: route_start_mode 0 teleporta para o 1º ponto. */
    public void startRouteFromList(double[] pts) {
        closeMap();
        engine.startRoute(pts, opts.routeMode, opts.routeStartMode == 0);
    }

    /** GENERATE: começa a andar sem teleporte. */
    public void startGenerated(double[] pts) {
        closeMap();
        engine.startRoute(pts, opts.routeMode, false);
    }

    private void walkTo(double lat, double lng, double alt) {
        savePrev();
        closeMap();
        engine.stopRoute();
        opts.routeMode = MockEngine.ROUTE_STOP;
        engine.startRoute(new double[]{engine.lat(), engine.lng(), engine.alt(), lat, lng, alt},
                MockEngine.ROUTE_STOP, false);
    }

    private void savePrev() {
        hasPrev = true;
        prevLat = engine.lat();
        prevLng = engine.lng();
        prevAlt = engine.alt();
    }

    private void fetchElevation(double lat, double lng) {
        if (!Prefs.getBool(this, Prefs.ELEVATION_LOOKUP_ENABLED)) return;
        final int seq = ++elevSeq;
        Io.run(() -> Elevation.fetch(getApplicationContext(), lat, lng), v -> {
            if (seq == elevSeq && engine != null && running && v != null && !Double.isNaN(v)) engine.setAltitude(v);
        });
    }

    // ---------------------------------------------------------------- gravação (F50, F100)

    public void startRecording(double lat, double lng, double alt) {
        if (engine.hasRoute()) engine.stopRoute();
        rec = new double[]{lat, lng, alt};
        recording = true;
        closeMap();
        ui.setRecording(true, 1);
    }

    public void addRecordPoint() {
        rec = Route.append(rec, engine.lat(), engine.lng(), engine.alt());
        ui.setRecording(true, Route.count(rec));
        ui.playRecordPointAnimation();
    }

    public void discardRecording() {
        recording = false;
        rec = null;
        ui.setRecording(false, 0);
    }

    /** Exige ≥ 2 pontos (checado por quem chama). RECORD_SAVED ou ERROR SAVE_FAILED para o request. */
    public void saveRecording(String name, Intent request) {
        final double[] pts = rec;
        final int n = Route.count(pts);
        discardRecording();
        Io.run(() -> RouteDao.insert(getApplicationContext(), name, null, pts), new Io.Callback<Route>() {
            @Override public void onResult(Route saved) {
                Intent r = new Intent(Intents.BC_RECORD_SAVED).putExtra(Intents.EXTRA_NAME, name)
                        .putExtra(Intents.EXTRA_POINT_COUNT, n);
                Intents.reply(OverlayService.this, request, r);
            }
            @Override public void onError(Throwable t) {
                Intents.broadcastError(OverlayService.this, request, Intents.ERR_SAVE_FAILED, String.valueOf(t.getMessage()));
            }
        });
    }

    // ---------------------------------------------------------------- ponte :map (F51)

    private void openMap(int mapAction) {
        if (mapOpen && mapAlive()) return;
        mapOpen = false; // :map morreu sem RETURN_FROM_MAP (crash/kill): reabre direto
        if (engine.isRoutePlaying()) engine.setRoutePlaying(false);
        ui.closePanels();
        boolean route = engine.hasRoute();
        final double[] pts = route ? engine.routePoints() : (recording ? rec : null);
        final Intent m = new Intent(this, MapOverlayService.class).setAction(Intents.INT_START_MAP)
                .putExtra(Intents.EXTRA_CUR_LAT, engine.lat())
                .putExtra(Intents.EXTRA_CUR_LNG, engine.lng())
                .putExtra(Intents.EXTRA_CUR_ALT, engine.alt())
                .putExtra(Intents.EXTRA_NEXT_INDEX, route ? engine.nextIndex() : -1)
                .putExtra(Intents.EXTRA_REVERSED, route && engine.isReversed())
                .putExtra(Intents.EXTRA_ROUTE_MODE, route ? engine.routeMode() : opts.routeMode)
                .putExtra(Intents.EXTRA_MAP_ACTION, mapAction)
                .putExtra(Prefs.LAST_MOCKED_TIME, ui.lastMockedTime());
        Bundle s = new Bundle();
        ui.putSession(s);
        m.putExtra(Prefs.TELEPORT_WALK_MODE, s.getBoolean(Prefs.TELEPORT_WALK_MODE, false));
        opts.toIntent(m);
        mapOpen = true;
        if (pts == null || Route.count(pts) <= Intents.INLINE_POINTS_MAX) {
            if (pts != null) Intents.putPoints(this, m, pts, null); // inline: não toca o banco nem sobe o Io
            startMap(m);
        } else {
            Io.run(() -> {
                Intents.putPoints(getApplicationContext(), m, pts, null);
                return m;
            }, new Io.Callback<Intent>() {
                @Override public void onResult(Intent v) { if (mapOpen && running) startMap(v); }
                @Override public void onError(Throwable t) { mapOpen = false; }
            });
        }
    }

    private void startMap(Intent m) {
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(m);
            else startService(m);
        } catch (RuntimeException e) {
            mapOpen = false;
        }
    }

    /** Só no toque do mapa (não por tick): o :map pode ter morrido sem RETURN_FROM_MAP. */
    @SuppressWarnings("deprecation")
    private boolean mapAlive() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            String name = MapOverlayService.class.getName();
            for (ActivityManager.RunningServiceInfo s : am.getRunningServices(100)) {
                if (name.equals(s.service.getClassName())) return true;
            }
        } catch (RuntimeException ignored) {
        }
        return false;
    }

    /** Fecha pelo overlay: o :map só destrói e mata o processo (sem RETURN). */
    private void closeMap() {
        if (!mapOpen) return;
        mapOpen = false;
        try {
            stopService(new Intent(this, MapOverlayService.class));
        } catch (RuntimeException ignored) {
        }
    }

    private void returnFromMap(Intent i) {
        mapOpen = false;
        opts = MapOptions.fromIntent(i, opts);
        final int action = Intents.getInt(i, Intents.EXTRA_MAP_ACTION, Intents.MAP_NONE);
        final double lat = Intents.getDouble(i, Intents.EXTRA_CUR_LAT, Double.NaN);
        final double lng = Intents.getDouble(i, Intents.EXTRA_CUR_LNG, Double.NaN);
        final double alt = Intents.getDouble(i, Intents.EXTRA_CUR_ALT, Double.NaN);
        final int mode = Intents.getInt(i, Intents.EXTRA_ROUTE_MODE, opts.routeMode);
        final boolean reversed = Intents.getBool(i, Intents.EXTRA_REVERSED, false);
        final int next = Intents.getInt(i, Intents.EXTRA_NEXT_INDEX, 0);
        boolean needsPts = action == Intents.MAP_START || action == Intents.MAP_RESUME || action == Intents.MAP_RESUME_RECORD;
        if (!needsPts) {
            applyMap(action, null, lat, lng, alt, mode, reversed, next);
        } else if (!i.hasExtra(Intents.EXTRA_POINTS_ROUTE_ID)) {
            applyMap(action, Intents.getPoints(this, i), lat, lng, alt, mode, reversed, next);
        } else {
            Io.run(() -> Intents.getPoints(getApplicationContext(), i),
                    pts -> { if (running) applyMap(action, pts, lat, lng, alt, mode, reversed, next); });
        }
    }

    private void applyMap(int action, double[] pts, double lat, double lng, double alt, int mode, boolean reversed, int next) {
        boolean hasPts = pts != null && Route.count(pts) > 0;
        boolean hasCur = !Double.isNaN(lat) && !Double.isNaN(lng);
        switch (action) {
            case Intents.MAP_START:
                if (hasPts) engine.startRoute(pts, mode, opts.routeStartMode == 0);
                break;
            case Intents.MAP_RESUME:
                if (hasPts) engine.resumeRoute(pts, mode, reversed, next);
                break;
            case Intents.MAP_TELEPORT:
                if (hasCur) teleportTo(lat, lng, alt);
                break;
            case Intents.MAP_RECORD:
                if (hasCur) {
                    teleportTo(lat, lng, alt);
                    startRecording(lat, lng, alt);
                }
                break;
            case Intents.MAP_RESUME_RECORD:
                if (hasPts) {
                    rec = pts;
                    recording = true;
                    ui.setRecording(true, Route.count(pts));
                }
                break;
            case Intents.MAP_SAVE_RECORD_ROUTE:
                discardRecording(); // o mapa já salvou
                break;
            default:
                break;
        }
    }

    // ---------------------------------------------------------------- MockEngine.Listener

    @Override
    public void onRouteProgress(int index1Based, int total) {
        ui.setRouteProgress(index1Based, total);
        notif.setProgress(index1Based, total);
    }

    @Override
    public void onRouteStateChanged(boolean hasRoute, boolean playing) {
        ui.setRouteState(hasRoute, playing);
        notif.setRoute(hasRoute, playing);
    }

    @Override
    public void onAutoSuspendCountdown(int secondsLeft) {
        ui.setAutoSuspendCountdown(secondsLeft);
    }

    // ---------------------------------------------------------------- Suspended (F62)

    @Override
    public void publishSuspended(double lat, double lng, double alt, float speedMs, float accuracy, float bearing) {
        if (mockIntent == null) mockIntent = MockingActivity.newIntent(this);
        MockingActivity.fill(mockIntent, lat, lng, alt, speedMs, accuracy, bearing);
        // Android 15+: a isenção de abrir Activity em segundo plano exige janela de overlay visível (DECISIONS dúvida 4)
        if (Build.VERSION.SDK_INT >= 35 && !ui.hasVisibleWindow()) {
            notif.setInject(mockIntent);
            return;
        }
        try {
            startActivity(mockIntent);
            notif.clearInject();
        } catch (RuntimeException e) {
            notif.setInject(mockIntent);
        }
    }

    // ---------------------------------------------------------------- OverlayView.Host

    @Override
    public void onGo(double lat, double lng, double alt, boolean walk) {
        if (walk) walkTo(lat, lng, alt);
        else teleportTo(lat, lng, alt);
    }

    @Override
    public void onGoBack(boolean walk) {
        if (!hasPrev) return;
        onGo(prevLat, prevLng, prevAlt, walk); // onGo guarda a atual como anterior: troca pos ↔ prev
    }

    @Override
    public boolean hasPreviousPosition() {
        return hasPrev;
    }

    @Override
    public void onOpenMap() {
        if (mapOpen && mapAlive()) closeMap();
        else openMap(Intents.MAP_NONE);
    }

    @Override
    public void onRouteStop() {
        engine.stopRoute();
    }

    @Override
    public void onRoutePlayPause() {
        if (engine.hasRoute()) engine.setRoutePlaying(!engine.isRoutePlaying());
    }

    @Override
    public void onRoutePrev() {
        engine.previous();
    }

    @Override
    public void onRouteNext() {
        engine.next();
    }

    @Override
    public void onRecordStart() {
        if (!recording) startRecording(engine.lat(), engine.lng(), engine.alt());
    }

    @Override
    public void onRecordAdd() {
        if (recording) addRecordPoint();
    }

    @Override
    public void onRecordDiscard() {
        discardRecording();
    }

    @Override
    public void onRecordOpenMap() {
        if (recording) openMap(Intents.MAP_RECORD);
    }

    @Override
    public void onRecordSubmit() {
        if (recording && Route.count(rec) >= 2) openMap(Intents.MAP_SAVE_RECORD_ROUTE);
    }
}
