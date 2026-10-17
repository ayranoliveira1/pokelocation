package com.pokelocation.app.ui.favorites;

import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.pokelocation.app.Io;
import com.pokelocation.app.R;
import com.pokelocation.app.data.FavoriteDao;
import com.pokelocation.app.ipc.Intents;
import com.pokelocation.app.model.Favorite;
import com.pokelocation.app.ui.base.BaseActivity;
import com.pokelocation.app.ui.base.StateView;

/** Edit Favorite: Name obrigatório, Address livre, Lat/Lng/Alt numéricos, Sort Order 1-based. */
public class EditFavoriteActivity extends BaseActivity {
    private Context app;
    private StateView state;
    private TextInputLayout nameL, latL, lngL, altL, sortL;
    private TextInputEditText name, address, lat, lng, alt, sort;
    private MenuItem saveItem;
    private Favorite orig;
    private String origText;    // snapshot dos campos para "Unsaved Changes"
    private boolean valid, saving;
    private Io.Task loadTask;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.fav_edit_activity);
        app = getApplicationContext();
        Toolbar tb = setupToolbar(getString(R.string.fav_edit_title), true);
        tb.setNavigationOnClickListener(v -> back());
        applyInsets(findViewById(R.id.fav_edit_root));

        state = findViewById(R.id.fav_edit_state);
        nameL = findViewById(R.id.fav_edit_name_l);
        latL = findViewById(R.id.fav_edit_lat_l);
        lngL = findViewById(R.id.fav_edit_lng_l);
        altL = findViewById(R.id.fav_edit_alt_l);
        sortL = findViewById(R.id.fav_edit_sort_l);
        name = findViewById(R.id.fav_edit_name);
        address = findViewById(R.id.fav_edit_address);
        lat = findViewById(R.id.fav_edit_lat);
        lng = findViewById(R.id.fav_edit_lng);
        alt = findViewById(R.id.fav_edit_alt);
        sort = findViewById(R.id.fav_edit_sort);
        state.setOnRetry(this::load);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { back(); }
        });
        load();
    }

    @Override
    protected void onDestroy() {
        if (loadTask != null) loadTask.cancel();
        super.onDestroy();
    }

    private void load() {
        final String id = getIntent().getStringExtra(Intents.EXTRA_ID);
        if (id == null) {
            finish();
            return;
        }
        state.setState(StateView.LOADING);
        loadTask = Io.run(() -> FavoriteDao.get(app, id), new Io.Callback<Favorite>() {
            @Override public void onResult(Favorite f) {
                if (isDestroyed()) return;
                if (f == null) {
                    finish();
                    return;
                }
                orig = f;
                name.setText(f.name);
                address.setText(f.address);
                lat.setText(String.valueOf(f.lat));
                lng.setText(String.valueOf(f.lng));
                alt.setText(String.valueOf(f.alt));
                sort.setText(String.valueOf(f.sort + 1));
                origText = snapshot();
                TextWatcher w = new TextWatcher() {
                    @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

                    @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}

                    @Override public void afterTextChanged(Editable s) { validate(); }
                };
                for (TextInputEditText e : new TextInputEditText[]{name, address, lat, lng, alt, sort}) e.addTextChangedListener(w);
                validate();
                state.setState(StateView.CONTENT);
            }

            @Override public void onError(Throwable t) {
                if (!isDestroyed()) state.setState(StateView.ERROR);
            }
        });
    }

    private void validate() {
        boolean ok = true;
        if (text(name).isEmpty()) {
            nameL.setError(getString(R.string.field_required));
            ok = false;
        } else {
            nameL.setError(null);
        }
        ok &= checkNumber(latL, lat, false);
        ok &= checkNumber(lngL, lng, false);
        ok &= checkNumber(altL, alt, false);
        ok &= checkNumber(sortL, sort, true);
        valid = ok;
        if (saveItem != null) saveItem.setEnabled(valid && !saving);
    }

    private boolean checkNumber(TextInputLayout l, TextInputEditText e, boolean integer) {
        boolean ok = integer ? parseInt(text(e)) != null : !Double.isNaN(parseDouble(text(e)));
        l.setError(ok ? null : getString(R.string.field_number));
        return ok;
    }

    private static double parseDouble(String s) {
        try {
            double v = Double.parseDouble(s.replace(',', '.'));
            return Double.isInfinite(v) ? Double.NaN : v;
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static Integer parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String text(TextInputEditText e) {
        return e.getText() == null ? "" : e.getText().toString().trim();
    }

    private String snapshot() {
        return text(name) + '\u0000' + text(address) + '\u0000' + text(lat) + '\u0000' + text(lng) + '\u0000'
                + text(alt) + '\u0000' + text(sort);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.fav_edit, menu);
        saveItem = menu.findItem(R.id.fav_menu_save);
        saveItem.setEnabled(valid && !saving);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.fav_menu_save) {
            save();
            return true;
        }
        if (item.getItemId() == android.R.id.home) {
            back();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void save() {
        if (!valid || saving || orig == null) return;
        saving = true;
        saveItem.setEnabled(false);
        final Favorite f = new Favorite(orig.id, text(name), text(address), parseDouble(text(lat)),
                parseDouble(text(lng)), parseDouble(text(alt)), parseInt(text(sort)) - 1, orig.folderId);
        state.setState(StateView.LOADING);
        Io.run(() -> {
            FavoriteDao.update(app, f);
            return Boolean.TRUE;
        }, new Io.Callback<Boolean>() {
            @Override public void onResult(Boolean ok) {
                if (isDestroyed()) return;
                setResult(RESULT_OK);
                finish();
            }

            @Override public void onError(Throwable t) {
                if (isDestroyed()) return;
                saving = false;
                state.setState(StateView.CONTENT);
                validate();
                new MaterialAlertDialogBuilder(EditFavoriteActivity.this)
                        .setTitle(R.string.error)
                        .setMessage(R.string.fav_update_error)
                        .setPositiveButton(R.string.ok, null)
                        .show();
            }
        });
    }

    private void back() {
        if (saving) return;
        if (origText == null || origText.equals(snapshot())) {
            finish();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.unsaved_changes)
                .setMessage(R.string.unsaved_changes_msg)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.leave, (d, w) -> finish())
                .show();
    }
}
