package com.pokelocation.app.mapoverlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.os.Process;
import android.view.WindowManager;

import com.pokelocation.app.Io;
import com.pokelocation.app.R;
import com.pokelocation.app.data.Prefs;
import com.pokelocation.app.ipc.Intents;
import com.pokelocation.app.model.Coord;
import com.pokelocation.app.model.MapOptions;
import com.pokelocation.app.model.Route;
import com.pokelocation.app.ui.map.RouteMapView;
import com.pokelocation.app.util.Themes;
import com.pokelocation.app.util.Ui;

/**
 * Mini-mapa flutuante (F51/F53), processo :map efêmero. Recebe INT_START_MAP do :overlay, devolve UM
 * INT_RETURN_FROM_MAP e para; onDestroy mata o processo (N02). Prefs só leitura. Ponte: docs/team/W2-api.md.
 */
public final class MapOverlayService extends Service implements RouteMapView.Host {
    private static final String CHANNEL = "pokelocation_map";
    private static final int NOTIF_ID = 2;

    private WindowManager wm;
    private RouteMapView map;
    private Io.Task loadTask;
    private boolean opening, returned, destroyed;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        try {
            Notification n = buildNotification();
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            else startForeground(NOTIF_ID, n);
        } catch (Exception e) {
            finishWith(Intents.MAP_NONE, null, -1, false);   // o :overlay não fica com mapOpen e rota pausada
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (Intents.INT_START_MAP.equals(action)) open(intent);
        else if (Intents.INT_CLOSE_MAP.equals(action)) finishWith(Intents.MAP_NONE, null, -1, false);
        else if (map == null && !opening && !returned) stopSelf();
        return START_NOT_STICKY;
    }

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, getString(R.string.map_notif_channel), NotificationManager.IMPORTANCE_LOW));
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        PendingIntent close = PendingIntent.getService(this, 0,
                new Intent(this, MapOverlayService.class).setAction(Intents.INT_CLOSE_MAP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return b.setSmallIcon(R.drawable.ic_map)
                .setContentTitle(getString(R.string.map_notif_title))
                .setOngoing(true)
                .setShowWhen(false)
                .addAction(new Notification.Action.Builder(null, getString(R.string.close), close).build())
                .build();
    }

    /** START_MAP repetido com o mapa aberto é ignorado (o :overlay fecha por stopService). */
    private void open(Intent i) {
        if (map != null || opening || destroyed || returned) return;
        opening = true;
        final Context app = getApplicationContext();
        final Intent in = i;
        loadTask = Io.run(() -> Intents.getPoints(app, in), new Io.Callback<double[]>() {
            @Override public void onResult(double[] pts) { show(in, pts); }

            @Override public void onError(Throwable t) { show(in, null); }
        });
    }

    private void show(Intent i, double[] pts) {
        opening = false;
        if (destroyed) return;
        Context ui = Themes.overlayContext(this);
        MapOptions o = MapOptions.fromIntent(i, MapOptions.load(this));
        int action = Intents.getInt(i, Intents.EXTRA_MAP_ACTION, Intents.MAP_NONE);
        boolean rec = action == Intents.MAP_RECORD || action == Intents.MAP_SAVE_RECORD_ROUTE;
        int next = Intents.getInt(i, Intents.EXTRA_NEXT_INDEX, -1);
        boolean hasPts = pts != null && pts.length >= 3;
        if (hasPts && !rec && next >= 0) o.routeMode = Intents.getInt(i, Intents.EXTRA_ROUTE_MODE, o.routeMode);

        map = new RouteMapView(ui, RouteMapView.MODE_FLOATING, this, o);
        double lat = Intents.getDouble(i, Intents.EXTRA_CUR_LAT, Double.NaN), lng = Intents.getDouble(i, Intents.EXTRA_CUR_LNG, Double.NaN);
        if (!Double.isNaN(lat) && !Double.isNaN(lng)) {
            Coord cur = new Coord(lat, lng, Intents.getDouble(i, Intents.EXTRA_CUR_ALT, Double.NaN));
            map.setCurrentPosition(cur);
            if (!hasPts) map.setStart(cur);
        }
        if (i.hasExtra(Prefs.TELEPORT_WALK_MODE)) map.setTeleportWalkMode(Intents.getBool(i, Prefs.TELEPORT_WALK_MODE, false));
        if (i.hasExtra(Prefs.LAST_MOCKED_TIME)) map.setLastMockedTime(i.getLongExtra(Prefs.LAST_MOCKED_TIME, 0));
        if (hasPts) {
            map.loadRoute(pts, rec ? -1 : next);
            map.setReversed(Intents.getBool(i, Intents.EXTRA_REVERSED, false));
        }
        map.setRecording(rec, action == Intents.MAP_SAVE_RECORD_ROUTE);

        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        if (Prefs.getBool(this, Prefs.PREVENT_SCREENSHOTS)) flags |= WindowManager.LayoutParams.FLAG_SECURE;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT, Ui.overlayWindowType(), flags, PixelFormat.TRANSLUCENT);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        try {
            wm.addView(map, lp);
        } catch (Exception e) {
            map.destroy();
            map = null;
            finishWith(Intents.MAP_NONE, null, -1, false);
        }
    }

    // ------------------------------------------------------------------ RouteMapView.Host → RETURN_FROM_MAP

    @Override public void onTeleportClick(Coord target) { finishWith(Intents.MAP_TELEPORT, null, -1, false); }

    @Override public void onStartRouteClick(double[] pts) { finishWith(Intents.MAP_START, pts, -1, false); }

    @Override public void onResumeRouteClick(double[] pts, int nextIndex, boolean reversed) {
        finishWith(Intents.MAP_RESUME, pts, nextIndex, reversed);
    }

    @Override public void onRecordClick(Coord start) { finishWith(Intents.MAP_RECORD, null, -1, false); }

    @Override public void onResumeRecordClick(double[] pts) { finishWith(Intents.MAP_RESUME_RECORD, pts, -1, false); }

    @Override public void onRecordSaved(Route saved) { finishWith(Intents.MAP_SAVE_RECORD_ROUTE, null, -1, false); }

    @Override public void onCloseClick() { finishWith(Intents.MAP_NONE, null, -1, false); }

    @Override public void onMapFailed() {
        Ui.toast(this, R.string.map_failed);
        finishWith(Intents.MAP_NONE, null, -1, false);
    }

    /**
     * Um único retorno (guard). cur_* = início do mapa (alvo do TELEPORT / 1º ponto do RECORD).
     * putPoints pode tocar o banco → Io; stopSelf só depois do envio (o killProcess do onDestroy não corta o Io).
     */
    private void finishWith(int action, double[] pts, int nextIndex, boolean reversed) {
        if (returned) return;
        returned = true;
        final Context app = getApplicationContext();
        final Coord start = map != null ? map.getStart() : null;
        final MapOptions o = map != null ? map.getOptions() : null;
        if (map != null) map.setVisibility(android.view.View.GONE);
        Io.run(() -> {
            Intent r = Intents.overlay(app, Intents.INT_RETURN_FROM_MAP);
            r.putExtra(Intents.EXTRA_MAP_ACTION, action);
            if (pts != null) Intents.putPoints(app, r, pts, null);
            if (start != null) {
                r.putExtra(Intents.EXTRA_CUR_LAT, start.lat);
                r.putExtra(Intents.EXTRA_CUR_LNG, start.lng);
                r.putExtra(Intents.EXTRA_CUR_ALT, start.alt);
            }
            r.putExtra(Intents.EXTRA_NEXT_INDEX, nextIndex);
            r.putExtra(Intents.EXTRA_REVERSED, reversed);
            if (o != null) {
                r.putExtra(Intents.EXTRA_ROUTE_MODE, o.routeMode);
                o.toIntent(r);
            }
            return r;
        }, new Io.Callback<Intent>() {
            @Override public void onResult(Intent r) {
                Intents.sendToOverlay(app, r);
                stopSelf();
            }

            @Override public void onError(Throwable t) { stopSelf(); }
        });
    }

    // ------------------------------------------------------------------ ciclo de vida

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (map != null) map.onTrimMemory(level);
    }

    /** Fechado pelo :overlay (stopService) ou após o retorno: só destrói e mata o processo, sem RETURN. */
    @Override public void onDestroy() {
        if (!destroyed) {
            destroyed = true;
            if (loadTask != null) loadTask.cancel();
            if (map != null) {
                map.destroy();
                try {
                    wm.removeView(map);
                } catch (Exception ignored) {
                }
                map = null;
            }
        }
        super.onDestroy();
        Process.killProcess(Process.myPid());
    }
}
