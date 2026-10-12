package com.pokelocation.app.engine;

import android.content.Context;
import android.content.pm.PackageManager;

import com.pokelocation.app.data.Prefs;

import java.util.Collections;
import java.util.Map;

/** Snapshot das prefs do motor, lido UMA vez (load) no onCreate do serviço. Nenhuma leitura de prefs no tick. */
public final class EngineConfig {
    public final int processMs, updateMs, idleMs;                 // clamp ≥ 50; = 1000 forçado se suspended
    public final double idleOffsetM, headingOffsetDeg;
    public final float speedOffsetKmh, altOffsetM, minIdleAcc, maxIdleAcc, minMovingAcc, maxMovingAcc;
    public final float walkKmh, jogKmh, runKmh;
    public final boolean saveLocation;
    public final boolean suspended;                               // suspended_mocking
    public final boolean indirect;                                // indirect_mocking && !suspended
    public final boolean autoSuspend;                             // auto_suspend_location (valor inicial da sessão)
    public final int autoSuspendIntervalS;                        // auto_suspend_interval, clamp 5..3600
    public final boolean agpsReset;                               // settings_enable_agps_reset && FEATURE_LOCATION_GPS
    public final boolean privacyModeEnabled;                      // privacy_mode_enabled

    private EngineConfig(int processMs, int updateMs, int idleMs, double idleOffsetM, double headingOffsetDeg,
                         float speedOffsetKmh, float altOffsetM, float minIdleAcc, float maxIdleAcc,
                         float minMovingAcc, float maxMovingAcc, float walkKmh, float jogKmh, float runKmh,
                         boolean saveLocation, boolean suspended, boolean indirect, boolean autoSuspend,
                         int autoSuspendIntervalS, boolean agpsReset, boolean privacyModeEnabled) {
        this.suspended = suspended;
        this.processMs = suspended ? 1000 : Math.max(50, processMs);
        this.updateMs = suspended ? 1000 : Math.max(50, updateMs);
        this.idleMs = suspended ? 1000 : Math.max(50, idleMs);
        this.idleOffsetM = idleOffsetM;
        this.headingOffsetDeg = headingOffsetDeg;
        this.speedOffsetKmh = speedOffsetKmh;
        this.altOffsetM = altOffsetM;
        this.minIdleAcc = minIdleAcc;
        this.maxIdleAcc = maxIdleAcc;
        this.minMovingAcc = minMovingAcc;
        this.maxMovingAcc = maxMovingAcc;
        this.walkKmh = walkKmh;
        this.jogKmh = jogKmh;
        this.runKmh = runKmh;
        this.saveLocation = saveLocation;
        this.indirect = indirect && !suspended;
        this.autoSuspend = autoSuspend;
        this.autoSuspendIntervalS = Math.max(5, Math.min(3600, autoSuspendIntervalS));
        this.agpsReset = agpsReset;
        this.privacyModeEnabled = privacyModeEnabled;
    }

    public static EngineConfig load(Context c) {
        boolean gps = c.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS);
        boolean privacyMode = c.getSharedPreferences("privacy_prefs", Context.MODE_PRIVATE)
            .getBoolean("privacy_mode_enabled", false);
        return from(Prefs.sp(c).getAll(), gps, privacyMode);
    }

    /** Sem Context: valores brutos das prefs (como SharedPreferences.getAll()); ausentes = defaults de Prefs. */
    public static EngineConfig from(Map<String, ?> prefs, boolean hasGpsFeature, boolean privacyModeEnabled) {
        Map<String, ?> m = prefs != null ? prefs : Collections.emptyMap();
        return new EngineConfig(i(m, Prefs.SETTINGS_PROCESS_INTERVAL), i(m, Prefs.SETTINGS_UPDATE_INTERVAL),
                i(m, Prefs.SETTINGS_IDLE_INTERVAL), d(m, Prefs.SETTINGS_IDLE_OFFSET), d(m, Prefs.SETTINGS_HEADING_ANGLE_OFFSET),
                f(m, Prefs.SETTINGS_SPEED_OFFSET), f(m, Prefs.SETTINGS_ALTITUDE_OFFSET),
                f(m, Prefs.SETTINGS_MIN_IDLE_ACCURACY), f(m, Prefs.SETTINGS_MAX_IDLE_ACCURACY),
                f(m, Prefs.SETTINGS_MIN_MOVING_ACCURACY), f(m, Prefs.SETTINGS_MAX_MOVING_ACCURACY),
                f(m, Prefs.SETTINGS_WALKING_SPEED), f(m, Prefs.SETTINGS_JOGGING_SPEED), f(m, Prefs.SETTINGS_RUNNING_SPEED),
                b(m, Prefs.SETTINGS_SAVE_LOCATION), b(m, Prefs.SUSPENDED_MOCKING), b(m, Prefs.INDIRECT_MOCKING),
                b(m, Prefs.AUTO_SUSPEND_LOCATION), i(m, Prefs.AUTO_SUSPEND_INTERVAL),
                b(m, Prefs.SETTINGS_ENABLE_AGPS_RESET) && hasGpsFeature, privacyModeEnabled);
    }

    /** Todos os campos, sem Context (testes JVM). Aplica os mesmos clamps/regras de load(). */
    public static EngineConfig forTest(int processMs, int updateMs, int idleMs, double idleOffsetM, double headingOffsetDeg,
                                       float speedOffsetKmh, float altOffsetM, float minIdleAcc, float maxIdleAcc,
                                       float minMovingAcc, float maxMovingAcc, float walkKmh, float jogKmh, float runKmh,
                                       boolean saveLocation, boolean suspended, boolean indirect, boolean autoSuspend,
                                       int autoSuspendIntervalS, boolean agpsReset, boolean privacyModeEnabled) {
        return new EngineConfig(processMs, updateMs, idleMs, idleOffsetM, headingOffsetDeg, speedOffsetKmh, altOffsetM,
                minIdleAcc, maxIdleAcc, minMovingAcc, maxMovingAcc, walkKmh, jogKmh, runKmh, saveLocation, suspended,
                indirect, autoSuspend, autoSuspendIntervalS, agpsReset, privacyModeEnabled);
    }

    /** Defaults da tabela de Prefs (sem Context). */
    public static EngineConfig defaults() { return from(null, true, false); }

    private static Object v(Map<String, ?> m, String k) {
        Object o = m.get(k);
        return o != null ? o : Prefs.defaultOf(k);
    }

    private static int i(Map<String, ?> m, String k) {
        Object o = v(m, k);
        return o instanceof Number ? ((Number) o).intValue() : 0;
    }

    private static float f(Map<String, ?> m, String k) {
        Object o = v(m, k);
        return o instanceof Number ? ((Number) o).floatValue() : 0f;
    }

    private static double d(Map<String, ?> m, String k) { return Prefs.decodeDouble(m.get(k), k); }

    private static boolean b(Map<String, ?> m, String k) {
        Object o = v(m, k);
        return o instanceof Boolean && (Boolean) o;
    }
}
