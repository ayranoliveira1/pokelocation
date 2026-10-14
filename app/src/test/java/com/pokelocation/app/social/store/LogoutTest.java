package com.pokelocation.app.social.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.content.ContextWrapper;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.RandomAccessFile;

/** Deixa o SocialDb estático na geração deste temp: outro teste que abra o banco na mesma JVM começa com logout próprio. */
public class LogoutTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static final class Files extends ContextWrapper {
        private final File root;

        Files(File root) {
            super(null);
            this.root = root;
        }

        @Override public Context getApplicationContext() { return this; }

        @Override public File getFilesDir() { return new File(root, "files"); }

        @Override public File getDatabasePath(String name) { return new File(root, "databases/" + name); }

        @Override public boolean deleteDatabase(String name) { return getDatabasePath(name).delete(); }
    }

    @Test public void logoutDeletesEverythingAndBumpsGeneration() throws Exception {
        Files c = new Files(tmp.getRoot());
        assertTrue(c.getFilesDir().mkdirs() && new File(tmp.getRoot(), "databases").mkdirs());
        File db = c.getDatabasePath(SocialDb.NAME);
        assertTrue(db.createNewFile());
        File lock = new File(c.getFilesDir(), "social.lock");
        long before = generation(lock);

        SocialDb.delete(c);

        assertFalse(db.exists());
        assertEquals(before + 1, generation(lock));
        try {
            SocialDb.atomic(c, () -> null);
            fail("operação depois do logout");
        } catch (IllegalStateException e) {
            assertEquals("logged out", e.getMessage());
        }
        assertFalse(db.exists());
    }

    private static long generation(File lock) throws Exception {
        if (!lock.exists()) return 0;
        try (RandomAccessFile f = new RandomAccessFile(lock, "r")) {
            return f.length() < 8 ? 0 : f.readLong();
        }
    }
}
