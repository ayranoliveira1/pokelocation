package com.pokelocation.app.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

public class ShortcutsTest {
    @Test public void pruneTakesOnlyThePrefixNotShown() {
        assertEquals(Arrays.asList("pogo_alerts", "pogo_alerts:e2"), Shortcuts.stale(
                Arrays.asList("pogo_alerts", "pogo_alerts:e1", "pogo_alerts:e2", "chat:c1"), "pogo_alerts",
                new HashSet<>(Collections.singletonList("pogo_alerts:e1"))));
        assertEquals(Collections.singletonList("chat:c1"), Shortcuts.stale(
                Arrays.asList("pogo_alerts:e1", "chat:c1"), "chat:", Collections.emptySet()));
    }
}
