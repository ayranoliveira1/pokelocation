package com.pokelocation.app.social.chat;

import static org.junit.Assert.assertEquals;

import com.pokelocation.app.social.store.SocialDb;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** O SQL do reenvio no sqlite3 real: quem pede só pede as próprias ilegíveis; quem reenvia só reenvia o que é dele. */
public class ResendSqlTest {
    private static final String SQLITE = "/usr/bin/sqlite3";
    private static final long NOW = 100L * 24 * 3600_000L, DAY = 24 * 3600_000L;

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private File db() throws Exception {
        Assume.assumeTrue(new File(SQLITE).canExecute());
        File f = new File(tmp.getRoot(), "c.db");
        sqlite(f, String.join(";", SocialDb.CHAT) + ";" + String.join(";", SocialDb.CHAT_V8) + ";" + SocialDb.CHAT_V9 + ";"
                + SocialDb.CHAT_V10 + ";" + String.join(";", SocialDb.CHAT_V11) + ";");
        return f;
    }

    private static String row(String conv, String cmid, String sender, int type, long ts) {
        return "INSERT INTO messages(conv_id, client_msg_id, sender, type, state, ts, body) VALUES ('" + conv + "', '" + cmid
                + "', '" + sender + "', " + type + ", 2, " + ts + ", x'00');";
    }

    @Test public void requesterAsksOnlyForItsUnreadableFromThatPeerInTheLastDay() throws Exception {
        File f = db();
        sqlite(f, row("d:A", "a1", "A", Chat.UNREADABLE, NOW - 10) + row("d:A", "a2", "A", Chat.UNREADABLE, NOW - 20)
                + row("d:A", "old", "A", Chat.UNREADABLE, NOW - 2 * DAY) + row("d:A", "ok", "A", Chat.TEXT, NOW - 5)
                + row("d:C", "c1", "C", Chat.UNREADABLE, NOW - 5) + row("g", "g1", "A", Chat.UNREADABLE, NOW - 5));
        assertEquals("a1\na2", sqlite(f, ".mode list\n" + bind(ChatDb.UNREADABLE_IDS, "'d:A'", "'A'", "" + (NOW - DAY)) + ";").trim());
    }

    @Test public void resenderOnlyResendsItsOwnDirectMessagesInTheWindow() throws Exception {
        File f = db();
        sqlite(f, row("d:B", "m1", "", Chat.TEXT, NOW - 10) + row("d:B", "m2", "", Chat.TEXT, NOW - 2 * DAY)
                + row("g", "m3", "", Chat.TEXT, NOW - 10) + row("d:B", "x", "B", Chat.TEXT, NOW - 10)
                + row("d:B", "n", "", Chat.UNREADABLE, NOW - 10));
        String since = "" + (NOW - DAY);
        assertEquals("1", count(f, bind(ChatDb.RESENDABLE, "'m1'", "'d:B'", since)));
        for (String id : new String[]{"m2", "m3", "x", "n"}) assertEquals(id, "0", count(f, bind(ChatDb.RESENDABLE, "'" + id + "'", "'d:B'", since)));
    }

    @Test public void replaceOnlyTouchesThatSendersUnreadableOnce() throws Exception {
        File f = db();
        sqlite(f, row("d:A", "a1", "A", Chat.UNREADABLE, NOW) + row("d:A", "t", "A", Chat.TEXT, NOW)
                + row("d:A", "mine", "", Chat.UNREADABLE, NOW) + row("d:C", "c1", "C", Chat.UNREADABLE, NOW));
        assertEquals("1", changes(f, bind(ChatDb.REPLACE, "1", "5", "0", "x'01'", "'d:A'", "'A'", "'a1'")));
        assertEquals("0", changes(f, bind(ChatDb.REPLACE, "1", "5", "0", "x'01'", "'d:A'", "'A'", "'a1'")));
        assertEquals("0", changes(f, bind(ChatDb.REPLACE, "1", "5", "0", "x'01'", "'d:A'", "'A'", "'t'")));
        assertEquals("0", changes(f, bind(ChatDb.REPLACE, "1", "5", "0", "x'01'", "'d:A'", "'A'", "'mine'")));
        assertEquals("0", changes(f, bind(ChatDb.REPLACE, "1", "5", "0", "x'01'", "'d:A'", "'A'", "'c1'")));
    }

    @Test public void aNewRequestReplacesThePendingOneAndNothingIsTakenWithoutATap() throws Exception {
        File f = db();
        sqlite(f, "INSERT INTO conversations(id, grp) VALUES ('d:B', 0);");
        sqlite(f, bind(ChatDb.REQUEST_SET, "'m1\nm2'", "" + (NOW - 10), "'d:B'") + ";");
        sqlite(f, bind(ChatDb.REQUEST_SET, "'m3'", "" + NOW, "'d:B'") + ";");
        assertEquals("m3", sqlite(f, ".mode list\n" + bind(ChatDb.REQUEST_GET, "'d:B'", "" + (NOW - DAY)) + ";").trim());
        assertEquals("", sqlite(f, ".mode list\n" + bind(ChatDb.REQUEST_GET, "'d:B'", "" + (NOW + 1)) + ";").trim()); // 24 h: expirou
        sqlite(f, bind(ChatDb.REQUEST_CLEAR, "'d:B'") + ";");
        assertEquals("", sqlite(f, ".mode list\n" + bind(ChatDb.REQUEST_GET, "'d:B'", "0") + ";").trim());
    }

    private static String count(File f, String select) throws Exception {
        return sqlite(f, ".mode list\nSELECT COUNT(*) FROM (" + select + ");").trim();
    }

    private static String changes(File f, String update) throws Exception {
        return sqlite(f, update + "; SELECT changes();").trim();
    }

    private static String bind(String sql, String... values) {
        for (String v : values) sql = sql.replaceFirst("\\?", v);
        return sql;
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
