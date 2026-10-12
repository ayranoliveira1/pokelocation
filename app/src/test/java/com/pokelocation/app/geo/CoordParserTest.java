package com.pokelocation.app.geo;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CoordParserTest {
    private final double[] o = new double[2];

    @Test
    public void validFormats() {
        assertTrue(CoordParser.parse("-23.55, -46.63", o));
        assertEquals(-23.55, o[0], 0);
        assertEquals(-46.63, o[1], 0);
        assertTrue(CoordParser.parse("10;20", o));
        assertTrue(CoordParser.parse("  10 20  ", o));
        assertTrue(CoordParser.parse("+1.5 , .5", o));
        assertEquals(0.5, o[1], 0);
        assertTrue(CoordParser.parse("90, 180", o));
    }

    @Test
    public void invalidFormats() {
        assertFalse(CoordParser.parse("", o));
        assertFalse(CoordParser.parse(null, o));
        assertFalse(CoordParser.parse("91, 0", o));
        assertFalse(CoordParser.parse("0, 180.1", o));
        assertFalse(CoordParser.parse("1, 2, 3", o));
        assertFalse(CoordParser.parse("NaN, 1", o));
        assertFalse(CoordParser.parse("1e2, 1", o));
        assertFalse(CoordParser.parse("abc", o));
    }

    @Test
    public void format() {
        assertEquals("-23.55, -46.63", CoordParser.format("Local: -23.55,-46.63 (SP)"));
        assertEquals("1, 2", CoordParser.format("x 1;   2 y"));
        assertNull(CoordParser.format("sem coordenadas"));
    }

    @Test
    public void textAndSnippet() {
        assertEquals("1.5, -2.0", CoordParser.text(1.5, -2));
        assertEquals("0.00001,0.0", CoordParser.snippet(1e-5, 0));
    }

    @Test
    public void waypointsWithGarbage() {
        double[] w = CoordParser.waypoints(" 1,2 ; lixo; 3,4,5;95,0; 6,7,x ;8,9");
        assertArrayEquals(new double[]{1, 2, Double.NaN, 3, 4, 5, 8, 9, Double.NaN}, w, 0);
        assertNull(CoordParser.waypoints("lixo;;"));
        assertNull(CoordParser.waypoints(null));
    }
}
