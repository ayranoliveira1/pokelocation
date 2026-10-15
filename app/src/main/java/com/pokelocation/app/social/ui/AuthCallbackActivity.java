package com.pokelocation.app.social.ui;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;

import com.pokelocation.app.Io;
import com.pokelocation.app.R;
import com.pokelocation.app.social.Session;
import com.pokelocation.app.social.net.Api;
import com.pokelocation.app.ui.base.BaseActivity;
import com.pokelocation.app.util.Ui;

/**
 * Recebe pokelocationauth://callback do Google (via servidor), troca o code e vai para o destino da origem do Login
 * (guardada com o PKCE: Amigos, de volta ou a Main). Só mostra progresso.
 */
public final class AuthCallbackActivity extends BaseActivity {
    private Io.Task task;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Uri data = getIntent().getData();
        if (data == null) {
            finish();
            return;
        }
        setContentView(R.layout.soc_auth_callback);
        Context app = getApplicationContext();
        if (Api.REAUTH.equals(Session.pendingReturnTo(app))) {
            deleteAccount(app, data);
            return;
        }
        if (Session.loggedIn(app)) { // aba do Google aberta antes de um login por email: não troca de conta por cima
            LoginActivity.go(this, null);
            return;
        }
        task = Io.run(() -> {
            Api.googleFinish(app, data);
            String returnTo = Session.takeReturnTo(app);
            return new Object[]{returnTo, LoginActivity.hasUsername(app)};
        }, new Io.Callback<Object[]>() {
            @Override public void onResult(Object[] r) {
                LoginActivity.afterLogin(AuthCallbackActivity.this, (String) r[0], (Boolean) r[1]);
            }
            @Override public void onError(Throwable t) {
                if (Session.loggedIn(app)) LoginActivity.go(AuthCallbackActivity.this, null);   // recriada: a 1ª instância já trocou o code (volta à Main)
                else if (Errors.cancelled(t)) finish();   // cancelou no Google: volta ao Login (na pilha), sem toast
                else {
                    Ui.toast(app, Errors.message(t));
                    finish();
                }
            }
        });
    }

    private void deleteAccount(Context app, Uri data) {
        task = Io.run(() -> {
            Api.deleteAccount(app, null, Api.googleReauthFinish(app, data));
            return Boolean.TRUE;
        }, new Io.Callback<Boolean>() {
            @Override public void onResult(Boolean r) {
                AccountActivity.deleted(AuthCallbackActivity.this);
            }
            @Override public void onError(Throwable t) {
                if (Errors.unauthorized(t)) {
                    AccountActivity.sessionEnded(AuthCallbackActivity.this);
                    return;
                }
                if (!Errors.cancelled(t)) Ui.toast(app, Errors.deleteMessage(t));
                finish();
            }
        });
    }

    @Override
    protected void onDestroy() {
        if (task != null) task.cancel();
        super.onDestroy();
    }
}
