package com.pokelocation.app.ipc;

import android.app.ActivityManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import com.pokelocation.app.data.RouteDao;
import com.pokelocation.app.model.Route;
import com.pokelocation.app.overlay.OverlayService;

import java.util.Arrays;
import java.util.List;

/** Ações, extras, erros e helpers de envio/leitura tolerante. Só primitivos/arrays entre processos. */
public final class Intents {
    private static final String TAG = "Intents";

    // Ações públicas (OverlayService exportado).
    public static final String ACTION_TELEPORT = "pokelocation.action.TELEPORT", ACTION_ROUTE = "pokelocation.action.ROUTE",
            ACTION_WALK = "pokelocation.action.WALK", ACTION_STOP = "pokelocation.action.STOP",
            ACTION_ROUTE_PAUSE = "pokelocation.action.ROUTE_PAUSE", ACTION_ROUTE_RESUME = "pokelocation.action.ROUTE_RESUME",
            ACTION_ROUTE_NEXT = "pokelocation.action.ROUTE_NEXT", ACTION_ROUTE_PREV = "pokelocation.action.ROUTE_PREV",
            ACTION_HIDE = "pokelocation.action.HIDE", ACTION_SHOW = "pokelocation.action.SHOW",
            ACTION_SPEED = "pokelocation.action.SPEED", ACTION_FAVORITES = "pokelocation.action.FAVORITES",
            ACTION_RECORD_START = "pokelocation.action.RECORD_START", ACTION_RECORD_ADD = "pokelocation.action.RECORD_ADD",
            ACTION_RECORD_STOP = "pokelocation.action.RECORD_STOP", ACTION_GENERATE = "pokelocation.action.GENERATE",
            ACTION_STATUS = "pokelocation.action.STATUS";
    public static final String[] PUBLIC_ACTIONS = {ACTION_TELEPORT, ACTION_ROUTE, ACTION_WALK, ACTION_STOP,
            ACTION_ROUTE_PAUSE, ACTION_ROUTE_RESUME, ACTION_ROUTE_NEXT, ACTION_ROUTE_PREV, ACTION_HIDE, ACTION_SHOW,
            ACTION_SPEED, ACTION_FAVORITES, ACTION_RECORD_START, ACTION_RECORD_ADD, ACTION_RECORD_STOP, ACTION_GENERATE,
            ACTION_STATUS};

    public static final String BC_STATUS_RESPONSE = "pokelocation.action.STATUS_RESPONSE",
            BC_ERROR = "pokelocation.action.ERROR", BC_RECORD_SAVED = "pokelocation.action.RECORD_SAVED",
            BC_SPOOF_STATE_CHANGED = "pokelocation.action.SPOOF_STATE_CHANGED";

    public static final String INT_STOP_JOYSTICK = "com.pokelocation.app.internal.STOP_JOYSTICK",
            INT_TELEPORT_UI = "com.pokelocation.app.internal.TELEPORT_UI",      // abre o TeleportPanel com lat,lng
            INT_SHARE = "com.pokelocation.app.internal.SHARE",
            INT_OPEN_MAP = "com.pokelocation.app.internal.OPEN_MAP",
            INT_SAVE_LOCATION = "com.pokelocation.app.internal.SAVE_LOCATION",
            INT_HIDE = "com.pokelocation.app.internal.HIDE",                    // extra hide
            INT_ROUTE_ACTION = "com.pokelocation.app.internal.ROUTE_ACTION",    // extra route_action
            INT_RETURN_FROM_MAP = "com.pokelocation.app.internal.RETURN_FROM_MAP",
            INT_START_MAP = "com.pokelocation.app.internal.START_MAP",
            INT_CLOSE_MAP = "com.pokelocation.app.internal.CLOSE_MAP",
            INT_REQUEST_PERMISSIONS = "com.pokelocation.app.internal.REQUEST_PERMISSIONS",
            INT_START_AND_REPLAY = "com.pokelocation.app.internal.START_AND_REPLAY",
            INT_WIDGET_REFRESH = "com.pokelocation.app.internal.WIDGET_REFRESH",
            INT_WIDGET_TOGGLE_TAB = "com.pokelocation.app.internal.WIDGET_TOGGLE_TAB",
            INT_FILL_LOCATION = "com.pokelocation.app.internal.FILL_LOCATION",
            INT_CHAT_UNREAD = "com.pokelocation.app.internal.CHAT_UNREAD";
    // Start do serviço = Intent explícito com action null.

