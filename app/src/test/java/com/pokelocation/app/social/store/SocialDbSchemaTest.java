package com.pokelocation.app.social.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class SocialDbSchemaTest {
    private static final String SQLITE = "/usr/bin/sqlite3";

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test public void v5IndexesThePruneAndKeepsTheRows() throws Exception {
        Assume.assumeTrue(new File(SQLITE).canExecute());
        File db = new File(tmp.getRoot(), "social.db");
        sqlite(db, SocialDb.SEEN_ENVELOPES + ";INSERT INTO seen_envelopes VALUES ('u', 'm', 1);");
        assertTrue(prunePlan(db), prunePlan(db).contains("SCAN seen_envelopes"));

        sqlite(db, SocialDb.SEEN_ENVELOPES_AT + ";" + SocialDb.SEEN_ENVELOPES_AT + ";"); // o onUpgrade pode repetir

        String plan = prunePlan(db);
        assertTrue(plan, plan.contains("SEARCH seen_envelopes") && plan.contains("INDEX seen_envelopes_at"));
        assertEquals("1", sqlite(db, "SELECT COUNT(*) FROM seen_envelopes;").trim());
    }

    @Test public void v7PagesByCursorWithTheIndex() throws Exception {
        Assume.assumeTrue(new File(SQLITE).canExecute());
        File db = new File(tmp.getRoot(), "chat.db");
        sqlite(db, String.join(";", SocialDb.CHAT) + ";");
        StringBuilder rows = new StringBuilder();
        for (int i = 1; i <= 7; i++) { // ts repetidos de propósito: o desempate é o id
            rows.append("INSERT INTO messages(conv_id, client_msg_id, sender, type, state, ts, body) VALUES ('c', 'm")
                    .append(i).append("', 'u', 1, 2, ").append(i / 2).append(", x'00');");
        }
        rows.append("INSERT INTO messages(conv_id, client_msg_id, sender, type, state, ts, body) VALUES ('outra', 'z', 'u', 1, 2, 9, x'00');");
        sqlite(db, rows.toString());

        String plan = sqlite(db, "EXPLAIN QUERY PLAN " + bind(SocialDb.PAGE, "'c'", "9", "9", "9", "3") + ";");
        assertTrue(plan, plan.contains("SEARCH messages USING INDEX messages_page") && !plan.contains("TEMP B-TREE"));

        java.util.List<String> seen = new java.util.ArrayList<>();
        long ts = Long.MAX_VALUE, id = Long.MAX_VALUE;
        for (int page = 0; page < 5; page++) {
            String out = sqlite(db, ".mode list\n" + bind("SELECT id, ts, client_msg_id FROM messages WHERE conv_id = ? AND ts <= ?"
                    + " AND (ts < ? OR id < ?) ORDER BY ts DESC, id DESC LIMIT ?", "'c'", "" + ts, "" + ts, "" + id, "3") + ";").trim();
            if (out.isEmpty()) break;
            for (String line : out.split("\n")) {
                String[] f = line.split("\\|");
                seen.add(f[2]);
                ts = Long.parseLong(f[1]);
                id = Long.parseLong(f[0]);
            }
            if (page == 0) { // chega uma nova depois da 1ª página: não pode aparecer nem empurrar as antigas
                sqlite(db, "INSERT INTO messages(conv_id, client_msg_id, sender, type, state, ts, body) VALUES ('c', 'nova', 'u', 1, 2, 50, x'00');");
            }
        }
        assertEquals(java.util.Arrays.asList("m7", "m6", "m5", "m4", "m3", "m2", "m1"), seen);
        assertTrue(sqlite(db, "INSERT OR IGNORE INTO messages(conv_id, client_msg_id, sender, type, state, ts, body)"
                + " VALUES ('c', 'm1', 'u', 1, 2, 0, x'00'); SELECT changes();").trim().endsWith("0"));
    }

    @Test public void v8PendingByGroupAndSender() throws Exception {
        Assume.assumeTrue(new File(SQLITE).canExecute());
        File db = new File(tmp.getRoot(), "v8.db");
        sqlite(db, String.join(";", SocialDb.CHAT) + ";" + String.join(";", SocialDb.CHAT_V8) + ";");
        String plan = sqlite(db, "EXPLAIN QUERY PLAN SELECT sender, msg_id, payload, at FROM pending_group"
                + " WHERE group_id = 'g' AND sender = 'u';");
        assertTrue(plan, plan.contains("SEARCH pending_group USING PRIMARY KEY (sender=?)"));
        String prune = sqlite(db, "EXPLAIN QUERY PLAN DELETE FROM pending_group WHERE at < 5;");
        assertTrue(prune, prune.contains("pending_group_at"));
        sqlite(db, "INSERT INTO messages(conv_id, client_msg_id, sender, type, state, ts, body) VALUES ('c','m','',1,1,1,x'00');");
        assertEquals("0", sqlite(db, ".mode list\nSELECT tries FROM messages;").trim());
    }

    @Test public void v9KeyNotDistributedByDefault() throws Exception {
        Assume.assumeTrue(new File(SQLITE).canExecute());
        File db = new File(tmp.getRoot(), "v9.db");
        sqlite(db, String.join(";", SocialDb.CHAT) + ";" + String.join(";", SocialDb.CHAT_V8) + ";"
                + "INSERT INTO conversations(id, grp) VALUES ('g', 1);");
        sqlite(db, SocialDb.CHAT_V9 + ";");
        assertEquals("0", sqlite(db, ".mode list\nSELECT key_ok FROM conversations;").trim());
    }

    /** Troca os ? em ordem: o sqlite3 da linha de comando não tem parâmetros. */
    private static String bind(String sql, String... values) {
        for (String v : values) sql = sql.replaceFirst("\\?", v);
        return sql;
    }

    private static String prunePlan(File db) throws Exception {
        return sqlite(db, "EXPLAIN QUERY PLAN DELETE FROM seen_envelopes WHERE at < 5;");
    }

    private static String sqlite(File db, String sql) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(SQLITE, "-bail", db.getPath()).redirectErrorStream(true).start();
        try (OutputStream in = p.getOutputStream()) {
            in.write(sql.getBytes(StandardCharsets.UTF_8));
        }
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(out, 0, p.waitFor());
        return out;
    }
}
