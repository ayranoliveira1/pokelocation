package com.pokelocation.app.widget;

import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.os.Bundle;
import android.widget.RadioGroup;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.pokelocation.app.R;
import com.pokelocation.app.data.Prefs;
import com.pokelocation.app.ui.base.BaseActivity;
import com.pokelocation.app.util.Themes;

/** "Widget Settings" (F104): tema e agrupamento por widget; tela de configuração do launcher (APPWIDGET_CONFIGURE). */
public class WidgetSettingsActivity extends BaseActivity {
    private int widgetId;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        widgetId = getIntent().getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish();
            return;
        }
        // O widget é aceito mesmo se o usuário sair sem mexer em nada
        setResult(RESULT_OK, new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId));
        setContentView(R.layout.widget_settings);
        setupToolbar(getString(R.string.widget_settings_title), true);
        applyInsets(findViewById(R.id.widget_settings_root));

        findViewById(R.id.widget_settings_choose).setOnClickListener(v -> startActivity(
                new Intent(this, WidgetConfigActivity.class).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)));

        String themeKey = Prefs.widgetKey(Prefs.WIDGET_THEME, widgetId);
        String theme = Prefs.getString(this, themeKey, Themes.SYSTEM);
        RadioGroup rg = findViewById(R.id.widget_settings_theme);
        rg.check(Themes.LIGHT.equals(theme) ? R.id.widget_settings_theme_light
                : Themes.DARK.equals(theme) ? R.id.widget_settings_theme_dark : R.id.widget_settings_theme_system);
        rg.setOnCheckedChangeListener((g, checked) -> {
            String t = checked == R.id.widget_settings_theme_light ? Themes.LIGHT
                    : checked == R.id.widget_settings_theme_dark ? Themes.DARK : Themes.SYSTEM;
            Prefs.edit(this).putString(themeKey, t).apply();
            QuickActionsWidgetProvider.renderCachedAsync(this, widgetId);
        });

        String groupKey = Prefs.widgetKey(Prefs.WIDGET_GROUP_BY_TYPE, widgetId);
        MaterialSwitch sw = findViewById(R.id.widget_settings_group);
        sw.setChecked(Prefs.getBool(this, groupKey, true));
        findViewById(R.id.widget_settings_group_row).setOnClickListener(v -> {
            sw.toggle();
            Prefs.edit(this).putBoolean(groupKey, sw.isChecked()).apply();
            QuickActionsWidgetProvider.renderCachedAsync(this, widgetId);
        });

        // Widget recém-adicionado com tela de configuração não recebe onUpdate: renderiza já
        if (state == null) QuickActionsWidgetProvider.refreshAsync(this, widgetId);
    }
}