    // Extras públicos.
    public static final String EXTRA_FROM_CHAT = "from_chat";   // INT_TELEPORT_UI: título do chat no painel
    public static final String EXTRA_UNREAD = "n", EXTRA_LOGGED_OUT = "logged_out";
    public static final String EXTRA_LAT = "lat", EXTRA_LNG = "lng", EXTRA_ALT = "alt", EXTRA_NAME = "name",
            EXTRA_ID = "id", EXTRA_WAYPOINTS = "waypoints", EXTRA_TELEPORT = "teleport", EXTRA_SPEED = "speed",
            EXTRA_MODE = "mode", EXTRA_MARKER_COUNT = "marker_count", EXTRA_OFFSET = "offset",
            EXTRA_REPLY_PACKAGE = "reply_package";
    // STATUS_RESPONSE
    public static final String EXTRA_BEARING = "bearing", EXTRA_IS_ROUTE_ACTIVE = "is_route_active",
            EXTRA_IS_ROUTE_PAUSED = "is_route_paused", EXTRA_ROUTE_REMAINING = "route_remaining",
            EXTRA_ROUTE_TOTAL = "route_total", EXTRA_ROUTE_MODE_NAME = "route_mode", EXTRA_IS_REVERSED = "is_reversed",
            EXTRA_IS_RUNNING = "is_running", EXTRA_IS_HIDDEN = "is_hidden", EXTRA_IS_RECORDING = "is_recording",
            EXTRA_RECORD_COUNT = "record_count";
    // ERROR / RECORD_SAVED
    public static final String EXTRA_SOURCE_ACTION = "source_action", EXTRA_ERROR = "error", EXTRA_MESSAGE = "message",
            EXTRA_POINT_COUNT = "point_count";
    public static final String ERR_NOT_WALKING = "NOT_WALKING", ERR_NO_ROUTE_ACTIVE = "NO_ROUTE_ACTIVE",
            ERR_INVALID_EXTRAS = "INVALID_EXTRAS", ERR_FAVORITE_NOT_FOUND = "FAVORITE_NOT_FOUND",
            ERR_ROUTE_NOT_FOUND = "ROUTE_NOT_FOUND", ERR_ALREADY_RECORDING = "ALREADY_RECORDING",
            ERR_NOT_RECORDING = "NOT_RECORDING", ERR_INSUFFICIENT_POINTS = "INSUFFICIENT_POINTS",
            ERR_SAVE_FAILED = "SAVE_FAILED", ERR_UNKNOWN_ACTION = "UNKNOWN_ACTION";
    public static final String EXTRA_SPOOF_ACTIVE = "spoof_active";   // + chaves Prefs.SESSION_KEYS no bundle

    // Extras internos.
    public static final String EXTRA_START_LAT = "start_lat", EXTRA_START_LNG = "start_lng", EXTRA_START_ALT = "start_alt",
            EXTRA_START_HIDDEN = "start_hidden", EXTRA_START_ROUTE_ID = "start_route_id", EXTRA_RECORD_ROUTE = "record_route",
            EXTRA_REPLAY_ACTION = "replay_action", EXTRA_HIDE = "hide", EXTRA_ROUTE_ACTION = "route_action",
            EXTRA_MAP_ACTION = "map_action", EXTRA_POINTS = "points", EXTRA_POINTS_ROUTE_ID = "points_route_id",
            EXTRA_NEXT_INDEX = "next_index", EXTRA_REVERSED = "reversed",
            EXTRA_ROUTE_MODE = "play_route_mode",   // modo da rota em reprodução (int); ≠ "route_mode" de MapOptions
            EXTRA_CUR_LAT = "cur_lat", EXTRA_CUR_LNG = "cur_lng", EXTRA_CUR_ALT = "cur_alt";
    public static final int ROUTE_ACTION_STOP = 0, ROUTE_ACTION_PAUSE = 1, ROUTE_ACTION_RESUME = 2, ROUTE_ACTION_NEXT = 3;
    public static final int MAP_NONE = 0, MAP_START = 1, MAP_RESUME = 2, MAP_TELEPORT = 3, MAP_RECORD = 4,
            MAP_RESUME_RECORD = 5, MAP_SAVE_RECORD_ROUTE = 6;
    public static final int INLINE_POINTS_MAX = 500;

