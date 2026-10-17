package com.pokelocation.app.ipc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

import com.pokelocation.app.Io;
import com.pokelocation.app.data.Prefs;
import com.pokelocation.app.model.Coord;

import java.util.function.Consumer;

/**
 * Posição atual para as telas do main. Com o joystick rodando, o "live" só é gravado em eventos discretos, e uma rota
 * tocando ficaria para trás: pergunta ao motor (um STATUS) e cai em Prefs.lastPosition sem resposta.
 */
public final class LiveStatus {
    private static final long TIMEOUT_MS = 1000;

    private LiveStatus() {}

    /** cb uma vez, na main thread; null se nunca houve posição. */
    public static void request(Context c, Consumer<Coord> cb) {
        Context app = c.getApplicationContext();
        if (!Intents.isOverlayRunning(app)) {
            cb.accept(Prefs.lastPosition(app));
            return;
        }
        Wait w = new Wait(app, cb);
        IntentFilter f = new IntentFilter(Intents.BC_STATUS_RESPONSE);
        String perm = app.getPackageName() + ".permission.INTERNAL";
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(w, f, perm, null, Context.RECEIVER_NOT_EXPORTED);
        else app.registerReceiver(w, f, perm, null);
        Io.main().postDelayed(w, TIMEOUT_MS);
        Intents.sendToOverlay(app, Intents.overlay(app, Intents.ACTION_STATUS));
    }

    static Coord engine(boolean running, double lat, double lng, double alt) {
        return running && Intents.validLatLng(lat, lng) ? new Coord(lat, lng, alt) : null;
    }

    private static final class Wait extends BroadcastReceiver implements Runnable {
        private final Context app;
        private final Consumer<Coord> cb;
        private boolean done;

        Wait(Context app, Consumer<Coord> cb) {
            this.app = app;
            this.cb = cb;
        }

        @Override public void onReceive(Context c, Intent i) {
            finish(engine(Intents.getBool(i, Intents.EXTRA_IS_RUNNING, false), Intents.getDouble(i, Intents.EXTRA_LAT, Double.NaN),
                    Intents.getDouble(i, Intents.EXTRA_LNG, Double.NaN), Intents.getDouble(i, Intents.EXTRA_ALT, Double.NaN)));
        }

        @Override public void run() { finish(null); }

        private void finish(Coord engine) {
            if (done) return;
            done = true;
            Io.main().removeCallbacks(this);
            app.unregisterReceiver(this);
            cb.accept(engine != null ? engine : Prefs.lastPosition(app));
        }
    }
}
