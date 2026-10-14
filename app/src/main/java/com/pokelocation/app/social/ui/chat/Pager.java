package com.pokelocation.app.social.ui.chat;

import com.pokelocation.app.social.chat.Chat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Mais nova primeiro (0 = embaixo no reverseLayout); toda mudança devolve lista nova para o DiffUtil. */
final class Pager {
    static final int PAGE = 50, PREFETCH = 10;
    static final Comparator<Chat.Msg> NEWEST_FIRST = (a, b) -> a.ts != b.ts ? Long.compare(b.ts, a.ts) : Long.compare(b.id, a.id);

    private ArrayList<Chat.Msg> items = new ArrayList<>();
    // O cursor só anda com as páginas: o que chega pelo Listener não pode empurrá-lo e fazer pular mensagens.
    private long beforeTs = Long.MAX_VALUE, beforeId = Long.MAX_VALUE;
    private boolean loading, end, started;

    /** lastVisible: maior posição visível, que no reverseLayout é o topo. */
    boolean begin(int lastVisible) {
        if (loading || end || (started && lastVisible < items.size() - PREFETCH)) return false;
        loading = started = true;
        return true;
    }

    long beforeTs() { return beforeTs; }
    long beforeId() { return beforeId; }
    boolean end() { return end; }
    boolean loading() { return loading; }
    List<Chat.Msg> items() { return items; }

    List<Chat.Msg> loaded(List<Chat.Msg> page) {
        loading = false;
        if (page.size() < PAGE) end = true;
        if (page.isEmpty()) return items;
        Chat.Msg oldest = page.get(page.size() - 1);
        beforeTs = oldest.ts;
        beforeId = oldest.id;
        ArrayList<Chat.Msg> next = new ArrayList<>(items.size() + page.size());
        next.addAll(items);
        for (Chat.Msg m : page) if (indexOf(next, m.clientMsgId) < 0) next.add(m);
        next.sort(NEWEST_FIRST);
        return items = next;
    }

    void failed() { loading = false; }

    /** null = ignorada: mais antiga que o lote carregado, virá na página dela. */
    List<Chat.Msg> upsert(Chat.Msg m) {
        if (beforeTs != Long.MAX_VALUE && !end && NEWEST_FIRST.compare(m, cursor()) > 0) return null;
        ArrayList<Chat.Msg> next = new ArrayList<>(items);
        int i = indexOf(next, m.clientMsgId);
        if (i >= 0) next.set(i, m);
        else next.add(m);
        next.sort(NEWEST_FIRST);
        return items = next;
    }

    private Chat.Msg cursor() {
        Chat.Msg c = new Chat.Msg();
        c.ts = beforeTs;
        c.id = beforeId;
        return c;
    }

    private static int indexOf(List<Chat.Msg> l, String clientMsgId) {
        for (int i = 0; i < l.size(); i++) if (l.get(i).clientMsgId.equals(clientMsgId)) return i;
        return -1;
    }

    static long stableId(String clientMsgId) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < clientMsgId.length(); i++) {
            h ^= clientMsgId.charAt(i);
            h *= 0x100000001b3L;
        }
        return h;
    }
}
