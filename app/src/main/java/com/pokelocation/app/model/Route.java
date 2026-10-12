package com.pokelocation.app.model;

import java.util.List;

public final class Route {
    public final String id, name, folderId;
    public final int sort;            // -1 = "Last Route" / rota sintética (marcadores)
    public final int count;           // nº de pontos (coluna n_points)
    public final double[] pts;        // null em listagens; intercalado lat,lng,alt

    public Route(String id, String name, String folderId, int sort, int count, double[] pts) {
        this.id = id;
        this.name = name;
        this.folderId = folderId;
        this.sort = sort;
        this.count = count;
        this.pts = pts;
    }

    public static int count(double[] pts) { return pts == null ? 0 : pts.length / 3; }

    public static Coord at(double[] pts, int i) { return new Coord(pts[i * 3], pts[i * 3 + 1], pts[i * 3 + 2]); }

    public static double[] of(List<Coord> list) {
        double[] p = new double[list.size() * 3];
        for (int i = 0; i < list.size(); i++) {
            Coord c = list.get(i);
            p[i * 3] = c.lat;
            p[i * 3 + 1] = c.lng;
            p[i * 3 + 2] = c.alt;
        }
        return p;
    }

    /** Cópia com o ponto no fim (pts null = vazio). */
    public static double[] append(double[] pts, double lat, double lng, double alt) {
        int n = pts == null ? 0 : pts.length;
        double[] p = new double[n + 3];
        if (n > 0) System.arraycopy(pts, 0, p, 0, n);
        p[n] = lat;
        p[n + 1] = lng;
        p[n + 2] = alt;
        return p;
    }
}
