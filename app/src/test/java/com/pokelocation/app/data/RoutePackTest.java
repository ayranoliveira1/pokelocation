package com.pokelocation.app.data;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class RoutePackTest {
    @Test
    public void packUnpackKeepsNaN() {
        double[] p = {-23.55, -46.63, Double.NaN, 1e-7, 179.9999999, 812.5};
        byte[] b = RouteDao.pack(p);
        assertEquals(48, b.length);
        assertArrayEquals(p, RouteDao.unpack(b), 0);
    }

    @Test
    public void littleEndian() {
        byte[] b = RouteDao.pack(new double[]{1.0, 0, 0});
        assertEquals((byte) 0x3F, b[7]);   // 1.0 = 0x3FF0000000000000, byte mais significativo no fim
        assertEquals(0, b[0]);
    }

    @Test
    public void empty() {
        assertEquals(0, RouteDao.pack(null).length);
        assertEquals(0, RouteDao.unpack(new byte[0]).length);
    }
}
