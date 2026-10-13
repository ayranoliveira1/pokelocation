package com.pokelocation.app.social.ui;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;

import com.pokelocation.app.Io;
import com.pokelocation.app.R;
import com.pokelocation.app.social.Session;
import com.pokelocation.app.social.net.Api;
import com.pokelocation.app.ui.base.BaseActivity;
import com.pokelocation.app.util.Ui;

/** Login opcional: email/senha (entrar ou criar conta) ou Google no navegador (volta pela AuthCallbackActivity). */
public final class LoginActivity extends BaseActivity {
    private EditText email, password;
    private View progress, signIn, signUp, google;
    private Io.Task task;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.soc_login);
        setupToolbar(getString(R.string.soc_login_title), true);
        applyInsets(findViewById(R.id.soc_login_root));
        email = findViewById(R.id.soc_login_email);
        password = findViewById(R.id.soc_login_password);
        progress = findViewById(R.id.soc_login_progress);
        signIn = findViewById(R.id.soc_login_sign_in);
        signUp = findViewById(R.id.soc_login_sign_up);
        google = findViewById(R.id.soc_login_google);
        signIn.setOnClickListener(v -> submit(false));
        signUp.setOnClickListener(v -> submit(true));
        google.setOnClickListener(v -> openGoogle());
    }

    /** O Google termina na AuthCallbackActivity, que abre Amigos por cima desta: ao voltar, já logado, sai. */
    @Override
    protected void onResume() {
        super.onResume();
        if (task == null && Session.loggedIn(this)) finish();
    }

    @Override
    protected void onDestroy() {
        if (task != null) task.cancel();
        super.onDestroy();
    }

    private void submit(boolean register) {
        String e = email.getText().toString().trim(), p = password.getText().toString();
        if (e.isEmpty() || p.isEmpty()) {
            Ui.toast(this, R.string.soc_fill_fields);
            return;
        }
        busy(true);
        Context app = getApplicationContext();
        task = Io.run(() -> {
            if (register) Api.register(app, e, p);
            else Api.login(app, e, p);
            return Boolean.TRUE;
        }, new Io.Callback<Boolean>() {
            @Override public void onResult(Boolean ok) {
                startActivity(new Intent(LoginActivity.this, FriendsActivity.class));
                finish();
            }
            @Override public void onError(Throwable t) {
                task = null;
                busy(false);
                Ui.toast(LoginActivity.this, Errors.message(t));
            }
        });
    }

    private void openGoogle() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Api.googleStart(this)));
        } catch (ActivityNotFoundException e) {
            Ui.toast(this, R.string.soc_no_browser);
        }
    }

    private void busy(boolean b) {
        progress.setVisibility(b ? View.VISIBLE : View.INVISIBLE);
        signIn.setEnabled(!b);
        signUp.setEnabled(!b);
        google.setEnabled(!b);
    }
}
