package com.pokelocation.app.pogo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.json.JSONObject;
import org.junit.Test;

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
        assertEquals(1, PogoFilter.fromJson(new JSONObject("{\"km\":3}")).km);
        assertEquals(1, PogoFilter.fromJson(new JSONObject("{}")).km);
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
        assertEquals(1, f.km);
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
}
