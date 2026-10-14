package com.pokelocation.app.social.ui.chat;

import android.content.Context;
import android.content.Intent;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public final class ChatPanel implements Chat.Listener {
    private static final int CONVS = 10, MSGS = 20;
    private static ChatPanel open;

    private final Context ctx, app;
    private final Consumer<String> teleport;
    private PanelHost host;
    private TextView title;
    private View back, empty, compose;
    private LinearLayout list;
    private ScrollView scroll;
    private EditText input;
    private Bubble bubbles;
    private final Map<String, View> shown = new HashMap<>();
    private Chat.Conv conv;
    private Io.Task task;
    private boolean pending, resending;
    private final List<Io.Task> acts = new ArrayList<>();

    /** teleport: recebe "lat, lng" e abre o TeleportPanel preenchido. */
    public static void show(Context overlayCtx, Consumer<String> teleport) {
        new ChatPanel(overlayCtx, teleport).open();
    }

    /** INT_CHAT_UNREAD com o painel aberto: sem WS no :overlay, é assim que chega o que o principal gravou. */
    public static void refreshOpen() {
        if (open == null || open.host == null) return;
        if (open.task != null) open.pending = true;
        else open.refresh();
    }

    public static void dismissOpen() {
        if (open != null && open.host != null) open.host.dismiss();
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
        open = this;
        Chat.overlayOpened(app, this);
        showList();
    }

    private void closed() {
        if (open == this) open = null;
        Chat.viewing(app, null);
        Chat.overlayClosed(this);
        if (task != null) task.cancel();
        for (Io.Task t : acts) t.cancel();
        acts.clear();
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
        loadList();
    }

    private void loadList() {
        load(() -> Chat.conversations(app), convs -> {
            list.removeAllViews();
            LayoutInflater inf = LayoutInflater.from(ctx);
            for (int i = 0; i < Math.min(CONVS, convs.size()); i++) {
                Chat.Conv c = convs.get(i);
                View row = inf.inflate(R.layout.soc_chat_row, list, false);
                ConvRow.bind(row, c);
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
        bubbles = new Bubble(ctx, c.group, this::onTap);
        if (!c.group) bubbles.setIdentity(c.title, m -> codeInApp(), m -> resend());
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
            merge(msgs);
            scrollToEndOnLayout();
        });
    }

    /**
     * markRead manda INT_CHAT_UNREAD, que volta aqui: só quando a conversa aberta tem não lidas (gravadas pelo
     * principal, onde o viewing do :overlay não vale); a releitura seguinte vem com 0 e para.
     */
    private void refresh() {
        if (conv == null) {
            loadList();
            return;
        }
        String id = conv.id;
        load(() -> {
            Chat.Conv c = Chat.conversation(app, id);
            if (c != null && c.unread > 0) Chat.markRead(app, id);
            return Chat.page(app, id, Long.MAX_VALUE, Long.MAX_VALUE, MSGS);
        }, msgs -> {
            boolean atEnd = scroll.getScrollY() + scroll.getHeight() >= list.getHeight();
            merge(msgs);
            if (atEnd) scrollToEndOnLayout();
        });
    }

    /**
     * msgs: mais nova primeiro. Remonta na ordem da página reaproveitando as Views já na tela (sem piscar: tudo no
     * mesmo frame); só as da página ficam, então o painel aberto por horas não cresce.
     */
    private void merge(List<Chat.Msg> msgs) {
        Map<String, View> next = new HashMap<>();
        list.removeAllViews();
        for (int i = msgs.size() - 1; i >= 0; i--) {
            Chat.Msg m = msgs.get(i);
            View row = shown.remove(m.clientMsgId);
            if (row == null) row = bubbles.create(list);
            bubbles.bind(row, m);
            list.addView(row);
            next.put(m.clientMsgId, row);
        }
        shown.clear();
        shown.putAll(next);
    }

    private void scrollToEndOnLayout() {
        list.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                v.removeOnLayoutChangeListener(this);
                scroll.scrollTo(0, v.getHeight());
            }
        });
    }

    private void onTap(Chat.Msg m) {
        if (m.from == null && m.state == Chat.FAILED) {
            act(() -> Chat.retry(app, m.id), this::onMessage, null);
        } else if (m.type == Chat.LOC || m.type == Chat.LIVE_START) {
            teleport.accept(CoordParser.text(m.lat, m.lng));
        }
    }

    /** Abre pelo nome: carregar a ChatActivity no :overlay traria o AppCompat junto. */
    private void codeInApp() {
        app.startActivity(new Intent().setClassName(app, ConvRow.CHAT_ACTIVITY)
                .putExtra(ConvRow.EXTRA_CONV, conv.id).putExtra(ConvRow.EXTRA_TITLE, conv.title)
                .putExtra(ConvRow.EXTRA_SAFETY, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        host.dismiss();
    }

    private void resend() {
        if (resending || conv == null) return;
        resending = true;
        String id = conv.id;
        act(() -> Chat.resend(app, id), n -> {
            resending = false;
            refresh();
        }, () -> resending = false);
    }

    private void send() {
        String text = input.getText().toString().trim();
        if (text.isEmpty() || conv == null) return;
        input.setText("");
        typing(false);
        String id = conv.id;
        act(() -> Chat.sendText(app, id, text), this::onMessage, null);
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
        View row = shown.get(m.clientMsgId);
        if (row != null) bubbles.bind(row, m);   // live (até 24/min) e estado: só a bolha, sem reler a página
        else if (task != null) pending = true;
        else refresh();
    }

    @Override
    public void onConversationsChanged() {
        if (host == null) return;
        if (task != null) pending = true;
        else refresh();
    }

    private interface Work<T> { T run() throws Exception; }

    private <T> void load(Work<T> work, Consumer<T> ok) {
        if (task != null) task.cancel();
        task = Io.run(work::run, new Io.Callback<T>() {
            @Override public void onResult(T v) {
                task = null;
                if (host == null) return;
                ok.accept(v);
                if (pending) {
                    pending = false;
                    refresh();
                }
            }
            @Override public void onError(Throwable t) {
                task = null;
                if (host == null) return;
                Ui.toast(app, Errors.message(t));
                if (pending) {
                    pending = false;
                    refresh();
                }
            }
        });
    }

    private <T> void act(Work<T> work, Consumer<T> ok, Runnable failed) {
        Io.Task[] t = new Io.Task[1];
        t[0] = Io.run(work::run, new Io.Callback<T>() {
            @Override public void onResult(T v) {
                acts.remove(t[0]);
                if (host != null && ok != null) ok.accept(v);
            }
            @Override public void onError(Throwable e) {
                acts.remove(t[0]);
                if (host == null) return;
                if (failed != null) failed.run();
                Ui.toast(app, Errors.message(e));
            }
        });
        acts.add(t[0]);
    }
}
