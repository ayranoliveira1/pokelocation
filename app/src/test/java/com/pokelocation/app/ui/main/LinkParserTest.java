package com.pokelocation.app.ui.main;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LinkParserTest {
    private static void ok(String link, double lat, double lng) {
        double[] out = new double[2];
        assertTrue(link, LinkParser.parse(link, out));
        assertEquals(link, lat, out[0], 1e-9);
        assertEquals(link, lng, out[1], 1e-9);
    }

    private static void bad(String link) {
        assertFalse(link, LinkParser.parse(link, new double[2]));
    }

    @Test public void geo() {
        ok("geo:-23.55,-46.63?z=17", -23.55, -46.63);
        ok("geo:0,0?q=-23.55,-46.63(label)", -23.55, -46.63);
        bad("geo:0,0?q=Avenida+Paulista");
        bad("geo:0,0?q=Rua+Augusta+12,+3");
        bad("https://maps.google.com/?q=Rua%20Augusta%2012%2C%203");
    }

    @Test public void google() {
        ok("https://maps.google.com/?q=-23.55,-46.63", -23.55, -46.63);
        ok("https://www.google.com/maps/place/Foo/@-23.55,-46.63,17z", -23.55, -46.63);
        ok("https://maps.google.com/?q=-23.55%2C-46.63", -23.55, -46.63);
        ok("https://maps.google.com/?q=+23.55,+46.63", 23.55, 46.63);
        ok("https://maps.google.com/?q=loc:-23.55,-46.63", -23.55, -46.63);
        ok("https://maps.google.com/maps?addr=-23.55,-46.63", -23.55, -46.63);
    }

    @Test public void others() {
        ok("https://maps.apple.com/?ll=-23.55,-46.63&q=Some%20Place", -23.55, -46.63);
        ok("https://share.here.com/l/52.5,13.4,Berlin", 52.5, 13.4);
        ok("https://map.baidu.com/?latlng=39.9;116.4", 39.9, 116.4);
    }

    @Test public void yandexIsLngLat() {
        ok("https://yandex.ru/maps/?ll=37.62,55.75&z=12", 55.75, 37.62);
        ok("https://maps.yandex.com/?pt=37.62,55.75", 55.75, 37.62);
    }

    @Test public void qqIsPointxLng() {
        ok("https://map.wap.qq.com/?pointx=116.4&pointy=39.9&type=marker", 39.9, 116.4);
        ok("https://map.qq.com/?type=marker&pointy=39.9&pointx=116.4", 39.9, 116.4);
    }

    @Test public void osm() {
        ok("https://www.openstreetmap.org/?mlat=-23.55&mlon=-46.63#map=17/-23.5/-46.6", -23.55, -46.63);
        ok("https://www.openstreetmap.org/#map=17/-23.55/-46.63", -23.55, -46.63);
    }

    @Test public void invalid() {
        bad(null);
        bad("");
        bad("https://maps.app.goo.gl/AbCdEf");
        bad("geo:95.0,10.0");
        bad("https://maps.google.com/?q=10,200");
        bad("https://maps.google.com/?q=%ZZ");
    }

    @Test public void trackFile() {
        assertTrue(LinkParser.isTrackFile("https://x.com/a/track.GPX"));
        assertTrue(LinkParser.isTrackFile("file:///sdcard/r.kml?x=1#y"));
        assertFalse(LinkParser.isTrackFile("https://x.com/gpx?q=1,2"));
        assertFalse(LinkParser.isTrackFile(null));
    }
}
