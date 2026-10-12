package com.pokelocation.app.model;

public final class Marker {
    public static final long USER_ID_MIN = 1_000_000_000L;
    public final long id;
    public final String name;            // pode ser null
    public final int typeId;
    public final double lat, lng, alt;   // alt NaN = desconhecida

    public Marker(long id, String name, int typeId, double lat, double lng, double alt) {
        this.id = id;
        this.name = name;
        this.typeId = typeId;
        this.lat = lat;
        this.lng = lng;
        this.alt = alt;
    }
}
