package com.pokelocation.app.ui.main;

import android.Manifest;
import android.app.Dialog;
import android.content.Intent;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.pokelocation.app.R;
import com.pokelocation.app.data.Prefs;
import com.pokelocation.app.util.Perms;

import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Checagens do Start: GPS → overlay → notificação (33+) → mock app → FINE (divulgação).
 * Construir no onCreate da Activity (registra os launchers antes de STARTED).
 */
public final class StartChecks {
    private static final String[] LOCATION = {
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION};

    private final AppCompatActivity a;
    private final Runnable onPassed;
    private final ActivityResultLauncher<String[]> fineLauncher;
    private final ActivityResultLauncher<String> notifLauncher;
    private Runnable afterFine;
    // Volta de uma tela de Settings aberta por uma checagem: se o problema foi resolvido, continua.
    private BooleanSupplier resumeCheck;
    private Runnable resumeThen;

    public StartChecks(AppCompatActivity a, Runnable onPassed) {
        this.a = a;
        this.onPassed = onPassed;
        // Android 12+ ignora pedido de FINE sem COARSE.
        fineLauncher = a.registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), this::onFineResult);
        notifLauncher = a.registerForActivityResult(new ActivityResultContracts.RequestPermission(), this::onNotifResult);
        a.getLifecycle().addObserver(new DefaultLifecycleObserver() {
            @Override public void onResume(@NonNull LifecycleOwner owner) {
                BooleanSupplier c = resumeCheck;
                Runnable then = resumeThen;
                resumeCheck = null;
                resumeThen = null;
                if (c != null && c.getAsBoolean()) then.run();
            }
        });
    }

    /** Roda a cadeia; tudo ok → onPassed. Para no primeiro que falha (diálogo/pedido) e retoma sozinho ao conceder. */
    public void run() {
        if (!Perms.gpsEnabled(a)) {
            ask(R.string.main_dlg_gps_title, R.string.main_dlg_gps_msg, Perms.locationSourceSettings(),
                    () -> Perms.gpsEnabled(a), this::run);
            return;
        }
        if (!Perms.canDrawOverlays(a)) {
            ask(R.string.main_dlg_draw_title, R.string.main_dlg_draw_msg, Perms.overlaySettings(a),
                    () -> Perms.canDrawOverlays(a), this::run);
            return;
        }
        if (!Perms.notificationsGranted(a)) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            return;
        }
        if (!Perms.isMockApp(a) && !Prefs.getBool(a, Prefs.ALWAYS_IGNORE_MOCK_LOCATION)) {
            MockLocationDialog.show(a, () -> requestFine(onPassed), () -> {
                resumeCheck = () -> Perms.isMockApp(a);
                resumeThen = this::run;
            });
            return;
        }
        requestFine(onPassed);
    }

    /** Só a FINE: divulgação → pedido. onGranted roda na main. */
    public void requestFine(Runnable onGranted) {
        if (Perms.hasFine(a)) {
            onGranted.run();
            return;
        }
        afterFine = onGranted;
        track(a, new MaterialAlertDialogBuilder(a)
                .setTitle(R.string.main_dlg_disclosure_title)
                .setMessage(R.string.main_dlg_disclosure_msg)
                .setPositiveButton(R.string.main_dlg_continue, (d, w) -> fineLauncher.launch(LOCATION))
                .setNegativeButton(R.string.cancel, null)
                .show());
    }

    private void onFineResult(Map<String, Boolean> result) {
        Runnable then = afterFine;
        afterFine = null;
        if (then == null) return;   // Activity recriada no meio do pedido: o usuário toca de novo
        if (Perms.hasFine(a)) then.run();
        else ask(R.string.main_dlg_location_title, R.string.main_dlg_location_msg, Perms.appDetails(a),
                () -> Perms.hasFine(a), then);
    }

    private void onNotifResult(Boolean granted) {
        if (Boolean.TRUE.equals(granted)) run();
        else ask(R.string.main_dlg_notification_title, R.string.main_dlg_notification_msg, Perms.notificationSettings(a),
                () -> Perms.notificationsGranted(a), this::run);
    }

    private void ask(int title, int msg, Intent settings, BooleanSupplier fixed, Runnable then) {
        track(a, new MaterialAlertDialogBuilder(a)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton(R.string.main_dlg_settings, (d, w) -> {
                    if (open(a, settings, R.string.main_dlg_open_failed)) {
                        resumeCheck = fixed;
                        resumeThen = then;
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show());
    }

    static boolean open(AppCompatActivity a, Intent i, int failMsg) {
        try {
            a.startActivity(i);
            return true;
        } catch (RuntimeException e) {
            message(a, failMsg);
            return false;
        }
    }

    static void message(AppCompatActivity a, int msg) {
        track(a, new MaterialAlertDialogBuilder(a)
                .setMessage(msg)
                .setPositiveButton(R.string.ok, null)
                .show());
    }

    /** Fecha o diálogo no ON_DESTROY da Activity (sem window leak). Usa o OnDismissListener do diálogo. */
    static <D extends Dialog> D track(AppCompatActivity a, D d) {
        DefaultLifecycleObserver o = new DefaultLifecycleObserver() {
            @Override public void onDestroy(@NonNull LifecycleOwner owner) {
                d.dismiss();
            }
        };
        a.getLifecycle().addObserver(o);
        d.setOnDismissListener(x -> a.getLifecycle().removeObserver(o));
        return d;
    }
}
