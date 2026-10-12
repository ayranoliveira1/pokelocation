package com.pokelocation.app.ui.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SettingsRulesTest {
    @Test
    public void parseIgnoresInvalidInput() {
        assertTrue(Double.isNaN(SettingsRules.parse("")));
        assertTrue(Double.isNaN(SettingsRules.parse("abc")));
        assertTrue(Double.isNaN(SettingsRules.parse("NaN")));
        assertTrue(Double.isNaN(SettingsRules.parse("Infinity")));
        assertTrue(Double.isNaN(SettingsRules.parse(null)));
    }

    @Test
    public void parseAcceptsDotAndComma() {
        assertEquals(0.25, SettingsRules.parse(" 0.25 "), 0);
        assertEquals(0.25, SettingsRules.parse("0,25"), 0);
        assertEquals(1000, SettingsRules.parse("1000"), 0);
    }

    @Test
    public void clampAutoSuspendRange() {
        assertEquals(5, SettingsRules.clamp(1, 5, 3600), 0);
        assertEquals(3600, SettingsRules.clamp(99999, 5, 3600), 0);
        assertEquals(30, SettingsRules.clamp(30, 5, 3600), 0);
    }
}
