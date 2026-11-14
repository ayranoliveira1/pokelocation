package com.pokelocation.app.pogo;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PogoChipsTest {
    @Test
    public void seenSitsOnTheRimAt45Degrees() {
        assertEquals(8.18f, PogoChips.seenInset(56, 6), 0.01f);
    }

    @Test
    public void seenNeverLeavesASmallBubble() {
        assertEquals(6f, PogoChips.seenInset(40, 6), 0f);
    }
}
