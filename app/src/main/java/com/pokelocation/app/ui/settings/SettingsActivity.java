package com.pokelocation.app.ui.settings;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.pokelocation.app.Io;
import com.pokelocation.app.R;
import com.pokelocation.app.data.Prefs;
import com.pokelocation.app.map.MapHelper;
import com.pokelocation.app.social.Session;
import com.pokelocation.app.social.net.Api;
import com.pokelocation.app.social.ui.LoginActivity;
import com.pokelocation.app.ipc.Intents;
import com.pokelocation.app.ui.base.BaseActivity;
import com.pokelocation.app.ui.main.MainActivity;
import com.pokelocation.app.util.Perms;
import com.pokelocation.app.util.PrivacyModeManager;
import com.pokelocation.app.util.Themes;
import com.pokelocation.app.util.Ui;

import java.text.Collator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Configurações (F89–F94). Tela própria (sem androidx.preference/Fragments): ~35 linhas infladas uma vez.
 * Valores lidos das prefs em refresh() (onResume e após cada mudança) e gravados na hora; só o main escreve.
 * Mudanças valem no próximo start do joystick (o :overlay lê o snapshot no onCreate).
 */
public final class SettingsActivity extends BaseActivity {
    private static final int INT = 0, FLOAT = 1, DOUBLE = 2;
    private static final String[] LANG_VALUES = {"default", "en", "pt-BR"};
    private static final String[] THEME_VALUES = {Themes.SYSTEM, Themes.LIGHT, Themes.DARK};
    private static final String[] TILE_VALUES = {MapHelper.TILES_OSM, MapHelper.TILES_OSM_DE};
    private static final String[] JOYSTICK_VALUES = {"drawer", "classic"};
    private static final int INTERVAL_MIN = 50, INTERVAL_MAX = 3_600_000;
    private static final int AUTO_SUSPEND_MIN = 5, AUTO_SUSPEND_MAX = 3600;
    private static final int WAIT_STEP_MS = 300, WAIT_TRIES = 20;

    /** Uma linha da tela. bind relê o valor das prefs. */
    private static final class Row {
        View root;
        TextView title, summary, value;
        MaterialSwitch sw;
        Runnable bind;

        void setEnabled(boolean e) {
            root.setEnabled(e);
            root.setAlpha(e ? 1f : 0.38f);
        }
    }

    private LinearLayout list;
    private TextView hint;
    private final ArrayList<Row> rows = new ArrayList<>();
    private Row autoSuspend, autoSuspendInterval, indirect, systemMode, autoStartApp;
    private final Row[] intervals = new Row[3];

    private String[] appLabels, appPackages;   // índice 0 = "None"
    private Io.Task appsTask, dbTask;
    private AlertDialog progress;
    private int waitTries;
    private final Runnable waitOverlay = this::waitOverlayThenSwap;

