package com.pokelocation.app.ui.main;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.pokelocation.app.R;
import com.pokelocation.app.data.Prefs;
import com.pokelocation.app.util.Perms;

public final class MockLocationDialog {
    private MockLocationDialog() {}

    /** Go to settings → Opções do desenvolvedor (não continua). Ignore → onContinue. Always ignore → grava e onContinue. */
    public static void show(AppCompatActivity a, Runnable onContinue) {
        show(a, onContinue, null);
    }

    /** onSettingsOpened: chamado se as Opções do desenvolvedor abriram (StartChecks retoma na volta). */
    static void show(AppCompatActivity a, Runnable onContinue, Runnable onSettingsOpened) {
        StartChecks.track(a, new MaterialAlertDialogBuilder(a)
                .setTitle(R.string.main_dlg_mock_title)
                .setMessage(R.string.main_dlg_mock_msg)
                .setPositiveButton(R.string.main_dlg_go_settings, (d, w) -> {
                    // Com as Opções do desenvolvedor desligadas a tela não existe (ou abre vazia).
                    if (!Perms.devOptionsEnabled(a)) StartChecks.message(a, R.string.main_dlg_mock_failed);
                    else if (StartChecks.open(a, Perms.devSettings(), R.string.main_dlg_mock_failed)
                            && onSettingsOpened != null) onSettingsOpened.run();
                })
                .setNegativeButton(R.string.main_dlg_ignore, (d, w) -> onContinue.run())
                .setNeutralButton(R.string.main_dlg_always_ignore, (d, w) -> {
                    Prefs.edit(a).putBoolean(Prefs.ALWAYS_IGNORE_MOCK_LOCATION, true).apply();
                    onContinue.run();
                })
                .show());
    }
}
