package com.pokelocation.app.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.pokelocation.app.ipc.WidgetPing;
import com.pokelocation.app.model.Folder;
import com.pokelocation.app.model.Route;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Síncrono (chamar no Io). Pontos em BLOB (24 B/ponto, LE). "Last Route" = id LAST_ROUTE_ID, sort -1, fora das listas. */
public final class RouteDao {
    public static final String LAST_ROUTE_ID = "last_route";
    static final String T = "route";
    private static final String NOT_LAST = "id!='" + LAST_ROUTE_ID + "'";
    private static final String[] COLS_LIST = {"id", "name", "folder_id", "sort", "n_points"};
    private static final String[] COLS_FULL = {"id", "name", "folder_id", "sort", "n_points", "points"};

    private RouteDao() {}

    private static List<Route> query(Context c, boolean full, String where, String[] args, String order, String limit) {
        List<Route> out = new ArrayList<>();
        try (Cursor cur = Db.get(c).query(T, full ? COLS_FULL : COLS_LIST, where, args, null, null, order, limit)) {
            while (cur.moveToNext()) {
                out.add(new Route(cur.getString(0), cur.getString(1), cur.isNull(2) ? null : cur.getString(2),
                        cur.getInt(3), cur.getInt(4), full ? unpack(cur.getBlob(5)) : null));
            }
        }
        return out;
    }

    private static String scope(String folderId, List<String> a) {
        return Db.eqOrNull("folder_id", folderId, a) + " AND " + NOT_LAST;
    }

    /** Sem pts; exclui Last Route. */
    public static List<Route> list(Context c, String folderId) {
        List<String> a = new ArrayList<>();
        return query(c, false, scope(folderId, a), Db.arr(a), "sort", null);
    }

    public static List<Route> listAll(Context c) {
        return query(c, false, NOT_LAST, null, "folder_id, sort", null);
    }

    /** Com pts (inclui a Last Route se pedida pelo id). */
    public static Route get(Context c, String id) {
        if (id == null) return null;
        List<Route> l = query(c, true, "id=?", new String[]{id}, null, "1");
        return l.isEmpty() ? null : l.get(0);
    }

    /** Igualdade exata, com pts, exclui Last Route. */
    public static Route findByName(Context c, String name) {
        if (name == null) return null;
        List<Route> l = query(c, true, "name=? AND " + NOT_LAST, new String[]{name}, "folder_id IS NOT NULL, folder_id, sort", "1");
        return l.isEmpty() ? null : l.get(0);
    }

    public static List<Route> getByIds(Context c, Collection<String> ids) {
        Map<String, Route> found = new HashMap<>();
        for (List<String> chunk : Db.chunks(ids)) {
            List<String> a = new ArrayList<>();
            for (Route r : query(c, true, "id IN " + Db.in(chunk, a), Db.arr(a), null, null)) found.put(r.id, r);
        }
        List<Route> out = new ArrayList<>(found.size());
        for (String id : ids) {
            Route r = found.get(id);
            if (r != null) out.add(r);
        }
        return out;
    }

    public static int count(Context c, String folderId) {
        List<String> a = new ArrayList<>();
        return Db.count(Db.get(c), T, scope(folderId, a), Db.arr(a));
    }

