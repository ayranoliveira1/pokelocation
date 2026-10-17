package com.pokelocation.app.util;

import android.content.Context;
import android.content.res.Configuration;
import android.view.ContextThemeWrapper;

import androidx.appcompat.app.AppCompatDelegate;

import com.pokelocation.app.R;
import com.pokelocation.app.data.Prefs;

public final class Themes {
    public static final String SYSTEM = "system", LIGHT = "light", DARK = "dark";

    private Themes() {}

    /** SÓ no processo main (AppCompat). */
    public static void applyNightMode(String mode) {
        int m = LIGHT.equals(mode) ? AppCompatDelegate.MODE_NIGHT_NO
                : DARK.equals(mode) ? AppCompatDelegate.MODE_NIGHT_YES
                : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        if (AppCompatDelegate.getDefaultNightMode() != m) AppCompatDelegate.setDefaultNightMode(m);
    }

    /** :overlay/:map: uiMode forçado por settings_theme_mode + tema framework Theme.PokeLocation.Overlay. */
    public static Context overlayContext(Context base) {
        String mode = Prefs.getString(base, Prefs.SETTINGS_THEME_MODE);
        Context c = base;
        if (LIGHT.equals(mode) || DARK.equals(mode)) {
            Configuration conf = new Configuration(base.getResources().getConfiguration());
            conf.uiMode = (conf.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                    | (DARK.equals(mode) ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
            c = base.createConfigurationContext(conf);
        }
        return new ContextThemeWrapper(c, R.style.Theme_PokeLocation_Overlay);
    }

    public static boolean isNight(Context c) {
        return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }
}
