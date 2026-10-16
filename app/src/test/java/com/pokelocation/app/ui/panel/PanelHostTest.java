package com.pokelocation.app.ui.panel;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PanelHostTest {
    private static final long NEVER = Long.MIN_VALUE / 2;

    @Test
    public void backThatTheImeTookIsOnlyTheKeyboard() {
        assertTrue(PanelHost.imeBack(false, false, 10_000, NEVER));   // o IME ficou com o DOWN: só o UP chegou
        assertTrue(PanelHost.imeBack(true, true, 10_000, NEVER));     // teclado aberto no DOWN
        assertTrue(PanelHost.imeBack(true, false, 10_000, 9_800));    // fechou há 200 ms
    }

    @Test
    public void secondBackClosesThePanel() {
        assertFalse(PanelHost.imeBack(true, false, 10_000, 9_000));   // teclado fechado há 1 s
        assertFalse(PanelHost.imeBack(true, false, 10_000, NEVER));   // sem teclado
    }
}
