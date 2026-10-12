package com.pokelocation.app.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;

import com.pokelocation.app.model.Marker;
import com.pokelocation.app.model.MarkerType;

import java.util.ArrayList;
import java.util.List;

/** Tipos de marcador (1 e 2 fixos) e marcadores (ids de usuário ≥ 1e9). Síncrono (chamar no Io). */
public final class MarkerDao {
    private static final String TYPE_SQL = "SELECT t.id,t.name,t.icon,t.color,(SELECT COUNT(*) FROM marker m WHERE m.type_id=t.id) FROM marker_type t";

    private MarkerDao() {}

    private static List<MarkerType> types(Context c, String where, String[] args) {
        List<MarkerType> out = new ArrayList<>();
        try (Cursor cur = Db.get(c).rawQuery(TYPE_SQL + (where != null ? " WHERE " + where : "") + " ORDER BY t.id", args)) {
            while (cur.moveToNext()) {
                out.add(new MarkerType(cur.getInt(0), cur.getString(1), cur.getString(2), cur.getInt(3), cur.getInt(4)));
            }
        }
        return out;
    }

    /** ORDER BY id, com count. */
    public static List<MarkerType> types(Context c) { return types(c, null, null); }

    public static MarkerType getType(Context c, int id) {
        List<MarkerType> l = types(c, "t.id=?", new String[]{String.valueOf(id)});
        return l.isEmpty() ? null : l.get(0);
    }

    /** id = max + 1. */
    public static MarkerType insertType(Context c, String name, String icon, int color) {
        SQLiteDatabase db = Db.get(c);
        int id;
        db.beginTransaction();
        try {
            id = (int) Math.max(2, longQuery(db, "SELECT IFNULL(MAX(id),0) FROM marker_type")) + 1;
            ContentValues v = new ContentValues(4);
            v.put("id", id);
            v.put("name", name);
            v.put("icon", icon);
            v.put("color", color);
            db.insertOrThrow("marker_type", null, v);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return new MarkerType(id, name, icon, color, 0);
    }

    public static void updateType(Context c, MarkerType t) {
        ContentValues v = new ContentValues(3);
        v.put("name", t.name);
        v.put("icon", t.icon);
        v.put("color", t.color);
        Db.get(c).update("marker_type", v, "id=?", new String[]{String.valueOf(t.id)});
    }

    /** 1 e 2: só apaga os marcadores. */
    public static void deleteType(Context c, int id) {
        SQLiteDatabase db = Db.get(c);
        db.beginTransaction();
        try {
            String[] a = {String.valueOf(id)};
            db.delete("marker", "type_id=?", a);
            if (id > 2) db.delete("marker_type", "id=?", a);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public static int countMarkers(Context c, int typeId) {
        return Db.count(Db.get(c), "marker", "type_id=?", new String[]{String.valueOf(typeId)});
    }

    public static int totalMarkers(Context c) {
        return Db.count(Db.get(c), "marker", "1", new String[0]);
    }

    private static List<Marker> query(Context c, String where, String[] args, String limit) {
        return query(c, where, args, "id", limit);
    }

    private static List<Marker> query(Context c, String where, String[] args, String order, String limit) {
        List<Marker> out = new ArrayList<>();
        try (Cursor cur = Db.get(c).query("marker", new String[]{"id", "name", "type_id", "lat", "lng", "alt"},
                where, args, null, null, order, limit)) {
            while (cur.moveToNext()) {
                out.add(new Marker(cur.getLong(0), cur.isNull(1) ? null : cur.getString(1), cur.getInt(2),
                        cur.getDouble(3), cur.getDouble(4), cur.isNull(5) ? Double.NaN : cur.getDouble(5)));
            }
        }
        return out;
    }

    /** limit ≤ 0 = sem limite. minLng > maxLng = cruza o antimeridiano. */
    public static List<Marker> inBox(Context c, int typeId, double minLat, double maxLat, double minLng, double maxLng, int limit) {
        String w = "type_id=? AND lat BETWEEN ? AND ? AND " + (minLng <= maxLng ? "lng BETWEEN ? AND ?" : "(lng>=? OR lng<=?)");
        String[] a = {String.valueOf(typeId), String.valueOf(minLat), String.valueOf(maxLat), String.valueOf(minLng), String.valueOf(maxLng)};
        return limit > 0 ? query(c, w, a, Db.nearCenter(minLat, maxLat, minLng, maxLng), String.valueOf(limit))
                : query(c, w, a, null);
    }

    /** ORDER BY id. */
    public static List<Marker> listByType(Context c, int typeId) {
        return query(c, "type_id=?", new String[]{String.valueOf(typeId)}, null);
    }

    private static long nextId(SQLiteDatabase db) {
        return Math.max(Marker.USER_ID_MIN, longQuery(db, "SELECT IFNULL(MAX(id),0) FROM marker") + 1);
    }

    /** id = max(1e9, max+1). */
    public static Marker insert(Context c, int typeId, String name, double lat, double lng, double alt) {
        SQLiteDatabase db = Db.get(c);
        long id;
        db.beginTransaction();
        try {
            id = nextId(db);
            ContentValues v = new ContentValues(6);
            v.put("id", id);
            v.put("name", name);
            v.put("type_id", typeId);
            v.put("lat", lat);
            v.put("lng", lng);
            if (Double.isNaN(alt)) v.putNull("alt");
            else v.put("alt", alt);
            db.insertOrThrow("marker", null, v);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return new Marker(id, name, typeId, lat, lng, alt);
    }

    /** Uma transação; names pode ser null. Retorna quantos foram inseridos. */
    public static int insertAll(Context c, int typeId, double[] pts, String[] names) {
        SQLiteDatabase db = Db.get(c);
        int n = pts == null ? 0 : pts.length / 3;
        db.beginTransaction();
        try (SQLiteStatement st = db.compileStatement("INSERT INTO marker(id,name,type_id,lat,lng,alt) VALUES (?,?,?,?,?,?)")) {
            long id = nextId(db);
            for (int i = 0; i < n; i++) {
                st.clearBindings();
                st.bindLong(1, id++);
                String name = names != null && i < names.length ? names[i] : null;
                if (name == null) st.bindNull(2);
                else st.bindString(2, name);
                st.bindLong(3, typeId);
                st.bindDouble(4, pts[i * 3]);
                st.bindDouble(5, pts[i * 3 + 1]);
                double alt = pts[i * 3 + 2];
                if (Double.isNaN(alt)) st.bindNull(6);
                else st.bindDouble(6, alt);
                st.executeInsert();
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return n;
    }

    public static void move(Context c, long id, double lat, double lng) {
        ContentValues v = new ContentValues(2);
        v.put("lat", lat);
        v.put("lng", lng);
        Db.get(c).update("marker", v, "id=?", new String[]{String.valueOf(id)});
    }

    public static void delete(Context c, long id) {
        Db.get(c).delete("marker", "id=?", new String[]{String.valueOf(id)});
    }

    /** Pontos intercalados ORDER BY id (alt NULL → NaN). */
    public static double[] typeAsRoute(Context c, int typeId) {
        List<Marker> l = listByType(c, typeId);
        double[] p = new double[l.size() * 3];
        for (int i = 0; i < l.size(); i++) {
            Marker m = l.get(i);
            p[i * 3] = m.lat;
            p[i * 3 + 1] = m.lng;
            p[i * 3 + 2] = m.alt;
        }
        return p;
    }

    private static long longQuery(SQLiteDatabase db, String sql) {
        try (Cursor c = db.rawQuery(sql, null)) {
            return c.moveToFirst() ? c.getLong(0) : 0;
        }
    }
}
