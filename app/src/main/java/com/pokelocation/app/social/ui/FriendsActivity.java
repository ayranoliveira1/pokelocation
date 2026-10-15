package com.pokelocation.app.social.ui;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.textfield.TextInputLayout;
import com.pokelocation.app.Io;
import com.pokelocation.app.R;
import com.pokelocation.app.social.Session;
import com.pokelocation.app.social.crypto.SignalLoader;
import com.pokelocation.app.social.loc.Locations;
import com.pokelocation.app.social.net.Api;
import com.pokelocation.app.social.ui.chat.ChatActivity;
import com.pokelocation.app.social.ui.chat.ChatListActivity;
import com.pokelocation.app.ui.base.BaseActivity;
import com.pokelocation.app.ui.base.StateView;
import com.pokelocation.app.util.Ui;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;

public final class FriendsActivity extends BaseActivity {
    private static final int MENU_CHATS = 1, TAB_REQUESTS = 1;
    private static final long ONLINE_S = 15;
    private StateView state;
    private View busyBar, outgoingH;
    private TabLayout tabs;
    private TextView empty;
    private LinearLayout list, outgoing;
    private Api.Me me;
    private Api.Friends data;
    private Map<String, Long> seen = Collections.emptyMap();
    private Io.Task task;
    private SignalProgress signal;
    private BottomSheetDialog sheet;
    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!Session.loggedIn(this)) {
            toLogin();
            return;
        }
        SessionEnd.watch(this);
        setContentView(R.layout.soc_friends);
        setupToolbar(getString(R.string.soc_friends), true);
        applyInsets(findViewById(R.id.soc_friends_root));
        state = findViewById(R.id.soc_friends_state);
        busyBar = findViewById(R.id.soc_friends_busy);
        tabs = findViewById(R.id.soc_friends_tabs);
        empty = findViewById(R.id.soc_friends_empty);
        list = findViewById(R.id.soc_friends_list);
        outgoing = findViewById(R.id.soc_friends_outgoing);
        outgoingH = findViewById(R.id.soc_friends_outgoing_h);
        tabs.addTab(tabs.newTab().setText(R.string.soc_tab_friends));
        tabs.addTab(tabs.newTab().setText(R.string.soc_tab_requests));
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) { if (data != null) show(); }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });
        findViewById(R.id.soc_friends_fab).setOnClickListener(v -> openSheet());
        state.setOnRetry(() -> {
            state.setState(StateView.LOADING);
            load();
        });
        load();
        signal = new SignalProgress(findViewById(R.id.soc_signal));
        if (!SignalLoader.loaded()) signal.start();   // amigos funcionam sem a lib; o bloco só informa
    }

    /** O @nome pode ter sido trocado na tela Conta. */
    @Override
    protected void onRestart() {
        super.onRestart();
        if (!Session.loggedIn(this)) {
            finish();
            return;
        }
        if (busy) return;
        me = null;
        load();
    }

    @Override
    protected void onDestroy() {
        if (task != null) task.cancel();
        if (sheet != null) sheet.dismiss();
        if (signal != null) signal.detach();   // null: saiu no onCreate sem login
        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu m) {
        m.add(0, MENU_CHATS, 0, R.string.soc_chat_title).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() != MENU_CHATS) return super.onOptionsItemSelected(item);
        startActivity(new Intent(this, ChatListActivity.class));
        return true;
    }

    /** "ABCD1234" → "ABCD-1234" para leitura; o normalizeCode remove o '-' na volta. */
    public static String pretty(String code) {
        return code != null && code.length() == 8 ? code.substring(0, 4) + "-" + code.substring(4) : code;
    }

    public static String label(String username, String code) {
        if (username != null) return "@" + username;
        return code != null ? pretty(code) : "";
    }

    static String initial(String username, String code) {
        String s = username != null ? username : code;
        return s == null || s.isEmpty() ? "?" : s.substring(0, 1).toUpperCase(Locale.ROOT);
    }

    static boolean online(long ageSeconds) {
        return ageSeconds <= ONLINE_S;
    }

    private void load() {
        Context app = getApplicationContext();
        boolean needMe = me == null, needSeen = data == null;   // presença só na 1ª carga: o Io é a thread única do app
        busy(true);
        task = Io.run(() -> {
            Api.Me m = needMe ? Api.me(app) : null;
            Api.Friends f = Api.friends(app);
            Map<String, Long> at = needSeen ? new HashMap<>() : null;
            if (needSeen && SignalLoader.loaded()) {
                try {
                    for (Locations.FriendLoc l : Locations.fetch(app)) at.put(l.userId, l.at);
                } catch (Exception ignored) {   // presença é só um detalhe da linha: sem ela, a lista sai igual
                }
            }
            return new Object[]{m, f, at};
        }, new Io.Callback<Object[]>() {
            @SuppressWarnings("unchecked")
            @Override public void onResult(Object[] r) {
                busy(false);
                if (r[0] != null) me = (Api.Me) r[0];
                if (me != null && me.username == null) {
                    requireUsername();
                    return;
                }
                data = (Api.Friends) r[1];
                if (r[2] != null) seen = (Map<String, Long>) r[2];
                show();
            }
            @Override public void onError(Throwable t) {
                busy(false);
                if (Errors.unauthorized(t)) {
                    toLogin();
                } else if (Errors.usernameRequired(t)) {
                    requireUsername();
                } else if (data == null) {
                    state.setErrorText(getString(Errors.message(t)));
                    state.setState(StateView.ERROR);
                } else {
                    Ui.toast(FriendsActivity.this, Errors.message(t));
                }
            }
        });
    }

    private void show() {
        Context app = getApplicationContext();
        int n = data.incoming.size();
        TabLayout.Tab reqTab = tabs.getTabAt(TAB_REQUESTS);
        if (reqTab != null) reqTab.setText(n == 0 ? getString(R.string.soc_tab_requests) : getString(R.string.soc_tab_requests_n, n));
        list.removeAllViews();
        outgoing.removeAllViews();
        if (tabs.getSelectedTabPosition() == TAB_REQUESTS) {
            for (Api.Request r : data.incoming) {
                row(list, r.username, r.friendCode, null, R.string.soc_accept, v -> act(() -> { Api.accept(app, r.id); return null; }, 0),
                        R.string.soc_decline, v -> act(() -> { Api.decline(app, r.id); return null; }, 0));
            }
            for (Api.Request r : data.outgoing) row(outgoing, r.username, r.friendCode, getString(R.string.soc_pending), 0, null, 0, null);
            empty.setText(R.string.soc_no_requests);
            empty.setVisibility(data.incoming.isEmpty() && data.outgoing.isEmpty() ? View.VISIBLE : View.GONE);
            outgoingH.setVisibility(data.outgoing.isEmpty() ? View.GONE : View.VISIBLE);
        } else {
            long now = System.currentTimeMillis();
            for (Api.Friend fr : data.accepted) {
                Long at = seen.get(fr.userId);
                String sub = null;
                if (at != null && FriendLayer.visible(at, now)) {
                    long age = FriendLayer.ageSeconds(at, now);
                    sub = online(age) ? getString(R.string.soc_online) : getString(R.string.soc_seen, age);
                }
                String name = label(fr.username, fr.friendCode);
                View r = row(list, fr.username, fr.friendCode, sub, 0, null, 0, null);
                r.setOnClickListener(v -> startActivity(ChatActivity.friend(this, fr.userId, name)));
                View more = r.findViewById(R.id.soc_row_more);
                more.setVisibility(View.VISIBLE);
                more.setOnClickListener(v -> {
                    PopupMenu pm = new PopupMenu(this, v);
                    pm.getMenu().add(0, R.string.soc_remove, 0, R.string.soc_remove);
                    pm.getMenu().add(0, R.string.soc_block, 1, R.string.soc_block);
                    pm.setOnMenuItemClickListener(it -> {
                        if (it.getItemId() == R.string.soc_remove) {
                            confirm(R.string.soc_remove_confirm, R.string.soc_remove, name, () -> Api.removeFriend(app, fr.userId));
                        } else {
                            confirm(R.string.soc_block_confirm, R.string.soc_block, name, () -> Api.block(app, fr.userId));
                        }
                        return true;
                    });
                    pm.show();
                });
            }
            empty.setText(R.string.soc_no_friends_yet);
            empty.setVisibility(data.accepted.isEmpty() ? View.VISIBLE : View.GONE);
            outgoingH.setVisibility(View.GONE);
        }
        state.setState(StateView.CONTENT);
    }

    private View row(LinearLayout parent, String username, String code, CharSequence sub, int aRes, View.OnClickListener a,
                     int bRes, View.OnClickListener b) {
        View v = getLayoutInflater().inflate(R.layout.soc_row, parent, false);
        TextView avatar = v.findViewById(R.id.soc_row_avatar);
        avatar.setText(initial(username, code));
        avatar.setBackgroundTintList(ColorStateList.valueOf(
                MaterialColors.getColor(avatar, com.google.android.material.R.attr.colorPrimaryContainer)));
        avatar.setTextColor(MaterialColors.getColor(avatar, com.google.android.material.R.attr.colorOnPrimaryContainer));
        avatar.setVisibility(View.VISIBLE);
        ((TextView) v.findViewById(R.id.soc_row_title)).setText(label(username, code));
        if (sub != null) {
            TextView s = v.findViewById(R.id.soc_row_sub);
            s.setText(sub);
            s.setVisibility(View.VISIBLE);
        }
        action(v.findViewById(R.id.soc_row_a), aRes, a);
        action(v.findViewById(R.id.soc_row_b), bRes, b);
        parent.addView(v);
        return v;
    }

    private static void action(Button btn, int textRes, View.OnClickListener click) {
        if (textRes == 0) return;
        btn.setText(textRes);
        btn.setOnClickListener(click);
        btn.setVisibility(View.VISIBLE);
    }

    private void openSheet() {
        if (sheet != null && sheet.isShowing()) return;
        View root = getLayoutInflater().inflate(R.layout.soc_friends_sheet, null);
        EditText in = root.findViewById(R.id.soc_sheet_input);
        TextInputLayout box = root.findViewById(R.id.soc_sheet_box);
        String user = me != null ? me.username : null, code = me != null ? me.friendCode : null;
        root.findViewById(R.id.soc_sheet_user_h).setVisibility(user != null ? View.VISIBLE : View.GONE);
        root.findViewById(R.id.soc_sheet_user_row).setVisibility(user != null ? View.VISIBLE : View.GONE);
        ((TextView) root.findViewById(R.id.soc_sheet_user)).setText(label(user, null));
        ((TextView) root.findViewById(R.id.soc_sheet_code)).setText(pretty(code));
        root.findViewById(R.id.soc_sheet_user_copy).setOnClickListener(v -> { if (user != null) Ui.copy(this, "@" + user, true); });
        root.findViewById(R.id.soc_sheet_user_share).setOnClickListener(v -> {
            if (user != null) Ui.share(this, getString(R.string.soc_share_username, user));
        });
        root.findViewById(R.id.soc_sheet_code_copy).setOnClickListener(v -> { if (code != null) Ui.copy(this, pretty(code), true); });
        root.findViewById(R.id.soc_sheet_code_share).setOnClickListener(v -> {
            if (code != null) Ui.share(this, getString(R.string.soc_share_code, pretty(code)));
        });
        Runnable add = () -> {
            String s = in.getText().toString().trim();
            if (Api.normalizeCode(s) == null && Api.normalizeUsername(s) == null) {
                box.setError(getString(R.string.soc_invalid_target));
                return;
            }
            box.setError(null);
            Context app = getApplicationContext();
            act(() -> { Api.requestFriend(app, s); return null; }, R.string.soc_request_sent);
        };
        root.findViewById(R.id.soc_sheet_add).setOnClickListener(v -> add.run());
        in.setOnEditorActionListener((v, id, ev) -> {
            if (id != EditorInfo.IME_ACTION_DONE) return false;
            add.run();
            return true;
        });
        sheet = new BottomSheetDialog(this);
        sheet.setContentView(root);
        sheet.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
        if (sheet.getWindow() != null) sheet.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        sheet.show();
    }

    private interface IoAction { void run() throws Exception; }

    private void confirm(int msgRes, int okRes, String name, IoAction work) {
        new MaterialAlertDialogBuilder(this)
                .setMessage(getString(msgRes, name))
                .setPositiveButton(okRes, (d, w) -> act(() -> { work.run(); return null; }, 0))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** Uma ação por vez; no sucesso avisa (doneRes ≠ 0) e recarrega. As lambdas usam o app context, nunca a Activity. */
    private void act(Callable<Void> work, int doneRes) {
        if (busy) return;
        busy(true);
        task = Io.run(work, new Io.Callback<Void>() {
            @Override public void onResult(Void v) {
                if (doneRes != 0) {
                    Ui.toast(FriendsActivity.this, doneRes);
                    if (sheet != null) sheet.dismiss();
                }
                load();
            }
            @Override public void onError(Throwable t) {
                busy(false);
                if (Errors.unauthorized(t)) toLogin();
                else if (Errors.usernameRequired(t)) requireUsername();
                else Ui.toast(FriendsActivity.this, Errors.message(t));
            }
        });
    }

    private void busy(boolean b) {
        busy = b;
        busyBar.setVisibility(b ? View.VISIBLE : View.INVISIBLE);
    }

    private void requireUsername() {
        startActivity(UsernameActivity.intent(this, LoginActivity.RETURN_FRIENDS, null));
        finish();
    }

    private void toLogin() {
        startActivity(new Intent(this, LoginActivity.class).putExtra(LoginActivity.EXTRA_RETURN_TO, LoginActivity.RETURN_FRIENDS));
        finish();
    }
}
