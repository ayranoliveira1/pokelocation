package com.pokelocation.app.ui.main;

import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.pokelocation.app.R;
import com.pokelocation.app.data.Prefs;
import com.pokelocation.app.geo.Cooldown;
import com.pokelocation.app.geo.Geo;
import com.pokelocation.app.model.Coord;
import com.pokelocation.app.util.Ui;

/** Bottom sheet "Cooldown Times". Sem Fragment; ticker 1 s só com a sheet aberta e a Activity visível. */
public final class CooldownSheet implements DefaultLifecycleObserver, Runnable {
    private final AppCompatActivity a;
    private final BottomSheetDialog dialog;
    private final TextView status, details;
    private final double meters;
    private final long cooldownMs, lastMocked;
    private final String distanceText, cooldownText;

    /** current = posição simulada, next = destino do rádio. Algum null ou last_mocked_time == 0 → só a tabela. */
    public static void show(AppCompatActivity a, Coord current, Coord next) {
        new CooldownSheet(a, current, next);
    }

    private CooldownSheet(AppCompatActivity a, Coord current, Coord next) {
        this.a = a;
        lastMocked = Prefs.getLong(a, Prefs.LAST_MOCKED_TIME);
        boolean live = current != null && next != null && lastMocked != 0;
        meters = live ? Geo.distance(current.lat, current.lng, next.lat, next.lng) : 0;
        int tier = live ? Cooldown.tier(meters) : -1;
        cooldownMs = live ? Cooldown.cooldownMs(tier) : 0;
        distanceText = live ? Cooldown.formatDistance(meters) : null;
        cooldownText = live ? Cooldown.formatDuration(cooldownMs) : null;

        LayoutInflater inf = a.getLayoutInflater();
        View root = inf.inflate(R.layout.main_sheet_cooldown, null);
        GradientDrawable bg = new GradientDrawable();
        float r = Ui.dp(a, 20);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        bg.setColor(ContextCompat.getColor(a, R.color.surface_container));
        root.setBackground(bg);
        status = root.findViewById(R.id.main_sheet_status);
        details = root.findViewById(R.id.main_sheet_details);
        status.setVisibility(live ? View.VISIBLE : View.GONE);
        details.setVisibility(live ? View.VISIBLE : View.GONE);
        root.findViewById(R.id.main_sheet_empty).setVisibility(live ? View.GONE : View.VISIBLE);

        ViewGroup rows = root.findViewById(R.id.main_sheet_rows);
        int hiBg = ContextCompat.getColor(a, R.color.primary_container);
        int hiFg = ContextCompat.getColor(a, R.color.on_primary_container);
        for (int i = 0; i < Cooldown.TIERS; i++) {
            View row = inf.inflate(R.layout.main_sheet_cooldown_row, rows, false);
            TextView label = row.findViewById(R.id.main_sheet_row_label);
            TextView value = row.findViewById(R.id.main_sheet_row_value);
            label.setText(Cooldown.tierLabel(i));
            value.setText(Cooldown.formatDuration(Cooldown.cooldownMs(i)));
            if (i == tier) {
                GradientDrawable hi = new GradientDrawable();
                hi.setCornerRadius(Ui.dp(a, 8));
                hi.setColor(hiBg);
                row.setBackground(hi);
                label.setTextColor(hiFg);
                value.setTextColor(hiFg);
                label.setTypeface(Typeface.DEFAULT_BOLD);
                value.setTypeface(Typeface.DEFAULT_BOLD);
            }
            rows.addView(row);
        }

        dialog = new BottomSheetDialog(a);
        dialog.setContentView(root);
        BottomSheetBehavior<?> b = dialog.getBehavior();
        b.setSkipCollapsed(true);
        b.setState(BottomSheetBehavior.STATE_EXPANDED);
        dialog.setOnDismissListener(d -> {
            details.removeCallbacks(this);
            a.getLifecycle().removeObserver(this);
        });
        dialog.show();
        a.getLifecycle().addObserver(this);   // Activity já STARTED → onStart na hora → primeiro tick
    }

    @Override public void onStart(@NonNull LifecycleOwner owner) {
        if (distanceText != null) run();
    }

    @Override public void onStop(@NonNull LifecycleOwner owner) {
        details.removeCallbacks(this);
    }

    @Override public void onDestroy(@NonNull LifecycleOwner owner) {
        dialog.dismiss();
    }

    /** Tick 1 s: só elapsed/remaining/status mudam (posições fixas enquanto a sheet está aberta). */
    @Override public void run() {
        long elapsed = Math.max(0, System.currentTimeMillis() - lastMocked);
        boolean ready = Cooldown.ready(meters, elapsed);
        status.setText(ready ? R.string.main_dlg_cd_ready : R.string.main_dlg_cd_cooling);
        status.setTextColor(ContextCompat.getColor(a, ready ? R.color.success : R.color.danger));
        details.setText(a.getString(R.string.main_dlg_cd_details, distanceText, cooldownText,
                Cooldown.formatElapsed(a, elapsed), Cooldown.formatDuration(Math.max(0, cooldownMs - elapsed))));
        details.removeCallbacks(this);
        details.postDelayed(this, 1000);
    }
}
