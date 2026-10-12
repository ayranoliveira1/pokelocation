package com.pokelocation.app.ui.map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RouteModelTest {
    private static RouteModel model(double... pts) {
        RouteModel m = new RouteModel();
        m.load(pts, -1);
        return m;
    }

    @Test public void addWithoutStartBecomesStart() {
        RouteModel m = new RouteModel();
        m.add(1, 2, Double.NaN);
        assertTrue(m.hasStart());
        assertFalse(m.hasWaypoints());
        m.add(3, 4, 5);
        assertEquals(2, m.size());
        assertEquals(3, m.lat(1), 0);
    }

    @Test public void growsPastInitialCapacity() {
        RouteModel m = new RouteModel();
        for (int i = 0; i < 5000; i++) m.add(i * 1e-4, 0, Double.NaN);
        assertEquals(5000, m.size());
        assertEquals(4999e-4, m.lat(4999), 1e-12);
    }

    @Test public void insertMidUsesAverageAltitudeAndShiftsNext() {
        RouteModel m = model(0, 0, 10, 0, 2, 30, 0, 4, Double.NaN);
        m.next = 2;
        assertEquals(1, m.insertMid(1));
        assertEquals(4, m.size());
        assertEquals(1, m.lng(1), 1e-6);
        assertEquals(20, m.alt(1), 0);
        assertEquals(3, m.next);
        assertEquals(-1, m.insertMid(0)); // início não copia
    }

    @Test public void midAlt() {
        assertEquals(15, RouteModel.midAlt(10, 20), 0);
        assertEquals(7, RouteModel.midAlt(Double.NaN, 7), 0);
        assertEquals(7, RouteModel.midAlt(7, Double.NaN), 0);
        assertTrue(Double.isNaN(RouteModel.midAlt(Double.NaN, Double.NaN)));
    }

    @Test public void deleteForbidsStartAndNext() {
        RouteModel m = model(0, 0, 0, 1, 1, 0, 2, 2, 0, 3, 3, 0);
        m.next = 2;
        assertFalse(m.delete(0));
        assertFalse(m.delete(2));
        assertTrue(m.delete(1));
        assertEquals(1, m.next);
        assertArrayEquals(new double[]{0, 0, 0, 2, 2, 0, 3, 3, 0}, m.toArray(), 0);
        assertTrue(m.delete(2));
        assertEquals(2, m.size());
    }

    @Test public void loadIgnoresInvalidNext() {
        RouteModel m = new RouteModel();
        m.load(new double[]{0, 0, 0, 1, 1, 0}, 5);
        assertEquals(-1, m.next);
        m.load(new double[]{0, 0, 0, 1, 1, 0}, 0);
        assertEquals(0, m.next);
        m.clearWaypoints();
        assertEquals(1, m.size());
        assertEquals(-1, m.next);
    }

    @Test public void startAction() {
        assertEquals(RouteModel.START_PICK, RouteModel.startAction(true, true, true, true, 1));
        assertEquals(RouteModel.START_ROUTE, RouteModel.startAction(false, true, false, false, 0));
        assertEquals(RouteModel.START_TELEPORT, RouteModel.startAction(false, false, false, false, 1));
        assertEquals(RouteModel.START_TELEPORT, RouteModel.startAction(false, false, false, true, 0));
        assertEquals(RouteModel.START_WALK, RouteModel.startAction(false, false, false, true, 1));
        assertEquals(RouteModel.START_WALK, RouteModel.startAction(false, false, true, false, 0));
    }

    @Test public void walkLine() {
        assertFalse(RouteModel.showWalkLine(false, false, true, 1));
        assertFalse(RouteModel.showWalkLine(true, false, false, 0));
        assertTrue(RouteModel.showWalkLine(true, false, true, 0));
        assertTrue(RouteModel.showWalkLine(true, false, false, 1));
        assertTrue(RouteModel.showWalkLine(false, true, false, 0));
    }
}
