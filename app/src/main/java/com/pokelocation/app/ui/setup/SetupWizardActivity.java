package com.pokelocation.app.ui.setup;

import android.Manifest;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.core.view.WindowCompat;
import androidx.core.widget.ImageViewCompat;

import com.google.android.material.button.MaterialButtonToggleGroup;
import com.pokelocation.app.R;
import com.pokelocation.app.data.Prefs;
import com.pokelocation.app.ui.base.BaseActivity;
import com.pokelocation.app.ui.main.MainActivity;
import com.pokelocation.app.ui.panel.MessagePanel;
import com.pokelocation.app.util.Perms;
import com.pokelocation.app.util.Themes;
import com.pokelocation.app.util.Ui;

/**
 * Setup Wizard. Uma tela inflada por vez num host; estado em {@link Wizard};
 * revalidação só no onResume (volta das Configurações do sistema).
 */
public final class SetupWizardActivity extends BaseActivity {
    private static final int REQ_PERMISSION = 1;
    private static final String S_STATE = "setup_state", S_STEPS = "setup_steps", S_OVERLAY = "setup_overlay";

    // Recursos por passo, na ordem de Wizard.NOTIFICATION..MOCK.
    private static final int[] TITLES = {R.string.setup_notif_title, R.string.setup_location_title,
            R.string.setup_overlay_title, R.string.setup_dev_title, R.string.setup_mock_title};
    private static final int[] SHORT = {R.string.setup_notif_short, R.string.setup_location_short,
            R.string.setup_overlay_short, R.string.setup_dev_short, R.string.setup_mock_short};
    private static final int[] WHY = {R.string.setup_notif_why, R.string.setup_location_why,
            R.string.setup_overlay_why, R.string.setup_dev_why, R.string.setup_mock_why};
    private static final int[] ACTIONS = {R.string.setup_notif_action, R.string.setup_location_action,
            R.string.setup_overlay_action, R.string.setup_dev_action, R.string.setup_mock_action};
    private static final int[] STEP_LISTS = {R.array.setup_notif_steps, R.array.setup_location_steps,
            R.array.setup_overlay_steps, 0, R.array.setup_mock_steps};
    /** Por Wizard.FAMILY_*. */
    private static final int[] DEV_LISTS = {R.array.setup_dev_steps_samsung, R.array.setup_dev_steps_oneplus,
            R.array.setup_dev_steps_xiaomi, R.array.setup_dev_steps_default};
    private static final int[] ICONS = {R.drawable.ic_notification, R.drawable.ic_pin, R.drawable.ic_move,
            R.drawable.ic_settings, R.drawable.ic_target};
    private static final int[] STATUS_OK = {R.string.setup_status_granted, R.string.setup_status_granted,
            R.string.setup_status_granted, R.string.setup_status_enabled, R.string.setup_status_set};
    private static final int[] STATUS_NO = {R.string.setup_status_not_granted, R.string.setup_status_not_granted,
            R.string.setup_status_not_granted, R.string.setup_status_not_enabled, R.string.setup_status_not_set};

