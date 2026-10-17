package com.pokelocation.app.pogo;

import static org.junit.Assert.assertEquals;

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

    /** Os PNGs do PokeMiners têm margem transparente (o Pikachu ocupa 36% × 40% de 256 px): o recorte quadrado o centraliza. */
    @Test public void squareAroundTheOpaquePartWithAThinMargin() {
        int[] pika = PogoIcons.square(256, 256, new int[]{100, 124, 193, 228});   // medido no pm25.icon.png
        assertEquals(110, pika[2]);                // 104 de altura + 3% de cada lado
        assertEquals(91, pika[0]);                 // centrado em x: (100 + 193) / 2 − 55
        assertEquals(121, pika[1]);                // centrado em y: (124 + 228) / 2 − 55
        int[] full = PogoIcons.square(128, 128, new int[]{9, 14, 118, 113});   // UICONS: já ocupa quase tudo
        assertEquals(116, full[2]);
        assertEquals(5, full[0]);
        assertEquals(5, full[1]);
        int[] edge = PogoIcons.square(256, 256, new int[]{0, 0, 250, 40});     // largo e no canto: preso dentro da imagem
        assertEquals(256, edge[2]);
        assertEquals(0, edge[0]);
        assertEquals(0, edge[1]);
        assertEquals(Arrays.toString(new int[]{0, 0, 96}), Arrays.toString(PogoIcons.square(96, 96, null)));   // tudo transparente: inteira
    }
}
