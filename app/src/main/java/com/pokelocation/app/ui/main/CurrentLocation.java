package com.pokelocation.app.ui.main;

import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Looper;
import android.util.Log;

import com.pokelocation.app.Io;

/** Leitura única da posição real (03 §3.9, §5.12): sem listener contínuo, timeout de 30 s. Só na main thread. */
final class CurrentLocation {
    interface Callback { void onLocation(Location l); }   // null = erro/timeout

    private static final long TIMEOUT_MS = 30_000;

    private LocationManager lm;
    private Callback cb;
    private LocationListener listener;
    private final Runnable timeout = () -> deliver(null);

    /** Exige FINE (quem chama checa). Cancela uma leitura anterior. */
    @SuppressWarnings("deprecation")
    void start(Context c, Callback callback) {
        Log.d("CurrentLocation", "start() called");
        cancel();
        cb = callback;
        Io.main().postDelayed(timeout, TIMEOUT_MS);
        try {
            lm = (LocationManager) c.getApplicationContext().getSystemService(Context.LOCATION_SERVICE);
            
            // Tenta providers em ordem: GPS, network, passive
            for (String provider : new String[] {
                    LocationManager.GPS_PROVIDER,
                    LocationManager.NETWORK_PROVIDER,
                    LocationManager.PASSIVE_PROVIDER
            }) {
                boolean enabled = lm.isProviderEnabled(provider);
                Log.d("CurrentLocation", "isProviderEnabled(" + provider + ")=" + enabled);
                if (!enabled) continue;
                
                Log.d("CurrentLocation", "Trying provider=" + provider);
                
                // Tenta ler última localização conhecida
                Location l = lm.getLastKnownLocation(provider);
                Log.d("CurrentLocation", "getLastKnownLocation(" + provider + ")=" + l + " mock=" + (l != null && l.isFromMockProvider()));
                if (l != null && !l.isFromMockProvider()) {
                    deliver(l);
                    return;
                }
            }
            
            // Fallback: pede atualização única para GPS se estiver habilitado
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                listener = this::deliver;
                lm.requestSingleUpdate(LocationManager.GPS_PROVIDER, listener, Looper.getMainLooper());
                Log.d("CurrentLocation", "requestSingleUpdate(GPS) called");
            } else {
                Log.d("CurrentLocation", "deliver(null) - no enabled provider with cached location");
                deliver(null);
            }
        } catch (RuntimeException e) {   // SecurityException / IllegalArgumentException
            Log.e("CurrentLocation", "Exception", e);
            deliver(null);
        }
    }

    boolean active() { return cb != null; }

    /** Idempotente. */
    void cancel() {
        Io.main().removeCallbacks(timeout);
        if (listener != null && lm != null) {
            try { lm.removeUpdates(listener); } catch (RuntimeException ignored) {}
        }
        listener = null;
        lm = null;
        cb = null;
    }

    private void deliver(Location l) {
        Log.d("CurrentLocation", "deliver() called with l=" + l);
        Callback c = cb;
        cancel();
        if (c != null) c.onLocation(l);
    }
}
