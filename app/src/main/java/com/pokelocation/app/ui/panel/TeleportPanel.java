package com.pokelocation.app.ui.panel;

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;

import com.pokelocation.app.Io;
import com.pokelocation.app.R;
import com.pokelocation.app.data.Prefs;
import com.pokelocation.app.geo.Cooldown;
import com.pokelocation.app.geo.CoordParser;
import com.pokelocation.app.geo.Geo;
import com.pokelocation.app.model.Coord;
import com.pokelocation.app.util.Ui;

/**
 * "Teleport To Location": Paste, Format, Favoritos, Walk Mode (troca rótulos), Teleport/Walk Back,
 * linha Cooldown.line atualizada a cada 1 s entre attach/detach. Fechar sem escolher → onCancel.
 */
public final class TeleportPanel {
    public static final class Args {
        public Coord current;                 // para a linha de distância
        public String text;                   // "lat, lng" inicial (pode ser "")
        public boolean walkMode, showBack, canGoBack, showFavorites, showDistance;
        public long lastMockedTime;
    }

    public interface Callback {
        void onGo(double lat, double lng, boolean walk);   // Teleport / Walk
        default void onBack(boolean walk) {}               // Teleport Back / Walk Back
        default void onFavorites() {}
        default void onWalkModeChanged(boolean walk) {}
        default void onCancel() {}
    }

    private PanelHost host;
    private boolean decided;

    private TeleportPanel() {}

    public static TeleportPanel show(Context c, Args a, Callback cb) {
        TeleportPanel p = new TeleportPanel();
        View v = LayoutInflater.from(c).inflate(R.layout.panel_teleport, null);
        EditText text = v.findViewById(R.id.tp_text);
        TextView error = v.findViewById(R.id.tp_error), distance = v.findViewById(R.id.tp_distance);
        CheckBox walk = v.findViewById(R.id.tp_walk);
        Button go = v.findViewById(R.id.tp_go), back = v.findViewById(R.id.tp_back), fav = v.findViewById(R.id.tp_favorites);
        double[] ll = new double[2];

        if (a.text != null) text.setText(a.text);
        walk.setChecked(a.walkMode);
        Runnable labels = () -> {
            boolean w = walk.isChecked();
            go.setText(w ? R.string.walk : R.string.teleport);
            back.setText(w ? R.string.walk_back : R.string.teleport_back);
        };
        labels.run();
        back.setVisibility(a.showBack ? View.VISIBLE : View.GONE);
        back.setEnabled(a.canGoBack);
        fav.setVisibility(a.showFavorites ? View.VISIBLE : View.GONE);

        boolean showDist = a.showDistance && a.current != null && a.lastMockedTime != 0
                && Prefs.getBool(c, Prefs.DISPLAY_DISTANCE_INFO);
        Runnable updateDistance = () -> {
            if (!showDist || !CoordParser.parse(text.getText().toString(), ll)) {
                distance.setVisibility(View.GONE);
                return;
            }
            long now = System.currentTimeMillis();
            double m = Geo.distance(a.current.lat, a.current.lng, ll[0], ll[1]);
            distance.setText(Cooldown.line(c, m, a.lastMockedTime, now));
            distance.setTextColor(Cooldown.color(c, m, a.lastMockedTime, now));
            distance.setVisibility(View.VISIBLE);
        };
        text.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int x, int y, int z) {}
            @Override public void onTextChanged(CharSequence s, int x, int y, int z) {}
            @Override public void afterTextChanged(Editable s) {
                error.setVisibility(View.GONE);
                updateDistance.run();
            }
        });
        if (showDist) {
            Runnable tick = new Runnable() {
                @Override
                public void run() {
                    updateDistance.run();
                    Io.main().postDelayed(this, 1000);
                }
            };
            v.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View x) { Io.main().post(tick); }
                @Override public void onViewDetachedFromWindow(View x) { Io.main().removeCallbacks(tick); }
            });
        }

        v.findViewById(R.id.tp_paste).setOnClickListener(x -> {
            String s = Ui.paste(c);
            text.setText(s);
            text.setSelection(text.length());
        });
        v.findViewById(R.id.tp_format).setOnClickListener(x -> {
            String f = CoordParser.format(text.getText().toString());
            if (f == null) {
                error.setVisibility(View.VISIBLE);
            } else {
                text.setText(f);
                text.setSelection(text.length());
            }
        });
        walk.setOnCheckedChangeListener((b, w) -> {
            labels.run();
            cb.onWalkModeChanged(w);
        });
        go.setOnClickListener(x -> {
            if (!CoordParser.parse(text.getText().toString(), ll)) {
                error.setVisibility(View.VISIBLE);
                return;
            }
            p.decided = true;
            p.dismiss();
            cb.onGo(ll[0], ll[1], walk.isChecked());
        });
        back.setOnClickListener(x -> {
            p.decided = true;
            p.dismiss();
            cb.onBack(walk.isChecked());
        });
        fav.setOnClickListener(x -> {
            p.decided = true;
            p.dismiss();
            cb.onFavorites();
        });
        v.findViewById(R.id.tp_cancel).setOnClickListener(x -> p.dismiss());

        p.host = PanelHost.show(c, v, true);
        p.host.setOnDismiss(() -> { if (!p.decided) cb.onCancel(); });
        return p;
    }

    public void dismiss() {
        if (host != null) host.dismiss();
    }
}
