package com.pokelocation.app.social.ui;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputLayout;
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
    private Io.Task task, deleting;

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
        SessionEnd.watch(this);
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
        View del = row(R.string.soc_delete_account, v -> confirmDelete());
        ((TextView) del.findViewById(R.id.set_row_title)).setTextColor(ContextCompat.getColor(this, R.color.danger));
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!Session.loggedIn(this)) {
            finish();
            return;
        }
        if (list != null) load();   // voltando da troca do @nome
    }

    @Override
    protected void onDestroy() {
        if (task != null) task.cancel();
        if (deleting != null) deleting.cancel();
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

    private void confirmDelete() {
        if (me == null) {
            Ui.toast(this, R.string.soc_delete_loading);
            load();
            return;
        }
        View form = me.hasPassword ? getLayoutInflater().inflate(R.layout.soc_delete, null) : null;
        AlertDialog d = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.soc_delete_account)
                .setMessage(me.hasPassword ? R.string.soc_delete_confirm : R.string.soc_delete_confirm_google)
                .setView(form)
                .setPositiveButton(R.string.soc_delete, null)
                .setNegativeButton(R.string.cancel, null)
                .create();
        d.setOnShowListener(x -> {
            Button ok = d.getButton(AlertDialog.BUTTON_POSITIVE);
            ok.setTextColor(ContextCompat.getColor(this, R.color.danger));
            if (form == null) {
                ok.setOnClickListener(v -> reauth(d, ok));
                return;
            }
            TextInputLayout box = form.findViewById(R.id.soc_delete_box);
            EditText pass = form.findViewById(R.id.soc_delete_password);
            ok.setOnClickListener(v -> deleteWithPassword(d, ok, box, pass.getText().toString()));
            pass.setOnEditorActionListener((v, action, e) -> {
                if (action != EditorInfo.IME_ACTION_DONE || !ok.isEnabled()) return false;
                ok.performClick();
                return true;
            });
            pass.requestFocus();
        });
        d.show();
    }

    private void deleteWithPassword(AlertDialog d, Button ok, TextInputLayout box, String password) {
        if (password.isEmpty()) return;
        box.setError(null);
        ok.setEnabled(false);
        Context app = getApplicationContext();
        deleting = Io.run(() -> {
            Api.deleteAccount(app, password, null);
            return Boolean.TRUE;
        }, new Io.Callback<Boolean>() {
            @Override public void onResult(Boolean r) {
                deleting = null;
                d.dismiss();
                deleted(AccountActivity.this);
            }
            @Override public void onError(Throwable t) {
                deleting = null;
                if (Errors.unauthorized(t)) {
                    d.dismiss();
                    sessionEnded(AccountActivity.this);
                    return;
                }
                ok.setEnabled(true);
                box.setError(getString(Errors.deleteMessage(t)));
            }
        });
    }

    private void reauth(AlertDialog d, Button ok) {
        ok.setEnabled(false);
        Context app = getApplicationContext();
        deleting = Io.run(() -> Api.googleReauthStart(app), new Io.Callback<Uri>() {
            @Override public void onResult(Uri url) {
                deleting = null;
                d.dismiss();
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, url));
                } catch (ActivityNotFoundException e) {
                    Ui.toast(AccountActivity.this, R.string.soc_no_browser);
                }
            }
            @Override public void onError(Throwable t) {
                deleting = null;
                ok.setEnabled(true);
                Ui.toast(AccountActivity.this, Errors.deleteMessage(t));
            }
        });
    }

    static void deleted(Activity a) {
        Ui.toast(a.getApplicationContext(), R.string.soc_delete_done);
        LoginActivity.go(a, null);
    }

    static void sessionEnded(Activity a) {
        LoginActivity.go(a, null);
    }
}
