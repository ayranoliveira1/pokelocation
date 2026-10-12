package com.pokelocation.app.model;

import com.pokelocation.app.geo.CoordParser;

public final class Coord {
    public static final Coord EMPTY = new Coord(0, 0, Double.NaN);
    public final double lat, lng, alt;                 // alt NaN = desconhecida

    public Coord(double lat, double lng, double alt) {
        this.lat = lat;
        this.lng = lng;
        this.alt = alt;
    }

    public boolean hasAlt() { return !Double.isNaN(alt); }

    public double altOr0() { return Double.isNaN(alt) ? 0 : alt; }

    /** lat e lng exatos (progresso de rota / PREV). */
    public boolean sameLatLng(Coord o) { return o != null && o.lat == lat && o.lng == lng; }

    public Coord withAlt(double alt) { return new Coord(lat, lng, alt); }

    /** "lat, lng" (campo de texto). */
    public String text() { return CoordParser.text(lat, lng); }

    /** "lat,lng" (clipboard/share/snippet). */
    public String snippet() { return CoordParser.snippet(lat, lng); }

    @Override
    public String toString() { return text(); }
}
