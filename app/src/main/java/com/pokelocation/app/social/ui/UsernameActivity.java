package com.pokelocation.app.social.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;

import com.google.android.material.textfield.TextInputLayout;
import com.pokelocation.app.Io;
import com.pokelocation.app.R;
import com.pokelocation.app.social.Session;
import com.pokelocation.app.social.net.Api;
import com.pokelocation.app.ui.base.BaseActivity;
import com.pokelocation.app.util.Ui;

public final class UsernameActivity extends BaseActivity {
    private static final String EXTRA_CURRENT = "current";

    private EditText input;
    private TextInputLayout box;
    private View progress, save;
    private String returnTo, current;
    private Io.Task task;

    /** current: o @nome atual sem '@', ou null. */
    public static Intent intent(Context c, String returnTo, String current) {
        return new Intent(c, UsernameActivity.class).putExtra(LoginActivity.EXTRA_RETURN_TO, returnTo)
                .putExtra(EXTRA_CURRENT, current);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!Session.loggedIn(this)) {
            finish();
            return;
        }
        returnTo = getIntent().getStringExtra(LoginActivity.EXTRA_RETURN_TO);
        current = getIntent().getStringExtra(EXTRA_CURRENT);
        setContentView(R.layout.soc_username);
        setupToolbar(getString(current == null ? R.string.soc_username_title : R.string.soc_username_change), true);
        applyInsets(findViewById(R.id.soc_username_root));
        input = findViewById(R.id.soc_username_input);
        box = findViewById(R.id.soc_username_box);
        progress = findViewById(R.id.soc_username_progress);
        save = findViewById(R.id.soc_username_save);
        validateLive(input, box);
        input.addTextChangedListener(new Watcher(() -> save.setEnabled(Api.normalizeUsername(input.getText().toString()) != null)));
        if (current != null) input.setText(current);
        save.setOnClickListener(v -> save());
        input.setOnEditorActionListener((v, id, ev) -> {
            if (id != EditorInfo.IME_ACTION_DONE) return false;
            save();
            return true;
        });
    }

    @Override
    protected void onDestroy() {
        if (task != null) task.cancel();
        super.onDestroy();
    }

    /** Só o formato: nome em uso ou reservado só o servidor sabe. */
    static void validateLive(EditText input, TextInputLayout box) {
        input.addTextChangedListener(new Watcher(() -> {
            String raw = input.getText().toString();
            box.setError(raw.isEmpty() || Api.normalizeUsername(raw) != null ? null
                    : input.getContext().getString(R.string.soc_username_rules));
        }));
    }

    private void save() {
        String u = Api.normalizeUsername(input.getText().toString());
        if (u == null || task != null) return;
        if (u.equals(current)) {
            LoginActivity.go(this, returnTo);
            return;
        }
        busy(true);
        Context app = getApplicationContext();
        task = Io.run(() -> {
            Api.setUsername(app, u);
            return Boolean.TRUE;
        }, new Io.Callback<Boolean>() {
            @Override public void onResult(Boolean ok) {
                LoginActivity.go(UsernameActivity.this, returnTo);
            }
            @Override public void onError(Throwable t) {
                task = null;
                busy(false);
                if (Errors.unauthorized(t)) {
                    startActivity(new Intent(UsernameActivity.this, LoginActivity.class)
                            .putExtra(LoginActivity.EXTRA_RETURN_TO, returnTo));
                    finish();
                } else if (Errors.aboutUsername(t) || Errors.usernameMessage(t) == R.string.soc_err_username_limit) {
                    box.setError(getString(Errors.usernameMessage(t)));
                } else {
                    Ui.toast(UsernameActivity.this, Errors.message(t));
                }
            }
        });
    }

    private void busy(boolean b) {
        progress.setVisibility(b ? View.VISIBLE : View.INVISIBLE);
        save.setEnabled(!b);
        input.setEnabled(!b);
    }

    private static final class Watcher implements TextWatcher {
        private final Runnable r;

        Watcher(Runnable r) { this.r = r; }

        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
        @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
        @Override public void afterTextChanged(Editable s) { r.run(); }
    }
}
