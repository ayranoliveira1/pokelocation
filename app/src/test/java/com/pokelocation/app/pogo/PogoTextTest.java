package com.pokelocation.app.pogo;

import static org.junit.Assert.assertEquals;

import com.pokelocation.app.R;

import org.junit.Test;

public class PogoTextTest {
    private static Pogo.Mon mon() {
        Pogo.Mon m = new Pogo.Mon();
        m.id = 522;
        m.name = "Blitzle";
        m.iv = 50;
        return m;
    }

    @Test
    public void titleHasShinyBackgroundFormAndCostumeWithoutNoEvolve() {
        Pogo.Mon m = mon();
        assertEquals("Blitzle", PogoText.title(m));
        m.form = "Normal";
        assertEquals("Blitzle", PogoText.title(m));
        m.shiny = true;
        m.background = true;
        m.form = "Alola";
        m.costume = "HALLOWEEN_2017_NOEVOLVE";
        assertEquals("Blitzle ✨ 🏞️ (Alola, Halloween 2017)", PogoText.title(m));
        m.form = null;
        assertEquals("Blitzle ✨ 🏞️ (Halloween 2017)", PogoText.title(m));
    }

    @Test
    public void subIsDexGenderSize() {
        Pogo.Mon m = mon();
        assertEquals("#522", PogoText.sub(m));
        m.gender = "Male";
        m.size = "M";
        assertEquals("#522 · ♂ · M", PogoText.sub(m));
        m.id = 25;
        m.gender = "Genderless";
        assertEquals("#025 · ⚲ · M", PogoText.sub(m));
    }

    @Test
    public void glowFollowsTheSite() {
        Pogo.Mon m = mon();
        assertEquals(0, PogoText.glow(m));
        m.iv = 100;
        assertEquals(0xFFEF2B3D, PogoText.glow(m));
        m.iv = 91;
        assertEquals(0xFFF5A623, PogoText.glow(m));
        m.iv = 0;
        assertEquals(0xFF9CA3AF, PogoText.glow(m));
        m.iv = 40;
        m.size = "XXL";
        assertEquals(0xFF2B7FFF, PogoText.glow(m));
        m.size = "XXS";
        assertEquals(0xFF22C55E, PogoText.glow(m));
        m.iv = -1;
        m.size = null;
        assertEquals(0, PogoText.glow(m));
    }

    @Test
    public void pvpOnlyWithRankAndLeagueEvolutionOnlyWhenDifferent() {
        Pogo.Mon m = mon();
        assertEquals(0, PogoText.pvpRes(m));
        m.pvpRank = 3;
        assertEquals(0, PogoText.pvpRes(m));
        m.pvpLeague = "ultra";
        assertEquals(R.string.pogo_card_pvp_ultra, PogoText.pvpRes(m));
        m.pvpLeague = "great";
        assertEquals(R.string.pogo_card_pvp_great, PogoText.pvpRes(m));
        m.pvpName = "Zebstrika";
        assertEquals("Great #3 · Zebstrika", PogoText.withEvolution("Great #3", m));
        m.pvpName = "Blitzle";
        assertEquals("Great #3", PogoText.withEvolution("Great #3", m));
    }

    @Test
    public void favoriteNameDropsMissingParts() {
        Pogo.Mon m = mon();
        m.iv = 100;
        m.cp = 605;
        assertEquals("Blitzle 100% PC 605", PogoCard.favName(m, "PC 605"));
        m.iv = -1;
        m.cp = 0;
        assertEquals("Blitzle", PogoCard.favName(m, "PC 0"));
    }

    @Test
    public void countSaysWhenFiltersAreOn() {
        PogoFilter f = new PogoFilter();
        f.sort = "CP";
        f.numResults = 500;
        f.pvpLeague = "ultra";   // sem pvp_mode não filtra
        assertEquals(R.string.pogo_map_none, PogoText.countRes(0, f, false));
        assertEquals(R.string.pogo_map_count, PogoText.countRes(4, f, false));
        assertEquals(R.string.pogo_map_none, PogoText.countRes(0, null, false));
        f.ivMin = 90.0;
        assertEquals(R.string.pogo_map_none_filtered, PogoText.countRes(0, f, false));
        assertEquals(R.string.pogo_map_count_filtered, PogoText.countRes(4, f, false));
        assertEquals(R.string.pogo_map_count_world_filtered, PogoText.countRes(4, f, true));
        assertEquals(R.string.pogo_map_none_filtered, PogoText.countRes(0, f, true));
        f.ivMin = null;
        assertEquals(R.string.pogo_map_count_world, PogoText.countRes(4, f, true));
        assertEquals(R.string.pogo_map_none_world, PogoText.countRes(0, f, true));
        f.shinyOnly = true;
        assertEquals(R.string.pogo_map_none_shiny, PogoText.countRes(0, f, false));
    }

    @Test
    public void formFromEnumOrLabel() {
        Pogo.Mon m = mon();
        m.name = "Bulbasaur";
        m.form = "BULBASAUR_NORMAL";
        assertEquals(null, PogoText.form(m));
        m.form = "ALOLA";
        assertEquals("Alola", PogoText.form(m));
        m.form = "Copy 2019";
        assertEquals("Copy 2019", PogoText.form(m));
        m.name = "Raichu";
        m.form = "RAICHU_ALOLA";
        assertEquals("Raichu (Alola)", PogoText.title(m));
        m.form = "FORM_UNSET";
        assertEquals("Raichu", PogoText.title(m));
    }
}
