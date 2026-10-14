package com.pokelocation.app.social;

import static org.junit.Assert.assertEquals;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class SessionTokensTest {
    private static final String SQLITE = "/usr/bin/sqlite3";
    private static final String TABLE = "CREATE TABLE session(id INTEGER PRIMARY KEY CHECK(id = 0), access BLOB NOT NULL,"
            + " refresh BLOB NOT NULL, fcm BLOB, username BLOB);";

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test public void renewalKeepsUsernameAndFcm() throws Exception {
        Assume.assumeTrue(new File(SQLITE).canExecute());
        File db = new File(tmp.getRoot(), "s.db");
        sqlite(db, TABLE + "INSERT INTO session VALUES (0, x'01', x'02', x'0f', x'0e');");
        sqlite(db, bind(Session.TOKENS_UPDATE, "x'11'", "x'12'") + ";");
        assertEquals("11|12|0F|0E", sqlite(db, ".mode list\nSELECT hex(access), hex(refresh), hex(fcm), hex(username) FROM session;").trim());
    }

    @Test public void firstLoginInsertsTheRow() throws Exception {
        Assume.assumeTrue(new File(SQLITE).canExecute());
        File db = new File(tmp.getRoot(), "s.db");
        sqlite(db, TABLE);
        assertEquals("0", sqlite(db, bind(Session.TOKENS_UPDATE, "x'11'", "x'12'") + "; SELECT changes();").trim());
        sqlite(db, bind(Session.TOKENS_INSERT, "x'11'", "x'12'") + ";");
        assertEquals("0|11|12", sqlite(db, ".mode list\nSELECT id, hex(access), hex(refresh) FROM session;").trim());
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
