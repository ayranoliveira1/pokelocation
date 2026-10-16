package com.pokelocation.app.pogo;

import android.os.Bundle;
import android.view.MenuItem;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.widget.Toolbar;

import com.pokelocation.app.R;
import com.pokelocation.app.ui.base.BaseActivity;

/** Filtros do mapa pelo PogoFilterForm (o mesmo do sheet, sem a folha). Cada mudança já é salva. */
public final class PogoFiltersActivity extends BaseActivity {
    private PogoFilter f;
    private FrameLayout box;
    private PogoFilterForm form;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        getLayoutInflater().inflate(R.layout.base_toolbar, root);
        box = new FrameLayout(this);
        root.addView(box, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        Toolbar tb = setupToolbar(getString(R.string.pogo_filters), true);
        tb.getMenu().add(R.string.pogo_f_clear).setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_ALWAYS)
                .setOnMenuItemClickListener(i -> {
                    form.reset();
                    return true;
                });
        applyInsets(root);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { if (!form.back()) finish(); }
        });
        f = PogoFilter.load(this);
        render();
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        render(); // login ou treinador podem ter mudado na conta
    }

    @Override
    protected void onDestroy() {
        if (form != null) form.destroy();
        super.onDestroy();
    }

    private void render() {
        if (form != null) form.destroy();
        form = new PogoFilterForm(this, f, () -> f.save(this), null, () -> TrainerLink.start(this, this::render));
        box.removeAllViews();
        box.addView(form.view());
    }
}
