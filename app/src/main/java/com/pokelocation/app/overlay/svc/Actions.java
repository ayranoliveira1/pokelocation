package com.pokelocation.app.overlay.svc;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.pokelocation.app.Io;
import com.pokelocation.app.R;
import com.pokelocation.app.data.FavoriteDao;
import com.pokelocation.app.data.Prefs;
import com.pokelocation.app.data.RouteDao;
import com.pokelocation.app.engine.MockEngine;
import com.pokelocation.app.geo.CoordParser;
import com.pokelocation.app.geo.Geo;
import com.pokelocation.app.ipc.Intents;
import com.pokelocation.app.model.Coord;
import com.pokelocation.app.model.Favorite;
import com.pokelocation.app.model.Route;
import com.pokelocation.app.overlay.OverlayNotification;
import com.pokelocation.app.overlay.OverlayService;
import com.pokelocation.app.util.Perms;

/**
 * Ações públicas pokelocation.action.* (F96–F100; 02 §3.9, 10 §3.4). Dispatcher por switch (sem objetos por ação).
 * Mensagens de ERROR são da API de automação (não UI): inglês fixo, como no original.
 */
public final class Actions {
    private Actions() {}

    /** Serviço não está rodando (frio) ou o startForeground falhou (N14). */
    public static void cold(OverlayService s, Intent i, String action) {
        switch (action) {
            case Intents.ACTION_STOP:
                break;
            case Intents.ACTION_STATUS:
                Intents.reply(s, i, status(s, null));
                break;
            default:
                if (wantsStart(i, action)) replay(s, i);
                else if (Intents.ACTION_TELEPORT.equals(action)) handle(s, i, action); // só responde o erro de faixa
                else if (!isPublic(action) && !action.startsWith(INTERNAL)) {
                    Intents.broadcastError(s, i, Intents.ERR_UNKNOWN_ACTION, "Unrecognized action: " + action);
                }
                // internas com o serviço parado (ex.: RETURN_FROM_MAP tardio): ignora
                break;
        }
    }

    private static final String INTERNAL = "com.pokelocation.app.internal.";

    /** F97: a Main sobe o joystick e reenvia com todos os extras. */
    private static void replay(OverlayService s, Intent i) {
        Intent main = Intents.replayToMain(s, i);
        boolean started = false;
        try {
            s.startActivity(main);
            started = true;
        } catch (RuntimeException ignored) {
        }
        // Android 10+ bloqueia o start em segundo plano sem lançar exceção. A isenção vem da permissão de overlay,
        // e no 15+ só vale com janela visível (o serviço frio não tem nenhuma). Segunda via: notificação de ação
        // (DECISIONS dúvida 4). A Main a cancela ao receber o replay.
        if (!started || (Build.VERSION.SDK_INT >= 29 && (Build.VERSION.SDK_INT >= 35 || !Perms.canDrawOverlays(s)))) {
            notifyReplay(s, main);
        }
    }

    /** Id da notificação de ação pendente (≠ OverlayNotification.ID e da notificação do :map). */
    public static final int REPLAY_NOTIFICATION_ID = 3;

