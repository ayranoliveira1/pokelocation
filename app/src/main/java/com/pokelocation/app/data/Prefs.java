package com.pokelocation.app.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;

import com.pokelocation.app.model.Coord;

import java.util.HashMap;
import java.util.Map;

/**
 * Chaves e defaults canônicos. Arquivo "prefs": SÓ o processo main escreve (PLAN §1.3).
 * double é gravado como long (Double.doubleToRawLongBits); ausente = default (NaN se não houver).
 */
public final class Prefs {
    public static final String FILE = "prefs", LIVE_FILE = "live";

    public static final String MAIN_SCREEN_MODE = "main_screen_mode";
    public static final String DISPLAY_DISTANCE_INFO = "display_distance_info";
    public static final String ALWAYS_IGNORE_MOCK_LOCATION = "always_ignore_mock_location";
    public static final String PREVENT_SCREENSHOTS = "prevent_screenshots";
    public static final String OPTIONS_VISIBILITY = "options_visibility";
    public static final String ROUTE_OPTIONS_VISIBILITY = "route_options_visibility";
    public static final String ROUTE_MODE = "route_mode";
    public static final String ROUTE_START_MODE = "route_start_mode";
    public static final String HIDE_JOYSTICK = "hide_joystick";
    public static final String DRAWER_HIDE_JOYSTICK = "drawer_hide_joystick";
    public static final String JOYSTICK_X = "joystick_x", JOYSTICK_Y = "joystick_y";
    public static final String DRAWER_X = "drawer_x", DRAWER_Y = "drawer_y";
    public static final String LAST_MOCKED_TIME = "last_mocked_time";
    public static final String CUSTOM_MARKERS_ENABLED = "custom_markers_enabled";
    /** Camada Pokémon: arquivo próprio, fora da sessão (o :overlay devolvia um valor velho no Stop). */
    public static final String POGO_FILE = "pogo_map", POGO_LAYER = "pogo_layer";
    public static final String TELEPORT_WALK_MODE = "teleport_walk_mode";
    public static final String GENERATE_MODE = "generate_mode";
    public static final String GENERATE_MARKER_COUNT = "generate_marker_count";
    public static final String GENERATE_OFFSET = "generate_offset";
    public static final String LAST_LATITUDE = "last_latitude", LAST_LONGITUDE = "last_longitude", LAST_ALTITUDE = "last_altitude";
    public static final String LATITUDE_TEXT = "latitude_text", LONGITUDE_TEXT = "longitude_text", ALTITUDE_TEXT = "altitude_text";
    public static final String SETTINGS_SAVE_LOCATION = "settings_save_location";
    public static final String SETTINGS_AUTO_CLOSE_APP = "settings_auto_close_app";
    public static final String SETTINGS_AUTO_START_JOYSTICK = "settings_auto_start_joystick";
    public static final String SETTINGS_AUTO_START_APP_NAME = "settings_auto_start_app_name";
    public static final String SETTINGS_AUTO_START_APP_PACKAGE = "settings_auto_start_app_package";
    public static final String SETTINGS_JOYSTICK_TYPE = "settings_joystick_type";       // "drawer" | "classic"
    public static final String SETTINGS_JOYSTICK_SIZE = "settings_joystick_size";       // 75..200
    public static final String SETTINGS_JOYSTICK_OPACITY = "settings_joystick_opacity"; // 10..100
    public static final String SETTINGS_WALKING_SPEED = "settings_walking_speed";
    public static final String SETTINGS_JOGGING_SPEED = "settings_jogging_speed";
    public static final String SETTINGS_RUNNING_SPEED = "settings_running_speed";
    public static final String SETTINGS_PROCESS_INTERVAL = "settings_process_interval";
    public static final String SETTINGS_UPDATE_INTERVAL = "settings_update_interval";
    public static final String SETTINGS_IDLE_INTERVAL = "settings_idle_interval";
    public static final String INDIRECT_MOCKING = "indirect_mocking";
    public static final String SUSPENDED_MOCKING = "suspended_mocking";
    public static final String AUTO_SUSPEND_LOCATION = "auto_suspend_location";
    public static final String AUTO_SUSPEND_INTERVAL = "auto_suspend_interval";         // s, 5..3600
    public static final String SETTINGS_ENABLE_AGPS_RESET = "settings_enable_agps_reset";
    public static final String PRIVACY_MODE_ENABLED = "privacy_mode_enabled";
    public static final String SETTINGS_IDLE_OFFSET = "settings_idle_offset";
    public static final String SETTINGS_HEADING_ANGLE_OFFSET = "settings_heading_angle_offset";
    public static final String SETTINGS_SPEED_OFFSET = "settings_speed_offset";
    public static final String SETTINGS_ALTITUDE_OFFSET = "settings_altitude_offset";
    public static final String SETTINGS_MIN_IDLE_ACCURACY = "settings_min_idle_accuracy";
    public static final String SETTINGS_MAX_IDLE_ACCURACY = "settings_max_idle_accuracy";
    public static final String SETTINGS_MIN_MOVING_ACCURACY = "settings_min_moving_accuracy";
    public static final String SETTINGS_MAX_MOVING_ACCURACY = "settings_max_moving_accuracy";
    public static final String SETTINGS_THEME_MODE = "settings_theme_mode";             // "system" | "light" | "dark"
    public static final String SYSTEM_MODE = "system_mode";
    public static final String SPOOF_ACTIVE = "spoof_active";
    public static final String SETUP_WIZARD_COMPLETED = "setup_wizard_completed";
    public static final String ELEVATION_LOOKUP_ENABLED = "elevation_lookup_enabled";
    public static final String MAP_TILE_SOURCE = "map_tile_source";                     // "osm" | "osm_de" (DECISIONS)
    public static final String SHARE_FILE = "social_share", SHARE_LOCATION = "share_location";
    // Bases das chaves por widget: widgetKey(base, id)
    public static final String WIDGET_ITEMS = "widget_items";                // String ""
    public static final String WIDGET_THEME = "widget_theme";                // String "system"
    public static final String WIDGET_GROUP_BY_TYPE = "widget_group_by_type"; // bool true
    public static final String WIDGET_SHOW_ROUTES = "widget_show_routes";     // bool false
    public static final String WIDGET_CACHE = "widget_cache";                // String ""

