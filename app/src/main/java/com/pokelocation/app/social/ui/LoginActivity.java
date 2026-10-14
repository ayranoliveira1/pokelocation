package com.pokelocation.app.social.ui;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;

import com.pokelocation.app.Io;
import com.pokelocation.app.R;
import com.pokelocation.app.social.Session;
import com.pokelocation.app.social.crypto.SignalLoader;
import com.pokelocation.app.social.net.Api;
import com.pokelocation.app.ui.base.BaseActivity;
import com.pokelocation.app.ui.main.MainActivity;
import com.pokelocation.app.util.Ui;

/** Login opcional: email/senha (entrar ou criar conta) ou Google no navegador (volta pela AuthCallbackActivity). */
public final class LoginActivity extends BaseActivity {
    private static final String SIGNAL_STARTED = "signal_started";
    /** Quem abre o Login diz para onde ir depois do sucesso. Sem o extra: volta à MainActivity. */
    public static final String EXTRA_RETURN_TO = "return_to", RETURN_MAIN = "main", RETURN_FRIENDS = "friends",
            RETURN_BACK = "back";
    private String returnTo;

    /** Tela a abrir depois do login; null = só fechar o Login (a de baixo atualiza no onResume). */
    static Class<?> next(String returnTo) {
        if (RETURN_FRIENDS.equals(returnTo)) return FriendsActivity.class;
        if (RETURN_BACK.equals(returnTo)) return null;
        return MainActivity.class;
    }

    /**
     * Sucesso do login (aqui ou na AuthCallbackActivity): carrega a libsignal já, senão o Keys.ensure só rodaria quando
     * alguém abrisse Amigos, e vai para o destino da origem.
     */
    static void afterLogin(Activity a, String returnTo) {
        SignalLoader.start(a.getApplicationContext(), null);
        Class<?> to = next(returnTo);
        if (to == MainActivity.class) {
            // a Main já existente vem para a frente (sem recriar a pilha); o handleIntent ignora intent sem action
            a.startActivity(new Intent(a, to).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        } else if (to != null) {
            a.startActivity(new Intent(a, to));
        }
        a.finish();
    }
    private EditText email, password;
    private View progress, signIn, signUp, google;
    private Io.Task task;
    private SignalProgress signal;
    private boolean signalStarted;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        returnTo = getIntent().getStringExtra(EXTRA_RETURN_TO);
        setContentView(R.layout.soc_login);
        setupToolbar(getString(R.string.soc_login_title), true);
        applyInsets(findViewById(R.id.soc_login_root));
        email = findViewById(R.id.soc_login_email);
        password = findViewById(R.id.soc_login_password);
        progress = findViewById(R.id.soc_login_progress);
        signIn = findViewById(R.id.soc_login_sign_in);
        signUp = findViewById(R.id.soc_login_sign_up);
        google = findViewById(R.id.soc_login_google);
        signal = new SignalProgress(findViewById(R.id.soc_signal));
        // recriada (tema/idioma) no meio do download: reanexa; sem toque em Entrar nada é baixado
        if (savedInstanceState != null && savedInstanceState.getBoolean(SIGNAL_STARTED)) startSignal();
        signIn.setOnClickListener(v -> submit(false));
        signUp.setOnClickListener(v -> submit(true));
        google.setOnClickListener(v -> openGoogle());
    }

    /** O Google termina na AuthCallbackActivity, que vai para o destino da origem: se voltar aqui já logado, sai. */
    @Override
    protected void onResume() {
        super.onResume();
        if (task == null && Session.loggedIn(this)) finish();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean(SIGNAL_STARTED, signalStarted);
    }

    @Override
    protected void onDestroy() {
        if (task != null) task.cancel();
        signal.detach();
        super.onDestroy();
    }

    private void submit(boolean register) {
        String e = email.getText().toString().trim(), p = password.getText().toString();
        if (e.isEmpty() || p.isEmpty()) {
            Ui.toast(this, R.string.soc_fill_fields);
            return;
        }
        busy(true);
        startSignal();   // em paralelo à autenticação
        Context app = getApplicationContext();
        task = Io.run(() -> {
            if (register) Api.register(app, e, p);
            else Api.login(app, e, p);
            return Boolean.TRUE;
        }, new Io.Callback<Boolean>() {
            @Override public void onResult(Boolean ok) {
                afterLogin(LoginActivity.this, returnTo);
            }
            @Override public void onError(Throwable t) {
                task = null;
                busy(false);
                Ui.toast(LoginActivity.this, Errors.message(t));
            }
        });
    }

    /** googleStart cifra o PKCE no Keystore (pode bloquear): Io. Falha do Keystore → mensagem genérica (Errors). */
    private void openGoogle() {
        startSignal();
        busy(true);
        Context app = getApplicationContext();
        String rt = returnTo;
        task = Io.run(() -> Api.googleStart(app, rt), new Io.Callback<Uri>() {
            @Override public void onResult(Uri url) {
                task = null;
                busy(false);
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, url));
                } catch (ActivityNotFoundException e) {
                    Ui.toast(LoginActivity.this, R.string.soc_no_browser);
                }
            }
            @Override public void onError(Throwable t) {
                task = null;
                busy(false);
                Ui.toast(LoginActivity.this, Errors.message(t));
            }
        });
    }

    private void startSignal() {
        signalStarted = true;
        signal.start();
    }

    private void busy(boolean b) {
        progress.setVisibility(b ? View.VISIBLE : View.INVISIBLE);
        signIn.setEnabled(!b);
        signUp.setEnabled(!b);
        google.setEnabled(!b);
    }
}
