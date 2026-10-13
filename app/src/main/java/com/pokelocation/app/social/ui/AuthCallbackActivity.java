package com.pokelocation.app.social.ui;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import com.pokelocation.app.Io;
import com.pokelocation.app.R;
import com.pokelocation.app.social.Session;
import com.pokelocation.app.social.net.Api;
import com.pokelocation.app.ui.base.BaseActivity;
import com.pokelocation.app.util.Ui;

/** Recebe pokelocationauth://callback do Google (via servidor), troca o code e abre Amigos. Só mostra progresso. */
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
            return Boolean.TRUE;
        }, new Io.Callback<Boolean>() {
            @Override public void onResult(Boolean ok) {
                startActivity(new Intent(AuthCallbackActivity.this, FriendsActivity.class));
                finish();
            }
            @Override public void onError(Throwable t) {
                if (Session.loggedIn(app)) onResult(Boolean.TRUE);   // recriada: a 1ª instância já trocou o code
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
