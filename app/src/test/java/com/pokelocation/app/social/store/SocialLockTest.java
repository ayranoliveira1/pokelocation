package com.pokelocation.app.social.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/** Regra multiprocesso (maestro): operação Signal inteira sob files/social.lock, exclusivo entre processos e threads. */
public class SocialLockTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    /** Outro processo (JVM filha) segura o lock: este espera até ele soltar. */
    @Test public void excludesOtherProcess() throws Exception {
        File lock = tmp.newFile("social.lock");
        Process child = new ProcessBuilder(System.getProperty("java.home") + "/bin/java", "-cp",
                System.getProperty("java.class.path"), SocialLockTest.class.getName(), lock.getPath(), "1500")
                .redirectErrorStream(true).start();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(child.getInputStream()))) {
            assertEquals("locked", r.readLine());
            long t0 = System.nanoTime();
            SocialDb.exclusive(lock, () -> null);
            assertTrue((System.nanoTime() - t0) / 1_000_000 >= 1000);
        } finally {
            child.destroy();
        }
    }

    /** JVM filha: segura o lock por argv[1] ms; com argv[1] = "bump", só incrementa a geração (logout lá). */
    public static void main(String[] a) throws Exception {
        SocialDb.exclusive(new File(a[0]), () -> {
            if ("bump".equals(a[1])) {
                SocialDb.bumpGeneration();
                return null;
            }
            System.out.println("locked");
            System.out.flush();
            Thread.sleep(Long.parseLong(a[1]));
            return null;
        });
    }

    /**
     * Geração no próprio social.lock (o inode do social.db pode ser reaproveitado num logout→login rápido): um logout
     * no OUTRO processo (JVM filha) incrementa, e este processo vê o novo valor sob o lock.
     */
    @Test public void generationIsSharedAcrossProcesses() throws Exception {
        File lock = tmp.newFile("social.lock");
        assertEquals(Long.valueOf(0), SocialDb.exclusive(lock, SocialDb::generation));
        SocialDb.exclusive(lock, () -> {
            SocialDb.bumpGeneration();
            return null;
        });
        Process child = new ProcessBuilder(System.getProperty("java.home") + "/bin/java", "-cp",
                System.getProperty("java.class.path"), SocialLockTest.class.getName(), lock.getPath(), "bump")
                .redirectErrorStream(true).start();
        assertEquals(0, child.waitFor());
        assertEquals(Long.valueOf(2), SocialDb.exclusive(lock, SocialDb::generation));
    }

    /** Operação dentro de operação no mesmo thread (ex.: Keys dentro de um decrypt): sem OverlappingFileLockException. */
    @Test public void reentrantInSameThread() throws Exception {
        File lock = tmp.newFile("social.lock");
        assertEquals("in", SocialDb.exclusive(lock, () -> SocialDb.exclusive(lock, () -> "in")));
    }

    /** Threads do mesmo processo também se excluem (o FileLock é por processo). */
    @Test public void serializesThreads() throws Exception {
        File lock = tmp.newFile("social.lock");
        int[] counter = {0};
        List<Thread> ts = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Thread t = new Thread(() -> {
                for (int j = 0; j < 200; j++) {
                    try {
                        SocialDb.exclusive(lock, () -> {
                            int v = counter[0];
                            Thread.yield();
                            counter[0] = v + 1;
                            return null;
                        });
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            });
            ts.add(t);
            t.start();
        }
        for (Thread t : ts) t.join();
        assertEquals(8 * 200, counter[0]);
    }
}
