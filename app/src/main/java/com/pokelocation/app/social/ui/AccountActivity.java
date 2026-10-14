package com.pokelocation.app.social.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.pokelocation.app.Io;
import com.pokelocation.app.R;
import com.pokelocation.app.data.Prefs;
import com.pokelocation.app.social.Session;
import com.pokelocation.app.social.net.Api;
import com.pokelocation.app.ui.base.BaseActivity;
import com.pokelocation.app.util.Ui;

public final class AccountActivity extends BaseActivity {
    static final String LOGIN_RETURN = LoginActivity.RETURN_BACK;

    private LinearLayout list;
    private TextView name, email;
    private MaterialSwitch share;
    private Api.Me me;
    private Io.Task task;

    static Class<?> entry(boolean loggedIn) {
        return loggedIn ? AccountActivity.class : LoginActivity.class;
    }

    public static Intent open(Context c) {
        boolean in = Session.loggedIn(c);
        Intent i = new Intent(c, entry(in));
        return in ? i : i.putExtra(LoginActivity.EXTRA_RETURN_TO, LOGIN_RETURN);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!Session.loggedIn(this)) {
            startActivity(open(this));
            finish();
            return;
        }
        setContentView(R.layout.soc_account);
        setupToolbar(getString(R.string.soc_account), true);
        applyInsets(findViewById(R.id.soc_account_root));
        list = findViewById(R.id.soc_account_list);
        View who = row(R.string.soc_account_signed_in, null);
        who.setClickable(false);
        who.setFocusable(false);
        name = who.findViewById(R.id.set_row_title);
        email = who.findViewById(R.id.set_row_summary);
        email.setVisibility(View.GONE);
        row(R.string.soc_username_change, v -> startActivity(
                UsernameActivity.intent(this, LoginActivity.RETURN_BACK, me != null ? me.username : null)));
        // Só grava; o LocationSharer do :overlay lê a cada tick e envia o LocationOff.
        View s = row(R.string.soc_share_location, v -> {
            Prefs.setShareLocation(this, !Prefs.shareLocation(this));
            share.setChecked(Prefs.shareLocation(this));
        });
        TextView sum = s.findViewById(R.id.set_row_summary);
        sum.setText(R.string.soc_share_location_summary);
        sum.setVisibility(View.VISIBLE);
        share = s.findViewById(R.id.set_row_switch);
        share.setVisibility(View.VISIBLE);
        share.setChecked(Prefs.shareLocation(this));
        row(R.string.soc_logout, v -> confirmLogout());
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (list != null) load();   // voltando da troca do @nome
    }

    @Override
    protected void onDestroy() {
        if (task != null) task.cancel();
        super.onDestroy();
    }

    private View row(int titleRes, View.OnClickListener click) {
        View v = getLayoutInflater().inflate(R.layout.set_row, list, false);
        ((TextView) v.findViewById(R.id.set_row_title)).setText(titleRes);
        v.findViewById(R.id.set_row_summary).setVisibility(View.GONE);
        if (click != null) v.setOnClickListener(click);
        list.addView(v);
        return v;
    }

    private void load() {
        if (task != null) task.cancel();
        Context app = getApplicationContext();
        task = Io.run(() -> Api.me(app), new Io.Callback<Api.Me>() {
            @Override public void onResult(Api.Me m) {
                task = null;
                me = m;
                if (m.username != null) name.setText(FriendsActivity.label(m.username, null));
                email.setText(m.email);
                email.setVisibility(m.email != null ? View.VISIBLE : View.GONE);
            }
            @Override public void onError(Throwable t) {
                task = null;
                if (Errors.unauthorized(t)) {
                    startActivity(open(AccountActivity.this));
                    finish();
                } else {
                    Ui.toast(AccountActivity.this, Errors.message(t));
                }
            }
        });
    }

    private void confirmLogout() {
        Context app = getApplicationContext();
        new MaterialAlertDialogBuilder(this)
                .setTitle(me != null && me.email != null ? me.email : getString(R.string.soc_account))
                .setMessage(R.string.soc_logout_confirm)
                .setPositiveButton(R.string.soc_logout, (d, w) -> {
                    if (task != null) task.cancel();
                    task = Io.run(() -> {
                        Api.logout(app);
                        return Boolean.TRUE;
                    }, new Io.Callback<Boolean>() {
                        @Override public void onResult(Boolean ok) { finish(); }
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}
