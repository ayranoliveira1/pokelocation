package com.pokelocation.app.pogo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

public class PogoPlacesTest {
    @Test
    public void nameIsDistrictAndCity() {
        assertEquals("Pinheiros, São Paulo", PogoPlaces.name("Pinheiros", "São Paulo", "SP"));
        assertEquals("São Paulo", PogoPlaces.name(null, "São Paulo", "SP"));
        assertEquals("Centro, Região X", PogoPlaces.name("Centro", null, "Região X"));
        assertEquals("Centro", PogoPlaces.name("Centro", "Centro", null));
        assertEquals("", PogoPlaces.name(null, null, null));
    }

    @Test
    public void cellIsTwoDecimals() {
        assertEquals(PogoPlaces.cell(-23.5612, -46.6912), PogoPlaces.cell(-23.5649, -46.6871));
        assertNotEquals(PogoPlaces.cell(-23.56, -46.69), PogoPlaces.cell(-23.57, -46.69));
        assertNotEquals(PogoPlaces.cell(-23.56, -46.69), PogoPlaces.cell(-23.56, -46.68));
        assertNotEquals(PogoPlaces.cell(90, 180), PogoPlaces.cell(-90, -180));
    }
}
