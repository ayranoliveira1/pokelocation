package com.pokelocation.app.ipc;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class InternalIntentsTest {
    // ownToken = o extra é exatamente o PendingIntent de auth (o equals roda no aparelho; aqui, a decisão)
    @Test
    public void internalWithoutAuthIsRefused() {
        assertFalse(Intents.trusted(Intents.INT_TELEPORT_UI, false));
        assertFalse(Intents.trusted(Intents.INT_STOP_JOYSTICK, false));
    }

    @Test
    public void internalWithAnotherOfOurPendingIntentsIsRefused() {
        assertFalse(Intents.trusted(Intents.INT_FILL_LOCATION, false));
    }

    @Test
    public void internalWithTheAuthTokenIsAccepted() {
        assertTrue(Intents.trusted(Intents.INT_TELEPORT_UI, true));
        assertTrue(Intents.trusted(Intents.INT_FILL_LOCATION, true));
    }

    @Test
    public void publicActionsAndPlainStartStayOpen() {
        assertTrue(Intents.trusted(Intents.ACTION_TELEPORT, false));
        assertTrue(Intents.trusted(Intents.ACTION_STOP, false));
        assertTrue(Intents.trusted(null, false));
    }

    @Test
    public void replayRebuildsOnlyPublicActionsOrPlainStart() {
        assertTrue(Intents.replayable(Intents.ACTION_TELEPORT));
        assertTrue(Intents.replayable(Intents.ACTION_STOP));
        assertTrue(Intents.replayable(null));
        assertFalse(Intents.replayable(Intents.INT_STOP_JOYSTICK));
        assertFalse(Intents.replayable(Intents.INT_TELEPORT_UI));
        assertFalse(Intents.replayable("com.evil.ANYTHING"));
    }

    @Test
    public void fillLocationIgnoresMissingOrOutOfRangeCoordinates() {
        assertTrue(Intents.validLatLng(-23.55, -46.63));
        assertTrue(Intents.validLatLng(90, -180));
        assertFalse(Intents.validLatLng(Double.NaN, 10));
        assertFalse(Intents.validLatLng(10, Double.NaN));
        assertFalse(Intents.validLatLng(90.1, 0));
        assertFalse(Intents.validLatLng(0, 180.1));
    }
}
