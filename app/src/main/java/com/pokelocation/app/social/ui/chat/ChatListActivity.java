package com.pokelocation.app.social.ui.chat;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputFilter;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.pokelocation.app.Io;
import com.pokelocation.app.R;
import com.pokelocation.app.social.Session;
import com.pokelocation.app.social.chat.Chat;
import com.pokelocation.app.social.net.Api;
import com.pokelocation.app.social.ui.Errors;
import com.pokelocation.app.social.ui.FriendsActivity;
import com.pokelocation.app.social.ui.LoginActivity;
import com.pokelocation.app.ui.base.BaseActivity;
import com.pokelocation.app.ui.base.StateView;
import com.pokelocation.app.util.Ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ChatListActivity extends BaseActivity implements Chat.Listener {
    private static final int MENU_NEW_GROUP = 1;

    private StateView state;
    private final Rows rows = new Rows();
    private Io.Task task, groupTask;
    private boolean listening;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!Session.loggedIn(this)) {
            toLogin();
            return;
        }
        setContentView(R.layout.soc_chat_list);
        setupToolbar(getString(R.string.soc_chat_title), true);
        applyInsets(findViewById(R.id.soc_chat_list_root));
        state = findViewById(R.id.soc_chat_list_state);
        state.setEmptyText(getString(R.string.soc_chat_empty));
        state.setOnRetry(() -> {
            state.setState(StateView.LOADING);
            load();
        });
        RecyclerView rv = findViewById(R.id.soc_chat_list_rv);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(rows);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (state == null) return;
        listening = true;
        Chat.listen(getApplicationContext(), this);
        load();
    }

    @Override
    protected void onStop() {
        if (listening) Chat.unlisten(this);
        listening = false;
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (task != null) task.cancel();
        if (groupTask != null) groupTask.cancel();
        super.onDestroy();
    }

    @Override public void onMessage(Chat.Msg m) {}

    @Override public void onConversationsChanged() { load(); }

    private void load() {
        if (task != null) task.cancel();
        Context app = getApplicationContext();
        task = ChatActivity.read(() -> Chat.conversations(app), new Io.Callback<List<Chat.Conv>>() {
            @Override public void onResult(List<Chat.Conv> l) {
                rows.set(l);
                state.setState(l.isEmpty() ? StateView.EMPTY : StateView.CONTENT);
            }
            @Override public void onError(Throwable t) {
                if (Errors.unauthorized(t)) {
                    toLogin();
                    return;
                }
                state.setErrorText(getString(Errors.message(t)));
                state.setState(StateView.ERROR);
            }
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu m) {
        m.add(0, MENU_NEW_GROUP, 0, R.string.soc_chat_new_group).setIcon(R.drawable.ic_add)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() != MENU_NEW_GROUP) return super.onOptionsItemSelected(item);
        newGroup();
        return true;
    }

    private void newGroup() {
        if (groupTask != null) return;
        Context app = getApplicationContext();
        groupTask = Io.run(() -> Api.friends(app), new Io.Callback<Api.Friends>() {
            @Override public void onResult(Api.Friends f) {
                groupTask = null;
                if (f.accepted.isEmpty()) {
                    Ui.toast(ChatListActivity.this, R.string.soc_chat_no_friends);
                    return;
                }
                pickMembers(f.accepted);
            }
            @Override public void onError(Throwable t) {
                groupTask = null;
                fail(t);
            }
        });
    }

    private void pickMembers(List<Api.Friend> friends) {
        CharSequence[] codes = new CharSequence[friends.size()];
        boolean[] checked = new boolean[codes.length];
        for (int i = 0; i < codes.length; i++) codes[i] = FriendsActivity.label(friends.get(i).username, friends.get(i).friendCode);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.soc_chat_pick_friends)
                .setMultiChoiceItems(codes, checked, (d, which, on) -> checked[which] = on)
                .setPositiveButton(R.string.soc_chat_next, (d, w) -> {
                    List<String> ids = new ArrayList<>();
                    for (int i = 0; i < checked.length; i++) if (checked[i]) ids.add(friends.get(i).userId);
                    if (!ids.isEmpty()) askName(ids);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void askName(List<String> ids) {
        EditText name = new EditText(this);
        name.setHint(R.string.soc_chat_group_name);
        name.setSingleLine(true);
        name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(60)});
        FrameLayout box = new FrameLayout(this);
        int pad = Ui.dp(this, 24);
        box.setPadding(pad, Ui.dp(this, 8), pad, 0);
        box.addView(name);
        Context app = getApplicationContext();
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.soc_chat_new_group)
                .setView(box)
                .setPositiveButton(R.string.soc_chat_create, (d, w) -> {
                    String n = name.getText().toString().trim();
                    if (n.isEmpty() || groupTask != null) return;
                    groupTask = Io.run(() -> Chat.createGroup(app, n, ids), new Io.Callback<String>() {
                        @Override public void onResult(String convId) {
                            groupTask = null;
                            startActivity(ChatActivity.conv(ChatListActivity.this, convId, n, true));
                        }
                        @Override public void onError(Throwable t) {
                            groupTask = null;
                            fail(t);
                        }
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private String preview(Chat.Conv c) {
        switch (c.previewType) {
            case Chat.TEXT: return c.preview == null ? "" : c.preview;
            case Chat.LOC: return getString(R.string.soc_chat_location);
            case Chat.LIVE_START: return getString(R.string.soc_chat_live_location);
            case Chat.LIVE_STOP: return getString(R.string.soc_chat_msg_live_stop);
            case Chat.UNREADABLE: return getString(R.string.soc_chat_unreadable);
            case Chat.IDENTITY_CHANGED: return getString(R.string.soc_chat_identity_changed);
            default: return "";
        }
    }

    private void fail(Throwable t) {
        if (Errors.unauthorized(t)) toLogin();
        else Ui.toast(this, Errors.message(t));
    }

    private void toLogin() {
        startActivity(new Intent(this, LoginActivity.class).putExtra(LoginActivity.EXTRA_RETURN_TO, LoginActivity.RETURN_BACK));
        finish();
    }

    private final class Rows extends RecyclerView.Adapter<Rows.VH> {
        private List<Chat.Conv> items = Collections.emptyList();

        void set(List<Chat.Conv> l) {
            items = l;
            notifyDataSetChanged();
        }

        @Override public int getItemCount() { return items.size(); }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.soc_row, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            Chat.Conv c = items.get(position);
            h.title.setText(c.title);
            h.title.setTypeface(null, c.unread > 0 ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            String preview = preview(c);
            if (c.unread > 0) preview = getString(R.string.soc_chat_unread, c.unread, preview);
            if (ChatActivity.liveUntil(c, System.currentTimeMillis()) > 0) preview = getString(R.string.soc_chat_live) + " · " + preview;
            h.sub.setText(preview);
            h.sub.setVisibility(h.sub.length() > 0 ? View.VISIBLE : View.GONE);
            h.itemView.setOnClickListener(v -> startActivity(ChatActivity.conv(ChatListActivity.this, c.id, c.title, c.group)));
        }

        final class VH extends RecyclerView.ViewHolder {
            final TextView title, sub;

            VH(View v) {
                super(v);
                title = v.findViewById(R.id.soc_row_title);
                sub = v.findViewById(R.id.soc_row_sub);
            }
        }
    }
}
