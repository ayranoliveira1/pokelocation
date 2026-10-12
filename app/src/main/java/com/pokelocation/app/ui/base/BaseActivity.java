package com.pokelocation.app.ui.base;

import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.pokelocation.app.R;
import com.pokelocation.app.data.Prefs;
import com.pokelocation.app.util.Themes;

/**
 * Base das Activities do main: retrato em telas < LARGE, edge-to-edge em todas as versões (obrigatório no API 35+),
 * FLAG_SECURE com prevent_screenshots.
 */
public abstract class BaseActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int size = getResources().getConfiguration().screenLayout & Configuration.SCREENLAYOUT_SIZE_MASK;
        if (size < Configuration.SCREENLAYOUT_SIZE_LARGE) setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        if (Prefs.getBool(this, Prefs.PREVENT_SCREENSHOTS)) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        boolean night = Themes.isNight(this);
        WindowInsetsControllerCompat ic = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        ic.setAppearanceLightStatusBars(night);    // toolbar primary: escuro no claro, claro no escuro
        ic.setAppearanceLightNavigationBars(!night);
    }

    /** Toolbar do layout base_toolbar (id toolbar) como ActionBar; showBack → seta que chama onBackPressed. */
    protected Toolbar setupToolbar(CharSequence title, boolean showBack) {
        Toolbar tb = findViewById(R.id.toolbar);
        setSupportActionBar(tb);
        if (getSupportActionBar() != null) getSupportActionBar().setTitle(title);
        if (showBack) {
            tb.setNavigationIcon(R.drawable.ic_arrow_back);
            tb.setNavigationContentDescription(androidx.appcompat.R.string.abc_action_bar_up_description);
            tb.setNavigationOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        }
        return tb;
    }

    /**
     * Padding de system bars + IME. Se houver R.id.toolbar_container (base_toolbar) dentro de root, o inset do topo vai
     * para o paddingTop do container (a cor primária fica atrás da status bar; a toolbar não muda) e o resto para root.
     */
    protected void applyInsets(View root) {
        View bar = root.findViewById(R.id.toolbar_container);
        int pl = root.getPaddingLeft(), pt = root.getPaddingTop(), pr = root.getPaddingRight(), pb = root.getPaddingBottom();
        int barTop = bar != null ? bar.getPaddingTop() : 0;
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets b = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()
                    | WindowInsetsCompat.Type.ime());
            if (bar != null) {
                bar.setPadding(bar.getPaddingLeft(), barTop + b.top, bar.getPaddingRight(), bar.getPaddingBottom());
                v.setPadding(pl + b.left, pt, pr + b.right, pb + b.bottom);
            } else {
                v.setPadding(pl + b.left, pt + b.top, pr + b.right, pb + b.bottom);
            }
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
    }
}
