package com.pokelocation.app.social.crypto;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.zip.GZIPOutputStream;

public class SignalLoaderTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static final String A = "https://a/x.gz", B = "https://b/x.gz";
    private static final long FREE = Long.MAX_VALUE;
    private File dir;
    private byte[] so, gz;
    private String sha;

    /** Servidor falso: serve gz a partir de from; cut = para no byte cut (conexão caiu); down = URLs fora. */
    private final class Fake implements SignalLoader.Fetch {
        byte[] body = gz;
        long cut = -1, badStart = -1;
        boolean ignoreRange;
        final List<String> down = new ArrayList<>();
        final List<Long> froms = new ArrayList<>();
        long served;

        @Override
        public SignalLoader.Resp open(String url, long from) throws IOException {
            if (down.contains(url)) throw new IOException("down " + url);
            froms.add(from);
            long start = badStart >= 0 ? badStart : ignoreRange ? 0 : from;
            long end = cut >= 0 ? cut : body.length;
            cut = -1; // só a 1ª conexão cai
            InputStream in = new ByteArrayInputStream(body, (int) start, (int) (end - start)) {
                @Override
                public synchronized int read(byte[] b, int off, int len) {
                    int n = super.read(b, off, len);
                    if (n > 0) served += n;
                    return n;
                }
            };
            return new SignalLoader.Resp(in, start, body.length);
        }
    }

    @Before
    public void setUp() throws Exception {
        dir = tmp.newFolder("libsignal");
        so = new byte[200_000];
        Random r = new Random(1);
        for (int i = 0; i < so.length; i++) so[i] = (byte) r.nextInt(16); // comprimível: .gz < .so, como o real
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (GZIPOutputStream z = new GZIPOutputStream(bo)) {
            z.write(so);
        }
        gz = bo.toByteArray();
        sha = hex(MessageDigest.getInstance("SHA-256").digest(so));
    }

    private File fetch(Fake f, long free, String... urls) throws IOException {
        return SignalLoader.fetch(dir, sha, so.length, free, urls, f, null);
    }

    private void assertInstalled(File f) throws IOException {
        assertArrayEquals(so, Files.readAllBytes(f.toPath()));
        assertFalse(f.canWrite());
        assertTrue(SignalLoader.verified(f, sha));
        assertFalse(new File(dir, SignalLoader.PART).exists());
    }

    @Test public void downloadsVerifiesAndInstallsReadOnly() throws IOException {
        assertInstalled(fetch(new Fake(), FREE, A));
    }

    @Test public void resumesWithRangeAfterCut() throws IOException {
        Fake f = new Fake();
        f.cut = gz.length / 2;
        try {
            fetch(f, FREE, A);
            fail();
        } catch (IOException expected) {
        }
        assertEquals(gz.length / 2, new File(dir, SignalLoader.PART).length());
        assertInstalled(fetch(f, FREE, A));
        assertEquals(Long.valueOf(gz.length / 2), f.froms.get(1));
    }

    @Test public void serverIgnoringRangeRestartsFromZero() throws IOException {
        Fake f = new Fake();
        f.cut = gz.length / 3;
        try {
            fetch(f, FREE, A);
            fail();
        } catch (IOException expected) {
        }
        f.ignoreRange = true;
        assertInstalled(fetch(f, FREE, A));
    }

    @Test public void corruptedGzipIsDeletedAndNeverInstalled() {
        Fake f = new Fake();
        f.body = gz.clone();
        f.body[gz.length / 2] ^= 0x55;
        assertRejected(f);
    }

    @Test public void wrongHashIsDeletedAndNeverInstalled() throws IOException {
        Fake f = new Fake();
        so[0] ^= 1;
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (GZIPOutputStream z = new GZIPOutputStream(bo)) {
            z.write(so);
        }
        f.body = bo.toByteArray();
        assertRejected(f);
    }

    private void assertRejected(Fake f) {
        try {
            fetch(f, FREE, A);
            fail();
        } catch (IOException expected) {
        }
        assertFalse(new File(dir, SignalLoader.SO).exists());
        assertFalse(new File(dir, SignalLoader.PART).exists());
        assertFalse(new File(dir, SignalLoader.TMP).exists());
    }

    /** Corpo sem fim (servidor com bug/comprometido) não enche o disco: .gz > .so é impossível. */
    @Test public void endlessBodyIsCutAtSize() {
        Fake f = new Fake();
        f.body = new byte[so.length * 3];
        assertRejected(f);
        assertTrue(f.served <= so.length + 64 * 1024); // parou no teto (+ 1 buffer), não leu tudo
    }

    /** 206 com início diferente do pedido: .part apagado para a próxima tentativa recomeçar do zero. */
    @Test public void wrongRangeStartDropsPart() throws IOException {
        Fake f = new Fake();
        f.cut = gz.length / 2;
        try {
            fetch(f, FREE, A);
            fail();
        } catch (IOException expected) {
        }
        f.badStart = 1;
        assertRejected(f);
        f.badStart = -1;
        assertInstalled(fetch(f, FREE, A));
    }

    @Test public void noSpaceFailsBeforeDownloading() {
        Fake f = new Fake();
        try {
            fetch(f, so.length, A);
            fail();
        } catch (IOException e) {
            assertEquals(SignalLoader.NO_SPACE, e.getMessage());
        }
        assertTrue(f.froms.isEmpty());
    }

    @Test public void primaryDownUsesFallback() throws IOException {
        Fake f = new Fake();
        f.down.add(A);
        assertInstalled(fetch(f, FREE, A, B));
    }

    @Test public void allDownFails() {
        Fake f = new Fake();
        f.down.add(A);
        f.down.add(B);
        assertRejected(f);
    }

    @Test public void validInstalledFileSkipsNetwork() throws IOException {
        fetch(new Fake(), FREE, A);
        Fake f = new Fake();
        assertInstalled(fetch(f, FREE, A));
        assertTrue(f.froms.isEmpty());
    }

    @Test public void tamperedInstalledFileIsReplaced() throws IOException {
        File so1 = fetch(new Fake(), FREE, A);
        so1.setWritable(true);
        Files.write(so1.toPath(), new byte[]{1, 2, 3});
        assertFalse(SignalLoader.verified(so1, sha));
        assertInstalled(fetch(new Fake(), FREE, A));
    }

    private boolean fetchAndLoad(boolean loggedIn, java.util.function.Consumer<String> loader) throws IOException {
        return SignalLoader.fetchAndLoad(dir, sha, so.length, FREE, new String[]{A}, new Fake(), null, () -> loggedIn, loader);
    }

    /** Baixa em paralelo à autenticação, mas System.load só com login. */
    @Test public void withoutLoginFetchesButDoesNotLoad() throws IOException {
        List<String> loads = new ArrayList<>();
        assertFalse(fetchAndLoad(false, loads::add));
        assertTrue(loads.isEmpty());
        assertInstalled(new File(dir, SignalLoader.SO));
    }

    @Test public void withLoginLoadsVerifiedFile() throws IOException {
        List<String> loads = new ArrayList<>();
        assertTrue(fetchAndLoad(true, loads::add));
        assertEquals(java.util.Collections.singletonList(new File(dir, SignalLoader.SO).getAbsolutePath()), loads);
    }

    @Test public void linkErrorDeletesFileAndIsUnavailable() throws IOException {
        try {
            fetchAndLoad(true, path -> { throw new UnsatisfiedLinkError("abi"); });
            fail();
        } catch (SignalLoader.Unavailable expected) {
        }
        assertFalse(new File(dir, SignalLoader.SO).exists());
    }

    /** Falha de link é determinística: o próximo processo não baixa 3 MB para falhar igual (até mudar o sha). */
    @Test public void linkErrorIsRememberedPerSha() throws IOException {
        try {
            fetchAndLoad(true, path -> { throw new UnsatisfiedLinkError("abi"); });
            fail();
        } catch (SignalLoader.Unavailable expected) {
        }
        Fake f = new Fake();
        try {
            SignalLoader.fetchAndLoad(dir, sha, so.length, FREE, new String[]{A}, f, null, () -> true, path -> {});
            fail();
        } catch (SignalLoader.Unavailable expected) {
        }
        assertTrue(f.froms.isEmpty());
        // outro sha (nova versão da libsignal): o marcador não bloqueia, baixa de novo (aqui falha no hash, não Unavailable)
        Fake g = new Fake();
        try {
            SignalLoader.fetchAndLoad(dir, "0" + sha.substring(1), so.length, FREE, new String[]{A}, g, null, () -> true, path -> {});
            fail();
        } catch (SignalLoader.Unavailable e) {
            fail("marcador vale só para o sha que falhou");
        } catch (IOException expected) {
        }
        assertFalse(g.froms.isEmpty());
    }

    /** Sem conseguir gravar o marcador (disco cheio/permissão) continua Unavailable: nada de virar erro transitório. */
    @Test public void linkErrorStaysUnavailableWhenMarkerFails() throws IOException {
        try {
            fetchAndLoad(true, path -> {
                dir.setWritable(false);
                throw new UnsatisfiedLinkError("abi");
            });
            fail();
        } catch (SignalLoader.Unavailable expected) {
        } finally {
            dir.setWritable(true);
        }
    }

    @Test public void parsePin() {
        String cfg = "arm64-v8a:aa:10,armeabi-v7a:bb:20,x86_64:cc:30";
        assertArrayEquals(new String[]{"armeabi-v7a", "bb", "20"}, SignalLoader.pin(cfg, "armeabi-v7a"));
        assertArrayEquals(new String[]{"x86_64", "cc", "30"}, SignalLoader.pin(cfg, "x86_64"));
        assertEquals(null, SignalLoader.pin(cfg, "mips"));
    }

    /** Processo x86 (32 bits) tem stub no APK mas não tem .so publicado: social indisponível, sem download. Só a ABI
     *  primária do processo conta: nunca cair para armeabi-v7a (houdini) e carregar ARM num processo x86. */
    @Test public void x86HasNoPin() {
        assertEquals(null, SignalLoader.pin("arm64-v8a:aa:10,armeabi-v7a:bb:20,x86_64:cc:30", "x86"));
    }

    /** Ping do FCM com a lib carregando: espera a carga terminar (com teto) e segue para drenar a fila. */
    @Test public void pingWaitsForTheLoadInProgress() throws Exception {
        SignalLoader.await(null, 10);
        Thread quick = new Thread(() -> sleep(50));
        quick.start();
        SignalLoader.await(quick, 5_000);
        assertFalse(quick.isAlive());
        Thread slow = new Thread(() -> sleep(5_000));
        slow.setDaemon(true);
        slow.start();
        try {
            SignalLoader.await(slow, 50);
            fail();
        } catch (IOException expected) {
            assertEquals("downloading", expected.getMessage());
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