    public static Route insert(Context c, String name, String folderId, double[] pts) {
        SQLiteDatabase db = Db.get(c);
        Route r;
        db.beginTransaction();
        try {
            r = new Route(UUID.randomUUID().toString(), name, folderId, count(c, folderId), Route.count(pts), pts);
            ContentValues v = new ContentValues(6);
            v.put("id", r.id);
            v.put("name", name);
            v.put("folder_id", folderId);
            v.put("sort", r.sort);
            v.put("n_points", r.count);
            v.put("points", pack(pts));
            db.insertOrThrow(T, null, v);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        WidgetPing.requestUpdate(c);
        return r;
    }

    /** pts null = mantém; pasta nova → fim dela; sort com clamp. */
    public static void update(Context c, Route r) {
        SQLiteDatabase db = Db.get(c);
        db.beginTransaction();
        try {
            Route old = query(c, false, "id=?", new String[]{r.id}, null, "1").stream().findFirst().orElse(null);
            if (old == null) return;
            int sort = old.sort;
            if (!FavoriteDao.eq(old.folderId, r.folderId)) {
                List<String> a = new ArrayList<>();
                Db.compact(db, T, scope(old.folderId, a), Db.arr(a), old.sort);
                sort = count(c, r.folderId);
            }
            ContentValues v = new ContentValues(5);
            v.put("name", r.name);
            v.put("folder_id", r.folderId);
            v.put("sort", sort);
            if (r.pts != null) {
                v.put("n_points", Route.count(r.pts));
                v.put("points", pack(r.pts));
            }
            db.update(T, v, "id=?", new String[]{r.id});
            if (r.sort != sort && r.sort >= 0) {
                List<String> a = new ArrayList<>();
                Db.shift(db, T, scope(r.folderId, a), Db.arr(a), sort, r.sort);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        WidgetPing.requestUpdate(c);
    }

    public static void rename(Context c, String id, String name) {
        ContentValues v = new ContentValues(1);
        v.put("name", name);
        Db.get(c).update(T, v, "id=?", new String[]{id});
        WidgetPing.requestUpdate(c);
    }

    public static void appendPoint(Context c, String id, double lat, double lng, double alt) {
        SQLiteDatabase db = Db.get(c);
        db.beginTransaction();
        try {
            Route r = get(c, id);
            if (r == null) return;
            double[] pts = Route.append(r.pts, lat, lng, alt);
            ContentValues v = new ContentValues(2);
            v.put("n_points", Route.count(pts));
            v.put("points", pack(pts));
            db.update(T, v, "id=?", new String[]{id});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        WidgetPing.requestUpdate(c);
    }

    /** Grava a rota sintética "Last Route" (sem pasta, sort -1). Não pinga o widget. */
    public static Route upsertLastRoute(Context c, double[] pts) {
        ContentValues v = new ContentValues(6);
        v.put("id", LAST_ROUTE_ID);
        v.put("name", "Last Route");
        v.putNull("folder_id");
        v.put("sort", -1);
        v.put("n_points", Route.count(pts));
        v.put("points", pack(pts));
        Db.get(c).insertWithOnConflict(T, null, v, SQLiteDatabase.CONFLICT_REPLACE);
        return new Route(LAST_ROUTE_ID, "Last Route", null, -1, Route.count(pts), pts);
    }

    public static void moveSort(Context c, String folderId, int from, int to) {
        SQLiteDatabase db = Db.get(c);
        db.beginTransaction();
        try {
            List<String> a = new ArrayList<>();
            Db.shift(db, T, scope(folderId, a), Db.arr(a), from, to);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        WidgetPing.requestUpdate(c);
    }

    public static void delete(Context c, String id) {
        deleteMany(c, Collections.singletonList(id));
    }

    public static void deleteMany(Context c, Collection<String> ids) {
        SQLiteDatabase db = Db.get(c);
        db.beginTransaction();
        try {
            List<Route> l = new ArrayList<>();
            for (List<String> chunk : Db.chunks(ids)) {
                List<String> a = new ArrayList<>();
                l.addAll(query(c, false, "id IN " + Db.in(chunk, a), Db.arr(a), null, null));
            }
            Collections.sort(l, (x, y) -> Integer.compare(y.sort, x.sort));
            for (Route r : l) {
                db.delete(T, "id=?", new String[]{r.id});
                if (r.sort < 0) continue;
                List<String> a = new ArrayList<>();
                Db.compact(db, T, scope(r.folderId, a), Db.arr(a), r.sort);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        WidgetPing.requestUpdate(c);
    }

    /** null: todas (menos Last Route) + pastas "routes"; id: itens e subpastas da subárvore (a pasta fica). */
    public static void deleteAll(Context c, String folderId) {
        SQLiteDatabase db = Db.get(c);
        db.beginTransaction();
        try {
            if (folderId == null) {
                db.delete(T, NOT_LAST, null);
                db.delete(FolderDao.T, "type=?", new String[]{Folder.ROUTES});
            } else {
                List<String> desc = FolderDao.descendantIds(c, folderId);
                for (List<String> chunk : Db.chunks(desc)) {
                    List<String> a = new ArrayList<>();
                    db.delete(T, "folder_id IN " + Db.in(chunk, a), Db.arr(a));
                }
                desc.remove(folderId);
                for (List<String> chunk : Db.chunks(desc)) {
                    List<String> a = new ArrayList<>();
                    db.delete(FolderDao.T, "id IN " + Db.in(chunk, a), Db.arr(a));
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        WidgetPing.requestUpdate(c);
    }

    public static void moveTo(Context c, Collection<String> ids, String destFolderId) {
        SQLiteDatabase db = Db.get(c);
        db.beginTransaction();
        try {
            for (String id : ids) {
                if (LAST_ROUTE_ID.equals(id)) continue;
                List<Route> l = query(c, false, "id=?", new String[]{id}, null, "1");
                if (l.isEmpty() || FavoriteDao.eq(l.get(0).folderId, destFolderId)) continue;
                Route r = l.get(0);
                List<String> a = new ArrayList<>();
                Db.compact(db, T, scope(r.folderId, a), Db.arr(a), r.sort);
                ContentValues v = new ContentValues(2);
                v.put("folder_id", destFolderId);
                v.put("sort", count(c, destFolderId));
                db.update(T, v, "id=?", new String[]{id});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        WidgetPing.requestUpdate(c);
    }

    public static List<String> idsInFolders(Context c, Collection<String> folderIds) {
        return FolderDao.itemIdsIn(c, T, folderIds, NOT_LAST);
    }

    /** 24 B/ponto: double lat,lng,alt little-endian (alt NaN = desconhecida). */
    public static byte[] pack(double[] pts) {
        int n = Route.count(pts);
        ByteBuffer b = ByteBuffer.allocate(n * 24).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < n * 3; i++) b.putDouble(pts[i]);
        return b.array();
    }

    public static double[] unpack(byte[] blob) {
        if (blob == null) return new double[0];
        int n = blob.length / 24;
        double[] p = new double[n * 3];
        ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN).asDoubleBuffer().get(p);
        return p;
    }
}
