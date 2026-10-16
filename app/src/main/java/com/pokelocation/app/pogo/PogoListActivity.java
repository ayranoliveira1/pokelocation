package com.pokelocation.app.pogo;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.activity.OnBackPressedCallback;

import com.pokelocation.app.R;
import com.pokelocation.app.data.Prefs;
import com.pokelocation.app.ipc.Intents;
import com.pokelocation.app.model.Coord;
import com.pokelocation.app.ui.base.BaseActivity;
import com.pokelocation.app.ui.main.MainActivity;

/** A lista de Pokémon no app (o mesmo PogoPanel do joystick); "Definir destino" só preenche o local da tela inicial. */
public final class PogoListActivity extends BaseActivity {
    private static final String EXTRA_LAT = "pogo_lat", EXTRA_LNG = "pogo_lng";
    private PogoPanel panel;
    private View cover;
    private final OnBackPressedCallback back = new OnBackPressedCallback(false) {
        @Override public void handleOnBackPressed() { if (onCoverBack != null) onCoverBack.run(); }
    };
    private Runnable onCoverBack;

    /** lat/lng: o local do formulário da tela inicial, ou NaN. */
    public static Intent intent(Context c, double lat, double lng) {
        return new Intent(c, PogoListActivity.class).putExtra(EXTRA_LAT, lat).putExtra(EXTRA_LNG, lng);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent i = getIntent();
        double[] c = center(this, i.getDoubleExtra(EXTRA_LAT, Double.NaN), i.getDoubleExtra(EXTRA_LNG, Double.NaN));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        getLayoutInflater().inflate(R.layout.base_toolbar, root, true);
        panel = new PogoPanel(this, c[0], c[1], R.string.pogo_card_set_dest, true, new PogoPanel.Host() {
            @Override public void go(double lat, double lng, String title) { fill(PogoListActivity.this, lat, lng); }

            @Override public void close() { finish(); }

            @Override public void cover(View v, Runnable onBack) {
                ViewGroup content = findViewById(android.R.id.content);
                if (cover != null) content.removeView(cover);
                cover = v;
                onCoverBack = onBack;
                if (v != null) content.addView(v);
                back.setEnabled(v != null);
            }
        });
        getOnBackPressedDispatcher().addCallback(this, back);
        root.addView(panel.view, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        setupToolbar(getString(R.string.pogo_map_layer), true);
        applyInsets(root);
        panel.start();
    }

    static void fill(Activity a, double lat, double lng) {
        a.startActivity(Intents.auth(a, new Intent(Intents.INT_FILL_LOCATION).setClass(a, MainActivity.class)
                .putExtra(Intents.EXTRA_LAT, lat).putExtra(Intents.EXTRA_LNG, lng)));
        a.finish();
    }

    @Override
    protected void onResume() {
        super.onResume();
        panel.onResume();
        panel.setPaused(false);
    }

    @Override
    protected void onPause() {
        panel.setPaused(true);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        panel.destroy();
        super.onDestroy();
    }

    /**
     * Centro do "Perto de": a posição do joystick se ele estiver rodando; senão o local do formulário (vindo da tela
     * inicial ou o último salvo); senão a última posição conhecida, que é onde o mapa da tela inicial abre; senão a
     * última localização do aparelho.
     */
    static double[] center(Context c, double formLat, double formLng) {
        Coord last = Prefs.lastPosition(c);
        if (Intents.isOverlayRunning(c) && last != null) return new double[]{last.lat, last.lng};
        if (Intents.validLatLng(formLat, formLng)) return new double[]{formLat, formLng};
        double lat = Prefs.getDouble(c, Prefs.LATITUDE_TEXT), lng = Prefs.getDouble(c, Prefs.LONGITUDE_TEXT);
        if (Intents.validLatLng(lat, lng)) return new double[]{lat, lng};
        if (last != null) return new double[]{last.lat, last.lng};
        double[] dev = device(c);   // instalação nova (nunca deu Start): onde o aparelho está
        return dev != null ? dev : new double[]{Double.NaN, Double.NaN};
    }

    /** O fix mais recente entre os providers ativos (o do mock vale: é a posição simulada), só com a permissão que o app já tem. */
    @SuppressLint("MissingPermission")
    private static double[] device(Context c) {
        if (c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null;
        LocationManager lm = c.getSystemService(LocationManager.class);
        if (lm == null) return null;
        Location best = null;
        for (String p : lm.getProviders(true)) {
            try {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            } catch (RuntimeException ignored) {   // provider sumiu/permissão revogada no meio
            }
        }
        return best == null ? null : new double[]{best.getLatitude(), best.getLongitude()};
    }
}
