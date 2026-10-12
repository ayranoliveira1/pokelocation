package com.pokelocation.app.ui.routes;

import android.content.Context;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.pokelocation.app.Io;
import com.pokelocation.app.R;
import com.pokelocation.app.data.Prefs;
import com.pokelocation.app.data.RouteDao;
import com.pokelocation.app.ipc.Intents;
import com.pokelocation.app.model.Route;
import com.pokelocation.app.ui.base.BaseActivity;
import com.pokelocation.app.ui.base.StateView;
import com.pokelocation.app.ui.panel.MessagePanel;
import com.pokelocation.app.ui.panel.PanelHost;
import com.pokelocation.app.util.Ui;

/** Edição "Advanced" (F68): nome, ordem, lista lat/lng/alt editável, distância total + ETA. Entrada: extra id. */
public final class EditRouteActivity extends BaseActivity implements EditRouteAdapter.Host {
    private Route route;              // id/pasta/sort originais (sem pts)
    private EditRouteAdapter adapter;
    private StateView state;
    private RecyclerView list;
    private Io.Task loadTask;
    private boolean dirty, saving;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.routes_edit_activity);
        setupToolbar(getString(R.string.routes_edit_route), true);
        applyInsets(findViewById(R.id.routes_edit_root));

        state = findViewById(R.id.routes_edit_state);
        list = findViewById(R.id.routes_edit_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setHasFixedSize(true);
        state.setErrorText(getString(R.string.routes_load_error));
        state.setOnRetry(this::load);

        FloatingActionButton fab = findViewById(R.id.routes_edit_fab);
        fab.setOnClickListener(v -> {
            if (adapter == null) return;
            int pos = adapter.addPoint();
            if (pos >= 0) list.scrollToPosition(pos);
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { leave(); }
        });
        load();
    }

    @Override protected void onDestroy() {
        if (loadTask != null) loadTask.cancel();
        PanelHost.closeAll();
        super.onDestroy();
    }

    private boolean alive() { return !isFinishing() && !isDestroyed(); }

    private void load() {
        final String id = getIntent().getStringExtra(Intents.EXTRA_ID);
        if (id == null) {
            finish();
            return;
        }
        if (loadTask != null) loadTask.cancel();
        state.setState(StateView.LOADING);
        final Context app = getApplicationContext();
        loadTask = Io.run(() -> RouteDao.get(app, id), new Io.Callback<Route>() {
            @Override public void onResult(Route r) {
                if (!alive()) return;
                if (r == null) {
                    Ui.toast(EditRouteActivity.this, R.string.unknown_error);
                    finish();
                    return;
                }
                route = new Route(r.id, r.name, r.folderId, r.sort, r.count, null);
                float kmh = Prefs.getFloat(EditRouteActivity.this, Prefs.SETTINGS_WALKING_SPEED);
                adapter = new EditRouteAdapter(EditRouteActivity.this, EditRouteActivity.this,
                        new RoutePoints(r.pts), r.name, String.valueOf(r.sort + 1), kmh);
                list.setAdapter(adapter);
                state.setState(StateView.CONTENT);
                invalidateOptionsMenu();
            }

            @Override public void onError(Throwable t) {
                if (alive()) state.setState(StateView.ERROR);
            }
        });
    }

    // ---- EditRouteAdapter.Host ----

    @Override public void onEdited() {
        dirty = true;
        invalidateOptionsMenu();
    }

    @Override public void onMessage(int resId) { Ui.toast(this, resId); }

    // ---- menu ----

    @Override
    public boolean onCreateOptionsMenu(Menu m) {
        getMenuInflater().inflate(R.menu.routes_edit, m);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu m) {
        MenuItem save = m.findItem(R.id.routes_menu_save);
        boolean on = !saving && adapter != null && adapter.canSave();
        save.setEnabled(on);
        save.getIcon().mutate().setAlpha(on ? 255 : 97);
        return super.onPrepareOptionsMenu(m);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.routes_menu_save) {
            save();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void save() {
        if (saving || adapter == null || !adapter.canSave()) return;
        saving = true;
        invalidateOptionsMenu();
        RoutePoints p = adapter.points();
        final Route r = new Route(route.id, adapter.name(), route.folderId, adapter.sortValue() - 1, p.size(), p.toArray());
        final Context app = getApplicationContext();
        Io.run(() -> {
            RouteDao.update(app, r);   // o DAO faz clamp do sort e desloca os vizinhos
            return Boolean.TRUE;
        }, new Io.Callback<Boolean>() {
            @Override public void onResult(Boolean ok) {
                if (!alive()) return;
                setResult(RESULT_OK);
                finish();
            }

            @Override public void onError(Throwable t) {
                if (!alive()) return;
                saving = false;
                invalidateOptionsMenu();
                Ui.toast(EditRouteActivity.this, R.string.routes_update_error);
            }
        });
    }

    private void leave() {
        if (saving) return;
        if (adapter != null && (dirty || adapter.isEditing())) {
            MessagePanel.show(this, getString(R.string.unsaved_changes), getString(R.string.unsaved_changes_msg),
                    getString(R.string.leave), getString(R.string.cancel), this::finish);
        } else {
            finish();
        }
    }
}
