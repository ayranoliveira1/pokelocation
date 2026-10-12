package com.pokelocation.app.ui.main;

import android.content.SharedPreferences;
import android.view.View;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.pokelocation.app.R;
import com.pokelocation.app.data.Prefs;

/** "Fix GPS Location" (F19, 11 §3.4): Default / Fix 1 (A-GPS) / Fix 2 (100 ms). Não cancelável. */
public final class GpsFixDialog {
    private GpsFixDialog() {}

    private static final int FAST_MS = 100, NORMAL_MS = 1000;   // desmarcar Fix 2 volta a 1000 (DECISIONS dúvida 5)

    /** Grava no Close (commit) e chama onChanged só se algo mudou. Quem chama reinicia o serviço se ele estiver rodando. */
    public static void show(AppCompatActivity a, Runnable onChanged) {
        View v = a.getLayoutInflater().inflate(R.layout.main_dialog_gps_fix, null);
        CheckBox def = v.findViewById(R.id.main_dlg_fix_default);
        CheckBox fix1 = v.findViewById(R.id.main_dlg_fix_1);
        CheckBox fix2 = v.findViewById(R.id.main_dlg_fix_2);
        boolean agps0 = Prefs.getBool(a, Prefs.SETTINGS_ENABLE_AGPS_RESET);
        boolean fast0 = Prefs.getInt(a, Prefs.SETTINGS_PROCESS_INTERVAL) == FAST_MS;
        fix1.setChecked(agps0);
        fix2.setChecked(fast0);
        def.setChecked(!agps0 && !fast0);
        if (Prefs.getBool(a, Prefs.SUSPENDED_MOCKING)) {
            fix2.setEnabled(false);
            ((TextView) v.findViewById(R.id.main_dlg_fix_2_desc)).setText(R.string.main_dlg_fix_2_suspended);
        }

        def.setOnCheckedChangeListener((b, checked) -> {
            if (checked) {
                fix1.setChecked(false);
                fix2.setChecked(false);
            } else if (!fix1.isChecked() && !fix2.isChecked()) {
                def.setChecked(true);   // Default = "nenhuma Fix": não se desmarca sozinho
            }
        });
        CheckBox.OnCheckedChangeListener onFix = (b, checked) -> {
            if (checked) def.setChecked(false);
            else if (!fix1.isChecked() && !fix2.isChecked()) def.setChecked(true);
        };
        fix1.setOnCheckedChangeListener(onFix);
        fix2.setOnCheckedChangeListener(onFix);

        StartChecks.track(a, new MaterialAlertDialogBuilder(a)
                .setTitle(R.string.main_dlg_fix_title)
                .setView(v)
                .setCancelable(false)
                .setPositiveButton(R.string.close, (d, w) -> {
                    boolean agps = fix1.isChecked(), fast = fix2.isChecked();
                    if (agps == agps0 && fast == fast0) return;
                    SharedPreferences.Editor e = Prefs.edit(a).putBoolean(Prefs.SETTINGS_ENABLE_AGPS_RESET, agps);
                    if (fast != fast0) e.putInt(Prefs.SETTINGS_PROCESS_INTERVAL, fast ? FAST_MS : NORMAL_MS);
                    e.commit();   // o serviço reiniciado lê as prefs no onCreate de um processo novo
                    onChanged.run();
                })
                .show());
    }
}
