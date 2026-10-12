package com.pokelocation.app.ipc;

import static org.junit.Assert.assertEquals;

import com.pokelocation.app.ui.main.MainActivity;

import org.junit.Test;

public class IntentsTest {
    @Test
    public void mainActivityNameMatchesClass() {
        assertEquals(MainActivity.class.getName(), Intents.MAIN_ACTIVITY);
    }
}
