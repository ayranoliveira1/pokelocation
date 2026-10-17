package com.pokelocation.app.pogo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class PogoIconsTest {
    private static final String PM = PogoIcons.PM, U = PogoIcons.UICONS;

    @Test public void normal() {
        assertEquals(Arrays.asList(PM + "pm25.icon.png", U + "25.png"), PogoIcons.urls(25, "Pikachu", null, null, false));
        assertEquals(Arrays.asList(PM + "pm25.icon.png", U + "25.png"), PogoIcons.urls(25, "Pikachu", "Normal", null, false));
    }

    @Test public void formLabelBecomesSuffix() {
        assertEquals(PM + "pm19.fALOLA.icon.png", PogoIcons.urls(19, "Rattata", "Alola", null, false).get(0));
        assertEquals(PM + "pm25.fCOPY_2019.icon.png", PogoIcons.urls(25, "Pikachu", "Copy 2019", null, false).get(0));
    }

    @Test public void unownBurmyWormadamUseSpeciesName() {
        assertEquals(PM + "pm201.fUNOWN_A.icon.png", PogoIcons.urls(201, "Unown", "A", null, false).get(0));
        assertEquals(PM + "pm412.fBURMY_PLANT.icon.png", PogoIcons.urls(412, "Burmy", "Plant", null, false).get(0));
    }

    @Test public void kyuremGenesectWithoutFormAreNormal() {
        assertEquals(PM + "pm646.fNORMAL.icon.png", PogoIcons.urls(646, "Kyurem", null, null, false).get(0));
        assertEquals(PM + "pm649.fNORMAL.s.icon.png", PogoIcons.urls(649, "Genesect", "Form Unset", null, true).get(0));
    }

    @Test public void scatterbugAndSUltraIgnoreForm() {
        assertEquals(PM + "pm664.icon.png", PogoIcons.urls(664, "Scatterbug", "Archipelago", null, false).get(0));
        assertEquals(PM + "pm800.icon.png", PogoIcons.urls(800, "Necrozma", "Ultra", null, false).get(0));
    }

    @Test public void costumeDropsNoEvolveAndShinyComesFirst() {
        List<String> l = PogoIcons.urls(25, "Pikachu", null, "HALLOWEEN_2017_NOEVOLVE", true);
        assertEquals(Arrays.asList(PM + "pm25.cHALLOWEEN_2017.s.icon.png", PM + "pm25.cHALLOWEEN_2017.icon.png",
                PM + "pm25.s.icon.png", PM + "pm25.icon.png", U + "25.png"), l);
        assertEquals(Arrays.asList(PM + "pm19.fALOLA.s.icon.png", PM + "pm19.fALOLA.icon.png", U + "19.png"),
                PogoIcons.urls(19, "Rattata", "Alola", null, true));
    }

    @Test public void sampleKeepsAtLeastTheTarget() {
        assertEquals(2, PogoIcons.sample(256, 256, 96));
        assertEquals(1, PogoIcons.sample(128, 128, 96));
        assertEquals(2, PogoIcons.sample(256, 256, 128));
        assertEquals(1, PogoIcons.sample(256, 256, 129));
        assertEquals(4, PogoIcons.sample(256, 256, 64));
        assertEquals(1, PogoIcons.sample(128, 128, 128));
        assertEquals(2, PogoIcons.sample(300, 256, 128));
        assertEquals(1, PogoIcons.sample(256, 256, 0));
    }

    @Test public void diskNamesDoNotCollide() {
        assertEquals("pm25.icon.png", PogoIcons.fileName(PM + "pm25.icon.png"));
        assertEquals("u25.png", PogoIcons.fileName(U + "25.png"));
    }

    @Test public void zeroMeansNoFormNorCostume() {
        assertEquals(Arrays.asList(PM + "pm25.icon.png", U + "25.png"), PogoIcons.urls(25, "Pikachu", "0", "0", false));
        assertEquals(Arrays.asList(PM + "pm19.icon.png", U + "19.png"), PogoIcons.urls(19, "Rattata", "46", "12", false));
        assertEquals(PM + "pm646.fNORMAL.icon.png", PogoIcons.urls(646, "Kyurem", "0", null, false).get(0));
    }

    /** Caixas opacas medidas em PNGs reais do PokeMiners (256 px): eles já vêm em escala entre as espécies. */
    private static final int[] CATERPIE = {99, 147, 146, 224}, PIKACHU = {100, 124, 193, 228}, DRAGONITE = {65, 10, 215, 190},
            KYOGRE = {2, 76, 243, 169};

    private static float share(int[] box, int[] sq) {
        return (float) Math.max(box[2] - box[0], box[3] - box[1]) / sq[2];
    }

    private static boolean contains(int[] sq, int[] box) {
        return sq[0] <= box[0] && sq[1] <= box[1] && sq[0] + sq[2] >= box[2] && sq[1] + sq[2] >= box[3];
    }

    /** Pequeno segue menor que médio, que segue menor que grande; os médios crescem; nenhum é cortado. */
    @Test public void sameZoomKeepsSpeciesProportional() {
        int[] cat = PogoIcons.square(256, 256, CATERPIE), pika = PogoIcons.square(256, 256, PIKACHU);
        int[] dragon = PogoIcons.square(256, 256, DRAGONITE), kyogre = PogoIcons.square(256, 256, KYOGRE);
        assertEquals(cat[2], pika[2]);                                   // o mesmo zoom: a proporção entre eles fica
        assertTrue(share(CATERPIE, cat) < share(PIKACHU, pika));
        assertTrue(share(PIKACHU, pika) < share(DRAGONITE, dragon));
        assertTrue(share(PIKACHU, pika) > 104f / 256 * 1.4f);              // o médio cresce (era 41% do ícone)
        for (int[][] c : new int[][][]{{CATERPIE, cat}, {PIKACHU, pika}, {DRAGONITE, dragon}, {KYOGRE, kyogre}})
            assertTrue(contains(c[1], c[0]));                             // ninguém cortado
        assertEquals(255, kyogre[2]);                                    // o maior: só a folga, quase sem zoom
        int[] full = PogoIcons.square(128, 128, new int[]{9, 14, 118, 113});   // UICONS: já vem quase cheio
        assertEquals(116, full[2]);
        assertTrue(contains(full, new int[]{9, 14, 118, 113}));
        assertEquals(Arrays.toString(new int[]{0, 0, 96}), Arrays.toString(PogoIcons.square(96, 96, null)));   // tudo transparente
    }

    /** Obs. d da QA: duas chaves no mesmo fallback (u25.png) baixavam juntas; agora uma espera a outra. */
    @Test public void sameFileIsFetchedOneAtATime() throws Exception {
        java.util.concurrent.atomic.AtomicInteger inside = new java.util.concurrent.atomic.AtomicInteger(), most =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        Runnable fetch = () -> {
            try {
                go.await();
                PogoIcons.onePerFile("u25.png", () -> {
                    most.accumulateAndGet(inside.incrementAndGet(), Math::max);
                    try {
                        Thread.sleep(50);   // o download
                    } catch (InterruptedException e) {
                        throw new java.io.IOException(e);
                    }
                    inside.decrementAndGet();
                    return 0;
                });
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
        Thread[] all = {new Thread(fetch), new Thread(fetch), new Thread(fetch)};   // 3: a trava removida deixava 2 juntas
        for (Thread t : all) t.start();
        go.countDown();
        for (Thread t : all) t.join();
        assertEquals(1, most.get());
    }
}
