package com.pokelocation.app.pogo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.pokelocation.app.App;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

public class PogoSeenTest {
    private static final long NOW = 1_700_000_000_000L, MIN = 60_000;

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static Pogo.Mon mon(int id, String encounter, String reveal, double lat, double lng, long despawn) {
        Pogo.Mon m = new Pogo.Mon();
        m.id = id;
        m.encounter = encounter;
        m.reveal = reveal;
        m.lat = lat;
        m.lng = lng;
        m.despawnMs = despawn;
        return m;
    }

    @Test public void sameSpawnSameKeyFromLookupSearchAndPush() throws Exception {
        Pogo.Mon lookup = Pogo.parseLookup("{\"results\":[{\"encounter_id\":326,\"reveal_code\":\"X\",\"pokemon_id\":19,"
                + "\"apx_lat\":-23.5,\"apx_long\":-46.6}]}").get(0);
        Pogo.Mon search = Pogo.parseSearch("{\"results\":[{\"encounter_id\":\"326\",\"reveal_code\":\"Y\"}]}").get(0);
        Map<String, String> d = new HashMap<>();
        d.put("id", "19");
        d.put("rc", "Z");
        d.put("e", "326");
        Pogo.Mon push = PogoAlert.mon(d);
        assertEquals("326", PogoSeen.key(lookup));
        assertEquals("326", PogoSeen.key(search));
        assertEquals("326", PogoSeen.key(push));
    }

    @Test public void otherSpawnOfTheSameSpeciesIsNotMarked() {
        Pogo.Mon a = mon(19, "1", null, Double.NaN, Double.NaN, 0), b = mon(19, "2", null, Double.NaN, Double.NaN, 0);
        Map<String, Long> m = PogoSeen.put(Collections.emptyMap(), PogoSeen.key(a), NOW + MIN, NOW);
        assertTrue(m.containsKey(PogoSeen.key(a)));
        assertFalse(m.containsKey(PogoSeen.key(b)));
    }

    @Test public void keyFallsBackToRevealThenIdPlaceDespawn() {
        assertEquals("r:RC", PogoSeen.key(mon(19, null, "RC", 1, 2, 0)));
        assertEquals("r:RC", PogoSeen.key(mon(19, "", "RC", 1, 2, 0)));
        assertEquals("i:19|-2355052|-4663331|99", PogoSeen.key(mon(19, null, null, -23.550520, -46.633308, 99)));
        assertNotEquals(PogoSeen.key(mon(19, null, null, 1, 2, 99)), PogoSeen.key(mon(19, null, null, 1, 2, 100)));
        assertEquals("i:19|||99", PogoSeen.key(mon(19, null, "", Double.NaN, Double.NaN, 99)));
        assertNull(PogoSeen.key(mon(0, null, null, 1, 2, 99)));
        assertNull(PogoSeen.key(mon(19, "1\t2", null, 1, 2, 99)));
        assertNull(PogoSeen.key(mon(19, null, "a\nb", 1, 2, 99)));
    }

    @Test public void keyIsBuiltOnce() {
        Pogo.Mon m = mon(19, null, "RC", 1, 2, 0);
        String k = PogoSeen.key(m);
        assertSame(k, m.spawn);
        assertSame(k, PogoSeen.key(m));
    }

    @Test public void untilIsLaterOfDespawnAndNowPlusAnHourOrTwoHoursWhenUnknown() {
        assertEquals(NOW + 10 * MIN + 60 * MIN, PogoSeen.until(mon(19, "1", null, 0, 0, NOW + 10 * MIN), NOW));
        assertEquals(NOW + 60 * MIN, PogoSeen.until(mon(19, "1", null, 0, 0, NOW - 180 * MIN), NOW));
        assertEquals(NOW + 120 * MIN, PogoSeen.until(mon(19, "1", null, 0, 0, 0), NOW));
    }

    @Test public void putExtendsButNeverShortens() {
        Map<String, Long> a = PogoSeen.put(Collections.emptyMap(), "1", NOW + 10 * MIN, NOW);
        assertEquals(NOW + 20 * MIN, (long) PogoSeen.put(a, "1", NOW + 20 * MIN, NOW).get("1"));
        assertEquals(NOW + 10 * MIN, (long) PogoSeen.put(a, "1", NOW + 5 * MIN, NOW).get("1"));
        assertEquals(NOW + 10 * MIN, (long) a.get("1"));
    }

    @Test public void putDropsExpiredAndCapsByEarliest() {
        Map<String, Long> m = new HashMap<>();
        m.put("old", NOW - 1);
        for (int i = 0; i < PogoSeen.MAX; i++) m.put("k" + i, NOW + MIN + i);
        Map<String, Long> out = PogoSeen.put(m, "new", NOW + 10_000 * MIN, NOW);
        assertEquals(PogoSeen.MAX, out.size());
        assertFalse(out.containsKey("old"));
        assertFalse(out.containsKey("k0"));
        assertTrue(out.containsKey("k1"));
        assertTrue(out.containsKey("new"));
    }

    @Test public void formatParseRoundTrip() {
        Map<String, Long> m = new HashMap<>();
        m.put("326", NOW + MIN);
        m.put("r:RC", NOW + 2 * MIN);
        m.put("i:19|1|2|99", NOW + 3 * MIN);
        Map<String, Long> back = new HashMap<>();
        PogoSeen.parse(PogoSeen.format(m), NOW, back);
        assertEquals(m, back);
    }

    @Test public void parseSkipsBadLinesAndExpired() {
        Map<String, Long> m = new HashMap<>();
        PogoSeen.parse("ok\t" + (NOW + MIN) + "\n\nnotab\n\t5\nbad\tx\nold\t" + (NOW - 1) + "\nhalf\t12", NOW, m);
        assertEquals(Collections.singletonMap("ok", NOW + MIN), m);
    }

    @Test public void parseKeepsTheLaterOfTwoFiles() {
        Map<String, Long> m = new HashMap<>();
        PogoSeen.parse("a\t" + (NOW + 2 * MIN) + "\nb\t" + (NOW + MIN) + "\n", NOW, m);
        PogoSeen.parse("a\t" + (NOW + MIN) + "\nb\t" + (NOW + 3 * MIN) + "\n", NOW, m);
        assertEquals(NOW + 2 * MIN, (long) m.get("a"));
        assertEquals(NOW + 3 * MIN, (long) m.get("b"));
    }

    @Test public void writeIsAtomicAndReadable() throws Exception {
        File f = new File(tmp.getRoot(), "pogo_seen");
        assertNull(PogoSeen.read(f));
        PogoSeen.write(f, "326\t1\n");
        PogoSeen.write(f, "326\t2\n");
        assertEquals("326\t2\n", PogoSeen.read(f));
        assertFalse(new File(tmp.getRoot(), "pogo_seen.tmp").exists());
    }

    @Test public void oneFilePerWritingProcess() {
        HashSet<String> names = new HashSet<>();
        names.add(PogoSeen.file(App.PROC_MAIN));
        names.add(PogoSeen.file(App.PROC_OVERLAY));
        names.add(PogoSeen.file(App.PROC_MAP));
        assertEquals(3, names.size());
        assertFalse(names.contains(null));
        assertNull(PogoSeen.file(App.PROC_POGOWEB));
    }
}