    public static final String[] SESSION_KEYS = {
            LAST_LATITUDE, LAST_LONGITUDE, LAST_ALTITUDE, LAST_MOCKED_TIME,
            JOYSTICK_X, JOYSTICK_Y, DRAWER_X, DRAWER_Y, TELEPORT_WALK_MODE,
            ROUTE_OPTIONS_VISIBILITY, ROUTE_MODE, ROUTE_START_MODE, CUSTOM_MARKERS_ENABLED,
            GENERATE_MODE, GENERATE_MARKER_COUNT, GENERATE_OFFSET};

    /** Chaves gravadas como double (long bits). */
    private static final String[] DOUBLE_KEYS = {GENERATE_OFFSET, LAST_LATITUDE, LAST_LONGITUDE, LAST_ALTITUDE,
            LATITUDE_TEXT, LONGITUDE_TEXT, ALTITUDE_TEXT, SETTINGS_IDLE_OFFSET, SETTINGS_HEADING_ANGLE_OFFSET};

    private static final Map<String, Object> DEFAULTS = new HashMap<>(96);

    static {
        Map<String, Object> d = DEFAULTS;
        d.put(MAIN_SCREEN_MODE, 0);
        d.put(DISPLAY_DISTANCE_INFO, true);
        d.put(ALWAYS_IGNORE_MOCK_LOCATION, false);
        d.put(PREVENT_SCREENSHOTS, false);
        d.put(OPTIONS_VISIBILITY, false);
        d.put(ROUTE_OPTIONS_VISIBILITY, false);
        d.put(ROUTE_MODE, 0);
        d.put(ROUTE_START_MODE, 0);
        d.put(HIDE_JOYSTICK, false);
        d.put(DRAWER_HIDE_JOYSTICK, false);
        d.put(JOYSTICK_X, -1);
        d.put(JOYSTICK_Y, -1);
        d.put(DRAWER_X, -1);
        d.put(DRAWER_Y, -1);
        d.put(LAST_MOCKED_TIME, 0L);
        d.put(CUSTOM_MARKERS_ENABLED, false);
        d.put(TELEPORT_WALK_MODE, false);
        d.put(GENERATE_MODE, 0);
        d.put(GENERATE_MARKER_COUNT, 10);
        d.put(GENERATE_OFFSET, 50.0);
        d.put(SETTINGS_SAVE_LOCATION, false);
        d.put(SETTINGS_AUTO_CLOSE_APP, false);
        d.put(SETTINGS_AUTO_START_JOYSTICK, false);
        d.put(SETTINGS_AUTO_START_APP_NAME, "");
        d.put(SETTINGS_AUTO_START_APP_PACKAGE, "");
        d.put(SETTINGS_JOYSTICK_TYPE, "drawer");
        d.put(SETTINGS_JOYSTICK_SIZE, 100);
        d.put(SETTINGS_JOYSTICK_OPACITY, 100);
        d.put(SETTINGS_WALKING_SPEED, 10f);
        d.put(SETTINGS_JOGGING_SPEED, 40f);
        d.put(SETTINGS_RUNNING_SPEED, 75f);
        d.put(SETTINGS_PROCESS_INTERVAL, 1000);
        d.put(SETTINGS_UPDATE_INTERVAL, 1000);
        d.put(SETTINGS_IDLE_INTERVAL, 1000);
        d.put(INDIRECT_MOCKING, false);
        d.put(SUSPENDED_MOCKING, false);
        d.put(AUTO_SUSPEND_LOCATION, true);
        d.put(AUTO_SUSPEND_INTERVAL, 30);
        d.put(SETTINGS_ENABLE_AGPS_RESET, false);
        d.put(PRIVACY_MODE_ENABLED, false);
        d.put(SETTINGS_IDLE_OFFSET, 0.25);
        d.put(SETTINGS_HEADING_ANGLE_OFFSET, 5.0);
        d.put(SETTINGS_SPEED_OFFSET, 1f);
        d.put(SETTINGS_ALTITUDE_OFFSET, 3f);
        d.put(SETTINGS_MIN_IDLE_ACCURACY, 2f);
        d.put(SETTINGS_MAX_IDLE_ACCURACY, 5f);
        d.put(SETTINGS_MIN_MOVING_ACCURACY, 6f);
        d.put(SETTINGS_MAX_MOVING_ACCURACY, 10f);
        d.put(SETTINGS_THEME_MODE, "system");
        d.put(SYSTEM_MODE, false);
        d.put(SPOOF_ACTIVE, false);
        d.put(SETUP_WIZARD_COMPLETED, false);
        d.put(ELEVATION_LOOKUP_ENABLED, true);
        d.put(MAP_TILE_SOURCE, "osm");
    }

