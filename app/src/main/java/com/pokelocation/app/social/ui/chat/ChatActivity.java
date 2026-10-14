package com.pokelocation.app.social.ui.chat;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.lifecycle.Lifecycle;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.pokelocation.app.Io;
import com.pokelocation.app.R;
import com.pokelocation.app.data.Prefs;
import com.pokelocation.app.ipc.Intents;
import com.pokelocation.app.model.Coord;
import com.pokelocation.app.social.Session;
import com.pokelocation.app.social.chat.Chat;
import com.pokelocation.app.social.net.Api;
import com.pokelocation.app.social.ui.Errors;
import com.pokelocation.app.social.ui.FriendsActivity;
import com.pokelocation.app.social.ui.LoginActivity;
import com.pokelocation.app.ui.base.BaseActivity;
import com.pokelocation.app.ui.base.StateView;
import com.pokelocation.app.ui.main.MainActivity;
import com.pokelocation.app.util.Ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class ChatActivity extends BaseActivity implements Chat.Listener {
    private static final String EXTRA_CONV = "conv", EXTRA_TITLE = "title", EXTRA_GROUP = "group", EXTRA_FRIEND = "friend";
    private static final long[] LIVE_MS = {15 * 60_000L, 60 * 60_000L, 8 * 60 * 60_000L};
    private static final String[] LIVE_LABELS = {"15 min", "1 h", "8 h"};
    private static final int MENU_SAFETY = 1, MENU_MEMBERS = 2;

    private Pager pager = new Pager();
    static final ThreadPoolExecutor READ = new ThreadPoolExecutor(1, 1, 10, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
            r -> new Thread(() -> {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                r.run();
            }, "pl-chat"));

    static {
        READ.allowCoreThreadTimeOut(true);
    }

    private final ArrayList<Io.Task> tasks = new ArrayList<>();
    private StateView state;
    private RecyclerView rv;
    private LinearLayoutManager lm;
    private MsgAdapter adapter;
    private EditText input;
    private View newChip;
    private String convId, friendUserId;
    private boolean group, listening;
    private List<Api.Friend> members = new ArrayList<>();
    private Chat.Conv conv;
    private final Runnable liveTick = this::showLive;

    public static Intent conv(Context c, String convId, String title, boolean group) {
        return new Intent(c, ChatActivity.class).putExtra(EXTRA_CONV, convId).putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_GROUP, group);
    }

    public static Intent friend(Context c, String userId, String label) {
        return new Intent(c, ChatActivity.class).putExtra(EXTRA_FRIEND, userId).putExtra(EXTRA_TITLE, label);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!Session.loggedIn(this)) {
            toLogin();
            return;
        }
        Intent in = getIntent();
        convId = in.getStringExtra(EXTRA_CONV);
        friendUserId = in.getStringExtra(EXTRA_FRIEND);
        group = in.getBooleanExtra(EXTRA_GROUP, false);
        String title = in.getStringExtra(EXTRA_TITLE);
        setContentView(R.layout.soc_chat);
        setupToolbar(title != null ? title : "…", true);
        applyInsets(findViewById(R.id.soc_chat_root));
        state = findViewById(R.id.soc_chat_state);
        rv = findViewById(R.id.soc_chat_rv);
        input = findViewById(R.id.soc_chat_input);
        newChip = findViewById(R.id.soc_chat_new);
        lm = new LinearLayoutManager(this, RecyclerView.VERTICAL, true);
        lm.setStackFromEnd(true);
        rv.setLayoutManager(lm);
        adapter = new MsgAdapter(this, group, this::onTap);
        rv.setAdapter(adapter);
        rv.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView r, int dx, int dy) {
                if (lm.findFirstVisibleItemPosition() <= 0) newChip.setVisibility(View.GONE);
                loadMore();
            }
        });
        newChip.setOnClickListener(v -> {
            newChip.setVisibility(View.GONE);
            rv.scrollToPosition(0);
        });
        findViewById(R.id.soc_chat_send).setOnClickListener(v -> sendText());
        findViewById(R.id.soc_chat_loc).setOnClickListener(v -> locationMenu());
        state.setOnRetry(() -> {
            state.setState(StateView.LOADING);
            start();
        });
        start();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!Session.loggedIn(this)) {   // saiu da conta com esta tela na pilha: some sem mostrar o que ficou em memória
            finish();
            return;
        }
        if (convId != null) listen();
    }

    @Override
    protected void onStop() {
        if (listening) Chat.unlisten(this);
        listening = false;
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        for (Io.Task t : tasks) t.cancel();
        Io.main().removeCallbacks(liveTick);
        super.onDestroy();
    }

    private void start() {
        Context app = getApplicationContext();
        if (convId == null) {
            String label = getIntent().getStringExtra(EXTRA_TITLE);
            read(() -> Chat.openDirect(app, friendUserId, label), id -> {
                convId = id;
                if (getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED)) listen();
                start();
            }, this::loadFailed);
            return;
        }
        loadMore();
        loadMembers();
    }

    private void loadMembers() {
        Context app = getApplicationContext();
        String id = convId;
        read(() -> Chat.members(app, id), m -> {
            members = m;
            Map<String, String> labels = new HashMap<>();
            for (Api.Friend f : m) labels.put(f.userId, f.label);
            adapter.setLabels(labels);
        }, null);
    }

    private void listen() {
        if (listening) return;
        listening = true;
        Chat.listen(getApplicationContext(), this);
        loadConv();
        // Com a tela parada nada chegava aqui: junta as mais recentes (o upsert deduplica pelo client_msg_id).
        if (!pager.items().isEmpty()) {
            Context app = getApplicationContext();
            String id = convId;
            read(() -> Chat.page(app, id, Long.MAX_VALUE, Long.MAX_VALUE, Pager.PAGE), this::merge, null);
        }
    }

    private void loadMore() {
        if (convId == null || !pager.begin(lm.findLastVisibleItemPosition())) return;
        Context app = getApplicationContext();
        String id = convId;
        long ts = pager.beforeTs(), before = pager.beforeId();
        boolean first = pager.items().isEmpty();
        Pager p = pager;
        read(() -> Chat.page(app, id, ts, before, Pager.PAGE), page -> {
            if (p != pager) return;   // o merge recomeçou do fim enquanto este lote carregava
            adapter.submitList(pager.loaded(page));
            if (first) {
                state.setState(StateView.CONTENT);
                markRead();
            }
        }, t -> {
            if (p != pager) return;
            pager.failed();
            if (first) loadFailed(t);
            else Ui.toast(this, Errors.message(t));
        });
    }

    private void loadFailed(Throwable t) {
        if (Errors.unauthorized(t)) {
            toLogin();
            return;
        }
        state.setErrorText(getString(Errors.message(t)));
        state.setState(StateView.ERROR);
    }

    @Override
    public void onMessage(Chat.Msg m) {
        if (convId == null || !convId.equals(m.convId)) return;
        boolean atBottom = lm.findFirstVisibleItemPosition() <= 0;
        boolean isNew = !contains(pager.items(), m.clientMsgId);
        List<Chat.Msg> list = pager.upsert(m);
        if (list == null) return;
        boolean scroll = isNew && (atBottom || m.from == null);
        adapter.submitList(list, () -> {
            // Inserir na posição 0 não move a tela no reverseLayout: rola só se já estava no fim ou se a mensagem é minha.
            if (scroll) rv.scrollToPosition(0);
            else if (isNew) newChip.setVisibility(View.VISIBLE);
        });
        if (isNew && m.from != null) markRead();
    }

    private void merge(List<Chat.Msg> newest) {
        if (newest.size() == Pager.PAGE && !contains(pager.items(), newest.get(Pager.PAGE - 1).clientMsgId)) {
            // Chegaram mais de um lote: entre esse lote e o que já estava na tela haveria um buraco; recomeça do fim.
            pager = new Pager();
            pager.begin(-1);
            adapter.submitList(pager.loaded(newest), () -> rv.scrollToPosition(0));
            newChip.setVisibility(View.GONE);
            return;
        }
        boolean atBottom = lm.findFirstVisibleItemPosition() <= 0, fresh = false;
        List<Chat.Msg> list = null;
        for (Chat.Msg m : newest) {
            boolean isNew = !contains(pager.items(), m.clientMsgId);
            List<Chat.Msg> l = pager.upsert(m);
            if (l == null) continue;
            list = l;
            fresh |= isNew && m.from != null;
        }
        if (list == null) return;
        boolean chip = fresh && !atBottom;
        adapter.submitList(list, () -> { if (chip) newChip.setVisibility(View.VISIBLE); });
        if (fresh) markRead();
    }

    @Override public void onConversationsChanged() { loadConv(); }

    private static boolean contains(List<Chat.Msg> items, String clientMsgId) {
        for (Chat.Msg o : items) if (o.clientMsgId.equals(clientMsgId)) return true;
        return false;
    }

    private void markRead() {
        Context app = getApplicationContext();
        String id = convId;
        read(() -> { Chat.markRead(app, id); return null; }, null, null);
    }

    private void sendText() {
        String text = input.getText().toString().trim();
        if (text.isEmpty() || convId == null) return;
        input.setText("");
        Context app = getApplicationContext();
        String id = convId;
        // Só lança se nem gravou (falha de rede vira FAILED pelo Listener): aí o texto volta para o campo.
        io(() -> Chat.sendText(app, id, text), this::onMessage, t -> {
            if (input.length() == 0) input.setText(text);
            fail(t);
        });
    }

    private void onTap(Chat.Msg m) {
        if (m.from == null && m.state == Chat.FAILED) {
            Context app = getApplicationContext();
            io(() -> Chat.retry(app, m.id), this::onMessage, null);
        } else if (m.type == Chat.LOC || m.type == Chat.LIVE_START) {
            openPin(m.lat, m.lng);
        } else if (m.type == Chat.IDENTITY_CHANGED && !group) {
            showSafety();
        }
    }

    /** Nunca o mapa: em alguns celulares abri-lo durante o jogo bagunça a localização. */
    static String pinAction(boolean overlayRunning) {
        return overlayRunning ? Intents.INT_TELEPORT_UI : Intents.INT_FILL_LOCATION;
    }

    private void openPin(double lat, double lng) {
        String action = pinAction(Intents.isOverlayRunning(this));
        if (Intents.INT_TELEPORT_UI.equals(action)) {
            Intents.sendToOverlay(this, Intents.overlay(this, action).putExtra(Intents.EXTRA_LAT, lat)
                    .putExtra(Intents.EXTRA_LNG, lng).putExtra(Intents.EXTRA_FROM_CHAT, true));
        } else {
            startActivity(Intents.auth(this, new Intent(action).setClass(this, MainActivity.class).putExtra(Intents.EXTRA_LAT, lat)
                    .putExtra(Intents.EXTRA_LNG, lng)));
        }
    }

    private void locationMenu() {
        if (convId == null) return;
        boolean live = conv != null && conv.myLiveUntil > System.currentTimeMillis();
        List<CharSequence> items = new ArrayList<>();
        items.add(getString(R.string.soc_chat_loc_current));
        for (String l : LIVE_LABELS) items.add(getString(R.string.soc_chat_loc_live, l));
        if (live) items.add(getString(R.string.soc_chat_loc_stop));
        Context app = getApplicationContext();
        String id = convId;
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.soc_chat_location)
                .setItems(items.toArray(new CharSequence[0]), (d, which) -> {
                    if (which == 0) {
                        Coord me = Prefs.lastPosition(app);
                        if (me == null) Ui.toast(this, R.string.soc_chat_no_position);
                        else io(() -> Chat.sendLocation(app, id, me.lat, me.lng), this::onMessage, null);
                    } else if (which <= LIVE_MS.length) {
                        long ms = LIVE_MS[which - 1];
                        io(() -> Chat.startLive(app, id, ms), this::onMessage, null);
                    } else {
                        io(() -> { Chat.stopLive(app, id); return null; }, null, null);
                    }
                })
                .show();
    }


    private void loadConv() {
        Context app = getApplicationContext();
        String id = convId;
        read(() -> Chat.conversation(app, id), c -> {
            conv = c;
            if (c != null && c.title != null && getSupportActionBar() != null) getSupportActionBar().setTitle(c.title);
            boolean left = c != null && c.left;
            findViewById(R.id.soc_chat_bar).setVisibility(left ? View.GONE : View.VISIBLE);
            findViewById(R.id.soc_chat_left).setVisibility(left ? View.VISIBLE : View.GONE);
            invalidateOptionsMenu();
            showLive();
        }, null);
    }

    private void showLive() {
        Io.main().removeCallbacks(liveTick);
        long now = System.currentTimeMillis(), until = ConvRow.liveUntil(conv, now);
        if (getSupportActionBar() != null) getSupportActionBar().setSubtitle(until == 0 ? null
                : getString(R.string.soc_chat_live_until, android.text.format.DateFormat.getTimeFormat(this).format(until)));
        if (until > 0) Io.main().postDelayed(liveTick, until - now + 500);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu m) {
        if (group && (conv == null || !conv.left)) m.add(0, MENU_MEMBERS, 0, R.string.soc_chat_members);
        else m.add(0, MENU_SAFETY, 0, R.string.soc_chat_safety);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == MENU_SAFETY) showSafety();
        else if (item.getItemId() == MENU_MEMBERS) showMembers();
        else return super.onOptionsItemSelected(item);
        return true;
    }

    private void showSafety() {
        String peer = friendUserId != null ? friendUserId : members.isEmpty() ? null : members.get(0).userId;
        if (peer == null) return;
        Context app = getApplicationContext();
        io(() -> Chat.safetyNumber(app, peer), n -> {
            TextView code = new TextView(this);
            code.setText(blocks(n, 3));
            code.setTypeface(Typeface.MONOSPACE);
            code.setTextSize(20);
            code.setGravity(Gravity.CENTER);
            code.setTextIsSelectable(true);
            int pad = Ui.dp(this, 24);
            code.setPadding(pad, pad / 2, pad, 0);
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.soc_chat_safety)
                    .setMessage(R.string.soc_chat_safety_hint)
                    .setView(code)
                    .setPositiveButton(R.string.ok, null)
                    .setNeutralButton(R.string.copy, (d, w) -> Ui.copy(this, n, true))
                    .show();
        }, null);
    }

    static String blocks(String digits, int perLine) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < digits.length(); i += 5) {
            if (i > 0) sb.append(i / 5 % perLine == 0 ? '\n' : ' ');
            sb.append(digits, i, Math.min(i + 5, digits.length()));
        }
        return sb.toString();
    }

    private void showMembers() {
        Context app = getApplicationContext();
        String id = convId;
        CharSequence[] codes = new CharSequence[members.size()];
        for (int i = 0; i < codes.length; i++) codes[i] = members.get(i).label;
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.soc_chat_members)
                .setItems(codes, (d, which) -> {
                    Api.Friend f = members.get(which);
                    confirm(getString(R.string.soc_chat_remove_member_confirm, codes[which]), R.string.soc_remove,
                            () -> io(() -> { Chat.removeMember(app, id, f.userId); return null; }, v -> loadMembers(), null));
                })
                .setPositiveButton(R.string.soc_chat_add_member, (d, w) -> addMember())
                .setNeutralButton(R.string.soc_chat_leave, (d, w) -> confirm(getString(R.string.soc_chat_leave_confirm),
                        R.string.soc_chat_leave, () -> io(() -> { Chat.leaveGroup(app, id); return null; }, v -> finish(), null)))
                .show();
    }

    private void addMember() {
        Context app = getApplicationContext();
        String id = convId;
        io(() -> Api.friends(app), f -> {
            List<Api.Friend> out = new ArrayList<>();
            for (Api.Friend fr : f.accepted) {
                boolean in = false;
                for (Api.Friend m : members) if (m.userId.equals(fr.userId)) in = true;
                if (!in) out.add(fr);
            }
            if (out.isEmpty()) {
                Ui.toast(this, R.string.soc_chat_no_friends);
                return;
            }
            CharSequence[] codes = new CharSequence[out.size()];
            for (int i = 0; i < codes.length; i++) codes[i] = FriendsActivity.label(out.get(i).username, out.get(i).friendCode);
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.soc_chat_add_member)
                    .setItems(codes, (d, which) -> io(() -> {
                        Chat.addMember(app, id, out.get(which).userId);
                        return null;
                    }, v -> loadMembers(), null))
                    .show();
        }, null);
    }

    private void confirm(String msg, int okRes, Runnable ok) {
        new MaterialAlertDialogBuilder(this)
                .setMessage(msg)
                .setPositiveButton(okRes, (d, w) -> ok.run())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private <T> void io(Callable<T> work, Consumer<T> ok, Consumer<Throwable> err) {
        go(false, work, ok, err);
    }

    /** Na READ: a página não espera um envio ou uma chamada de rede no Io. */
    private <T> void read(Callable<T> work, Consumer<T> ok, Consumer<Throwable> err) {
        go(true, work, ok, err);
    }

    private <T> void go(boolean db, Callable<T> work, Consumer<T> ok, Consumer<Throwable> err) {
        Io.Task[] t = new Io.Task[1];
        Io.Callback<T> cb = new Io.Callback<T>() {
            @Override public void onResult(T v) {
                tasks.remove(t[0]);
                if (ok != null) ok.accept(v);
            }
            @Override public void onError(Throwable e) {
                tasks.remove(t[0]);
                if (err != null) err.accept(e);
                else fail(e);
            }
        };
        t[0] = db ? read(work, cb) : Io.run(work, cb);
        tasks.add(t[0]);
    }

    static <T> Io.Task read(Callable<T> work, Io.Callback<T> cb) {
        Io.Task t = new Io.Task();
        READ.execute(() -> {
            if (t.isCancelled()) return;
            try {
                T v = work.call();
                Io.main().post(() -> { if (!t.isCancelled()) cb.onResult(v); });
            } catch (Throwable e) {
                Log.w("ChatActivity", "read failed", e);
                Io.main().post(() -> { if (!t.isCancelled()) cb.onError(e); });
            }
        });
        return t;
    }

    private void fail(Throwable t) {
        if (Errors.unauthorized(t)) toLogin();
        else Ui.toast(this, Errors.message(t));
    }

    private void toLogin() {
        startActivity(new Intent(this, LoginActivity.class).putExtra(LoginActivity.EXTRA_RETURN_TO, LoginActivity.RETURN_BACK));
        finish();
    }
}
