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

/** O SQL real do upgrade v4 → v5 rodando no sqlite3 do sistema (pulado se não houver o binário). */
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
