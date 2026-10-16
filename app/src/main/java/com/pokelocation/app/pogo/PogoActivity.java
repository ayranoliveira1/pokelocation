package com.pokelocation.app.pogo;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.verify.domain.DomainVerificationManager;
import android.content.pm.verify.domain.DomainVerificationUserState;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.pokelocation.app.Io;
import com.pokelocation.app.R;
import com.pokelocation.app.social.Session;
import com.pokelocation.app.social.ui.AccountActivity;
import com.pokelocation.app.ui.base.BaseActivity;
import com.pokelocation.app.util.Ui;

import org.json.JSONArray;

/** Conta PoGo Coordinates e destino do redirect https do login (singleTask: o Custom Tab acima dela é fechado). */
public final class PogoActivity extends BaseActivity {
    static final int NEED_ACCOUNT = 0, SIGN_IN = 1, BUSY = 2, LINKED = 3;
    private LinearLayout list;
    private Io.Task task;
    private boolean busy, toSettings;
    private final ActivityResultLauncher<Intent> discord = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), r -> {
                Pogo.wipeWebDataWhenGone(getApplicationContext());
                Intent d = r.getData();
                String code = d != null ? d.getStringExtra(PogoDiscordActivity.EXTRA_CODE) : null;
                if (r.getResultCode() == RESULT_OK && code != null
                        && "login:discord".equals(d.getStringExtra(PogoDiscordActivity.EXTRA_STATE)))
                    run("discord", code);
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.soc_account);
        setupToolbar(getString(R.string.pogo_account), true);
        applyInsets(findViewById(R.id.soc_account_root));
        list = findViewById(R.id.soc_account_list);
        Context app = getApplicationContext();
        Io.run(() -> Pogo.wipeWebData(app)); // o :pogoweb pode ter morrido antes da limpeza anterior
        handle(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handle(intent);
        render();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (task == null && Pogo.loggedIn(this)) run(null, null);
        render(); // volta do Login (RETURN_BACK) ou logout do social em outra tela
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!toSettings) return;
        toSettings = false;
        if (!busy && !Pogo.loggedIn(this) && linkAllowed()) login();
    }

    @Override
    protected void onDestroy() {
        if (task != null && !busy) task.cancel(); // a troca do code segue: o pending já foi consumido e o code é de uso único
        super.onDestroy();
    }

    private void handle(Intent intent) {
        Uri u = intent.getData();
        if (u == null || !Pogo.isCallback(u, "google")) return;
        setIntent(new Intent(intent).setData(null));
        if (!Pogo.consumePending(this)) return;
        String code = u.getQueryParameter("code");
        if (code != null && u.getQueryParameter("error") == null) run("google", code);
    }

    /** code != null: troca o code pelo JWT; senão só atualiza o /users/me. */
    private void run(String provider, String code) {
        if (task != null) task.cancel();
        busy = code != null;
        Context app = getApplicationContext();
        task = Io.run(() -> {
            if (code != null) Pogo.login(app, provider, code);
            else Pogo.me(app);
            return Boolean.TRUE;
        }, new Io.Callback<Boolean>() {
            @Override public void onResult(Boolean ok) {
                task = null;
                busy = false;
                if (!isDestroyed()) render();
            }
            @Override public void onError(Throwable t) {
                task = null;
                busy = false;
                if (isDestroyed()) return;
                render();
                if (code != null) Ui.toast(PogoActivity.this, R.string.pogo_login_failed);
            }
        });
    }

    static int screen(boolean busy, boolean social, boolean pogo) {
        if (busy) return BUSY;
        if (!social) return NEED_ACCOUNT;
        return pogo ? LINKED : SIGN_IN;
    }

    private void render() {
        list.removeAllViews();
        int screen = screen(busy, Session.loggedIn(this), Pogo.loggedIn(this));
        if (screen == BUSY) {
            row(getString(R.string.pogo_logging_in), null, null);
        } else if (screen == NEED_ACCOUNT) {
            // o vínculo fica no nosso servidor, preso ao users.id
            row(getString(R.string.pogo_need_account), null, v -> startActivity(AccountActivity.open(this)));
        } else if (screen == SIGN_IN) {
            row(getString(R.string.pogo_login), getString(R.string.pogo_login_summary), v -> login());
            row(getString(R.string.pogo_login_discord), null, v -> discord.launch(new Intent(this, PogoDiscordActivity.class)));
        } else {
            String name = Pogo.name(this);
            View who = row(name != null ? name : getString(R.string.pogo_account), trainers(), null);
            who.setClickable(false);
            if (Pogo.trainers(this).length() == 0)
                row(getString(R.string.pogo_t_link), getString(R.string.pogo_no_trainer), v -> TrainerLink.start(this, this::render));
        }
        row(getString(R.string.pogo_map_layer), null,
                v -> startActivity(PogoListActivity.intent(this, Double.NaN, Double.NaN)));
        if (!busy && Pogo.canAlert(this)) row(getString(R.string.pogo_alerts), null, v -> startActivity(PogoAlertsActivity.intent(this)));
        row(getString(R.string.pogo_filters), getString(R.string.pogo_filters_summary),
                v -> startActivity(new Intent(this, PogoFiltersActivity.class)));
        if (!busy && Pogo.loggedIn(this)) row(getString(R.string.soc_logout), null, v -> confirmLogout());
    }

    private String trainers() {
        JSONArray t = Pogo.trainers(this);
        if (t.length() == 0) return null;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            if (i > 0) b.append(", ");
            String n = Pogo.str(t.optJSONObject(i), "trainer_name");
            b.append(n != null ? n : Pogo.str(t.optJSONObject(i), "trainer_code"));
        }
        return getString(R.string.pogo_trainers, b);
    }

    private View row(String title, String summary, View.OnClickListener click) {
        View v = getLayoutInflater().inflate(R.layout.set_row, list, false);
        ((TextView) v.findViewById(R.id.set_row_title)).setText(title);
        TextView s = v.findViewById(R.id.set_row_summary);
        if (summary != null) s.setText(summary);
        else s.setVisibility(View.GONE);
        if (click != null) v.setOnClickListener(click);
        list.addView(v);
        return v;
    }

    /** Custom Tab sem androidx.browser: o extra SESSION (mesmo null) é o que o Chrome lê para abrir como tab. */
    private void login() {
        if (!linkAllowed()) {
            askOpenByDefault();
            return;
        }
        Bundle b = new Bundle();
        b.putBinder("android.support.customtabs.extra.SESSION", null);
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Pogo.beginLogin(this)).putExtras(b));
        } catch (ActivityNotFoundException e) {
            Ui.toast(this, R.string.soc_no_browser);
        }
    }

    /** API 31+: sem o domínio em "Abrir por padrão", o /callback fica no navegador e o site consome o code. */
    private boolean linkAllowed() {
        if (Build.VERSION.SDK_INT < 31) return true;
        try {
            DomainVerificationUserState st = getSystemService(DomainVerificationManager.class)
                    .getDomainVerificationUserState(getPackageName());
            Integer s = st == null ? null : st.getHostToStateMap().get("pogocoordinates.com");
            return s != null && (s == DomainVerificationUserState.DOMAIN_STATE_SELECTED
                    || s == DomainVerificationUserState.DOMAIN_STATE_VERIFIED);
        } catch (PackageManager.NameNotFoundException e) {
            return true;
        }
    }

    private void askOpenByDefault() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.pogo_open_default_title)
                .setMessage(R.string.pogo_open_default_msg)
                .setPositiveButton(R.string.pogo_open_settings, (d, w) -> {
                    Uri pkg = Uri.parse("package:" + getPackageName());
                    toSettings = true;
                    try {
                        startActivity(new Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, pkg));
                    } catch (ActivityNotFoundException e) {
                        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg));
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void confirmLogout() {
        Context app = getApplicationContext();
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.pogo_account)
                .setMessage(R.string.pogo_logout_confirm)
                .setPositiveButton(R.string.soc_logout, (d, w) -> {
                    if (task != null) task.cancel();
                    task = Io.run(() -> {
                        Pogo.logout(app);
                        return Boolean.TRUE;
                    }, new Io.Callback<Boolean>() {
                        @Override public void onResult(Boolean ok) {
                            task = null;
                            render();
                        }
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}
