package com.pokelocation.app.model;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;

import com.pokelocation.app.data.Prefs;
import com.pokelocation.app.ipc.Intents;

/** Opções do mapa (mutável). Trafega main → :overlay → :map e volta. */
public final class MapOptions {
    public boolean routeOptionsVisible;   // route_options_visibility
    public int routeMode;                 // route_mode 0 stop,1 loop,2 reverse
    public int routeStartMode;            // route_start_mode 0 teleport,1 walk
    public boolean customMarkersEnabled;  // custom_markers_enabled
    public int generateMode;              // generate_mode 0 random,1 circle
    public int generateCount = 10;        // generate_marker_count
    public double generateOffset = 50.0;  // generate_offset

    /** Defaults da tabela de Prefs. */
    public MapOptions() {}

    public MapOptions copy() {
        MapOptions o = new MapOptions();
        o.routeOptionsVisible = routeOptionsVisible;
        o.routeMode = routeMode;
        o.routeStartMode = routeStartMode;
        o.customMarkersEnabled = customMarkersEnabled;
        o.generateMode = generateMode;
        o.generateCount = generateCount;
        o.generateOffset = generateOffset;
        return o;
    }

    public static MapOptions load(Context c) {
        MapOptions o = new MapOptions();
        o.routeOptionsVisible = Prefs.getBool(c, Prefs.ROUTE_OPTIONS_VISIBILITY);
        o.routeMode = Prefs.getInt(c, Prefs.ROUTE_MODE);
        o.routeStartMode = Prefs.getInt(c, Prefs.ROUTE_START_MODE);
        o.customMarkersEnabled = Prefs.getBool(c, Prefs.CUSTOM_MARKERS_ENABLED);
        o.generateMode = Prefs.getInt(c, Prefs.GENERATE_MODE);
        o.generateCount = Prefs.getInt(c, Prefs.GENERATE_MARKER_COUNT);
        o.generateOffset = Prefs.getDouble(c, Prefs.GENERATE_OFFSET);
        return o;
    }

    /** Só no main. */
    public void save(SharedPreferences.Editor e) {
        e.putBoolean(Prefs.ROUTE_OPTIONS_VISIBILITY, routeOptionsVisible)
                .putInt(Prefs.ROUTE_MODE, routeMode)
                .putInt(Prefs.ROUTE_START_MODE, routeStartMode)
                .putBoolean(Prefs.CUSTOM_MARKERS_ENABLED, customMarkersEnabled)
                .putInt(Prefs.GENERATE_MODE, generateMode)
                .putInt(Prefs.GENERATE_MARKER_COUNT, generateCount);
        Prefs.putDouble(e, Prefs.GENERATE_OFFSET, generateOffset);
    }

    /** Extras com os mesmos nomes das chaves. */
    public void toIntent(Intent i) {
        i.putExtra(Prefs.ROUTE_OPTIONS_VISIBILITY, routeOptionsVisible)
                .putExtra(Prefs.ROUTE_MODE, routeMode)
                .putExtra(Prefs.ROUTE_START_MODE, routeStartMode)
                .putExtra(Prefs.CUSTOM_MARKERS_ENABLED, customMarkersEnabled)
                .putExtra(Prefs.GENERATE_MODE, generateMode)
                .putExtra(Prefs.GENERATE_MARKER_COUNT, generateCount)
                .putExtra(Prefs.GENERATE_OFFSET, generateOffset);
    }

    /** Extras presentes sobrescrevem fallback (ou os defaults se fallback == null). */
    public static MapOptions fromIntent(Intent i, MapOptions fallback) {
        MapOptions o = fallback != null ? fallback.copy() : new MapOptions();
        if (i == null) return o;
        o.routeOptionsVisible = Intents.getBool(i, Prefs.ROUTE_OPTIONS_VISIBILITY, o.routeOptionsVisible);
        o.routeMode = Intents.getInt(i, Prefs.ROUTE_MODE, o.routeMode);
        o.routeStartMode = Intents.getInt(i, Prefs.ROUTE_START_MODE, o.routeStartMode);
        o.customMarkersEnabled = Intents.getBool(i, Prefs.CUSTOM_MARKERS_ENABLED, o.customMarkersEnabled);
        o.generateMode = Intents.getInt(i, Prefs.GENERATE_MODE, o.generateMode);
        o.generateCount = Intents.getInt(i, Prefs.GENERATE_MARKER_COUNT, o.generateCount);
        o.generateOffset = Intents.getDouble(i, Prefs.GENERATE_OFFSET, o.generateOffset);
        return o;
    }

    public void toBundle(Bundle b) {
        b.putBoolean(Prefs.ROUTE_OPTIONS_VISIBILITY, routeOptionsVisible);
        b.putInt(Prefs.ROUTE_MODE, routeMode);
        b.putInt(Prefs.ROUTE_START_MODE, routeStartMode);
        b.putBoolean(Prefs.CUSTOM_MARKERS_ENABLED, customMarkersEnabled);
        b.putInt(Prefs.GENERATE_MODE, generateMode);
        b.putInt(Prefs.GENERATE_MARKER_COUNT, generateCount);
        b.putDouble(Prefs.GENERATE_OFFSET, generateOffset);
    }
}
