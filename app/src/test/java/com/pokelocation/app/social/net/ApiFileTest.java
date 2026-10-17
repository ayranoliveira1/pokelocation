package com.pokelocation.app.social.net;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Item 7: o GET binário de /v1/pogo/* (ícones e catálogo) contra respostas falsas do nosso servidor. */
public class ApiFileTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private static final byte[] PNG = "png-bytes".getBytes(StandardCharsets.UTF_8);

    /** O servidor do Malho: 200 com o ETag "v1", 304 quando o If-None-Match bate. */
    private static final class Fake extends HttpURLConnection {
        final int status;
        final byte[] body;
        final String etag;

        Fake(int status, byte[] body, String etag) throws IOException {
            super(new URL("http://127.0.0.1/v1/pogo/icon/pm/pm25.icon.png"));
            this.status = status;
            this.body = body;
            this.etag = etag;
        }

        @Override public int getResponseCode() {
            return etag != null && etag.equals(getRequestProperty("If-None-Match")) ? 304 : status;
        }

        @Override public InputStream getInputStream() throws IOException {
            if (getResponseCode() >= 400) throw new IOException("use getErrorStream");
            return new ByteArrayInputStream(getResponseCode() == 304 ? new byte[0] : body);
        }

        @Override public InputStream getErrorStream() { return getResponseCode() >= 400 ? new ByteArrayInputStream(body) : null; }

        @Override public String getHeaderField(String name) { return "ETag".equals(name) ? etag : null; }

        @Override public void connect() {}

        @Override public void disconnect() {}

        @Override public boolean usingProxy() { return false; }
    }

    private static void write(File f, String s) throws IOException {
        Files.write(f.toPath(), s.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    @Test public void okWritesTheFileAndReturnsTheEtag() throws Exception {
        File out = new File(tmp.getRoot(), "pm25.icon.png");
        assertEquals("\"v1\"", Api.file(new Fake(200, PNG, "\"v1\""), null, out, 1024, 5_000));
        assertArrayEquals(PNG, Files.readAllBytes(out.toPath()));
    }

    @Test public void sameEtagIs304AndKeepsTheFile() throws Exception {
        File out = tmp.newFile("pogo_catalog.json");
        write(out, "old");
        assertNull(Api.file(new Fake(200, PNG, "\"v1\""), "\"v1\"", out, 1024, 5_000));
        assertEquals("old", read(out));
    }

    @Test public void otherEtagDownloadsAgain() throws Exception {
        File out = tmp.newFile("pogo_catalog.json");
        write(out, "old");
        assertEquals("\"v1\"", Api.file(new Fake(200, PNG, "\"v1\""), "\"v0\"", out, 1024, 5_000));
        assertArrayEquals(PNG, Files.readAllBytes(out.toPath()));
    }

    @Test public void withoutEtagIsEmpty() throws Exception {
        assertEquals("", Api.file(new Fake(200, PNG, null), null, new File(tmp.getRoot(), "u25.png"), 1024, 5_000));
    }

    @Test public void notFoundIsAnErrorWithTheEnvelopeAndNoFile() throws Exception {
        File out = new File(tmp.getRoot(), "pm9999.icon.png");
        try {
            Api.file(new Fake(404, "{\"error\":\"not_found\"}".getBytes(StandardCharsets.UTF_8), null), null, out, 1024, 5_000);
            fail();
        } catch (Api.Error e) {
            assertEquals(404, e.http);
            assertEquals("not_found", e.code);
        }
        assertFalse(out.exists());
    }

    @Test public void tooLargeKeepsTheOldFileAndLeavesNoTemp() throws Exception {
        File out = tmp.newFile("pm1.icon.png");
        write(out, "old");
        try {
            Api.file(new Fake(200, PNG, null), null, out, PNG.length - 1, 5_000);
            fail();
        } catch (IOException e) {
            assertEquals("response too large", e.getMessage());
        }
        assertEquals("old", read(out));
        assertEquals(1, tmp.getRoot().listFiles().length);
    }
}
