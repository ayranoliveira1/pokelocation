package com.pokelocation.app.ui.panel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

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

    @Test
    public void hideTakesOpenPanelsOutAndShowPutsThemBack() throws Exception {
        FakeWm wm = new FakeWm();
        View win = new FrameLayout(null);
        List<Object> open = field(null, "sOpen");
        Constructor<PanelHost> k = PanelHost.class.getDeclaredConstructor();
        k.setAccessible(true);
        PanelHost h = k.newInstance();
        set(h, "wm", wm);
        set(h, "window", win);
        set(h, "lp", new WindowManager.LayoutParams());
        open.add(h);
        try {
            PanelHost.setAllVisible(false);
            PanelHost.setAllVisible(false);
            assertEquals(1, wm.removed.size());
            assertEquals(0, wm.added.size());
            PanelHost.setAllVisible(true);
            PanelHost.setAllVisible(true);
            assertEquals(1, wm.added.size());
            assertTrue(wm.added.get(0) == win);
        } finally {
            open.remove(h);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object o, String name) throws Exception {
        Field f = PanelHost.class.getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(o);
    }

    private static void set(Object o, String name, Object v) throws Exception {
        Field f = PanelHost.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(o, v);
    }

    private static final class FakeWm implements WindowManager {
        final List<View> added = new ArrayList<>(), removed = new ArrayList<>();

        @Override public void addView(View v, ViewGroup.LayoutParams p) { added.add(v); }

        @Override public void removeViewImmediate(View v) { removed.add(v); }

        @Override public void removeView(View v) { removed.add(v); }

        @Override public void updateViewLayout(View v, ViewGroup.LayoutParams p) {}

        @Override public Display getDefaultDisplay() { return null; }
    }
}
