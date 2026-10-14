package com.pokelocation.app.social.ui.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.pokelocation.app.social.chat.Chat;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class PagerTest {
    /** Banco falso com a mesma consulta keyset do Chat.page: (ts, id) < cursor, ts DESC, id DESC. */
    private final List<Chat.Msg> db = new ArrayList<>();
    private long nextId = 1;

    private Chat.Msg insert(long ts) {
        Chat.Msg m = new Chat.Msg();
        m.id = nextId++;
        m.ts = ts;
        m.clientMsgId = "c" + m.id;
        db.add(m);
        return m;
    }

    private List<Chat.Msg> page(long beforeTs, long beforeId, int limit) {
        List<Chat.Msg> all = new ArrayList<>(db);
        all.sort(Pager.NEWEST_FIRST);
        List<Chat.Msg> out = new ArrayList<>();
        for (Chat.Msg m : all) {
            if (m.ts < beforeTs || (m.ts == beforeTs && m.id < beforeId)) out.add(m);
            if (out.size() == limit) break;
        }
        return out;
    }

    private List<Chat.Msg> loadNext(Pager p) {
        return p.loaded(page(p.beforeTs(), p.beforeId(), Pager.PAGE));
    }

    private static void assertNewestFirstNoDup(List<Chat.Msg> items) {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            assertTrue("duplicada: " + items.get(i).clientMsgId, seen.add(items.get(i).clientMsgId));
            if (i > 0) assertTrue(Pager.NEWEST_FIRST.compare(items.get(i - 1), items.get(i)) < 0);
        }
    }

    @Test
    public void firstPageIs50NewestThenBatchesOf50UntilEnd() {
        for (int i = 0; i < 120; i++) insert(1000 + i);
        Pager p = new Pager();
        assertTrue(p.begin(-1));
        List<Chat.Msg> items = loadNext(p);
        assertEquals(50, items.size());
        assertEquals(1119, items.get(0).ts);
        assertFalse(p.end());

        assertTrue(p.begin(items.size() - 1));
        assertEquals(100, loadNext(p).size());
        assertTrue(p.begin(99));
        items = loadNext(p);
        assertEquals(120, items.size());
        assertTrue(p.end());
        assertFalse(p.begin(119));   // fim do histórico: não dispara de novo
        assertNewestFirstNoDup(items);
    }

    @Test
    public void exactMultipleNeedsOneEmptyPageToEnd() {
        for (int i = 0; i < 50; i++) insert(i);
        Pager p = new Pager();
        p.begin(-1);
        loadNext(p);
        assertFalse(p.end());
        assertTrue(p.begin(49));
        assertEquals(50, loadNext(p).size());
        assertTrue(p.end());
    }

    @Test
    public void prefetchOnlyNearTheTop() {
        for (int i = 0; i < 200; i++) insert(i);
        Pager p = new Pager();
        p.begin(-1);
        loadNext(p);
        assertFalse(p.begin(0));
        assertFalse(p.begin(38));
        assertTrue(p.begin(40));   // faltam 10 para o topo
    }

    @Test
    public void oneBatchAtATime() {
        for (int i = 0; i < 200; i++) insert(i);
        Pager p = new Pager();
        assertTrue(p.begin(-1));
        assertFalse(p.begin(-1));
        loadNext(p);
        assertTrue(p.begin(49));
        assertFalse(p.begin(49));   // ainda carregando
        p.failed();
        assertTrue(p.begin(49));
    }

    @Test
    public void newMessagesInTheMiddleNeverDuplicateNorSkip() {
        for (int i = 0; i < 130; i++) insert(1000 + i);
        Pager p = new Pager();
        p.begin(-1);
        List<Chat.Msg> items = loadNext(p);
        long ts = 5000;
        while (!p.end()) {
            for (int k = 0; k < 7; k++) {   // chegam no banco e pelo Listener entre um lote e outro
                List<Chat.Msg> after = p.upsert(insert(ts++));
                assertEquals(0, after.indexOf(db.get(db.size() - 1)));   // entra embaixo
            }
            assertTrue(p.begin(items.size() - 1));
            items = loadNext(p);
        }
        assertEquals(db.size(), items.size());
        assertNewestFirstNoDup(items);
    }

    @Test
    public void liveMessageOlderThanCursorWaitsForItsPage() {
        for (int i = 0; i < 60; i++) insert(1000 + i);
        Pager p = new Pager();
        p.begin(-1);
        int before = loadNext(p).size();
        Chat.Msg late = insert(1);   // fora de ordem: mais antiga que o lote carregado
        assertNull(p.upsert(late));
        p.begin(before - 1);
        List<Chat.Msg> items = loadNext(p);
        assertEquals(61, items.size());
        assertEquals(late.clientMsgId, items.get(60).clientMsgId);
        assertNewestFirstNoDup(items);
    }

    @Test
    public void liveBeforeFirstPageAndStateChangesReplaceInPlace() {
        for (int i = 0; i < 10; i++) insert(1000 + i);
        Pager p = new Pager();
        p.begin(-1);
        Chat.Msg mine = insert(2000);
        mine.state = Chat.SENDING;
        p.upsert(mine);   // chegou antes da 1ª página terminar e também vem nela
        List<Chat.Msg> items = loadNext(p);
        assertEquals(11, items.size());
        assertNewestFirstNoDup(items);

        Chat.Msg sent = new Chat.Msg();
        sent.id = mine.id;
        sent.ts = mine.ts;
        sent.clientMsgId = mine.clientMsgId;
        sent.state = Chat.SENT;
        items = p.upsert(sent);
        assertEquals(11, items.size());
        assertEquals(Chat.SENT, items.get(0).state);
    }

    @Test
    public void snapshotsAreNewListsForDiffUtil() {
        insert(1);
        Pager p = new Pager();
        p.begin(-1);
        List<Chat.Msg> a = loadNext(p);
        List<Chat.Msg> b = p.upsert(insert(2));
        assertEquals(1, a.size());
        assertEquals(2, b.size());
    }

    @Test
    public void stableIdIsDeterministicPerClientMsgId() {
        assertEquals(Pager.stableId("abc"), Pager.stableId("abc"));
        assertFalse(Pager.stableId("abc") == Pager.stableId("abd"));
    }
}