    // Extras entre telas.
    public static final String EXTRA_SELECT_MODE = "select_mode", EXTRA_IDS = "ids", EXTRA_FOLDER_IDS = "folder_ids",
            EXTRA_TYPE_IDS = "type_ids", EXTRA_ADDRESS = "address";

    private Intents() {}

    private static Object raw(Intent i, String key) {
        if (i == null) return null;
        Bundle b = i.getExtras();
        if (b == null) return null;
        try {
            @SuppressWarnings("deprecation") Object v = b.get(key);
            return v;
        } catch (Exception e) {
            return null;
        }
    }

    // Leitura tolerante: Number → valor; String → parse; senão def.
    public static double getDouble(Intent i, String key, double def) {
        Object v = raw(i, key);
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof String) {
            try {
                return Double.parseDouble(((String) v).trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return def;
    }

    public static float getFloat(Intent i, String key, float def) {
        Object v = raw(i, key);
        if (v instanceof Number) return ((Number) v).floatValue();
        if (v instanceof String) {
            try {
                return Float.parseFloat(((String) v).trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return def;
    }

    public static int getInt(Intent i, String key, int def) {
        Object v = raw(i, key);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) {
            String s = ((String) v).trim();
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException e) {
                try {
                    double d = Double.parseDouble(s);
                    if (!Double.isNaN(d)) return (int) d;
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return def;
    }

    /** Boolean, "true"/"false" (ignora caixa) ou número (≠ 0). */
    public static boolean getBool(Intent i, String key, boolean def) {
        Object v = raw(i, key);
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) {
            String s = ((String) v).trim();
            if ("true".equalsIgnoreCase(s) || "1".equals(s)) return true;
            if ("false".equalsIgnoreCase(s) || "0".equals(s)) return false;
        }
        if (v instanceof Number) return ((Number) v).doubleValue() != 0;
        return def;
    }

    // Pontos entre processos: chamar no Io, pode tocar o banco.

    /** n ≤ 500 → EXTRA_POINTS (double[]); senão EXTRA_POINTS_ROUTE_ID = id salvo ou upsertLastRoute(pts).id. */
    public static void putPoints(Context c, Intent i, double[] pts, String savedRouteIdOrNull) {
        if (pts == null) return;
        if (Route.count(pts) <= INLINE_POINTS_MAX) {
            i.putExtra(EXTRA_POINTS, pts);
        } else {
            String id = savedRouteIdOrNull != null ? savedRouteIdOrNull : RouteDao.upsertLastRoute(c, pts).id;
            i.putExtra(EXTRA_POINTS_ROUTE_ID, id);
        }
    }

    /** null se não há pontos nem rota. */
    public static double[] getPoints(Context c, Intent i) {
        if (i == null) return null;
        double[] p = i.getDoubleArrayExtra(EXTRA_POINTS);
        if (p != null) return p;
        String id = i.getStringExtra(EXTRA_POINTS_ROUTE_ID);
        if (id == null) return null;
        Route r = RouteDao.get(c, id);
        return r != null ? r.pts : null;
    }

    /** Nome da MainActivity como String: um class literal carregaria AppCompatActivity no :overlay (RAM). */
    public static final String MAIN_ACTIVITY = "com.pokelocation.app.ui.main.MainActivity";

    /** Intent explícito para a MainActivity sem carregar a classe (seguro no :overlay). */
    public static Intent main(Context c) {
        return new Intent().setClassName(c.getPackageName(), MAIN_ACTIVITY);
    }

    /** Explícito para OverlayService (action null = start). */
    public static Intent overlay(Context c, String actionOrNull) {
        Intent i = new Intent(actionOrNull).setComponent(new ComponentName(c, OverlayService.class));
        return actionOrNull != null && actionOrNull.startsWith(INTERNAL) ? auth(c, i) : i;
    }

    private static final String INTERNAL = "com.pokelocation.app.internal.", EXTRA_AUTH = "auth";

    /**
     * Um PendingIntent não se forja e este nunca sai do app: só vai dentro de intents para os nossos componentes (os
     * do widget e das notificações, que o launcher e os listeners seguram, são outros).
     */
    public static Intent auth(Context c, Intent i) {
        return i.putExtra(EXTRA_AUTH, authToken(c, 0));
    }

    private static PendingIntent authToken(Context c, int flags) {
        return PendingIntent.getBroadcast(c, 0, new Intent(INTERNAL + "AUTH").setPackage(c.getPackageName()),
                PendingIntent.FLAG_IMMUTABLE | flags);
    }

    /** Componentes exportados: ação interna de outro app é ignorada; as públicas seguem abertas. */
    @SuppressWarnings("deprecation")
    public static boolean trusted(Context c, Intent i) {
        boolean ours = false;
        try {
            PendingIntent pi = i.getParcelableExtra(EXTRA_AUTH);
            ours = pi != null && pi.equals(authToken(c, PendingIntent.FLAG_NO_CREATE));
        } catch (RuntimeException ignored) {   // extra de classe desconhecida vindo de fora
        }
        return trusted(i.getAction(), ours);
    }

    static boolean trusted(String action, boolean ownToken) {
        return action == null || !action.startsWith(INTERNAL) || ownToken;
    }

    /** O replay só existe para ações públicas: senão o overlay() assinaria uma interna vinda de fora. */
    static boolean replayable(String action) {
        return action == null || Arrays.asList(PUBLIC_ACTIONS).contains(action);
    }

    public static boolean validLatLng(double lat, double lng) {
        return Math.abs(lat) <= 90 && Math.abs(lng) <= 180;
    }

    /** startForegroundService (26+) / startService; exceções são engolidas (logadas). */
    public static void sendToOverlay(Context c, Intent i) {
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Exception e) {
            Log.w(TAG, "sendToOverlay failed", e);
        }
    }

    /** MainActivity + INT_START_AND_REPLAY + replay_action + TODOS os extras; NEW_TASK|SINGLE_TOP. */
    public static Intent replayToMain(Context c, Intent original) {
        Intent r = main(c).setAction(INT_START_AND_REPLAY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (original != null) {
            Bundle b = original.getExtras();
            if (b != null) r.putExtras(b);
            r.putExtra(EXTRA_REPLAY_ACTION, original.getAction());
        }
        return auth(c, r);
    }

    /** → Intent para OverlayService com a ação e os extras originais. */
    public static Intent unwrapReplay(Context c, Intent replay) {
        String action = replay.getStringExtra(EXTRA_REPLAY_ACTION);
        if (!replayable(action)) return null;
        Intent o = overlay(c, action);
        Bundle b = replay.getExtras();
        if (b != null) o.putExtras(b);
        o.removeExtra(EXTRA_REPLAY_ACTION);
        return o;
    }

    @SuppressWarnings("deprecation")
    public static boolean isOverlayRunning(Context c) {
        try {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningServiceInfo> l = am.getRunningServices(100);
            String name = OverlayService.class.getName();
            for (ActivityManager.RunningServiceInfo s : l) {
                if (name.equals(s.service.getClassName()) && c.getPackageName().equals(s.service.getPackageName())) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * Toda resposta do serviço (STATUS_RESPONSE, ERROR, RECORD_SAVED): setPackage(reply_package), ou só o próprio app
     * sem ele. Broadcast implícito é barrado pelo Android 8+ para receivers de manifest.
     */
    public static void reply(Context c, Intent request, Intent response) {
        String rp = request != null ? request.getStringExtra(EXTRA_REPLY_PACKAGE) : null;
        response.setPackage(rp != null && !rp.trim().isEmpty() ? rp.trim() : c.getPackageName());
        c.sendBroadcast(response);
    }

    public static void broadcastError(Context c, Intent request, String code, String message) {
        Intent e = new Intent(BC_ERROR)
                .putExtra(EXTRA_SOURCE_ACTION, request != null ? request.getAction() : null)
                .putExtra(EXTRA_ERROR, code)
                .putExtra(EXTRA_MESSAGE, message);
        reply(c, request, e);
    }

    /** setPackage(self). session (Prefs.SESSION_KEYS) só no Stop. */
    public static void broadcastSpoofState(Context c, boolean active, Bundle sessionOrNull) {
        Intent b = new Intent(BC_SPOOF_STATE_CHANGED).setPackage(c.getPackageName())
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        if (sessionOrNull != null) b.putExtras(sessionOrNull);
        b.putExtra(EXTRA_SPOOF_ACTIVE, active);
        c.sendBroadcast(b);
    }
}