    private ActivityResultLauncher<String> createDoc;
    private ActivityResultLauncher<String[]> openDoc;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.set_activity);
        setupToolbar(getString(R.string.set_title), true);
        applyInsets(findViewById(R.id.set_root));
        list = findViewById(R.id.set_list);
        hint = findViewById(R.id.set_hint);

        createDoc = registerForActivityResult(new ActivityResultContracts.CreateDocument("application/octet-stream"), this::onBackupTarget);
        openDoc = registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::onRestoreSource);

        buildAccount();
        buildGeneral();
        buildJoystick();
        buildBackup();
        buildAdvanced();
        loadApps();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hint.setVisibility(Intents.isOverlayRunning(this) ? View.VISIBLE : View.GONE);
        refresh();
    }

    @Override
    protected void onDestroy() {
        if (appsTask != null) appsTask.cancel();
        if (dbTask != null) dbTask.cancel();
        Io.main().removeCallbacks(waitOverlay);
        dismissProgress();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- seções

    private void buildGeneral() {
        category(R.string.set_cat_general);
        listRow(R.string.set_language, R.array.set_language_entries, LANG_VALUES, this::currentLanguage, v ->
                AppCompatDelegate.setApplicationLocales("default".equals(v)
                        ? LocaleListCompat.getEmptyLocaleList() : LocaleListCompat.forLanguageTags(v)));
        listRow(R.string.set_theme, R.array.set_theme_entries, THEME_VALUES,
                () -> Prefs.getString(this, Prefs.SETTINGS_THEME_MODE), v -> {
                    putString(Prefs.SETTINGS_THEME_MODE, v);
                    Themes.applyNightMode(v);
                });
        listRow(R.string.set_map_style, R.array.set_map_style_entries, TILE_VALUES,
                () -> Prefs.getString(this, Prefs.MAP_TILE_SOURCE), v -> putString(Prefs.MAP_TILE_SOURCE, v));
        switchRow(Prefs.ELEVATION_LOOKUP_ENABLED, R.string.set_elevation, R.string.set_elevation_summary);
        row(R.string.set_dev_options, R.string.set_dev_options_summary, v -> openDevOptions());
        switchRow(Prefs.ALWAYS_IGNORE_MOCK_LOCATION, R.string.set_ignore_mock, R.string.set_ignore_mock_summary);
        switchRow(Prefs.PREVENT_SCREENSHOTS, R.string.set_prevent_screenshots, R.string.set_prevent_screenshots_summary);
        switchRow(Prefs.DISPLAY_DISTANCE_INFO, R.string.set_distance_info, R.string.set_distance_info_summary);

        // Modos de mock (F90, 11 §4.2)
        Row suspended = switchRow(Prefs.SUSPENDED_MOCKING, R.string.set_suspended, R.string.set_suspended_summary);
        suspended.root.setOnClickListener(v -> toggleSuspended());
        autoSuspend = switchRow(Prefs.AUTO_SUSPEND_LOCATION, R.string.set_auto_suspend, R.string.set_auto_suspend_summary);
        autoSuspendInterval = numberRow(Prefs.AUTO_SUSPEND_INTERVAL, INT, R.string.set_auto_suspend_interval,
                R.string.set_auto_suspend_interval_summary, R.string.set_unit_s, AUTO_SUSPEND_MIN, AUTO_SUSPEND_MAX);
        indirect = switchRow(Prefs.INDIRECT_MOCKING, R.string.set_indirect, R.string.set_indirect_summary);
        if (Build.VERSION.SDK_INT < 29 && getPackageManager().hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS)) {
            systemMode = switchRow(Prefs.SYSTEM_MODE, R.string.set_system_mode, R.string.set_system_mode_summary);
            systemMode.root.setOnClickListener(v -> {
                if (Prefs.getBool(this, Prefs.SYSTEM_MODE)) {
                    Prefs.edit(this).putBoolean(Prefs.SUSPENDED_MOCKING, false)
                            .putBoolean(Prefs.INDIRECT_MOCKING, false).apply();
                }
                refresh();
            });
        } else {
            row(R.string.set_system_mode, R.string.set_system_mode_disabled, null);
        }
        switchRow(Prefs.SETTINGS_ENABLE_AGPS_RESET, R.string.set_agps, R.string.set_agps_summary);
        row(R.string.set_privacy_mode, R.string.set_privacy_mode_summary, v -> showPrivacyModeDialog());

        switchRow(Prefs.SETTINGS_SAVE_LOCATION, R.string.set_save_location, R.string.set_save_location_summary);
        switchRow(Prefs.SETTINGS_AUTO_CLOSE_APP, R.string.set_auto_close, R.string.set_auto_close_summary);
        switchRow(Prefs.SETTINGS_AUTO_START_JOYSTICK, R.string.set_auto_start_joystick, R.string.set_auto_start_joystick_summary);
        autoStartApp = row(R.string.set_auto_start_app, 0, v -> pickApp());
        autoStartApp.summary.setVisibility(View.VISIBLE);
        autoStartApp.bind = () -> {
            String name = Prefs.getString(this, Prefs.SETTINGS_AUTO_START_APP_NAME);
            autoStartApp.summary.setText(appLabels == null ? getString(R.string.loading)
                    : name.isEmpty() ? getString(R.string.set_none) : name);
        };
    }

    /** Conta social (opcional): sem login abre o login; com login mostra o email e oferece sair. */
    private void buildAccount() {
        category(R.string.soc_account);
        String[] email = {null};
        Io.Task[] me = {null};
        Context app = getApplicationContext();
        getLifecycle().addObserver((LifecycleEventObserver) (owner, ev) -> {
            if (ev == Lifecycle.Event.ON_DESTROY && me[0] != null) me[0].cancel();
        });
        Row r = row(R.string.soc_account_row, 0, v -> {
            if (!Session.loggedIn(this)) {
                startActivity(new Intent(this, LoginActivity.class));
                return;
            }
            new MaterialAlertDialogBuilder(this)
                    .setTitle(email[0] != null ? email[0] : getString(R.string.soc_account))
                    .setMessage(R.string.soc_logout_confirm)
                    .setPositiveButton(R.string.soc_logout, (d, w) -> {
                        if (me[0] != null) me[0].cancel();
                        me[0] = Io.run(() -> {
                            Api.logout(app);
                            return Boolean.TRUE;
                        }, new Io.Callback<Boolean>() {
                            @Override public void onResult(Boolean ok) {
                                me[0] = null;
                                email[0] = null;
                                if (!dead()) refresh();
                            }
                        });
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        });
        r.summary.setVisibility(View.VISIBLE);
        // Só grava (arquivo social_share); o LocationSharer do :overlay (A8) lê a cada tick e envia o LocationOff
        Row share = row(R.string.soc_share_location, R.string.soc_share_location_summary, v -> {
            Prefs.setShareLocation(this, !Prefs.shareLocation(this));
            refresh();
        });
        share.sw.setVisibility(View.VISIBLE);
        share.bind = () -> {
            share.sw.setChecked(Prefs.shareLocation(this));
            share.root.setVisibility(Session.loggedIn(this) ? View.VISIBLE : View.GONE);
        };
        r.bind = () -> {
            boolean in = Session.loggedIn(this);
            if (!in) email[0] = null;
            r.summary.setText(!in ? getString(R.string.soc_account_signed_out)
                    : email[0] != null ? email[0] : getString(R.string.soc_account_signed_in));
            if (!in || email[0] != null || me[0] != null) return;
            me[0] = Io.run(() -> Api.me(app), new Io.Callback<Api.Me>() {
                @Override public void onResult(Api.Me m) {
                    me[0] = null;
                    email[0] = m.email;
                    if (!dead()) refresh();
                }
                @Override public void onError(Throwable t) {
                    me[0] = null;   // sem rede: fica "Conectado" e tenta de novo no próximo onResume
                    if (!Session.loggedIn(app) && !dead()) refresh();   // 401: a Api já limpou a sessão
                }
            });
        };
    }

    private void buildJoystick() {
        category(R.string.set_cat_joystick);
        listRow(R.string.set_joystick_type, R.array.set_joystick_type_entries, JOYSTICK_VALUES,
                () -> Prefs.getString(this, Prefs.SETTINGS_JOYSTICK_TYPE), v -> putString(Prefs.SETTINGS_JOYSTICK_TYPE, v));
        sliderRow(Prefs.SETTINGS_JOYSTICK_SIZE, R.string.set_joystick_size, 75, 200);
        sliderRow(Prefs.SETTINGS_JOYSTICK_OPACITY, R.string.set_joystick_opacity, 10, 100);
        numberRow(Prefs.SETTINGS_WALKING_SPEED, FLOAT, R.string.set_walking_speed, 0, R.string.set_unit_kmh, 0.1, Float.MAX_VALUE);
        numberRow(Prefs.SETTINGS_JOGGING_SPEED, FLOAT, R.string.set_jogging_speed, 0, R.string.set_unit_kmh, 0.1, Float.MAX_VALUE);
        numberRow(Prefs.SETTINGS_RUNNING_SPEED, FLOAT, R.string.set_running_speed, 0, R.string.set_unit_kmh, 0.1, Float.MAX_VALUE);
    }

    private void buildBackup() {
        category(R.string.set_cat_backup);
        row(R.string.set_save_db, R.string.set_save_db_summary, v -> launch(() -> createDoc.launch(DbBackup.fileName())));
        row(R.string.set_restore_db, R.string.set_restore_db_summary, v -> launch(() -> openDoc.launch(new String[]{"*/*"})));
    }

    private void buildAdvanced() {
        category(R.string.set_cat_advanced);
        row(0, R.string.set_advanced_warning, null);
        intervals[0] = numberRow(Prefs.SETTINGS_PROCESS_INTERVAL, INT, R.string.set_process_interval,
                R.string.set_process_interval_summary, R.string.set_unit_ms, INTERVAL_MIN, INTERVAL_MAX);
        intervals[1] = numberRow(Prefs.SETTINGS_UPDATE_INTERVAL, INT, R.string.set_update_interval,
                R.string.set_update_interval_summary, R.string.set_unit_ms, INTERVAL_MIN, INTERVAL_MAX);
        intervals[2] = numberRow(Prefs.SETTINGS_IDLE_INTERVAL, INT, R.string.set_idle_interval,
                R.string.set_idle_interval_summary, R.string.set_unit_ms, INTERVAL_MIN, INTERVAL_MAX);
        double max = Float.MAX_VALUE;
        numberRow(Prefs.SETTINGS_IDLE_OFFSET, DOUBLE, R.string.set_idle_offset, R.string.set_idle_offset_summary, R.string.set_unit_m, 0, max);
        numberRow(Prefs.SETTINGS_HEADING_ANGLE_OFFSET, DOUBLE, R.string.set_heading_offset, R.string.set_heading_offset_summary, R.string.set_unit_deg, 0, 180);
        numberRow(Prefs.SETTINGS_SPEED_OFFSET, FLOAT, R.string.set_speed_offset, R.string.set_speed_offset_summary, R.string.set_unit_kmh, 0, max);
        numberRow(Prefs.SETTINGS_ALTITUDE_OFFSET, FLOAT, R.string.set_altitude_offset, R.string.set_altitude_offset_summary, R.string.set_unit_m, 0, max);
        numberRow(Prefs.SETTINGS_MIN_IDLE_ACCURACY, FLOAT, R.string.set_min_idle_accuracy, R.string.set_min_idle_accuracy_summary, R.string.set_unit_m, 0, max);
        numberRow(Prefs.SETTINGS_MAX_IDLE_ACCURACY, FLOAT, R.string.set_max_idle_accuracy, R.string.set_max_idle_accuracy_summary, R.string.set_unit_m, 0, max);
        numberRow(Prefs.SETTINGS_MIN_MOVING_ACCURACY, FLOAT, R.string.set_min_moving_accuracy, R.string.set_min_moving_accuracy_summary, R.string.set_unit_m, 0, max);
        numberRow(Prefs.SETTINGS_MAX_MOVING_ACCURACY, FLOAT, R.string.set_max_moving_accuracy, R.string.set_max_moving_accuracy_summary, R.string.set_unit_m, 0, max);
    }

    // ---------------------------------------------------------------- estado

    private void refresh() {
        for (Row r : rows) if (r.bind != null) r.bind.run();
        boolean suspended = Prefs.getBool(this, Prefs.SUSPENDED_MOCKING);
        boolean systemModeEnabled = Build.VERSION.SDK_INT < 29 && 
                getPackageManager().hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS) &&
                Prefs.getBool(this, Prefs.SYSTEM_MODE);
        autoSuspend.setEnabled(suspended);
        autoSuspendInterval.setEnabled(suspended && Prefs.getBool(this, Prefs.AUTO_SUSPEND_LOCATION));
        indirect.setEnabled(!suspended && !systemModeEnabled);
        for (Row r : intervals) r.setEnabled(!suspended && !systemModeEnabled);
        autoStartApp.setEnabled(appLabels != null);
    }

    /** Suspended ON também desliga Indirect e System Mode, e força 1000 ms nos 3 intervalos; OFF não restaura nada (11 §4.2). */
    private void toggleSuspended() {
        boolean on = !Prefs.getBool(this, Prefs.SUSPENDED_MOCKING);
        SharedPreferences.Editor e = Prefs.edit(this).putBoolean(Prefs.SUSPENDED_MOCKING, on);
        if (on) {
            e.putBoolean(Prefs.INDIRECT_MOCKING, false)
                    .putBoolean(Prefs.SYSTEM_MODE, false)
                    .putInt(Prefs.SETTINGS_PROCESS_INTERVAL, 1000)
                    .putInt(Prefs.SETTINGS_UPDATE_INTERVAL, 1000)
                    .putInt(Prefs.SETTINGS_IDLE_INTERVAL, 1000);
        }
        e.apply();
        refresh();
    }

    private String currentLanguage() {
        LocaleListCompat l = AppCompatDelegate.getApplicationLocales();
        if (l.isEmpty() || l.get(0) == null) return "default";
        String lang = l.get(0).getLanguage();
        return "pt".equals(lang) ? "pt-BR" : "en".equals(lang) ? "en" : "default";
    }

    private void putString(String key, String v) {
        Prefs.edit(this).putString(key, v).apply();
    }

    // ---------------------------------------------------------------- linhas

    private void category(int titleRes) {
        TextView t = (TextView) getLayoutInflater().inflate(R.layout.set_category, list, false);
        t.setText(titleRes);
        list.addView(t);
    }

    /** titleRes/summaryRes 0 = sem a linha correspondente; click null = linha só de texto. */
    private Row row(int titleRes, int summaryRes, View.OnClickListener click) {
        Row r = new Row();
        r.root = getLayoutInflater().inflate(R.layout.set_row, list, false);
        r.title = r.root.findViewById(R.id.set_row_title);
        r.summary = r.root.findViewById(R.id.set_row_summary);
        r.value = r.root.findViewById(R.id.set_row_value);
        r.sw = r.root.findViewById(R.id.set_row_switch);
        if (titleRes != 0) r.title.setText(titleRes);
        else r.title.setVisibility(View.GONE);
        if (summaryRes != 0) r.summary.setText(summaryRes);
        else r.summary.setVisibility(View.GONE);
        if (click != null) r.root.setOnClickListener(click);
        else {
            r.root.setClickable(false);
            r.root.setFocusable(false);
            r.root.setBackground(null);
        }
        list.addView(r.root);
        rows.add(r);
        return r;
    }

    private Row switchRow(String key, int titleRes, int summaryRes) {
        Row r = row(titleRes, summaryRes, v -> {
            Prefs.edit(this).putBoolean(key, !Prefs.getBool(this, key)).apply();
            refresh();
        });
        r.sw.setVisibility(View.VISIBLE);
        r.bind = () -> r.sw.setChecked(Prefs.getBool(this, key));
        return r;
    }

    /** Lista de escolha única; summary = rótulo do valor atual (fallback: 1º). */
    private void listRow(int titleRes, int entriesRes, String[] values, Supplier<String> get, Consumer<String> set) {
        CharSequence[] entries = getResources().getTextArray(entriesRes);
        Row r = row(titleRes, 0, v -> new MaterialAlertDialogBuilder(this)
                .setTitle(titleRes)
                .setSingleChoiceItems(entries, indexOf(values, get.get()), (d, which) -> {
                    d.dismiss();
                    if (!values[which].equals(get.get())) set.accept(values[which]);
                    refresh();
                })
                .setNegativeButton(R.string.cancel, null)
                .show());
        r.summary.setVisibility(View.VISIBLE);
        r.bind = () -> r.summary.setText(entries[indexOf(values, get.get())]);
    }

    private static int indexOf(String[] values, String v) {
        for (int i = 0; i < values.length; i++) if (values[i].equals(v)) return i;
        return 0;
    }

    /** Campo numérico com unidade à direita. Entrada inválida é ignorada; fora da faixa vira o extremo. */
    private Row numberRow(String key, int type, int titleRes, int summaryRes, int unitRes, double min, double max) {
        Row r = row(titleRes, summaryRes, v -> editNumber(key, type, titleRes, min, max));
        r.value.setVisibility(View.VISIBLE);
        r.bind = () -> r.value.setText(getString(unitRes, numberText(key, type)));
        return r;
    }

    private String numberText(String key, int type) {
        switch (type) {
            case INT: return Integer.toString(Prefs.getInt(this, key));
            case FLOAT: return Float.toString(Prefs.getFloat(this, key));
            default: return Double.toString(Prefs.getDouble(this, key));
        }
    }

    private void editNumber(String key, int type, int titleRes, double min, double max) {
        View v = getLayoutInflater().inflate(R.layout.set_dialog_value, null);
        EditText input = v.findViewById(R.id.set_value_input);
        input.setInputType(type == INT ? InputType.TYPE_CLASS_NUMBER
                : InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setText(numberText(key, type));
        input.setSelection(input.length());
        new MaterialAlertDialogBuilder(this)
                .setTitle(titleRes)
                .setView(v)
                .setPositiveButton(R.string.ok, (d, w) -> {
                    double val = SettingsRules.parse(input.getText().toString());
                    if (Double.isNaN(val)) return;   // inválido: o valor antigo fica
                    val = SettingsRules.clamp(val, min, max);
                    SharedPreferences.Editor e = Prefs.edit(this);
                    if (type == INT) e.putInt(key, (int) Math.round(val));
                    else if (type == FLOAT) e.putFloat(key, (float) val);
                    else Prefs.putDouble(e, key, val);
                    e.apply();
                    refresh();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** Slider "%d %" de min..max; valor = progress + min (setMin só no API 26); grava só no OK. */
    private void sliderRow(String key, int titleRes, int min, int max) {
        Row r = row(titleRes, 0, v -> {
            View content = getLayoutInflater().inflate(R.layout.set_dialog_slider, null);
            TextView label = content.findViewById(R.id.set_slider_value);
            SeekBar seek = content.findViewById(R.id.set_slider_seek);
            seek.setMax(max - min);
            seek.setProgress(Math.max(0, Math.min(max - min, Prefs.getInt(this, key) - min)));
            label.setText(getString(R.string.set_fmt_percent, seek.getProgress() + min));
            seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                    label.setText(getString(R.string.set_fmt_percent, p + min));
                }
                @Override public void onStartTrackingTouch(SeekBar s) {}
                @Override public void onStopTrackingTouch(SeekBar s) {}
            });
            new MaterialAlertDialogBuilder(this)
                    .setTitle(titleRes)
                    .setView(content)
                    .setPositiveButton(R.string.ok, (d, w) -> {
                        Prefs.edit(this).putInt(key, seek.getProgress() + min).apply();
                        refresh();
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        });
        r.value.setVisibility(View.VISIBLE);
        r.bind = () -> r.value.setText(getString(R.string.set_fmt_percent, Prefs.getInt(this, key)));
    }

    // ---------------------------------------------------------------- ações

    private void showPrivacyModeDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.set_privacy_mode)
                .setMessage(R.string.set_privacy_mode_warning)
                .setPositiveButton(R.string.set_privacy_mode_generate, (d, w) -> {
                    PrivacyModeManager.generateCloneApp(this, null);
                    message(R.string.success, R.string.set_privacy_mode_started);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void openDevOptions() {
        try {
            startActivity(Perms.devSettings());
        } catch (ActivityNotFoundException | SecurityException e) {
            message(R.string.state_error_title, R.string.set_dev_options_error);
        }
    }

    private void launch(Runnable picker) {
        try {
            picker.run();
        } catch (ActivityNotFoundException e) {
            message(R.string.state_error_title, R.string.set_no_file_manager);
        }
    }

    private void message(int titleRes, int msgRes) {
        new MaterialAlertDialogBuilder(this).setTitle(titleRes).setMessage(msgRes)
                .setPositiveButton(R.string.ok, null).show();
    }

    private boolean dead() {
        return isFinishing() || isDestroyed();
    }

    // ---------------------------------------------------------------- Auto Start App (F94)

    private void loadApps() {
        Context app = getApplicationContext();
        String none = getString(R.string.set_none);
        appsTask = Io.run(() -> queryApps(app, none), new Io.Callback<String[][]>() {
            @Override public void onResult(String[][] r) {
                if (dead()) return;
                appLabels = r[0];
                appPackages = r[1];
                refresh();
            }
            @Override public void onError(Throwable t) {
                if (dead()) return;
                message(R.string.state_error_title, R.string.set_apps_error);
            }
        });
    }

    /** Apps com ícone no launcher (sem o próprio), labels carregados uma vez e ordenados; [0] = None. */
    private static String[][] queryApps(Context c, String none) {
        PackageManager pm = c.getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> found = pm.queryIntentActivities(launcher, 0);
        Map<String, String> byPackage = new LinkedHashMap<>();
        String self = c.getPackageName();
        for (ResolveInfo ri : found) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(self) || byPackage.containsKey(pkg)) continue;
            byPackage.put(pkg, String.valueOf(ri.loadLabel(pm)));
        }
        ArrayList<String[]> apps = new ArrayList<>(byPackage.size());
        for (Map.Entry<String, String> e : byPackage.entrySet()) apps.add(new String[]{e.getValue(), e.getKey()});
        Collator collator = Collator.getInstance();
        apps.sort((a, b) -> collator.compare(a[0], b[0]));
        String[] labels = new String[apps.size() + 1], packages = new String[apps.size() + 1];
        labels[0] = none;
        packages[0] = "";
        for (int i = 0; i < apps.size(); i++) {
            labels[i + 1] = apps.get(i)[0];
            packages[i + 1] = apps.get(i)[1];
        }
        return new String[][]{labels, packages};
    }

    private void pickApp() {
        if (appLabels == null) return;
        String current = Prefs.getString(this, Prefs.SETTINGS_AUTO_START_APP_PACKAGE);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.set_auto_start_app)
                .setSingleChoiceItems(appLabels, indexOf(appPackages, current), (d, which) -> {
                    d.dismiss();
                    Prefs.edit(this)
                            .putString(Prefs.SETTINGS_AUTO_START_APP_NAME, which == 0 ? "" : appLabels[which])
                            .putString(Prefs.SETTINGS_AUTO_START_APP_PACKAGE, appPackages[which])
                            .apply();
                    refresh();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ---------------------------------------------------------------- Backup / Restore (F93)

    private void onBackupTarget(Uri uri) {
        if (uri == null) return;
        showProgress(R.string.set_saving_db);
        Context app = getApplicationContext();
        dbTask = Io.run(() -> {
            DbBackup.save(app, uri);
            return Boolean.TRUE;
        }, new Io.Callback<Boolean>() {
            @Override public void onResult(Boolean ok) {
                if (dead()) return;
                dismissProgress();
                message(R.string.success, R.string.set_db_saved);
            }
            @Override public void onError(Throwable t) {
                if (dead()) return;
                dismissProgress();
                message(R.string.state_error_title, R.string.set_save_error);
            }
        });
    }

    private void onRestoreSource(Uri uri) {
        if (uri == null) return;
        showProgress(R.string.set_restoring_db);
        Context app = getApplicationContext();
        dbTask = Io.run(() -> DbBackup.stage(app, uri), new Io.Callback<Boolean>() {
            @Override public void onResult(Boolean ok) {
                if (dead()) return;
                if (!ok) {
                    dismissProgress();
                    message(R.string.state_error_title, R.string.set_wrong_file);
                    return;
                }
                if (Intents.isOverlayRunning(app)) {
                    Intents.sendToOverlay(app, Intents.overlay(app, Intents.INT_STOP_JOYSTICK));
                }
                waitTries = 0;
                waitOverlayThenSwap();
            }
            @Override public void onError(Throwable t) {
                if (dead()) return;
                dismissProgress();
                message(R.string.state_error_title, R.string.set_restore_error);
            }
        });
    }

    /**
     * Espera o :overlay morrer (fecha o banco dele) por até ~6 s, sem sleep; depois troca o arquivo.
     * Se ele não morrer, aborta: trocar o arquivo sob uma conexão viva perderia as gravações dele.
     */
    private void waitOverlayThenSwap() {
        if (dead()) return;
        if (Intents.isOverlayRunning(this)) {
            if (waitTries++ < WAIT_TRIES) {
                Io.main().postDelayed(waitOverlay, WAIT_STEP_MS);
            } else {
                dismissProgress();
                message(R.string.state_error_title, R.string.set_restore_error);
            }
            return;
        }
        Context app = getApplicationContext();
        dbTask = Io.run(() -> DbBackup.swap(app), new Io.Callback<Boolean>() {
            @Override public void onResult(Boolean ok) {
                if (dead()) return;
                dismissProgress();
                if (ok) askRestart();
                else message(R.string.state_error_title, R.string.set_restore_error);
            }
            @Override public void onError(Throwable t) {
                if (dead()) return;
                dismissProgress();
                message(R.string.state_error_title, R.string.set_restore_error);
            }
        });
    }

    /** Reinício obrigatório: descarta conexões e caches do banco antigo em todo o processo. */
    private void askRestart() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.set_restart_title)
                .setMessage(R.string.set_restart_msg)
                .setCancelable(false)
                .setPositiveButton(R.string.set_restart, (d, w) -> {
                    startActivity(Intent.makeRestartActivityTask(new ComponentName(this, MainActivity.class)));
                    Runtime.getRuntime().exit(0);
                })
                .show();
    }

    private void showProgress(int msgRes) {
        dismissProgress();
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(true);
        FrameLayout box = new FrameLayout(this);
        int pad = Ui.dp(this, 24);
        box.setPadding(pad, Ui.dp(this, 8), pad, 0);
        box.addView(bar);
        progress = new MaterialAlertDialogBuilder(this).setMessage(msgRes).setView(box).setCancelable(false).show();
    }

    private void dismissProgress() {
        if (progress != null) {
            progress.dismiss();
            progress = null;
        }
    }
}
