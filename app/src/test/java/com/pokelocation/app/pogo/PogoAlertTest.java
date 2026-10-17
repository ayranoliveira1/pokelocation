package com.pokelocation.app.pogo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PogoAlertTest {
    private static final long NOW = 1_700_000_000_000L, MIN = 60_000;

    private static Map<String, String> push(String... kv) {
        Map<String, String> d = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) d.put(kv[i], kv[i + 1]);
        return d;
    }

    private static Map<String, String> spawn(String e, long despawn) {
        return push("t", "p", "n", "Dratini", "rc", "rc" + e, "e", e, "d", String.valueOf(despawn), "r", "Raros");
    }

    @Test public void notificationTextIsTheLocationNeverTheRuleName() {
        assertEquals("Paris, France", PogoAlert.where(push("r", "Raros", "l", "Paris, France", "la", "1", "lo", "2")));
        assertEquals("-23.55052, -46.63331", PogoAlert.where(push("r", "Raros", "la", "-23.550520", "lo", "-46.633308")));
        assertNull(PogoAlert.where(push("r", "Raros", "l", "", "la", "-23.5")));
        assertNull(PogoAlert.where(push("r", "Raros")));
    }

    @Test public void titleLikeTheDesign() {
        long now = 1_000_000;
        assertEquals("✨ Dratini 100% CP512 · 12 min", PogoAlert.title("Dratini", "100", "512", now + 11 * 60_000 + 1, now, true));
        assertEquals("Dratini · 1 min", PogoAlert.title("Dratini", "", null, now + 5_000, now, false));
        assertEquals("? 90%", PogoAlert.title(null, "90", "", now - 1, now, false));
    }

    @Test public void pushBecomesTheMonTheIconNeeds() {
        Map<String, String> d = push("t", "p", "n", "Rattata");
        assertEquals(0, PogoAlert.mon(d).id); // servidor antigo, sem id: sem foto
        d.put("id", "19");
        d.put("f", "Alola");
        d.put("c", "");
        d.put("s", "1");
        Pogo.Mon m = PogoAlert.mon(d);
        assertEquals(19, m.id);
        assertEquals("Rattata", m.name);
        assertEquals("Alola", m.form);
        assertNull(m.costume);
        assertTrue(m.shiny);
        assertEquals(PogoIcons.PM + "pm19.fALOLA.s.icon.png", PogoIcons.urls(m.id, m.name, m.form, m.costume, m.shiny).get(0));
        d.put("id", "x");
        assertEquals(0, PogoAlert.mon(d).id);
        d.put("id", "25");
        d.remove("s");
        assertFalse(PogoAlert.mon(d).shiny);
    }

    @Test public void alertPushTextBecomesMon() {
        Pogo.Mon m = PogoAlert.mon(push("rc", "abc", "n", "Dratini", "id", "147", "iv", "97.8", "cp", "512",
                "d", "1700000000000", "s", "1", "r", "Raros"));
        assertEquals("abc", m.reveal);
        assertEquals(97.8, m.iv, 0);
        assertEquals(512, m.cp);
        assertEquals(1_700_000_000_000L, m.despawnMs);
        assertTrue(m.verified);
        assertTrue(Double.isNaN(m.lat));
        assertEquals("Raros", m.location); // sem lugar nem coordenada: o nome da regra
    }

    @Test public void unknownIsDefaultAndNoIdStillOpens() {
        Pogo.Mon m = PogoAlert.mon(push("rc", "abc", "n", "Dratini", "iv", "", "d", "x", "la", "x"));
        assertEquals("abc", m.reveal);
        assertEquals(0, m.id);
        assertEquals(-1, m.iv, 0);
        assertEquals(0, m.cp);
        assertEquals(0, m.despawnMs);
        assertFalse(m.shiny);
        assertTrue(Double.isNaN(m.lat));
    }

    @Test public void optionalFieldsOfTheNewServer() {
        Pogo.Mon near = PogoAlert.mon(push("rc", "a", "la", "-23.55052", "lo", "-46.63331", "v", "0", "lv", "30", "r", "Raros"));
        assertEquals(-23.55052, near.lat, 0);
        assertEquals(-46.63331, near.lng, 0);
        assertFalse(near.verified);
        assertEquals(30, near.level, 0);
        assertNull(near.location); // com coordenada, o lugar vem do Geocoder
        Pogo.Mon world = PogoAlert.mon(push("rc", "b", "l", "Paris, France", "v", "1", "r", "Mundo"));
        assertEquals("Paris, France", world.location);
        assertTrue(world.verified);
    }

    /** 2 Pokémon no filtro avisam 1 vez; as rodadas seguintes não; outro spawn da espécie, sim. */
    @Test public void sameSpawnOnlyOnce() {
        JSONObject seen = new JSONObject();
        JSONArray log = new JSONArray();
        long d = NOW + 20 * MIN;
        assertTrue(PogoAlert.add(seen, log, spawn("A", d), NOW));
        assertTrue(PogoAlert.add(seen, log, spawn("B", d), NOW));
        assertFalse(PogoAlert.add(seen, log, spawn("A", d), NOW + 3 * MIN));
        assertFalse(PogoAlert.add(seen, log, spawn("B", d), NOW + 3 * MIN));
        assertEquals(2, log.length());
        assertTrue(PogoAlert.add(seen, log, spawn("C", d), NOW + 6 * MIN));
        assertEquals(3, log.length());
    }

    @Test public void survivesRestart() throws Exception {
        JSONObject seen = new JSONObject();
        JSONArray log = new JSONArray();
        assertTrue(PogoAlert.add(seen, log, spawn("A", NOW + 20 * MIN), NOW));
        JSONObject seen2 = new JSONObject(seen.toString());
        JSONArray log2 = new JSONArray(log.toString());
        assertFalse(PogoAlert.add(seen2, log2, spawn("A", NOW + 20 * MIN), NOW + MIN));
        assertEquals(1, log2.length());
    }

    @Test public void evictedFromListStillBlocks() {
        JSONObject seen = new JSONObject();
        JSONArray log = new JSONArray();
        long d = NOW + 30 * MIN;
        for (int i = 0; i <= PogoAlert.MAX_LOG; i++) assertTrue(PogoAlert.add(seen, log, spawn("e" + i, d), NOW + i));
        assertEquals(PogoAlert.MAX_LOG, log.length());
        assertEquals("e1", log.optJSONObject(0).optString("e"));
        assertFalse(PogoAlert.add(seen, log, spawn("e0", d), NOW + MIN));
        assertEquals(PogoAlert.MAX_LOG, log.length());
        assertEquals("e50", log.optJSONObject(log.length() - 1).optString("e"));
    }

    @Test public void expiredStaysInTheListUntilTheCap() {
        JSONObject seen = new JSONObject();
        JSONArray log = new JSONArray();
        assertTrue(PogoAlert.add(seen, log, spawn("A", NOW + MIN), NOW));
        assertTrue(PogoAlert.add(seen, log, spawn("B", NOW + 5 * MIN), NOW + 3 * 60 * MIN));
        assertEquals(2, log.length());
    }

    @Test public void correctedDespawnExtends() {
        JSONObject seen = new JSONObject();
        JSONArray log = new JSONArray();
        long t = NOW + 10 * MIN;
        assertTrue(PogoAlert.add(seen, log, spawn("A", t), NOW));
        assertFalse(PogoAlert.add(seen, log, spawn("A", t + 20 * MIN), NOW + 5 * MIN));
        assertFalse(PogoAlert.add(seen, log, spawn("A", t + 20 * MIN), t + 65 * MIN));
        assertEquals(String.valueOf(t + 20 * MIN), log.optJSONObject(0).optString("d")); // a lista mostra o despawn novo
    }

    @Test public void deadSpawnLeavesTheSeenSet() {
        JSONObject seen = new JSONObject();
        JSONArray log = new JSONArray();
        assertTrue(PogoAlert.add(seen, log, spawn("A", NOW), NOW - MIN));
        assertFalse(PogoAlert.add(seen, log, spawn("A", NOW), NOW + 59 * MIN));
        assertTrue(PogoAlert.add(seen, log, spawn("A", NOW), NOW + 61 * MIN));
    }

    @Test public void noDespawnKeepsTwoHours() {
        JSONObject seen = new JSONObject();
        JSONArray log = new JSONArray();
        assertTrue(PogoAlert.add(seen, log, push("e", "A", "rc", "x"), NOW));
        assertTrue(PogoAlert.add(seen, log, push("e", "B", "rc", "y"), NOW));
        assertFalse(PogoAlert.add(seen, log, push("e", "A", "rc", "x"), NOW + 119 * MIN));
        assertTrue(PogoAlert.add(seen, log, push("e", "B", "rc", "y"), NOW + 121 * MIN));
    }

    @Test public void oldServerFallsBackToRevealCode() {
        JSONObject seen = new JSONObject();
        JSONArray log = new JSONArray();
        assertTrue(PogoAlert.add(seen, log, push("rc", "x", "d", String.valueOf(NOW + MIN)), NOW));
        assertFalse(PogoAlert.add(seen, log, push("rc", "x", "d", String.valueOf(NOW + MIN)), NOW + 1));
    }

    @Test public void seenHasASafetyCap() {
        JSONObject seen = new JSONObject();
        JSONArray log = new JSONArray();
        for (int i = 0; i <= PogoAlert.MAX_SEEN; i++) PogoAlert.add(seen, log, spawn("e" + i, NOW + MIN + i), NOW);
        assertEquals(PogoAlert.MAX_SEEN, seen.length());
        assertFalse(seen.has("e0")); // sai o que morre primeiro
        assertTrue(PogoAlert.add(seen, log, spawn("short", NOW + 2), NOW + 1));
        assertTrue(seen.has("short")); // nem quando o que acabou de entrar é o que morre primeiro
        assertFalse(PogoAlert.add(seen, log, spawn("short", NOW + 2), NOW + 1));
    }

    @Test public void listIsNewestFirst() {
        JSONObject seen = new JSONObject();
        JSONArray log = new JSONArray();
        PogoAlert.add(seen, log, spawn("A", NOW + MIN), NOW);
        PogoAlert.add(seen, log, spawn("B", NOW + MIN), NOW + 1);
        List<Pogo.Mon> l = PogoAlert.mons(log);
        assertEquals("rcB", l.get(0).reveal);
        assertEquals("rcA", l.get(1).reveal);
    }

    @Test public void serverPageBecomesTheLocalLog() throws Exception {
        Pogo.Page p = Pogo.page(new JSONObject("{\"items\":[{\"id\":\"9\",\"created_at\":1,\"data\":{\"n\":\"Dratini\",\"rc\":\"b\"}},"
                + "{\"id\":\"7\",\"created_at\":0,\"data\":{\"n\":\"Rattata\",\"rc\":\"a\"}}],\"next\":\"7\"}"));
        assertEquals("7", p.next);
        assertEquals("9", p.mons.get(0).historyId);
        assertEquals("Dratini", p.mons.get(0).name);
        assertEquals("7", p.mons.get(1).historyId);
        assertEquals("7", p.log.getJSONObject(0).getString("h"));
        assertNull(Pogo.page(new JSONObject("{\"items\":[],\"next\":null}")).next);
    }

    @Test public void dropRemovesOnlyThatHistoryItem() throws Exception {
        JSONArray log = new JSONArray("[{\"h\":\"1\",\"n\":\"A\"},{\"n\":\"old\"},{\"h\":\"2\",\"n\":\"B\"}]");
        PogoAlert.drop(log, "1");
        assertEquals(2, log.length());
        assertEquals("old", log.getJSONObject(0).getString("n"));
        assertEquals("2", log.getJSONObject(1).getString("h"));
    }

    @Test
    public void budgetIsSharedByABurst() {
        PogoAlert.sBurstEnd = PogoAlert.sLast = 0;
        assertEquals(8_000, PogoAlert.budget(NOW));
        PogoAlert.done(NOW + 5_000);
        assertEquals(2_950, PogoAlert.budget(NOW + 5_050));
        PogoAlert.done(NOW + 8_000);
        assertEquals(1_000, PogoAlert.budget(NOW + 8_050));
        PogoAlert.done(NOW + 9_050);
        assertEquals(1_000, PogoAlert.budget(NOW + 9_100));
        PogoAlert.done(NOW + 10_100);
        assertEquals(8_000, PogoAlert.budget(NOW + 12_000));
    }
}
