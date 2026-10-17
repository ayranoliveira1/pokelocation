package com.pokelocation.app.pogo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class PogoFilterTest {
    @Test public void defaultSendsOnlySort() throws Exception {
        assertEquals("{\"sort\":\"IV\"}", new PogoFilter().toJson().toString());
    }

    @Test public void everyFieldRoundTrips() throws Exception {
        PogoFilter f = new PogoFilter();
        f.pokemonIds.add(133);
        f.speciesForms.add(new int[]{19, 46});
        f.ivMin = 90.5;
        f.ivMax = 100.0;
        f.cpMin = 10;
        f.cpMax = 3000;
        f.levelMin = 20.0;
        f.levelMax = 35.0;
        f.gender = "Female";
        f.sizes.add("XXL");
        f.boosted = true;
        f.sort = "LEVEL";
        f.pvpMode = true;
        f.pvpLeague = "ultra";
        f.pvpTopRank = 5;
        f.pvpIncludePreEvolutions = false;
        f.pvpMinCp = 1400;
        f.shinyOnly = true;
        f.shinyAccount = "521284665027";
        f.backgroundOnly = true;
        f.numResults = 500;
        JSONObject j = f.toJson();
        assertEquals("[{\"pokemon_id\":19,\"form_id\":46}]", j.getJSONArray("species_forms").toString());
        String stored = j.put("num_results", f.numResults).toString();
        PogoFilter g = PogoFilter.fromJson(new JSONObject(stored));
        assertEquals(stored, g.toJson().put("num_results", g.numResults).toString());
    }

    @Test public void unknownEnumsFallBackToDefault() throws Exception {
        PogoFilter f = PogoFilter.fromJson(new JSONObject(
                "{\"sort\":\"DISTANCE\",\"gender\":\"male\",\"pvp_league\":\"master\",\"sizes\":[\"XXXL\",\"XS\"],"
                        + "\"num_results\":300,\"boosted\":\"yes\",\"shiny_account\":null,\"iv_min\":\"90\"}"));
        assertEquals("IV", f.sort);
        assertNull(f.gender);
        assertEquals("great", f.pvpLeague);
        assertEquals(1, f.sizes.size());
        assertEquals(100, f.numResults);
        assertNull(f.boosted);
        assertNull(f.shinyAccount);
        assertNull(f.ivMin);
    }

    @Test public void kmIsStoredButNeverSentToTheApi() throws Exception {
        PogoFilter f = new PogoFilter();
        f.km = 0;
        assertEquals(0, PogoFilter.fromJson(f.stored()).km);
        f.km = 5;
        assertEquals(5, PogoFilter.fromJson(f.stored()).km);
        assertEquals(0, PogoFilter.fromJson(new JSONObject("{\"km\":3}")).km);
        assertEquals(0, PogoFilter.fromJson(new JSONObject("{}")).km);
        assertEquals(false, f.toJson().has("km"));
    }

    @Test public void copyIsIndependentAndSetKeepsTheObject() {
        PogoFilter f = new PogoFilter();
        f.pokemonIds.add(25);
        f.sizes.add("XL");
        f.ivMin = 90.0;
        f.km = 10;
        PogoFilter c = f.copy();
        c.pokemonIds.add(26);
        c.ivMin = 50.0;
        assertEquals(1, f.pokemonIds.size());
        assertEquals(90.0, f.ivMin, 0);
        assertEquals(10, c.km);
        f.set(c);
        assertEquals(2, f.pokemonIds.size());
        assertEquals(50.0, f.ivMin, 0);
        assertEquals("[XL]", f.sizes.toString());
        f.set(new PogoFilter());
        assertEquals(0, f.pokemonIds.size());
        assertNull(f.ivMin);
        assertEquals(0, f.km);
    }

    @Test public void loadKeepsTheNewestWriter() {
        assertEquals("ov", PogoFilter.newest("main", 10, "ov", 20));
        assertEquals("main", PogoFilter.newest("main", 30, "ov", 20));
        assertEquals("main", PogoFilter.newest("main", 20, "ov", 20));
        assertEquals("main", PogoFilter.newest("main", 0, null, 0));
        assertEquals("ov", PogoFilter.newest(null, 0, "ov", 5));
        assertEquals("ov", PogoFilter.newest("old", 0, "ov", 1));
        assertNull(PogoFilter.newest(null, 0, null, 0));
    }

    @Test public void labelsTravelInStorageButNotToTheApi() throws Exception {
        PogoFilter f = new PogoFilter();
        f.pokemonIds.add(25);
        f.labels.put(PogoFilter.labelKey(25, -1), "Pikachu");
        assertEquals("Pikachu", f.copy().labels.get("25|-1"));
        assertEquals(false, f.toJson().has("labels"));
        f.ivMin = 90.0;
        f.sizes.add("XL");
        f.km = 0;
        f.sort = "CP";
        assertEquals(3, f.activeCount());
    }

    @Test public void worldIsTheDefaultScope() throws Exception {
        assertEquals(0, new PogoFilter().km);
        assertEquals(0, new PogoFilter().activeCount());
        // pref antiga: o 1 km era o padrão, sem escolha
        assertEquals(0, PogoFilter.fromJson(new JSONObject("{\"sort\":\"IV\",\"km\":1}")).km);
        // escolha explícita de Perto continua Perto
        assertEquals(5, PogoFilter.fromJson(new JSONObject("{\"km\":5}")).km);
        PogoFilter near = new PogoFilter();
        near.km = 1;
        near.kmSet = true;
        assertEquals(1, PogoFilter.fromJson(near.stored()).km);
        assertEquals(1, near.activeCount());
        assertEquals(1, near.copy().km);
    }

    private static PogoFilter.Saved saved(String name, double ivMin) {
        PogoFilter f = new PogoFilter();
        f.ivMin = ivMin;
        f.km = 2;
        f.labels.put("133|-1", "Eevee");
        return new PogoFilter.Saved(name, f);
    }

    @Test public void savedListRoundTripsInOrder() {
        List<PogoFilter.Saved> l = new ArrayList<>();
        l.add(saved("Raros", 90));
        l.add(saved("Cem", 100));
        List<PogoFilter.Saved> back = PogoFilter.savedFrom(PogoFilter.savedJson(l));
        assertEquals(2, back.size());
        assertEquals("Raros", back.get(0).name);
        assertEquals(Double.valueOf(90), back.get(0).filter.ivMin);
        assertEquals(2, back.get(0).filter.km);
        assertEquals("Eevee", back.get(0).filter.labels.get("133|-1"));
        assertEquals("Cem", back.get(1).name);
    }

    @Test public void savedToleratesBadData() {
        assertTrue(PogoFilter.savedFrom(null).isEmpty());
        assertTrue(PogoFilter.savedFrom("not json").isEmpty());
        List<PogoFilter.Saved> l = PogoFilter.savedFrom("[{\"name\":\"\"},{\"name\":\"Ok\",\"filter\":{\"sort\":\"CP\"}},7]");
        assertEquals(1, l.size());
        assertEquals("CP", l.get(0).filter.sort);
    }

    @Test public void nameIsTakenIgnoringCaseAndEdgeSpaces() {
        List<PogoFilter.Saved> l = new ArrayList<>();
        l.add(saved("Raros", 90));
        assertTrue(PogoFilter.taken(l, "raros"));
        assertTrue(PogoFilter.taken(l, "  RAROS "));
        assertFalse(PogoFilter.taken(l, "Raros 2"));
        assertFalse(PogoFilter.taken(l, "Ra ros"));
    }

    @Test public void savedHasACap() {
        List<PogoFilter.Saved> l = new ArrayList<>();
        for (int i = 0; i < PogoFilter.MAX_SAVED; i++) l.add(saved("f" + i, i));
        assertEquals(20, PogoFilter.MAX_SAVED);
        assertTrue(PogoFilter.savedFrom(PogoFilter.savedJson(l)).size() == PogoFilter.MAX_SAVED);
        assertFalse(PogoFilter.canSave(l));
        l.remove(0);
        assertTrue(PogoFilter.canSave(l));
    }

    @Test public void sameIgnoresLabelsOnly() {
        PogoFilter a = saved("x", 90).filter, b = a.copy();
        b.labels.clear();
        assertTrue(a.same(b));
        b.km = 0;
        assertFalse(a.same(b));
    }
}
