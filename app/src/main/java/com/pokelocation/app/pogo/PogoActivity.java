package com.pokelocation.app.pogo;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.verify.domain.DomainVerificationManager;
import android.content.pm.verify.domain.DomainVerificationUserState;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.pokelocation.app.Io;
import com.pokelocation.app.R;
import com.pokelocation.app.social.Session;
import com.pokelocation.app.social.ui.AccountActivity;
import com.pokelocation.app.ui.base.BaseActivity;
import com.pokelocation.app.util.Ui;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/** Conta PoGo Coordinates e destino do redirect https do login (singleTask: o Custom Tab acima dela é fechado). */
public final class PogoActivity extends BaseActivity {
    static final int NEED_ACCOUNT = 0, SIGN_IN = 1, BUSY = 2, LINKED = 3;
    private LinearLayout list, trainers;
    private ImageView icon;
    private TextView avatar, title, sub, trainerLabel;
    private View progress, signIn, logout;
    private Button action;
    private BottomSheetDialog sheet;
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
        setContentView(R.layout.pogo_account);
        setupToolbar(getString(R.string.pogo_account), true);
        applyInsets(findViewById(R.id.pogo_acc_root));
        list = findViewById(R.id.pogo_acc_list);
        trainers = findViewById(R.id.pogo_acc_trainers);
        trainerLabel = findViewById(R.id.pogo_acc_trainer_label);
        icon = findViewById(R.id.pogo_acc_icon);
        avatar = findViewById(R.id.pogo_acc_avatar);
        avatar.setBackgroundTintList(ColorStateList.valueOf(
                MaterialColors.getColor(avatar, com.google.android.material.R.attr.colorPrimaryContainer)));
        avatar.setTextColor(MaterialColors.getColor(avatar, com.google.android.material.R.attr.colorOnPrimaryContainer));
        title = findViewById(R.id.pogo_acc_title);
        sub = findViewById(R.id.pogo_acc_sub);
        progress = findViewById(R.id.pogo_acc_progress);
        action = findViewById(R.id.pogo_acc_action);
        signIn = findViewById(R.id.pogo_acc_sign_in);
        signIn.setOnClickListener(v -> chooseLogin());
        logout = findViewById(R.id.pogo_acc_logout);
        logout.setOnClickListener(v -> confirmLogout());
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
        if (sheet != null) sheet.dismiss();
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
        int screen = screen(busy, Session.loggedIn(this), Pogo.loggedIn(this));
        boolean linked = screen == LINKED;
        icon.setVisibility(linked ? View.GONE : View.VISIBLE);
        avatar.setVisibility(linked ? View.VISIBLE : View.GONE);
        progress.setVisibility(screen == BUSY ? View.VISIBLE : View.GONE);
        signIn.setVisibility(screen == SIGN_IN ? View.VISIBLE : View.GONE);
        logout.setVisibility(linked ? View.VISIBLE : View.GONE);
        trainers.setVisibility(linked ? View.VISIBLE : View.GONE);
        action.setVisibility(View.GONE);
        sub.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0);
        if (screen == BUSY) {
            title.setText(R.string.pogo_logging_in);
            sub.setText(null);
        } else if (screen == NEED_ACCOUNT) {
            // o vínculo fica no nosso servidor, preso ao users.id
            title.setText(R.string.pogo_account);
            sub.setText(R.string.pogo_need_account);
            action(R.string.pogo_enter_account, v -> startActivity(AccountActivity.open(this)));
        } else if (screen == SIGN_IN) {
            title.setText(R.string.pogo_not_connected);
            sub.setText(R.string.pogo_login_summary);
        } else {
            renderAccount();
        }
        sub.setVisibility(sub.length() > 0 ? View.VISIBLE : View.GONE);
        list.removeAllViews();
        row(getString(R.string.pogo_map_layer), null,
                v -> startActivity(PogoListActivity.intent(this, Double.NaN, Double.NaN)));
        if (linked && Pogo.canAlert(this)) row(getString(R.string.pogo_alerts), null, v -> startActivity(PogoAlertsActivity.intent(this)));
        row(getString(R.string.pogo_filters), getString(R.string.pogo_filters_summary),
                v -> startActivity(new Intent(this, PogoFiltersActivity.class)));
    }

    private void renderAccount() {
        String name = Pogo.name(this);
        title.setText(name != null ? name : getString(R.string.pogo_account));
        avatar.setText(name != null && !name.isEmpty() ? name.substring(0, 1).toUpperCase(Locale.ROOT) : "?");
        String p = Pogo.provider(this);
        boolean discord = "discord".equals(p);
        sub.setText(p == null ? null : getString(R.string.pogo_via, discord ? "Discord" : "Google"));
        if (p != null) sub.setCompoundDrawablesRelativeWithIntrinsicBounds(
                discord ? R.drawable.pogo_ic_discord : R.drawable.soc_ic_google, 0, 0, 0);
        trainers.removeViews(2, trainers.getChildCount() - 2); // ficam o divisor e o rótulo
        JSONArray t = Pogo.trainers(this);
        trainerLabel.setText(t.length() == 0 ? R.string.pogo_no_trainer : R.string.pogo_trainer_linked);
        if (t.length() == 0) action(R.string.pogo_t_link, v -> TrainerLink.start(this, this::render));
        for (int i = 0; i < t.length(); i++) trainer(t.optJSONObject(i));
    }

    private void trainer(JSONObject t) {
        View v = getLayoutInflater().inflate(R.layout.pogo_trainer_row, trainers, false);
        String n = Pogo.str(t, "trainer_name");
        ((TextView) v.findViewById(R.id.pogo_tr_name)).setText(n != null ? n : Pogo.str(t, "trainer_code"));
        ((ImageView) v.findViewById(R.id.pogo_tr_team)).setImageTintList(ColorStateList.valueOf(teamColor(v, t.optInt("team"))));
        int level = t.optInt("level");
        String team = Pogo.str(t, "team_name");
        String info = level > 0 ? getString(R.string.pogo_level, level) : null;
        if (team != null) info = info == null ? team : info + " · " + team;
        if (info != null) ((TextView) v.findViewById(R.id.pogo_tr_info)).setText(info);
        else v.findViewById(R.id.pogo_tr_info_row).setVisibility(View.GONE);
        trainers.addView(v);
    }

    private int teamColor(View v, int team) {
        switch (team) {
            case 1: return getColor(R.color.pogo_team_mystic);
            case 2: return getColor(R.color.pogo_team_valor);
            case 3: return getColor(R.color.pogo_team_instinct);
            default: return MaterialColors.getColor(v, com.google.android.material.R.attr.colorOutline);
        }
    }

    private void action(int text, View.OnClickListener click) {
        action.setText(text);
        action.setOnClickListener(click);
        action.setVisibility(View.VISIBLE);
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

    private void chooseLogin() {
        View root = getLayoutInflater().inflate(R.layout.pogo_login_sheet, null);
        ((TextView) root.findViewById(R.id.pogo_sheet_google_hint))
                .setText(linkAllowed() ? R.string.pogo_google_hint_ok : R.string.pogo_google_hint);
        root.findViewById(R.id.pogo_sheet_google).setOnClickListener(v -> {
            sheet.dismiss();
            login();
        });
        root.findViewById(R.id.pogo_sheet_discord).setOnClickListener(v -> {
            sheet.dismiss();
            discord.launch(new Intent(this, PogoDiscordActivity.class));
        });
        sheet = new BottomSheetDialog(this);
        sheet.setContentView(root);
        sheet.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
        sheet.show();
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
