package com.pokelocation.app.overlay;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.Toast;

import com.pokelocation.app.R;
import com.pokelocation.app.data.Prefs;
import com.pokelocation.app.engine.LocationInjector;
import com.pokelocation.app.util.Perms;
import com.pokelocation.app.util.Themes;

/**
 * Suspended Mocking (F62, 11 §2.8). Roda no :overlay e usa o injetor do serviço: publica uma vez (e de novo a cada
 * onNewIntent) e fecha na primeira localização não-mock ou aos 20 s. Diálogo a partir de 3 s, Cancel a partir de 7 s.
 */
public final class MockingActivity extends Activity {
    private static final String P = "com.pokelocation.app.internal.extra.";
    static final String EXTRA_LAT = P + "LAT", EXTRA_LNG = P + "LNG", EXTRA_ALT = P + "ALT",
            EXTRA_SPEED = P + "SPEED", EXTRA_ACCURACY = P + "ACCURACY", EXTRA_BEARING = P + "BEARING";
    private static final long DIALOG_MS = 3000, CANCEL_MS = 7000, TIMEOUT_MS = 20000, STEP_MS = 1000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable step = this::step;
    private final LocationListener listener = new LocationListener() {
        @Override public void onLocationChanged(Location l) {
            boolean mock = Build.VERSION.SDK_INT >= 31 ? l.isMock() : l.isFromMockProvider();
            if (!mock) close();
        }
        @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
        @Override public void onProviderEnabled(String provider) {}
        @Override public void onProviderDisabled(String provider) {}
    };
    private LocationManager lm;
    private AlertDialog dialog;
    private long start;
    private boolean closed;

    /** Intent de abertura (o serviço reaproveita a mesma instância). alt NaN → 0. */
    static Intent fill(Intent i, double lat, double lng, double alt, float speedMs, float accuracy, float bearing) {
        return i.putExtra(EXTRA_LAT, lat).putExtra(EXTRA_LNG, lng).putExtra(EXTRA_ALT, Double.isNaN(alt) ? 0 : alt)
                .putExtra(EXTRA_SPEED, speedMs).putExtra(EXTRA_ACCURACY, accuracy).putExtra(EXTRA_BEARING, bearing);
    }

    static Intent newIntent(Context c) {
        return new Intent(c, MockingActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                | Intent.FLAG_ACTIVITY_NO_ANIMATION);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Prefs.getBool(this, Prefs.PREVENT_SCREENSHOTS)) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        setFinishOnTouchOutside(false);
        if (!Perms.hasFine(this)) {
            Toast.makeText(this, R.string.ov_mocking_need_location, Toast.LENGTH_LONG).show();
            close();
            return;
        }
        start = SystemClock.elapsedRealtime();
        if (!publish(getIntent())) {
            close();
            return;
        }
        if (!listen()) {
            Toast.makeText(this, R.string.unknown_error, Toast.LENGTH_SHORT).show();
            close();
            return;
        }
        handler.postDelayed(step, STEP_MS);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (!closed) publish(intent); // republica sem reiniciar o relógio
    }

    private boolean publish(Intent i) {
        LocationInjector inj = LocationInjector.current();
        if (inj == null || i == null || !i.hasExtra(EXTRA_LAT) || !i.hasExtra(EXTRA_LNG)) return false;
        inj.publish(i.getDoubleExtra(EXTRA_LAT, 0), i.getDoubleExtra(EXTRA_LNG, 0), i.getDoubleExtra(EXTRA_ALT, 0),
                i.getFloatExtra(EXTRA_SPEED, 0), true, i.getFloatExtra(EXTRA_ACCURACY, 0),
                i.getFloatExtra(EXTRA_BEARING, 0), true);
        return true;
    }

    @SuppressLint("MissingPermission") // FINE checada no onCreate
    private boolean listen() {
        lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return false;
        boolean ok = register(LocationManager.GPS_PROVIDER);
        if (Build.VERSION.SDK_INT >= 31 && lm.getAllProviders().contains(LocationManager.FUSED_PROVIDER)) {
            ok |= register(LocationManager.FUSED_PROVIDER);
        }
        return ok;
    }

    @SuppressLint("MissingPermission")
    private boolean register(String provider) {
        try {
            lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper());
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void step() {
        if (closed) return;
        long t = SystemClock.elapsedRealtime() - start;
        if (t > TIMEOUT_MS) {
            close();
            return;
        }
        if (t > DIALOG_MS && dialog == null) showDialog();
        if (t > CANCEL_MS && dialog != null) {
            Button b = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            if (b != null) b.setVisibility(View.VISIBLE);
        }
        handler.postDelayed(step, STEP_MS);
    }

    private void showDialog() {
        boolean night = Themes.isNight(Themes.overlayContext(this));
        dialog = new AlertDialog.Builder(this, night ? android.R.style.Theme_Material_Dialog_Alert
                : android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle(R.string.ov_mocking_title)
                .setMessage(R.string.ov_mocking_msg)
                .setNegativeButton(R.string.cancel, (d, w) -> close())
                .setCancelable(false)
                .create();
        dialog.setCanceledOnTouchOutside(false);
        try {
            dialog.show();
        } catch (RuntimeException e) { // Activity já finalizando
            dialog = null;
            return;
        }
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        Button b = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        if (b != null) {
            b.setTextColor(getColor(R.color.accent));
            b.setVisibility(View.GONE);
        }
    }

    /** Idempotente. */
    private void close() {
        if (closed) return;
        release();
        finishAndRemoveTask();
    }

    private void release() {
        closed = true;
        handler.removeCallbacks(step);
        if (lm != null) {
            try {
                lm.removeUpdates(listener);
            } catch (RuntimeException ignored) {
            }
            lm = null;
        }
        if (dialog != null) {
            try {
                dialog.dismiss();
            } catch (RuntimeException ignored) {
            }
            dialog = null;
        }
    }

    @Override
    protected void onDestroy() {
        release();
        super.onDestroy();
    }
}