    private static void notifyReplay(OverlayService s, Intent main) {
        try {
            PendingIntent pi = PendingIntent.getActivity(s, REPLAY_NOTIFICATION_ID, main,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(s, OverlayNotification.CHANNEL) : new Notification.Builder(s);
            Notification n = b.setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(s.getString(R.string.ov_svc_replay_title))
                    .setContentText(s.getString(R.string.ov_svc_replay_text))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build();
            ((NotificationManager) s.getSystemService(Context.NOTIFICATION_SERVICE)).notify(REPLAY_NOTIFICATION_ID, n);
        } catch (RuntimeException ignored) {
        }
    }

    /** Ação pública que, com o serviço parado, sobe o joystick. TELEPORT sem lat/lng e WALK vazio só são ignorados (10 §3.4); fora da faixa, só o erro. */
    public static boolean wantsStart(Intent i, String action) {
        switch (action) {
            case Intents.ACTION_STOP:
            case Intents.ACTION_STATUS:
                return false;
            case Intents.ACTION_TELEPORT:
                return Intents.validLatLng(Intents.getDouble(i, Intents.EXTRA_LAT, Double.NaN),
                        Intents.getDouble(i, Intents.EXTRA_LNG, Double.NaN));
            case Intents.ACTION_WALK:
                return Route.count(CoordParser.waypoints(i.getStringExtra(Intents.EXTRA_WAYPOINTS))) > 0;
            default:
                return isPublic(action);
        }
    }

    private static boolean isPublic(String action) {
        for (String a : Intents.PUBLIC_ACTIONS) if (a.equals(action)) return true;
        return false;
    }

    /** Serviço rodando; o cold só o usa para o TELEPORT que não sobe o joystick. */
    public static void handle(OverlayService s, Intent i, String action) {
        MockEngine e = s.engine();
        switch (action) {
            case Intents.ACTION_TELEPORT: {
                double lat = Intents.getDouble(i, Intents.EXTRA_LAT, Double.NaN);
                double lng = Intents.getDouble(i, Intents.EXTRA_LNG, Double.NaN);
                if (Double.isNaN(lat) || Double.isNaN(lng)) return; // sem lat/lng: ignora (02 §3.9)
                if (!Intents.validLatLng(lat, lng)) {
                    Intents.broadcastError(s, i, Intents.ERR_INVALID_EXTRAS, "lat must be in [-90, 90] and lng in [-180, 180]");
                    return;
                }
                s.teleportTo(lat, lng, Intents.getDouble(i, Intents.EXTRA_ALT, Double.NaN));
                return;
            }
            case Intents.ACTION_ROUTE:
                route(s, i);
                return;
            case Intents.ACTION_WALK: {
                double[] pts = CoordParser.waypoints(i.getStringExtra(Intents.EXTRA_WAYPOINTS));
                if (pts == null || Route.count(pts) == 0) return; // lista vazia: ignora (10 §3.4)
                float speed = Intents.getFloat(i, Intents.EXTRA_SPEED, 0f);
                if (speed > 0) s.setSpeed(speed);
                s.mapOptions().routeStartMode = Intents.getBool(i, Intents.EXTRA_TELEPORT, false) ? 0 : 1;
                s.startRouteFromList(pts);
                return;
            }
            case Intents.ACTION_STOP:
                s.stopSpoof();
                return;
            case Intents.ACTION_ROUTE_PAUSE:
                if (e.isRoutePlaying()) e.setRoutePlaying(false);
                else Intents.broadcastError(s, i, Intents.ERR_NOT_WALKING, "No route is currently walking");
                return;
            case Intents.ACTION_ROUTE_RESUME:
                if (e.hasRoute() && !e.isRoutePlaying()) e.setRoutePlaying(true);
                else Intents.broadcastError(s, i, Intents.ERR_NO_ROUTE_ACTIVE, "No route is active to resume");
                return;
            case Intents.ACTION_ROUTE_NEXT:
                if (!e.next()) Intents.broadcastError(s, i, Intents.ERR_NO_ROUTE_ACTIVE, "No route is active");
                return;
            case Intents.ACTION_ROUTE_PREV:
                if (!e.previous()) Intents.broadcastError(s, i, Intents.ERR_NO_ROUTE_ACTIVE, "No route is active");
                return;
            case Intents.ACTION_HIDE:
                s.setHidden(true);
                return;
            case Intents.ACTION_SHOW:
                s.setHidden(false);
                return;
            case Intents.ACTION_SPEED: {
                float speed = Intents.getFloat(i, Intents.EXTRA_SPEED, Float.NaN);
                if (speed > 0) s.setSpeed(speed);
                else Intents.broadcastError(s, i, Intents.ERR_INVALID_EXTRAS, "speed must be > 0");
                return;
            }
            case Intents.ACTION_FAVORITES:
                favorite(s, i);
                return;
            case Intents.ACTION_RECORD_START:
                if (s.isRecording()) {
                    Intents.broadcastError(s, i, Intents.ERR_ALREADY_RECORDING, "A recording is already in progress");
                } else {
                    s.startRecording(e.lat(), e.lng(), e.alt());
                }
                return;
            case Intents.ACTION_RECORD_ADD:
                if (s.isRecording()) s.addRecordPoint();
                else Intents.broadcastError(s, i, Intents.ERR_NOT_RECORDING, "Not currently recording a route");
                return;
            case Intents.ACTION_RECORD_STOP: {
                if (!s.isRecording()) {
                    Intents.broadcastError(s, i, Intents.ERR_NOT_RECORDING, "Not currently recording a route");
                    return;
                }
                String name = i.getStringExtra(Intents.EXTRA_NAME);
                if (name == null || name.trim().isEmpty()) {
                    s.discardRecording();
                } else if (s.recordCount() < 2) {
                    Intents.broadcastError(s, i, Intents.ERR_INSUFFICIENT_POINTS,
                            "At least 2 recorded points are required to save a route");
                } else {
                    s.saveRecording(name.trim(), i);
                }
                return;
            }
            case Intents.ACTION_GENERATE: {
                int mode = Intents.getInt(i, Intents.EXTRA_MODE, MockEngine.GEN_RANDOM);
                int count = Intents.getInt(i, Intents.EXTRA_MARKER_COUNT, 10);
                double offset = Intents.getDouble(i, Intents.EXTRA_OFFSET, 50);
                if (count < 1 || count > 5000) {
                    Intents.broadcastError(s, i, Intents.ERR_INVALID_EXTRAS, "marker_count must be between 1 and 5000");
                } else if (!(offset > 0)) {
                    Intents.broadcastError(s, i, Intents.ERR_INVALID_EXTRAS, "offset must be greater than 0");
                } else {
                    s.startGenerated(Geo.generate(e.lat(), e.lng(), mode, count, offset));
                }
                return;
            }
            case Intents.ACTION_STATUS:
                Intents.reply(s, i, status(s, s));
                return;
            default:
                Intents.broadcastError(s, i, Intents.ERR_UNKNOWN_ACTION, "Unrecognized action: " + action);
        }
    }

    private static void route(OverlayService s, Intent i) {
        String id = i.getStringExtra(Intents.EXTRA_ID);
        String name = i.getStringExtra(Intents.EXTRA_NAME);
        boolean byId = id != null && !id.trim().isEmpty();
        if (!byId && (name == null || name.isEmpty())) {
            Intents.broadcastError(s, i, Intents.ERR_INVALID_EXTRAS, "name or id is required");
            return;
        }
        Context app = s.getApplicationContext();
        String key = byId ? id.trim() : name;
        Io.run(() -> byId ? RouteDao.get(app, key) : RouteDao.findByName(app, key), new Io.Callback<Route>() {
            @Override public void onResult(Route r) {
                if (!s.isRunning()) return;
                if (r == null || Route.count(r.pts) == 0) {
                    Intents.broadcastError(s, i, Intents.ERR_ROUTE_NOT_FOUND, "No route found: " + key);
                } else {
                    s.startRouteFromList(r.pts);
                }
            }
            @Override public void onError(Throwable t) {
                Intents.broadcastError(s, i, Intents.ERR_ROUTE_NOT_FOUND, s.getString(R.string.ov_svc_route_load_error));
            }
        });
    }

    private static void favorite(OverlayService s, Intent i) {
        String name = i.getStringExtra(Intents.EXTRA_NAME);
        if (name == null || name.trim().isEmpty()) {
            Intents.broadcastError(s, i, Intents.ERR_INVALID_EXTRAS, "name is required");
            return;
        }
        Context app = s.getApplicationContext();
        Io.run(() -> FavoriteDao.findByName(app, name), new Io.Callback<Favorite>() {
            @Override public void onResult(Favorite f) {
                if (f == null) {
                    Intents.broadcastError(s, i, Intents.ERR_FAVORITE_NOT_FOUND, "No favorite found with name: " + name);
                } else if (s.isRunning()) {
                    s.teleportTo(f.lat, f.lng, f.alt != 0 ? f.alt : Double.NaN); // favorito guarda 0 = desconhecida
                }
            }
            @Override public void onError(Throwable t) {
                Intents.broadcastError(s, i, Intents.ERR_FAVORITE_NOT_FOUND, "No favorite found with name: " + name);
            }
        });
    }

    /** STATUS_RESPONSE (02 §3.9). running == null: serviço frio, posição das prefs (10 §3.4 item 6). */
    private static Intent status(OverlayService s, OverlayService running) {
        Intent r = new Intent(Intents.BC_STATUS_RESPONSE);
        if (running == null) {
            Coord c = Prefs.lastPosition(s);
            return r.putExtra(Intents.EXTRA_LAT, c == null ? 0d : c.lat)
                    .putExtra(Intents.EXTRA_LNG, c == null ? 0d : c.lng)
                    .putExtra(Intents.EXTRA_ALT, c == null ? 0d : c.altOr0())
                    .putExtra(Intents.EXTRA_SPEED, Prefs.getFloat(s, Prefs.SETTINGS_WALKING_SPEED))
                    .putExtra(Intents.EXTRA_BEARING, 0f)
                    .putExtra(Intents.EXTRA_IS_ROUTE_ACTIVE, false)
                    .putExtra(Intents.EXTRA_IS_ROUTE_PAUSED, false)
                    .putExtra(Intents.EXTRA_ROUTE_REMAINING, 0)
                    .putExtra(Intents.EXTRA_ROUTE_TOTAL, 0)
                    .putExtra(Intents.EXTRA_ROUTE_MODE_NAME, modeName(Prefs.getInt(s, Prefs.ROUTE_MODE)))
                    .putExtra(Intents.EXTRA_IS_REVERSED, false)
                    .putExtra(Intents.EXTRA_IS_RUNNING, false)
                    .putExtra(Intents.EXTRA_IS_HIDDEN, false)
                    .putExtra(Intents.EXTRA_IS_RECORDING, false)
                    .putExtra(Intents.EXTRA_RECORD_COUNT, 0);
        }
        MockEngine e = running.engine();
        boolean has = e.hasRoute(), playing = e.isRoutePlaying();
        double alt = e.alt();
        return r.putExtra(Intents.EXTRA_LAT, e.lat())
                .putExtra(Intents.EXTRA_LNG, e.lng())
                .putExtra(Intents.EXTRA_ALT, Double.isNaN(alt) ? 0d : alt)
                .putExtra(Intents.EXTRA_SPEED, e.getSpeedKmh())
                .putExtra(Intents.EXTRA_BEARING, e.bearing())
                .putExtra(Intents.EXTRA_IS_ROUTE_ACTIVE, has && playing)
                .putExtra(Intents.EXTRA_IS_ROUTE_PAUSED, has && !playing)
                .putExtra(Intents.EXTRA_ROUTE_REMAINING, e.routeRemaining())
                .putExtra(Intents.EXTRA_ROUTE_TOTAL, e.routeTotal())
                .putExtra(Intents.EXTRA_ROUTE_MODE_NAME, modeName(has ? e.routeMode() : running.mapOptions().routeMode))
                .putExtra(Intents.EXTRA_IS_REVERSED, e.isReversed())
                .putExtra(Intents.EXTRA_IS_RUNNING, true)
                .putExtra(Intents.EXTRA_IS_HIDDEN, running.isHidden())
                .putExtra(Intents.EXTRA_IS_RECORDING, running.isRecording())
                .putExtra(Intents.EXTRA_RECORD_COUNT, running.recordCount());
    }

    private static String modeName(int mode) {
        return mode == MockEngine.ROUTE_LOOP ? "loop" : mode == MockEngine.ROUTE_REVERSE ? "reverse" : "stop";
    }
}
