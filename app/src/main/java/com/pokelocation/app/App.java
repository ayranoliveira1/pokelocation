package com.pokelocation.app;

import android.app.Application;
import android.os.Build;

import com.pokelocation.app.data.Prefs;
import com.pokelocation.app.map.MapHelper;
import com.pokelocation.app.util.Themes;

import java.io.FileInputStream;

/** Process-aware: fora do processo main o onCreate retorna cedo (nada de AppCompat/osmdroid no :overlay). */
public final class App extends Application {
    public static final int PROC_MAIN = 0, PROC_OVERLAY = 1, PROC_MAP = 2, PROC_POGOWEB = 3;
    private static int sProcess = -1;

    public static int process() {
        if (sProcess < 0) sProcess = detect();
        return sProcess;
    }

    private static int detect() {
        String name = null;
        if (Build.VERSION.SDK_INT >= 28) name = Application.getProcessName();
        if (name == null) {
            try (FileInputStream in = new FileInputStream("/proc/self/cmdline")) {
                byte[] b = new byte[256];
                int n = in.read(b), len = 0;
                while (len < n && b[len] != 0) len++;
                name = new String(b, 0, len);
            } catch (Exception ignored) {
                name = "";
            }
        }
        if (name.endsWith(":overlay")) return PROC_OVERLAY;
        if (name.endsWith(":map")) return PROC_MAP;
        if (name.endsWith(":pogoweb")) return PROC_POGOWEB;
        return PROC_MAIN;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (process() != PROC_MAIN) return;
        Themes.applyNightMode(Prefs.getString(this, Prefs.SETTINGS_THEME_MODE));
        // DECISIONS: CARTO Voyager removido (exige API key); o valor antigo migra para o padrão OSM
        if ("voyager".equals(Prefs.getString(this, Prefs.MAP_TILE_SOURCE))) {
            Prefs.edit(this).putString(Prefs.MAP_TILE_SOURCE, "osm").apply();
        }
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // :overlay e :pogoweb nunca carregam osmdroid
        if (process() == PROC_MAIN || process() == PROC_MAP) MapHelper.trimMemory(level);
    }
}