    private Prefs() {}

    /** Default da tabela §3.4 (null se a chave não tem default, ex.: last_latitude). */
    public static Object defaultOf(String key) { return DEFAULTS.get(key); }

    public static boolean isDoubleKey(String key) {
        for (String k : DOUBLE_KEYS) if (k.equals(key)) return true;
        return false;
    }

    public static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static SharedPreferences.Editor edit(Context c) { return sp(c).edit(); }

    public static boolean getBool(Context c, String key) {
        Object d = DEFAULTS.get(key);
        return getBool(c, key, d instanceof Boolean && (Boolean) d);
    }

    public static int getInt(Context c, String key) {
        Object d = DEFAULTS.get(key);
        int def = d instanceof Number ? ((Number) d).intValue() : 0;
        try {
            return sp(c).getInt(key, def);
        } catch (ClassCastException e) {
            return def;
        }
    }

    public static long getLong(Context c, String key) {
        Object d = DEFAULTS.get(key);
        long def = d instanceof Number ? ((Number) d).longValue() : 0L;
        try {
            return sp(c).getLong(key, def);
        } catch (ClassCastException e) {
            return def;
        }
    }

    public static float getFloat(Context c, String key) {
        Object d = DEFAULTS.get(key);
        float def = d instanceof Number ? ((Number) d).floatValue() : 0f;
        try {
            return sp(c).getFloat(key, def);
        } catch (ClassCastException e) {
            return def;
        }
    }

    /** NaN se ausente e sem default. */
    public static double getDouble(Context c, String key) {
        SharedPreferences p = sp(c);
        if (!p.contains(key)) return decodeDouble(null, key);
        try {
            return Double.longBitsToDouble(p.getLong(key, 0));
        } catch (ClassCastException e) {
            return decodeDouble(p.getAll().get(key), key);
        }
    }

    public static String getString(Context c, String key) {
        Object d = DEFAULTS.get(key);
        return getString(c, key, d instanceof String ? (String) d : "");
    }

    public static boolean getBool(Context c, String key, boolean def) {
        try {
            return sp(c).getBoolean(key, def);
        } catch (ClassCastException e) {
            return def;
        }
    }

    public static String getString(Context c, String key, String def) {
        try {
            return sp(c).getString(key, def);
        } catch (ClassCastException e) {
            return def;
        }
    }

    /** NaN → remove. */
    public static void putDouble(SharedPreferences.Editor e, String key, double v) {
        if (Double.isNaN(v)) e.remove(key);
        else e.putLong(key, Double.doubleToRawLongBits(v));
    }

    /** Valor bruto de getAll() (long bits, ou número) → double; ausente → default ou NaN. */
    public static double decodeDouble(Object raw, String key) {
        if (raw instanceof Long) return Double.longBitsToDouble((Long) raw);
        if (raw instanceof Number) return ((Number) raw).doubleValue();
        if (raw instanceof String) {
            try {
                return Double.parseDouble((String) raw);
            } catch (NumberFormatException ignored) {
            }
        }
        Object d = DEFAULTS.get(key);
        return d instanceof Number ? ((Number) d).doubleValue() : Double.NaN;
    }

