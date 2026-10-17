package com.pokelocation.app.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Um critério só para clique e arraste: o tremor de um toque curto não fecha os botões nem grava a posição. */
public class WindowDragTest {
    @Test public void tremorIsStillAClick() {
        assertFalse(WindowDrag.dragged(0, 0));
        assertFalse(WindowDrag.dragged(3, -2));
        assertFalse(WindowDrag.dragged(-19, 19));
    }

    @Test public void pastTheSlopIsADrag() {
        assertTrue(WindowDrag.dragged(20, 0));
        assertTrue(WindowDrag.dragged(0, -20));
        assertTrue(WindowDrag.dragged(150, 300));
    }
}
