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
        task = Io.run(() -> {
            Api.googleFinish(app, data);
            return Session.takeReturnTo(app);
        }, new Io.Callback<String>() {
            @Override public void onResult(String returnTo) {
                LoginActivity.afterLogin(AuthCallbackActivity.this, returnTo);
            }
            @Override public void onError(Throwable t) {
                if (Session.loggedIn(app)) onResult(null);   // recriada: a 1ª instância já trocou o code (volta à Main)
                else if (Errors.cancelled(t)) finish();   // cancelou no Google: volta ao Login (na pilha), sem toast
                else {
                    Ui.toast(app, Errors.message(t));
                    finish();
                }
            }
        });
    }

    @Override
    protected void onDestroy() {
        if (task != null) task.cancel();
        super.onDestroy();
    }
}
