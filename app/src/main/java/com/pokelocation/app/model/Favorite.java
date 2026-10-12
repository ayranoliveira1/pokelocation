package com.pokelocation.app.model;

public final class Favorite {
    public final String id;              // UUID
    public final String name, address;   // address "" se desconhecido
    public final double lat, lng, alt;   // alt 0 se desconhecida
    public final int sort;               // 0-based dentro da pasta
    public final String folderId;        // null = raiz

    public Favorite(String id, String name, String address, double lat, double lng, double alt, int sort, String folderId) {
        this.id = id;
        this.name = name;
        this.address = address == null ? "" : address;
        this.lat = lat;
        this.lng = lng;
        this.alt = alt;
        this.sort = sort;
        this.folderId = folderId;
    }

    public Favorite withSort(int sort) { return new Favorite(id, name, address, lat, lng, alt, sort, folderId); }

    public Coord coord() { return new Coord(lat, lng, alt); }
}
