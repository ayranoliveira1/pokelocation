package com.pokelocation.app.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class WidgetDataTest {

    @Test
    public void parseTrimsDropsJunkAndDuplicates() {
        List<String> t = WidgetData.parseTokens(" fav:a ,route:r1,,fav:,route:,x:1,fav:a, route:r2 ");
        assertEquals(Arrays.asList("fav:a", "route:r1", "route:r2"), t);
        assertTrue(WidgetData.parseTokens("").isEmpty());
        assertTrue(WidgetData.parseTokens(null).isEmpty());
    }

    @Test
    public void parseCapsAt100() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 150; i++) sb.append("fav:").append(i).append(',');
        List<String> t = WidgetData.parseTokens(sb.toString());
        assertEquals(WidgetData.MAX_ITEMS, t.size());
        assertEquals("fav:99", t.get(99));
    }

    @Test
    public void joinRoundTrips() {
        List<WidgetData.Item> items = Arrays.asList(
                new WidgetData.Item(false, "a", "A", 1, 2, 3), new WidgetData.Item(true, "r", "R", 0, 0, 0));
        String raw = WidgetData.joinTokens(items);
        assertEquals("fav:a,route:r", raw);
        assertEquals(Arrays.asList("fav:a", "route:r"), WidgetData.parseTokens(raw));
        assertEquals(Arrays.asList("route:x"), WidgetData.tokens(true, Arrays.asList("x", "", null)));
    }
}
