package com.pokelocation.app.engine;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.location.provider.ProviderProperties;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;

import com.pokelocation.app.data.Prefs;
import com.pokelocation.app.util.PrivacyModeManager;

import java.lang.reflect.Method;

/**
 * Só API pública de test provider (DECISIONS). UMA instância por processo :overlay.
 * publish() não aloca: um único Location e um único Bundle{satellites=10}.
 * System Mode usa ILocationManager.reportLocation() via reflection para bypassar mock detection.
 * Fallback para test provider quando system mode indisponível (API >= 29 ou sem permissão).
 */
public class LocationInjector {
    private static final String TAG = "LocationInjector";
    public static final String[] PROVIDERS_NORMAL = {"gps", "network", "passive"};
    public static final String[] PROVIDERS_INDIRECT = {"network"};       // F61

    private static LocationInjector sCurrent;

    private final LocationManager lm;
    private final String[] providers;
    private final boolean[] active;
    private final Location loc;
    private final boolean privacyModeEnabled;
    private boolean agpsReset;
    private boolean loggedAgps, loggedPublish;
    private boolean systemMode;
    private Method reportLocationMethod;
    private Object iLocationManager;

    /** Cria Location("gps") + Bundle{satellites=10} UMA vez. */
    public LocationInjector(Context c, boolean indirect, boolean agpsReset, boolean privacyModeEnabled) {
        lm = (LocationManager) c.getApplicationContext().getSystemService(Context.LOCATION_SERVICE);
        providers = indirect ? PROVIDERS_INDIRECT : PROVIDERS_NORMAL;
        active = new boolean[providers.length];
        this.agpsReset = agpsReset;
        this.privacyModeEnabled = privacyModeEnabled;
        loc = new Location("gps");
        Bundle extras = new Bundle(1);
        extras.putInt("satellites", 10);
        loc.setExtras(extras);
        systemMode = Prefs.getBool(c, Prefs.SYSTEM_MODE) && tryInitSystemMode(c);
        sCurrent = this;
    }

    /** Teste JVM: sem Context nem LocationManager; o teste sobrescreve os ganchos. */
    LocationInjector(boolean indirect) {
        lm = null;
        providers = indirect ? PROVIDERS_INDIRECT : PROVIDERS_NORMAL;
        active = new boolean[providers.length];
        loc = new Location("gps");
        privacyModeEnabled = false;
    }

