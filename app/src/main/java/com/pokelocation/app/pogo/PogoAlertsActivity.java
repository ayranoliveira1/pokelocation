package com.pokelocation.app.pogo;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.tabs.TabLayout;
import com.pokelocation.app.Io;
import com.pokelocation.app.R;
import com.pokelocation.app.geo.CoordParser;
import com.pokelocation.app.ui.base.BaseActivity;
import com.pokelocation.app.util.Perms;
import com.pokelocation.app.util.Ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Alertas de Pokémon em duas abas. Notificações: os Pokémon já avisados (PogoAlert), no PogoPanel com lista fixa.
 * Meus alertas: as regras do nosso servidor, que manda o push, com liga/desliga e excluir; "Novo alerta" troca a lista
 * pelo PogoFilterForm (a "Área" dele é o escopo) + nome. Sem edição: excluir e criar outro.
 */
public final class PogoAlertsActivity extends BaseActivity {
    private final List<Pogo.Rule> rules = new ArrayList<>();
    private ListView list;
    private TextView status, center;
    private Button add;
    private View listBox, editor;
    private EditText name;
    private FrameLayout formBox;
    private PogoFilterForm form;
    private PogoFilter editing;
    private double lat, lng;
    private BaseAdapter adapter;
    private boolean busy, deniedForever;
    private LinearLayout banner;
    private Button bannerAction;
    private TabLayout tabs;
    private PogoPanel panel;
    private boolean resumed;
    private Map<String, String> pushed;
    private final ActivityResultLauncher<String> notifPerm = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (!granted && Build.VERSION.SDK_INT >= 33
                        && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) deniedForever = true;
                checkNotifications(false);
            });
    private final OnBackPressedCallback back = new OnBackPressedCallback(true) {
        @Override public void handleOnBackPressed() {
            boolean rulesTab = tabs.getSelectedTabPosition() == 1;
            if (rulesTab && form != null) {
                if (!form.back()) closeEditor();
            } else if (rulesTab || !panel.back()) {
                finish();
            }
        }
    };

    public static Intent intent(Context c) {
        return new Intent(c, PogoAlertsActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        double[] c = PogoListActivity.center(this, Double.NaN, Double.NaN);
        lat = c[0];
        lng = c[1];
        int pad = Ui.dp(this, 16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        getLayoutInflater().inflate(R.layout.base_toolbar, root, true);
        banner = new LinearLayout(this);
        banner.setGravity(Gravity.CENTER_VERTICAL);
        banner.setPadding(pad, Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
        banner.setBackgroundColor(getColor(R.color.primary_container));
        TextView off = text(14);
        off.setText(R.string.pogo_notif_off);
        off.setTextColor(getColor(R.color.on_primary_container));
        banner.addView(off, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bannerAction = new Button(this);
        bannerAction.setOnClickListener(v -> fixNotifications());
        banner.addView(bannerAction);
        banner.setVisibility(View.GONE);
        root.addView(banner);
        tabs = new TabLayout(this);
        tabs.addTab(tabs.newTab().setText(R.string.pogo_tab_notified));
        tabs.addTab(tabs.newTab().setText(R.string.pogo_tab_rules));
        root.addView(tabs);

        LinearLayout lb = new LinearLayout(this);
        lb.setOrientation(LinearLayout.VERTICAL);
        status = text(14);
        status.setPadding(pad, pad, pad, 0);
        lb.addView(status);
        list = new ListView(this);
        list.setDivider(null);
        lb.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        add = new Button(this);
        add.setText(R.string.pogo_alert_new);
        add.setOnClickListener(v -> openEditor());
        lb.addView(add, margins(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT), pad));
        listBox = lb;

        LinearLayout ed = new LinearLayout(this);
        ed.setOrientation(LinearLayout.VERTICAL);
        ed.setVisibility(View.GONE);
        name = new EditText(this);
        name.setHint(R.string.pogo_alert_name);
        name.setSingleLine(true);
        name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(60)});   // o servidor recusa nome com mais de 60
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        ed.addView(name, margins(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT), pad));
        center = text(13);
        center.setPadding(pad, 0, pad, 0);
        ed.addView(center);
        formBox = new FrameLayout(this);
        ed.addView(formBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.END);
        buttons.setPadding(pad, 0, pad, pad);
        Button cancel = new Button(this), save = new Button(this);
        cancel.setText(R.string.cancel);
        cancel.setOnClickListener(v -> closeEditor());
        save.setText(R.string.save);
        save.setOnClickListener(v -> save());
        buttons.addView(cancel);
        buttons.addView(save);
        ed.addView(buttons);
        editor = ed;

        panel = new PogoPanel(this, lat, lng, R.string.pogo_card_set_dest, true, new PogoPanel.Host() {
            @Override public void go(double la, double ln, String title) { PogoListActivity.fill(PogoAlertsActivity.this, la, ln); }

            @Override public void close() { finish(); }
        });
        FrameLayout content = new FrameLayout(this);
        content.addView(panel.view);
        content.addView(listBox);
        content.addView(editor);
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        setupToolbar(getString(R.string.pogo_alerts), true);
        applyInsets(root);
        getOnBackPressedDispatcher().addCallback(this, back);

        adapter = new BaseAdapter() {
            @Override public int getCount() { return rules.size(); }
            @Override public Object getItem(int i) { return rules.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View row, ViewGroup parent) { return row(rules.get(i)); }
        };
        list.setAdapter(adapter);
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) { showTab(); }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });
        showTab();
        load();
        Bundle b = getIntent().getBundleExtra(PogoAlert.EXTRA);
        if (b != null) {
            pushed = new HashMap<>();
            for (String k : b.keySet()) pushed.put(k, b.getString(k));
        }
        checkNotifications(true);
    }

    /** Volta do jogo com a tela no topo: os pushes que chegaram no meio entram na lista. */
    @Override
    protected void onStart() {
        super.onStart();
        Context app = getApplicationContext();
        Io.run(() -> PogoAlert.load(app), new Io.Callback<List<Pogo.Mon>>() {
            @Override public void onResult(List<Pogo.Mon> l) {
                if (isDestroyed()) return;
                panel.showFixed(l, getString(R.string.pogo_notified_none));
                if (pushed != null && pushed.get("rc") != null) panel.showMon(PogoAlert.mon(pushed));   // toque no push
                pushed = null;
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (form != null) form.render();   // volta do "Vincular treinador"
        checkNotifications(false);         // volta das configurações
        showTab();
    }

    @Override
    protected void onPause() {
        resumed = false;
        panel.setPaused(true);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (form != null) form.destroy();
        form = null;
        panel.destroy();
        super.onDestroy();
    }

    private void showTab() {
        boolean notified = tabs.getSelectedTabPosition() == 0;
        panel.view.setVisibility(notified ? View.VISIBLE : View.GONE);
        listBox.setVisibility(notified || form != null ? View.GONE : View.VISIBLE);
        editor.setVisibility(!notified && form != null ? View.VISIBLE : View.GONE);
        panel.setPaused(!notified || !resumed);   // o tick de 1 s só com a aba à vista
    }

    private void load() {
        status.setText(R.string.pogo_map_loading);
        Context app = getApplicationContext();
        Io.run(() -> Pogo.rules(app), new Io.Callback<List<Pogo.Rule>>() {
            @Override public void onResult(List<Pogo.Rule> l) {
                rules.clear();
                rules.addAll(l);
                render();
            }

            @Override public void onError(Throwable t) { status.setText(error(t)); }
        });
    }

    private void render() {
        if (isDestroyed()) return;
        adapter.notifyDataSetChanged();
        boolean full = rules.size() >= Pogo.MAX_RULES;
        status.setText(full ? getString(R.string.pogo_alert_limit, Pogo.MAX_RULES) : rules.isEmpty() ? getString(R.string.pogo_alert_none) : "");
        status.setVisibility(TextUtils.isEmpty(status.getText()) ? View.GONE : View.VISIBLE);
        add.setEnabled(!full && !busy);
    }

    private View row(Pogo.Rule r) {
        int pad = Ui.dp(this, 16);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(pad, Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(16);
        title.setText(r.name);
        title.setTextColor(getColor(R.color.on_surface));
        TextView sub = text(13);
        if ("trainer_required".equals(r.disabledReason) && !r.enabled) {   // o servidor desligou: o vínculo caiu
            sub.setText(R.string.pogo_alert_need_trainer);
            sub.setTextColor(getColor(R.color.danger));
            sub.setOnClickListener(v -> startActivity(new Intent(this, PogoActivity.class)));
        } else {
            sub.setText(summary(r));
        }
        texts.addView(title);
        texts.addView(sub);
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Switch on = new Switch(this);
        on.setChecked(r.enabled);
        on.setOnCheckedChangeListener((b, checked) -> setEnabled(r, checked, b));
        row.addView(on);
        ImageView del = new ImageView(this);
        del.setImageResource(R.drawable.ic_delete);
        del.setColorFilter(getColor(R.color.on_surface_variant));
        del.setContentDescription(getString(R.string.delete));
        int t = Ui.dp(this, 48), p = Ui.dp(this, 12);
        del.setPadding(p, p, p, p);
        del.setOnClickListener(v -> confirmDelete(r));
        row.addView(del, new LinearLayout.LayoutParams(t, t));
        return row;
    }

    private String summary(Pogo.Rule r) {
        String s = r.world ? getString(R.string.pogo_panel_world) : getString(R.string.pogo_chip_near, r.radiusKm);
        // o escopo já está no texto ("Perto · 2 km"); o activeCount também o conta
        int n = r.filter == null ? 0 : r.filter.activeCount() - (r.filter.km > 0 ? 1 : 0);
        return n > 0 ? s + " · " + getString(R.string.pogo_alert_filters, n) : s;
    }

    private void setEnabled(Pogo.Rule r, boolean on, View sw) {
        if (r.enabled == on) return;
        r.enabled = on;
        if (on) checkNotifications(true);
        sw.setEnabled(false);
        Context app = getApplicationContext();
        Io.run(() -> Pogo.updateRule(app, r), new Io.Callback<Pogo.Rule>() {
            @Override public void onResult(Pogo.Rule saved) {   // o PUT limpa o disabled_reason: vale o que o servidor devolveu
                int i = rules.indexOf(r);
                if (saved != null && i >= 0) rules.set(i, saved);
                render();
            }

            @Override public void onError(Throwable t) {
                r.enabled = !on;
                Ui.toast(app, error(t));
                adapter.notifyDataSetChanged();
            }
        });
    }

    private void confirmDelete(Pogo.Rule r) {
        new MaterialAlertDialogBuilder(this)
                .setMessage(getString(R.string.pogo_alert_delete, r.name))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (d, w) -> delete(r))
                .show();
    }

    private void delete(Pogo.Rule r) {
        Context app = getApplicationContext();
        Io.run(() -> {
            Pogo.deleteRule(app, r.id);
            return Boolean.TRUE;
        }, new Io.Callback<Boolean>() {
            @Override public void onResult(Boolean ok) {
                rules.remove(r);
                render();
            }

            @Override public void onError(Throwable t) { Ui.toast(app, error(t)); }
        });
    }

    /** Começa dos filtros salvos (os que o usuário já usa no mapa). */
    private void openEditor() {
        editing = PogoFilter.load(this).copy();
        form = new PogoFilterForm(this, editing, () -> {}, on -> {},
                () -> startActivity(new Intent(this, PogoActivity.class)));
        formBox.addView(form.view());
        name.setText("");
        center.setText(Double.isNaN(lat) ? "" : getString(R.string.pogo_alert_center, CoordParser.text(lat, lng)));
        listBox.setVisibility(View.GONE);
        editor.setVisibility(View.VISIBLE);
    }

    private void closeEditor() {
        if (form != null) form.destroy();
        form = null;
        editing = null;
        formBox.removeAllViews();
        editor.setVisibility(View.GONE);
        listBox.setVisibility(View.VISIBLE);
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(name.getWindowToken(), 0);
    }

    private void save() {
        if (busy || editing == null) return;
        Pogo.Rule r = new Pogo.Rule();
        r.filter = PogoText.asSent(this, editing);   // sem treinador o form esconde o Shiny: um shiny salvo de antes viraria 422
        r.world = editing.km == 0;
        if (!r.world && Double.isNaN(lat)) {
            Ui.toast(this, R.string.pogo_alert_no_center);
            return;
        }
        r.lat = lat;
        r.lng = lng;
        r.radiusKm = editing.km;
        r.enabled = true;
        String n = name.getText().toString().trim();
        r.name = n.isEmpty() ? summary(r) : n;
        busy = true;
        Context app = getApplicationContext();
        Io.run(() -> Pogo.addRule(app, r), new Io.Callback<Pogo.Rule>() {
            @Override public void onResult(Pogo.Rule saved) {
                busy = false;
                rules.add(saved);
                closeEditor();
                render();
                checkNotifications(true);
            }

            @Override public void onError(Throwable t) {
                busy = false;
                Ui.toast(app, error(t));
            }
        });
    }

    private static final int N_OK = 0, N_ASK = 1, N_APP = 2, N_CHANNEL = 3;

    /** Por que o push de alerta não chegaria (só avisa: criar e ligar regras continua livre). */
    private int notifications() {
        if (!Perms.notificationsGranted(this)) return deniedForever ? N_APP : N_ASK;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (!nm.areNotificationsEnabled()) return N_APP;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = nm.getNotificationChannel(PogoAlert.CHANNEL);   // null = ainda não houve push: não está bloqueado
            if (ch != null && ch.getImportance() == NotificationManager.IMPORTANCE_NONE) return N_CHANNEL;
        }
        return N_OK;
    }

    /** ask: pede a permissão do API 33+ (ao abrir, criar ou ligar um alerta); sem ask, só atualiza o banner. */
    private void checkNotifications(boolean ask) {
        int n = notifications();
        if (ask && n == N_ASK) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS);
            return;
        }
        banner.setVisibility(n == N_OK ? View.GONE : View.VISIBLE);
        bannerAction.setText(n == N_ASK ? R.string.pogo_notif_enable : R.string.pogo_notif_settings);
    }

    private void fixNotifications() {
        int n = notifications();
        if (n == N_ASK) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS);
            return;
        }
        Intent i = n == N_CHANNEL && Build.VERSION.SDK_INT >= 26
                ? new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())
                        .putExtra(Settings.EXTRA_CHANNEL_ID, PogoAlert.CHANNEL)
                : Perms.notificationSettings(this);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            startActivity(Perms.appDetails(this));
        }
    }

    private String error(Throwable t) {
        if (Pogo.ruleLimit(t)) return getString(R.string.pogo_alert_limit, Pogo.MAX_RULES);
        if (Pogo.relogin(t)) return getString(R.string.pogo_alert_relogin);
        if (Pogo.trainerRequired(t)) return getString(R.string.pogo_no_trainer);
        if (Pogo.shinyAccountUnavailable(t)) return getString(R.string.pogo_alert_bad_account);
        return PogoText.error(this, t);
    }

    private TextView text(int sp) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(getColor(R.color.on_surface_variant));
        return t;
    }

    private static LinearLayout.LayoutParams margins(LinearLayout.LayoutParams lp, int m) {
        lp.setMargins(m, m / 2, m, m / 2);
        return lp;
    }
}