    private Wizard wizard;
    private FrameLayout host;
    /** Abrimos a tela de overlay do sistema: o próximo onResume trata o retorno. */
    private boolean pendingOverlay;
    private boolean adbTab;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        // Sem toolbar primária: fundo surface → ícones da barra de status escuros no tema claro.
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                .setAppearanceLightStatusBars(!Themes.isNight(this));
        host = new FrameLayout(this);
        setContentView(host);
        applyInsets(host);
        wizard = new Wizard(this::isOk, Prefs.getBool(this, Prefs.SETUP_WIZARD_COMPLETED));
        if (state != null) {
            wizard.restore(state.getIntArray(S_STATE), state.getIntArray(S_STEPS));
            pendingOverlay = state.getBoolean(S_OVERLAY);
        }
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() { back(); }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pendingOverlay) {
            pendingOverlay = false;
            wizard.overlayReturned(Perms.restrictedSettingsLikely(this));
        } else {
            wizard.revalidate();
        }
        render();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        out.putIntArray(S_STATE, wizard.save());
        out.putIntArray(S_STEPS, wizard.steps);
        out.putBoolean(S_OVERLAY, pendingOverlay);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_PERMISSION || wizard.screen != Wizard.STEP) return;
        int step = wizard.current();
        boolean granted = isOk(step);
        wizard.permissionResult(granted);
        // Fix no hub com notificações negadas em definitivo: o sistema não mostra mais o diálogo.
        if (!granted && step == Wizard.NOTIFICATION && wizard.fixing >= 0 && Build.VERSION.SDK_INT >= 33
                && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            openOrToast(Perms.notificationSettings(this));
        }
        render();
    }

    private boolean isOk(int step) {
        switch (step) {
            case Wizard.NOTIFICATION: return Perms.notificationsGranted(this);
            case Wizard.LOCATION: return Perms.hasFine(this);
            case Wizard.OVERLAY: return Perms.canDrawOverlays(this);
            case Wizard.DEV_OPTIONS: return Perms.devOptionsEnabled(this);
            default: return Perms.isMockApp(this);
        }
    }

    private void back() {
        switch (wizard.screen) {
            case Wizard.OVERLAY_DENIAL:
            case Wizard.LOCATION_DENIED:
                wizard.toStep();
                render();
                break;
            case Wizard.STEP:
                if (wizard.fixing >= 0) {
                    wizard.toComplete();
                    render();
                } else {
                    confirmExit();
                }
                break;
            case Wizard.WELCOME:
                confirmExit();
                break;
            default:
                finishWizard();
        }
    }

    private void confirmExit() {
        MessagePanel.show(this, getText(R.string.setup_exit_title), getText(R.string.setup_exit_msg),
                getText(R.string.setup_exit), getText(R.string.cancel), this::finishWizard);
    }

    /** "Start Using PokeLocation" / Exit: no 1º uso marca concluído e abre a Main; no hub só fecha. */
    private void finishWizard() {
        if (!wizard.hub) {
            Prefs.edit(this).putBoolean(Prefs.SETUP_WIZARD_COMPLETED, true).apply();
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
        }
        finish();
    }

    // Uma tela por vez: a anterior sai da hierarquia.

    private void render() {
        host.removeAllViews();
        switch (wizard.screen) {
            case Wizard.WELCOME: renderWelcome(); break;
            case Wizard.STEP: renderStep(); break;
            case Wizard.OVERLAY_DENIAL: renderDenial(); break;
            case Wizard.LOCATION_DENIED: renderLocationDenied(); break;
            default: renderComplete();
        }
    }

    private View inflate(int layout) {
        View v = getLayoutInflater().inflate(layout, host, false);
        host.addView(v);
        return v;
    }

    private void renderWelcome() {
        View v = inflate(R.layout.setup_welcome);
        StringBuilder b = new StringBuilder();
        for (int s : wizard.steps) {
            if (b.length() > 0) b.append('\n');
            b.append("•  ").append(getString(SHORT[s]));
        }
        ((TextView) v.findViewById(R.id.setup_pending)).setText(b);
        v.findViewById(R.id.setup_go).setOnClickListener(x -> {
            wizard.start();
            render();
        });
    }

    private void renderStep() {
        View v = inflate(R.layout.setup_step);
        int s = wizard.current();
        boolean fixing = wizard.fixing >= 0;

        ImageButton top = v.findViewById(R.id.setup_top);
        top.setImageResource(fixing ? R.drawable.ic_arrow_back : R.drawable.ic_close);
        top.setContentDescription(getText(fixing ? R.string.setup_back : R.string.setup_close));
        top.setOnClickListener(x -> back());
        ((TextView) v.findViewById(R.id.setup_counter))
                .setText(getString(R.string.setup_step_of, wizard.stepNumber(), wizard.stepTotal()));
        ((ImageView) v.findViewById(R.id.setup_icon)).setImageResource(ICONS[s]);
        ((TextView) v.findViewById(R.id.setup_title)).setText(TITLES[s]);
        ((TextView) v.findViewById(R.id.setup_why)).setText(WHY[s]);
        v.findViewById(R.id.setup_privacy).setVisibility(s == Wizard.LOCATION ? View.VISIBLE : View.GONE);

        TextView intro = v.findViewById(R.id.setup_intro);
        int list;
        if (s == Wizard.DEV_OPTIONS) {
            String brand = Wizard.brand(Build.MANUFACTURER);
            intro.setText(brand == null ? getString(R.string.setup_intro_generic) : getString(R.string.setup_intro_brand, brand));
            list = DEV_LISTS[Wizard.family(Build.MANUFACTURER)];
        } else {
            intro.setText(s == Wizard.MOCK ? R.string.setup_intro_mock : R.string.setup_intro);
            list = STEP_LISTS[s];
        }
        ((TextView) v.findViewById(R.id.setup_list)).setText(numbered(getResources().getStringArray(list)));

        TextView tip = v.findViewById(R.id.setup_tip);
        if (s == Wizard.DEV_OPTIONS || s == Wizard.MOCK) {
            tip.setText(s == Wizard.DEV_OPTIONS ? R.string.setup_tip_dev : R.string.setup_tip_mock);
            tip.setVisibility(View.VISIBLE);
        }
        v.findViewById(R.id.setup_error).setVisibility(wizard.verificationError ? View.VISIBLE : View.GONE);

        Button primary = v.findViewById(R.id.setup_primary);
        primary.setText(ACTIONS[s]);
        primary.setOnClickListener(x -> onPrimary(s));

        View done = v.findViewById(R.id.setup_done);
        done.setVisibility(wizard.showIveDone() ? View.VISIBLE : View.GONE);
        done.setOnClickListener(x -> {
            wizard.iveDone();
            render();
        });

        Button skip = v.findViewById(R.id.setup_skip);
        skip.setText(fixing ? R.string.setup_back_to_setup : R.string.setup_skip);
        skip.setOnClickListener(x -> {
            if (wizard.fixing >= 0) wizard.toComplete();
            else wizard.skip();
            render();
        });
    }

    private void onPrimary(int step) {
        wizard.primary();
        switch (step) {
            case Wizard.NOTIFICATION:
                if (Build.VERSION.SDK_INT >= 33) {
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_PERMISSION);
                    return;
                }
                wizard.revalidate();   // abaixo do 33 o passo já está satisfeito
                break;
            case Wizard.LOCATION:
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_PERMISSION);
                return;
            case Wizard.OVERLAY:
                openOverlaySettings();
                break;
            case Wizard.DEV_OPTIONS:
                openOrToast(new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS));
                break;
            default:
                openOrToast(Perms.devSettings());
        }
        render();
    }

    private void openOverlaySettings() {
        pendingOverlay = open(Perms.overlaySettings(this))
                || openOrToast(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
    }

    /** Abre a tela pedida; senão as Configurações gerais; senão avisa. */
    private boolean openOrToast(Intent i) {
        if (open(i) || open(new Intent(Settings.ACTION_SETTINGS))) return true;
        Ui.toast(this, R.string.setup_open_failed);
        return false;
    }

    private boolean open(Intent i) {
        try {
            startActivity(i);
            return true;
        } catch (RuntimeException e) {   // ActivityNotFoundException / SecurityException
            return false;
        }
    }

    private void renderDenial() {
        View v = inflate(R.layout.setup_denial);
        v.findViewById(R.id.setup_top).setOnClickListener(x -> back());
        ((TextView) v.findViewById(R.id.setup_list))
                .setText(numbered(getResources().getStringArray(R.array.setup_denial_steps)));

        View settingsBlock = v.findViewById(R.id.setup_block_settings);
        View adbBlock = v.findViewById(R.id.setup_block_adb);
        MaterialButtonToggleGroup tabs = v.findViewById(R.id.setup_tabs);
        tabs.check(adbTab ? R.id.setup_tab_adb : R.id.setup_tab_settings);
        settingsBlock.setVisibility(adbTab ? View.GONE : View.VISIBLE);
        adbBlock.setVisibility(adbTab ? View.VISIBLE : View.GONE);
        tabs.addOnButtonCheckedListener((g, id, checked) -> {
            if (!checked) return;
            adbTab = id == R.id.setup_tab_adb;
            settingsBlock.setVisibility(adbTab ? View.GONE : View.VISIBLE);
            adbBlock.setVisibility(adbTab ? View.VISIBLE : View.GONE);
        });

        v.findViewById(R.id.setup_open_info).setOnClickListener(x -> openOrToast(Perms.appDetails(this)));
        v.findViewById(R.id.setup_try_again).setOnClickListener(x -> openOverlaySettings());

        String cmd = getString(R.string.setup_adb_cmd, getPackageName());
        ((TextView) v.findViewById(R.id.setup_cmd)).setText(cmd);
        v.findViewById(R.id.setup_cmd_card).setOnClickListener(x -> Ui.copy(this, cmd, true));
    }

    private void renderLocationDenied() {
        View v = inflate(R.layout.setup_location_denied);
        v.findViewById(R.id.setup_top).setOnClickListener(x -> back());
        v.findViewById(R.id.setup_open_settings).setOnClickListener(x -> openOrToast(Perms.appDetails(this)));
    }

    private void renderComplete() {
        View v = inflate(R.layout.setup_complete);
        LinearLayout rows = v.findViewById(R.id.setup_rows);
        boolean allOk = true;
        int danger = getColor(R.color.danger), success = getColor(R.color.success),
                muted = getColor(R.color.on_surface_variant);
        for (int s = 0; s < Wizard.COUNT; s++) {
            boolean ok = isOk(s);
            allOk &= ok;
            View row = getLayoutInflater().inflate(R.layout.setup_row, rows, false);
            ((ImageView) row.findViewById(R.id.setup_row_icon)).setImageResource(ICONS[s]);
            ((TextView) row.findViewById(R.id.setup_row_name)).setText(TITLES[s]);
            TextView status = row.findViewById(R.id.setup_row_status);
            boolean skipped = !ok && wizard.isSkipped(s);
            status.setText(ok ? STATUS_OK[s] : skipped ? R.string.setup_status_skipped : STATUS_NO[s]);
            status.setTextColor(ok ? success : skipped ? muted : danger);
            View fix = row.findViewById(R.id.setup_row_fix);
            fix.setVisibility(ok ? View.GONE : View.VISIBLE);
            int step = s;
            fix.setOnClickListener(x -> {
                wizard.fix(step);
                render();
            });
            rows.addView(row);
        }
        ImageView icon = v.findViewById(R.id.setup_icon);
        icon.setImageResource(allOk ? R.drawable.ic_check : R.drawable.ic_info);
        ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(allOk ? success : getColor(R.color.warning)));
        ((TextView) v.findViewById(R.id.setup_title)).setText(allOk ? R.string.setup_all_set : R.string.setup_incomplete);
        ((TextView) v.findViewById(R.id.setup_subtitle)).setText(allOk ? R.string.setup_all_set_text
                : wizard.skipped != 0 ? R.string.setup_incomplete_skipped : R.string.setup_incomplete_attention);
        v.findViewById(R.id.setup_finish).setOnClickListener(x -> finishWizard());
    }

    private static String numbered(String[] items) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < items.length; i++) {
            if (i > 0) b.append('\n');
            b.append(i + 1).append(".  ").append(items[i]);
        }
        return b.toString();
    }
}