    private boolean tryInitSystemMode(Context c) {
        if (Build.VERSION.SDK_INT >= 29) return false;
        if (!c.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS)) return false;
        if (c.checkPermission("android.permission.INSTALL_LOCATION_PROVIDER", 
                android.os.Process.myPid(), android.os.Process.myUid()) != 
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        try {
            Class<?> iLocationManagerClass = Class.forName("android.location.ILocationManager");
            Class<?> iLocationListenerClass = Class.forName("android.location.ILocationListener");
            Object service = c.getSystemService(Context.LOCATION_SERVICE);
            if (service == null) return false;
            Method getServiceMethod = service.getClass().getMethod("getService");
            iLocationManager = getServiceMethod.invoke(service);
            if (iLocationManager == null) return false;
            reportLocationMethod = iLocationManagerClass.getMethod("reportLocation", 
                    Class.forName("android.location.Location"), boolean.class);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "System Mode init failed: " + e.getMessage());
            return false;
        }
    }

    /** Instância viva do processo (a MockingActivity usa); null se nenhuma. */
    public static LocationInjector current() { return sCurrent; }

    // Ganchos sobrescritos no teste JVM.
    @SuppressLint("InlinedApi")   // constantes inteiras (inlined), valem em qualquer API
    @SuppressWarnings("deprecation")
    void addProvider(String p) {
        // requiresNetwork, requiresSatellite, requiresCell, hasMonetaryCost, altitude, speed, bearing, POWER_LOW, ACCURACY_FINE
        lm.addTestProvider(p, false, false, false, false, true, true, true,
                ProviderProperties.POWER_USAGE_LOW, ProviderProperties.ACCURACY_FINE);
        lm.setTestProviderEnabled(p, true);
    }

    @SuppressWarnings("deprecation")
    void sendLocation(String p, Location l) {
        if (Build.VERSION.SDK_INT < 29) lm.setTestProviderStatus(p, LocationProvider.AVAILABLE, null, l.getTime());
        lm.setTestProviderLocation(p, l);
    }

    /** Remove resíduo dos 3 → addTestProvider + setTestProviderEnabled nos providers do modo; false se nenhum. */
    public boolean register() {
        for (String p : PROVIDERS_NORMAL) {
            try {
                lm.removeTestProvider(p);
            } catch (Exception ignored) {
            }
        }
        boolean any = false;
        for (int i = 0; i < providers.length; i++) {
            String p = providers[i];
            try {
                addProvider(p);
                active[i] = true;
                any = true;
            } catch (SecurityException e) {
                Log.w(TAG, "mock location not allowed: " + p);
            } catch (Exception e) {
                Log.w(TAG, "addTestProvider failed: " + p + " " + e);
            }
        }
        return any;
    }

    /** Sem alocar. setTestProviderStatus só < API 29; depois A-GPS reset se ativo (F63). */
    public void publish(double lat, double lng, double alt, float speedMs, boolean hasSpeed,
                        float accuracy, float bearing, boolean hasBearing) {
        if (systemMode && reportLocationMethod != null) {
            publishSystemMode(lat, lng, alt, speedMs, hasSpeed, accuracy, bearing, hasBearing);
            return;
        }
        Location l = loc;
        l.setLatitude(lat);
        l.setLongitude(lng);
        l.setAltitude(Double.isNaN(alt) ? 0 : alt);
        l.setAccuracy(accuracy);
        if (hasSpeed) l.setSpeed(speedMs);
        else l.removeSpeed();
        if (hasBearing) l.setBearing(bearing);
        else l.removeBearing();
        long now = System.currentTimeMillis();
        l.setTime(now);
        l.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        for (int i = 0; i < providers.length; i++) {
            if (!active[i]) continue;
            String p = providers[i];
            try {
                l.setProvider(p);
                sendLocation(p, l);
            } catch (IllegalArgumentException e) {
                // A Samsung apaga os test providers do pacote quando qualquer processo do app morre.
                try {
                    addProvider(p);
                    sendLocation(p, l);
                    Log.w(TAG, "test provider recreated: " + p);
                } catch (SecurityException e2) {
                    active[i] = false;
                    logPublish(p, e2);
                } catch (Exception e2) {
                    logPublish(p, e2);
                }
            } catch (Exception e) {
                logPublish(p, e);
            }
        }
        if (agpsReset) {
            resetAgps();
        }
    }

    private void logPublish(String p, Exception e) {
        if (loggedPublish) return;
        loggedPublish = true;
        Log.w(TAG, "setTestProviderLocation failed: " + p + " " + e);
    }

    private void publishSystemMode(double lat, double lng, double alt, float speedMs, boolean hasSpeed,
                                   float accuracy, float bearing, boolean hasBearing) {
        Location l = loc;
        l.setLatitude(lat);
        l.setLongitude(lng);
        l.setAltitude(Double.isNaN(alt) ? 0 : alt);
        l.setAccuracy(accuracy);
        if (hasSpeed) l.setSpeed(speedMs);
        else l.removeSpeed();
        if (hasBearing) l.setBearing(bearing);
        else l.removeBearing();
        long now = System.currentTimeMillis();
        l.setTime(now);
        l.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        try {
            reportLocationMethod.invoke(iLocationManager, l, null);
        } catch (Exception e) {
            if (!loggedPublish) {
                loggedPublish = true;
                Log.w(TAG, "reportLocation failed: " + e.getMessage());
            }
        }
    }

    private void resetAgps() {
        try {
            lm.sendExtraCommand("gps", "delete_aiding_data", null);
            lm.sendExtraCommand("gps", "force_xtra_injection", null);
            lm.sendExtraCommand("gps", "force_time_injection", null);
            if (Build.VERSION.SDK_INT >= 30) lm.sendExtraCommand("gps", "force_psds_injection", null);
        } catch (SecurityException e) {
            agpsReset = false;   // só nesta sessão
            logAgps(e);
        } catch (Exception e) {
            logAgps(e);
        }
    }

    private void logAgps(Exception e) {
        if (loggedAgps) return;
        loggedAgps = true;
        Log.w(TAG, "A-GPS reset failed: " + e);
    }

    /** removeTestProvider por provider (try/catch); limpa current(). */
    public void unregister() {
        for (int i = 0; i < providers.length; i++) {
            if (!active[i]) continue;
            active[i] = false;
            try {
                lm.removeTestProvider(providers[i]);
            } catch (Exception ignored) {
            }
        }
        if (sCurrent == this) sCurrent = null;
    }
}
