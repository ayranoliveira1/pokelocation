package com.pokelocation.app.data;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

public class PrefsShareLocationTest {
    /** SharedPreferences falso só com getBoolean (o resto não é usado pela leitura). */
    private static SharedPreferences fake(Map<String, Boolean> m) {
        return (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.class}, (p, method, args) -> {
                    if (!method.getName().equals("getBoolean")) throw new UnsupportedOperationException(method.getName());
                    Boolean v = m.get((String) args[0]);
                    return v != null ? v : args[1];
                });
    }

    @Test
    public void onByDefaultAndFollowsStoredValue() {
        Map<String, Boolean> m = new HashMap<>();
        assertTrue(Prefs.shareLocation(fake(m)));
        m.put(Prefs.SHARE_LOCATION, false);
        assertFalse(Prefs.shareLocation(fake(m)));
    }

    @Test
    public void ownFileOutsidePrefsAndLive() {
        assertNotEquals(Prefs.FILE, Prefs.SHARE_FILE);
        assertNotEquals(Prefs.LIVE_FILE, Prefs.SHARE_FILE);
        assertNull(Prefs.defaultOf(Prefs.SHARE_LOCATION));   // não mora no arquivo 'prefs'
    }
}
