package com.pokelocation.app.social;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.ContextWrapper;

import com.pokelocation.app.social.store.SocialDb;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.RandomAccessFile;

public class SessionDeadTest {
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
    }

    @Test public void deadSessionMakesNoCallUntilANewLogin() throws Exception {
        Files c = new Files(tmp.getRoot());
        assertTrue(c.getFilesDir().mkdirs() && new File(tmp.getRoot(), "databases").mkdirs());
        assertTrue(c.getDatabasePath(SocialDb.NAME).createNewFile());
        SocialDb.locked(c, () -> null);

        assertTrue(Session.died());
        assertFalse(Session.died());
        assertNull(Session.access(c));

        try (RandomAccessFile f = new RandomAccessFile(new File(c.getFilesDir(), "social.lock"), "rw")) {
            long g = f.length() < 8 ? 0 : f.readLong();
            f.seek(0);
            f.writeLong(g + 1);
        }
        SocialDb.locked(c, () -> null);
        assertTrue(Session.died());
    }
}
