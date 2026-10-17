package com.pokelocation.app.ui.deeplink;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.pokelocation.app.ipc.Intents;

import org.junit.Test;

public class DeepLinkActivityTest {

    @Test
    public void hostsMapToPublicActions() {
        String[][] map = {
                {"teleport", Intents.ACTION_TELEPORT}, {"route", Intents.ACTION_ROUTE}, {"walk", Intents.ACTION_WALK},
                {"stop", Intents.ACTION_STOP}, {"route-pause", Intents.ACTION_ROUTE_PAUSE},
                {"route-resume", Intents.ACTION_ROUTE_RESUME}, {"route-next", Intents.ACTION_ROUTE_NEXT},
                {"route-prev", Intents.ACTION_ROUTE_PREV}, {"hide", Intents.ACTION_HIDE}, {"show", Intents.ACTION_SHOW},
                {"speed", Intents.ACTION_SPEED}, {"favorites", Intents.ACTION_FAVORITES},
                {"record-start", Intents.ACTION_RECORD_START}, {"record-stop", Intents.ACTION_RECORD_STOP},
                {"record-add", Intents.ACTION_RECORD_ADD}, {"generate", Intents.ACTION_GENERATE}};
        assertEquals(16, map.length);
        for (String[] m : map) assertEquals(m[0], m[1], DeepLinkActivity.action(m[0]));
        assertNull(DeepLinkActivity.action("update"));
        assertNull(DeepLinkActivity.action("status"));
        assertNull(DeepLinkActivity.action(null));
    }

    @Test
    public void paramsAreTyped() {
        assertEquals(120.0, (Double) DeepLinkActivity.parse("offset", "120"), 0);    // generate?offset=120
        assertEquals(-23.5505123, (Double) DeepLinkActivity.parse("lat", " -23.5505123 "), 0);
        assertEquals(12f, (Float) DeepLinkActivity.parse("speed", "12"), 0);
        assertEquals(10, DeepLinkActivity.parse("marker_count", "10"));
        assertEquals(Boolean.FALSE, DeepLinkActivity.parse("teleport", "false"));
        assertEquals("1,2;3,4", DeepLinkActivity.parse("waypoints", "1,2;3,4"));
        assertEquals("My Route", DeepLinkActivity.parse("name", "My Route"));
    }

    @Test
    public void invalidParamsAreOmitted() {
        assertNull(DeepLinkActivity.parse("lat", "abc"));
        assertNull(DeepLinkActivity.parse("lat", "NaN"));
        assertNull(DeepLinkActivity.parse("alt", "Infinity"));
        assertNull(DeepLinkActivity.parse("mode", "1.5"));
        assertNull(DeepLinkActivity.parse("teleport", "yes"));
        assertNull(DeepLinkActivity.parse("lng", null));
    }
}
