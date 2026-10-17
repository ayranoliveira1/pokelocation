package com.pokelocation.app.ui.gpx;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.pokelocation.app.R;
import com.pokelocation.app.map.MapHelper;
import com.pokelocation.app.util.Ui;

/** "Add As New Marker": cria um tipo de marcador (nome pré-preenchido + ícone + cor). */
final class NewMarkerTypeDialog {
    interface Callback { void onCreate(String name, String icon, int color); }

    /** Mesma paleta do editor de tipos (sem roxo). */
    private static final int[] COLORS = {0xFF1E88E5, 0xFF0D47A1, 0xFF43A047, 0xFF00897B,
            0xFFFB8C00, 0xFFE53935, 0xFFFDD835, 0xFF6D4C41};

    private final Context c;
    private final ImageView[] icons = new ImageView[MapHelper.ICON_KEYS.length];
    private final View[] swatches = new View[COLORS.length];
    private int icon, color;

    private NewMarkerTypeDialog(Context c) { this.c = c; }

    static void show(Context c, String name, Callback cb) {
        new NewMarkerTypeDialog(c).open(name, cb);
    }

    private void open(String name, Callback cb) {
        View v = View.inflate(c, R.layout.gpx_new_type, null);
        EditText et = v.findViewById(R.id.gpx_type_name);
        if (name != null) et.setText(name);
        LinearLayout iconRow = v.findViewById(R.id.gpx_type_icons), colorRow = v.findViewById(R.id.gpx_type_colors);
        int size = Ui.dp(c, 40), pad = Ui.dp(c, 8), gap = Ui.dp(c, 4);
        for (int i = 0; i < icons.length; i++) {
            ImageView iv = new ImageView(c);
            iv.setImageResource(iconRes(MapHelper.ICON_KEYS[i]));
            iv.setPadding(pad, pad, pad, pad);
            iv.setContentDescription(c.getString(R.string.gpx_cd_icon, i + 1));
            final int idx = i;
            iv.setOnClickListener(x -> { icon = idx; refresh(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, size, 1f);   // divide a largura: nada cortado
            lp.setMarginEnd(gap);
            iconRow.addView(iv, lp);
            icons[i] = iv;
        }
        int sw = Ui.dp(c, 28);
        for (int i = 0; i < swatches.length; i++) {
            View s = new View(c);
            final int idx = i;
            s.setOnClickListener(x -> { color = idx; refresh(); });
            s.setContentDescription(c.getString(R.string.gpx_cd_color, i + 1));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(sw, sw);
            lp.setMarginEnd(Ui.dp(c, 6));
            lp.gravity = Gravity.CENTER_VERTICAL;
            colorRow.addView(s, lp);
            swatches[i] = s;
        }
        refresh();

        AlertDialog d = new MaterialAlertDialogBuilder(c)
                .setTitle(R.string.gpx_new_type_title)
                .setView(v)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, (di, w) -> {
                    String n = et.getText().toString().trim();
                    if (!n.isEmpty()) cb.onCreate(n, MapHelper.ICON_KEYS[icon], COLORS[color]);
                })
                .show();
        Button ok = d.getButton(AlertDialog.BUTTON_POSITIVE);
        ok.setEnabled(et.getText().toString().trim().length() > 0);
        et.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int n) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int n) {}
            @Override public void afterTextChanged(Editable s) { ok.setEnabled(s.toString().trim().length() > 0); }
        });
    }

    private void refresh() {
        int col = COLORS[color];
        int stroke = Ui.dp(c, 2);
        int ring = ContextCompat.getColor(c, R.color.on_surface);
        for (int i = 0; i < icons.length; i++) {
            icons[i].setImageTintList(ColorStateList.valueOf(col));
            GradientDrawable bg = null;
            if (i == icon) {
                bg = new GradientDrawable();
                bg.setShape(GradientDrawable.OVAL);
                bg.setColor(ContextCompat.getColor(c, R.color.primary_container));
            }
            icons[i].setBackground(bg);
        }
        for (int i = 0; i < swatches.length; i++) {
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(COLORS[i]);
            if (i == color) g.setStroke(stroke, ring);
            swatches[i].setBackground(g);
        }
    }

    private static int iconRes(String key) {
        switch (key) {
            case "flag": return R.drawable.ic_flag;
            case "park": return R.drawable.ic_park;
            case "star": return R.drawable.ic_star;
            case "home": return R.drawable.ic_home;
            case "heart": return R.drawable.ic_favorite;
            default: return R.drawable.ic_pin;
        }
    }
}
