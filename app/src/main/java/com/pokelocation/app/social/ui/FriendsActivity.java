package com.pokelocation.app.social.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.pokelocation.app.Io;
import com.pokelocation.app.R;
import com.pokelocation.app.social.Session;
import com.pokelocation.app.social.crypto.SignalLoader;
import com.pokelocation.app.social.net.Api;
import com.pokelocation.app.social.ui.chat.ChatActivity;
import com.pokelocation.app.social.ui.chat.ChatListActivity;
import com.pokelocation.app.ui.base.BaseActivity;
import com.pokelocation.app.ui.base.StateView;
import com.pokelocation.app.util.Ui;

import java.util.List;
import java.util.concurrent.Callable;

public final class FriendsActivity extends BaseActivity {
    private static final int MENU_CHATS = 1;
    private StateView state;
    private View busyBar;
    private TextView code;
    private EditText input;
    private LinearLayout incoming, outgoing, friends;
    private String myCode;
    private Io.Task task;
    private SignalProgress signal;
    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!Session.loggedIn(this)) {
            toLogin();
            return;
        }
        setContentView(R.layout.soc_friends);
        setupToolbar(getString(R.string.soc_friends), true);
        applyInsets(findViewById(R.id.soc_friends_root));
        state = findViewById(R.id.soc_friends_state);
        busyBar = findViewById(R.id.soc_friends_busy);
        code = findViewById(R.id.soc_friends_code);
        input = findViewById(R.id.soc_friends_input);
        incoming = findViewById(R.id.soc_friends_incoming);
        outgoing = findViewById(R.id.soc_friends_outgoing);
        friends = findViewById(R.id.soc_friends_list);
        findViewById(R.id.soc_friends_copy).setOnClickListener(v -> { if (myCode != null) Ui.copy(this, pretty(myCode), true); });
        findViewById(R.id.soc_friends_share).setOnClickListener(v -> {
            if (myCode != null) Ui.share(this, getString(R.string.soc_share_code, pretty(myCode)));
        });
        findViewById(R.id.soc_friends_add).setOnClickListener(v -> add());
        input.setOnEditorActionListener((v, id, ev) -> {
            if (id != EditorInfo.IME_ACTION_DONE) return false;
            add();
            return true;
        });
        state.setOnRetry(() -> {
            state.setState(StateView.LOADING);
            load();
        });
        load();
        signal = new SignalProgress(findViewById(R.id.soc_signal));
        if (!SignalLoader.loaded()) signal.start();   // amigos funcionam sem a lib; o bloco só informa
    }

    @Override
    protected void onDestroy() {
        if (task != null) task.cancel();
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

    private void load() {
        Context app = getApplicationContext();
        boolean needMe = myCode == null;
        busy(true);
        task = Io.run(() -> new Object[]{needMe ? Api.me(app) : null, Api.friends(app)}, new Io.Callback<Object[]>() {
            @Override public void onResult(Object[] r) {
                busy(false);
                if (r[0] != null) myCode = ((Api.Me) r[0]).friendCode;
                show((Api.Friends) r[1]);
            }
            @Override public void onError(Throwable t) {
                busy(false);
                if (Errors.unauthorized(t)) {
                    toLogin();
                    return;
                }
                if (myCode == null) {   // 1ª carga: tela de erro com retry; depois, só um aviso e a lista antiga
                    state.setErrorText(getString(Errors.message(t)));
                    state.setState(StateView.ERROR);
                } else {
                    Ui.toast(FriendsActivity.this, Errors.message(t));
                }
            }
        });
    }

    private void show(Api.Friends f) {
        Context app = getApplicationContext();
        code.setText(pretty(myCode));
        incoming.removeAllViews();
        for (Api.Request r : f.incoming) {
            row(incoming, r.friendCode, 0, R.string.soc_accept, v -> act(() -> { Api.accept(app, r.id); return null; }, 0),
                    R.string.soc_decline, v -> act(() -> { Api.decline(app, r.id); return null; }, 0));
        }
        outgoing.removeAllViews();
        for (Api.Request r : f.outgoing) row(outgoing, r.friendCode, R.string.soc_pending, 0, null, 0, null);
        friends.removeAllViews();
        for (Api.Friend fr : f.accepted) {
            View r = row(friends, fr.friendCode, 0, R.string.soc_remove, v -> confirm(R.string.soc_remove_confirm, R.string.soc_remove, fr,
                    () -> Api.removeFriend(app, fr.userId)),
                    R.string.soc_block, v -> confirm(R.string.soc_block_confirm, R.string.soc_block, fr,
                    () -> Api.block(app, fr.userId)));
            r.setOnClickListener(v -> startActivity(ChatActivity.friend(this, fr.userId, fr.friendCode)));
        }
        visible(R.id.soc_friends_incoming_h, !f.incoming.isEmpty());
        visible(R.id.soc_friends_outgoing_h, !f.outgoing.isEmpty());
        visible(R.id.soc_friends_none, f.accepted.isEmpty());
        state.setState(StateView.CONTENT);
    }

    private View row(LinearLayout parent, String friendCode, int subRes, int aRes, View.OnClickListener a,
                     int bRes, View.OnClickListener b) {
        View v = getLayoutInflater().inflate(R.layout.soc_row, parent, false);
        ((TextView) v.findViewById(R.id.soc_row_title)).setText(pretty(friendCode));
        if (subRes != 0) {
            TextView sub = v.findViewById(R.id.soc_row_sub);
            sub.setText(subRes);
            sub.setVisibility(View.VISIBLE);
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

    private void visible(int id, boolean v) {
        findViewById(id).setVisibility(v ? View.VISIBLE : View.GONE);
    }

    private void add() {
        String c = Api.normalizeCode(input.getText().toString());
        if (c == null) {
            Ui.toast(this, R.string.soc_invalid_code);
            return;
        }
        Context app = getApplicationContext();
        act(() -> { Api.requestFriend(app, c); return null; }, R.string.soc_request_sent);
    }

    private interface IoAction { void run() throws Exception; }

    private void confirm(int msgRes, int okRes, Api.Friend fr, IoAction work) {
        new MaterialAlertDialogBuilder(this)
                .setMessage(getString(msgRes, pretty(fr.friendCode)))
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
                    input.setText("");
                }
                load();
            }
            @Override public void onError(Throwable t) {
                busy(false);
                if (Errors.unauthorized(t)) toLogin();
                else Ui.toast(FriendsActivity.this, Errors.message(t));
            }
        });
    }

    private void busy(boolean b) {
        busy = b;
        busyBar.setVisibility(b ? View.VISIBLE : View.INVISIBLE);
    }

    private void toLogin() {
        startActivity(new Intent(this, LoginActivity.class).putExtra(LoginActivity.EXTRA_RETURN_TO, LoginActivity.RETURN_FRIENDS));
        finish();
    }
}