    /**
     * Arquivo próprio com um só escritor (o processo principal); o :overlay lê a cada tick. Fora de "prefs" para o
     * :overlay não recarregar as prefs do motor no meio da sessão.
     */
    @SuppressWarnings("deprecation")
    public static boolean shareLocation(Context c) {
        return shareLocation(c.getSharedPreferences(SHARE_FILE, Context.MODE_MULTI_PROCESS));
    }

    static boolean shareLocation(SharedPreferences p) {
        return p.getBoolean(SHARE_LOCATION, true);
    }

    /** Só no processo principal. commit: o :overlay só vê o valor depois de gravado no disco. */
    @SuppressWarnings("deprecation")
    public static void setShareLocation(Context c, boolean on) {
        c.getSharedPreferences(SHARE_FILE, Context.MODE_MULTI_PROCESS).edit().putBoolean(SHARE_LOCATION, on).commit();
    }

    /**
     * Só o mapa do main e o do :map leem e gravam (o switch), cada um direto no arquivo: uma chave só, então a escrita
     * de um processo não atropela nada do outro.
     */
    @SuppressWarnings("deprecation")
    public static boolean pogoLayer(Context c) {
        return c.getSharedPreferences(POGO_FILE, Context.MODE_MULTI_PROCESS).getBoolean(POGO_LAYER, false);
    }

    @SuppressWarnings("deprecation")
    public static void setPogoLayer(Context c, boolean on) {
        c.getSharedPreferences(POGO_FILE, Context.MODE_MULTI_PROCESS).edit().putBoolean(POGO_LAYER, on).apply();
    }

    public static String widgetKey(String base, int appWidgetId) { return base + "_" + appWidgetId; }

    /** last_* (main) x arquivo "live" (:overlay, MODE_MULTI_PROCESS): o mais novo; null se nunca houve. */
    @SuppressWarnings("deprecation")
    public static Coord lastPosition(Context c) {
        Map<String, ?> main = sp(c).getAll();
        double lat = decodeDouble(main.get(LAST_LATITUDE), LAST_LATITUDE);
        double lng = decodeDouble(main.get(LAST_LONGITUDE), LAST_LONGITUDE);
        Object t = main.get(LAST_MOCKED_TIME);
        long mainTime = t instanceof Long ? (Long) t : 0L;

        Map<String, ?> live = c.getSharedPreferences(LIVE_FILE, Context.MODE_MULTI_PROCESS).getAll();
        Object lt = live.get("time");
        long liveTime = lt instanceof Long ? (Long) lt : 0L;
        if (liveTime > 0 && liveTime > mainTime && live.get("lat") != null) {
            return new Coord(decodeDouble(live.get("lat"), ""), decodeDouble(live.get("lng"), ""), decodeDouble(live.get("alt"), ""));
        }
        if (Double.isNaN(lat) || Double.isNaN(lng)) return null;
        return new Coord(lat, lng, decodeDouble(main.get(LAST_ALTITUDE), LAST_ALTITUDE));
    }

    /** SÓ :overlay (arquivo "live": lat,lng,alt,time). Chamar no máximo a cada 5 s. */
    @SuppressWarnings("deprecation")
    public static void saveLive(Context c, double lat, double lng, double alt) {
        SharedPreferences.Editor e = c.getSharedPreferences(LIVE_FILE, Context.MODE_MULTI_PROCESS).edit();
        putDouble(e, "lat", lat);
        putDouble(e, "lng", lng);
        putDouble(e, "alt", alt);
        e.putLong("time", System.currentTimeMillis()).apply();
    }

    /** SÓ main (SpoofStateReceiver): copia as SESSION_KEYS presentes no bundle. */
    @SuppressWarnings("deprecation")
    public static void applySession(Context c, Bundle session) {
        if (session == null) return;
        SharedPreferences.Editor e = edit(c);
        for (String k : SESSION_KEYS) {
            if (!session.containsKey(k)) continue;
            Object v = session.get(k);
            if (isDoubleKey(k)) {
                if (v instanceof Number) putDouble(e, k, ((Number) v).doubleValue());
                else if (v == null) e.remove(k);
            } else if (v instanceof Boolean) e.putBoolean(k, (Boolean) v);
            else if (LAST_MOCKED_TIME.equals(k) && v instanceof Number) e.putLong(k, ((Number) v).longValue());
            else if (v instanceof Number) e.putInt(k, ((Number) v).intValue());
        }
        e.apply();
    }
}
