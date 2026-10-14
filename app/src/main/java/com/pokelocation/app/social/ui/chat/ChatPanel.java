package com.pokelocation.app.social.ui.chat;

import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.pokelocation.app.Io;
import com.pokelocation.app.R;
import com.pokelocation.app.geo.CoordParser;
import com.pokelocation.app.social.chat.Chat;
import com.pokelocation.app.social.net.Api;
import com.pokelocation.app.social.ui.Errors;
import com.pokelocation.app.ui.panel.PanelHost;
import com.pokelocation.app.util.Ui;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public final class ChatPanel implements Chat.Listener {
    private static final int CONVS = 10, MSGS = 20;

    private final Context ctx, app;
    private final Consumer<String> teleport;
    private PanelHost host;
    private TextView title;
    private View back, empty, compose;
    private LinearLayout list;
    private ScrollView scroll;
    private EditText input;
    private MsgAdapter bubbles;
    private final Map<String, MsgAdapter.VH> shown = new HashMap<>();
    private Chat.Conv conv;
    private Io.Task task;

    /** teleport: recebe "lat, lng" e abre o TeleportPanel preenchido. */
    public static void show(Context overlayCtx, Consumer<String> teleport) {
        new ChatPanel(overlayCtx, teleport).open();
    }

    private ChatPanel(Context overlayCtx, Consumer<String> teleport) {
        // O tema do overlay é só framework; as linhas do chat usam atributos do Material.
        ctx = new ContextThemeWrapper(overlayCtx, R.style.Theme_PokeLocation);
        app = overlayCtx.getApplicationContext();
        this.teleport = teleport;
    }

    private void open() {
        View root = LayoutInflater.from(ctx).inflate(R.layout.soc_ov_chat, null);
        title = root.findViewById(R.id.soc_ov_title);
        back = root.findViewById(R.id.soc_ov_back);
        empty = root.findViewById(R.id.soc_ov_empty);
        compose = root.findViewById(R.id.soc_ov_compose);
        list = root.findViewById(R.id.soc_ov_list);
        scroll = root.findViewById(R.id.soc_ov_scroll);
        input = root.findViewById(R.id.soc_ov_input);
        back.setOnClickListener(v -> showList());
        root.findViewById(R.id.soc_ov_close).setOnClickListener(v -> host.dismiss());
        root.findViewById(R.id.soc_ov_send).setOnClickListener(v -> send());
        input.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_UP) typing(true);
            return false;
        });
        host = PanelHost.show(ctx, root, true);
        host.overGame();
        host.setOnDismiss(this::closed);
        Chat.overlayOpened(app, this);
        showList();
    }

    private void closed() {
        Chat.viewing(app, null);
        Chat.overlayClosed(this);
        if (task != null) task.cancel();
        host = null;
        list = null;
        bubbles = null;
        shown.clear();
    }

    private void showList() {
        conv = null;
        shown.clear();
        Chat.viewing(app, null);
        typing(false);
        title.setText(R.string.soc_chat_title);
        back.setVisibility(View.GONE);
        compose.setVisibility(View.GONE);
        load(() -> Chat.conversations(app), convs -> {
            list.removeAllViews();
            LayoutInflater inf = LayoutInflater.from(ctx);
            for (int i = 0; i < Math.min(CONVS, convs.size()); i++) {
                Chat.Conv c = convs.get(i);
                View row = inf.inflate(R.layout.soc_chat_row, list, false);
                ChatListActivity.bindRow(row, c);
                row.setOnClickListener(v -> showConv(c));
                list.addView(row);
            }
            empty.setVisibility(convs.isEmpty() ? View.VISIBLE : View.GONE);
        });
    }

    private void showConv(Chat.Conv c) {
        conv = c;
        Chat.viewing(app, c.id);
        title.setText(c.title != null ? c.title : "…");
        back.setVisibility(View.VISIBLE);
        compose.setVisibility(c.left ? View.GONE : View.VISIBLE);
        empty.setVisibility(View.GONE);
        bubbles = new MsgAdapter(ctx, c.group, this::onTap);
        loadConv();
    }

    private void loadConv() {
        String id = conv.id;
        boolean group = conv.group;
        load(() -> {
            Object[] r = {Chat.page(app, id, Long.MAX_VALUE, Long.MAX_VALUE, MSGS), group ? Chat.members(app, id) : null};
            Chat.markRead(app, id);
            return r;
        }, r -> {
            @SuppressWarnings("unchecked") List<Chat.Msg> msgs = (List<Chat.Msg>) r[0];
            @SuppressWarnings("unchecked") List<Api.Friend> members = (List<Api.Friend>) r[1];
            if (members != null) {
                Map<String, String> labels = new HashMap<>();
                for (Api.Friend f : members) labels.put(f.userId, f.label);
                bubbles.setLabels(labels);
            }
            list.removeAllViews();
            shown.clear();
            for (int i = msgs.size() - 1; i >= 0; i--) {
                MsgAdapter.VH h = bubbles.onCreateViewHolder(list, 0);
                bubbles.bind(h, msgs.get(i));
                list.addView(h.itemView);
                shown.put(msgs.get(i).clientMsgId, h);
            }
            scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    private void onTap(Chat.Msg m) {
        if (m.from == null && m.state == Chat.FAILED) {
            act(() -> Chat.retry(app, m.id));
        } else if (m.type == Chat.LOC || m.type == Chat.LIVE_START) {
            teleport.accept(CoordParser.text(m.lat, m.lng));
        }
    }

    private void send() {
        String text = input.getText().toString().trim();
        if (text.isEmpty() || conv == null) return;
        input.setText("");
        typing(false);
        String id = conv.id;
        act(() -> Chat.sendText(app, id, text));
    }

    /** Só focável enquanto digita: fora disso o jogo fica com o foco e o teclado. */
    private void typing(boolean on) {
        if (host == null) return;
        host.setFocusable(on);
        InputMethodManager imm = (InputMethodManager) ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (on) {
            input.requestFocus();
            input.post(() -> imm.showSoftInput(input, 0));
        } else {
            imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
        }
    }

    @Override
    public void onMessage(Chat.Msg m) {
        if (conv == null || !conv.id.equals(m.convId)) return;
        MsgAdapter.VH h = shown.get(m.clientMsgId);
        if (h != null) bubbles.bind(h, m);   // live (até 24/min) e estado: só a bolha, sem reler a página
        else loadConv();
    }

    @Override
    public void onConversationsChanged() {
        if (conv == null && host != null) showList();
    }

    private interface Work<T> { T run() throws Exception; }

    private <T> void load(Work<T> work, Consumer<T> ok) {
        if (task != null) task.cancel();
        task = Io.run(work::run, new Io.Callback<T>() {
            @Override public void onResult(T v) {
                task = null;
                if (host != null) ok.accept(v);
            }
            @Override public void onError(Throwable t) {
                task = null;
                if (host != null) Ui.toast(app, Errors.message(t));
            }
        });
    }

    private void act(Work<Chat.Msg> work) {
        Io.run(work::run, new Io.Callback<Chat.Msg>() {
            @Override public void onResult(Chat.Msg m) { if (host != null) onMessage(m); }
            @Override public void onError(Throwable t) { if (host != null) Ui.toast(app, Errors.message(t)); }
        });
    }
}
