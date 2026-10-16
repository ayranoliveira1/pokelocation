package com.pokelocation.app.pogo;

import android.content.Context;
import android.content.Intent;
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
            @Override public void go(double lat, double lng, String title) {
                startActivity(Intents.auth(PogoListActivity.this, new Intent(Intents.INT_FILL_LOCATION)
                        .setClass(PogoListActivity.this, MainActivity.class)
                        .putExtra(Intents.EXTRA_LAT, lat).putExtra(Intents.EXTRA_LNG, lng)));
                finish();
            }

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
        panel.start(alert(i));
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        showAlert(intent);
    }

    /** Toque no push de alerta: o card do Pokémon por cima da lista (a coordenada vem pelo "Ver coordenadas"). */
    private void showAlert(Intent i) {
        Pogo.Mon m = alert(i);
        if (m != null) panel.showMon(m);
    }

    private static Pogo.Mon alert(Intent i) {
        Bundle b = i.getBundleExtra(PogoAlert.EXTRA);
        return b == null || b.getString("rc") == null ? null
                : alertMon(b.getString("rc"), b.getString("n"), b.getString("iv"), b.getString("cp"), b.getString("d"), b.getString("s"));
    }

    /** Os dados do FCM vêm como texto; vazio ou inválido = desconhecido. */
    static Pogo.Mon alertMon(String rc, String n, String iv, String cp, String d, String s) {
        Pogo.Mon m = new Pogo.Mon();
        m.reveal = rc;
        m.name = n;
        m.iv = num(iv, -1);
        m.cp = (int) num(cp, 0);
        m.despawnMs = (long) num(d, 0);
        m.shiny = "1".equals(s);
        m.verified = true;
        m.lat = m.lng = Double.NaN;
        return m;
    }

    private static double num(String s, double def) {
        try {
            return s == null || s.isEmpty() ? def : Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        panel.onResume();
    }

    @Override
    protected void onDestroy() {
        panel.destroy();
        super.onDestroy();
    }

    /**
     * Centro do "Perto de": a posição do joystick se ele estiver rodando; senão o local do formulário (vindo da tela
     * inicial ou o último salvo); senão a última posição conhecida, que é onde o mapa da tela inicial abre.
     */
    static double[] center(Context c, double formLat, double formLng) {
        Coord last = Prefs.lastPosition(c);
        if (Intents.isOverlayRunning(c) && last != null) return new double[]{last.lat, last.lng};
        if (Intents.validLatLng(formLat, formLng)) return new double[]{formLat, formLng};
        double lat = Prefs.getDouble(c, Prefs.LATITUDE_TEXT), lng = Prefs.getDouble(c, Prefs.LONGITUDE_TEXT);
        if (Intents.validLatLng(lat, lng)) return new double[]{lat, lng};
        return last != null ? new double[]{last.lat, last.lng} : new double[]{Double.NaN, Double.NaN};
    }
}
