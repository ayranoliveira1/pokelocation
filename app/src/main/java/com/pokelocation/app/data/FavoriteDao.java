package com.pokelocation.app.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.pokelocation.app.ipc.WidgetPing;
import com.pokelocation.app.model.Favorite;
import com.pokelocation.app.model.Folder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Síncrono (chamar no Io). Escritas em transação; depois do commit chama WidgetPing.requestUpdate. */
public final class FavoriteDao {
    static final String T = "favorite";
    private static final String COLS = "id,name,address,lat,lng,alt,sort,folder_id";

    private FavoriteDao() {}

    private static Favorite row(Cursor c) {
        return new Favorite(c.getString(0), c.getString(1), c.getString(2), c.getDouble(3), c.getDouble(4),
                c.getDouble(5), c.getInt(6), c.isNull(7) ? null : c.getString(7));
    }

    private static List<Favorite> query(Context c, String where, String[] args, String order, String limit) {
        List<Favorite> out = new ArrayList<>();
        try (Cursor cur = Db.get(c).query(T, COLS.split(","), where, args, null, null, order, limit)) {
            while (cur.moveToNext()) out.add(row(cur));
        }
        return out;
    }

    /** null = raiz; ORDER BY sort. */
    public static List<Favorite> list(Context c, String folderId) {
        List<String> a = new ArrayList<>();
        String w = Db.eqOrNull("folder_id", folderId, a);
        return query(c, w, Db.arr(a), "sort", null);
    }

    public static List<Favorite> listAll(Context c) {
        return query(c, null, null, "folder_id, sort", null);
    }

    public static Favorite get(Context c, String id) {
        if (id == null) return null;
        List<Favorite> l = query(c, "id=?", new String[]{id}, null, "1");
        return l.isEmpty() ? null : l.get(0);
    }

    /** Na ordem dos ids (ids inexistentes são pulados). */
    public static List<Favorite> getByIds(Context c, Collection<String> ids) {
        Map<String, Favorite> found = new HashMap<>();
        for (List<String> chunk : Db.chunks(ids)) {
            List<String> a = new ArrayList<>();
            String w = "id IN " + Db.in(chunk, a);
            for (Favorite f : query(c, w, Db.arr(a), null, null)) found.put(f.id, f);
        }
        List<Favorite> out = new ArrayList<>(found.size());
        for (String id : ids) {
            Favorite f = found.get(id);
            if (f != null) out.add(f);
        }
        return out;
    }

    /** Case-insensitive (Unicode), o primeiro por pasta/sort. */
    public static Favorite findByName(Context c, String name) {
        if (name == null) return null;
        String n = name.trim();
        try (Cursor cur = Db.get(c).rawQuery("SELECT id,name FROM favorite ORDER BY folder_id IS NOT NULL, folder_id, sort", null)) {
            while (cur.moveToNext()) {
                if (n.equalsIgnoreCase(cur.getString(1).trim())) return get(c, cur.getString(0));
            }
        }
        return null;
    }

    /** limit ≤ 0 = sem limite. minLng > maxLng = cruza o antimeridiano. */
    public static List<Favorite> inBox(Context c, double minLat, double maxLat, double minLng, double maxLng, int limit) {
        String w = "lat BETWEEN ? AND ? AND " + (minLng <= maxLng ? "lng BETWEEN ? AND ?" : "(lng>=? OR lng<=?)");
        String[] a = {String.valueOf(minLat), String.valueOf(maxLat), String.valueOf(minLng), String.valueOf(maxLng)};
        return limit > 0 ? query(c, w, a, Db.nearCenter(minLat, maxLat, minLng, maxLng), String.valueOf(limit))
                : query(c, w, a, null, null);
    }

    public static int count(Context c, String folderId) {
        List<String> a = new ArrayList<>();
        String w = Db.eqOrNull("folder_id", folderId, a);
        return Db.count(Db.get(c), T, w, Db.arr(a));
    }

    public static int countAll(Context c) {
        return Db.count(Db.get(c), T, "1", new String[0]);
    }

    public static Favorite insert(Context c, String name, String address, double lat, double lng, double alt, String folderId) {
        SQLiteDatabase db = Db.get(c);
        Favorite f;
        db.beginTransaction();
        try {
            int sort = count(c, folderId);
            f = new Favorite(UUID.randomUUID().toString(), name, address, lat, lng, Double.isNaN(alt) ? 0 : alt, sort, folderId);
            db.insertOrThrow(T, null, values(f));
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        WidgetPing.requestUpdate(c);
        return f;
    }

    private static ContentValues values(Favorite f) {
        ContentValues v = new ContentValues(8);
        v.put("id", f.id);
        v.put("name", f.name);
        v.put("address", f.address);
        v.put("lat", f.lat);
        v.put("lng", f.lng);
        v.put("alt", Double.isNaN(f.alt) ? 0 : f.alt);
        v.put("sort", f.sort);
        v.put("folder_id", f.folderId);
        return v;
    }

    private static String scope(String folderId, List<String> a) { return Db.eqOrNull("folder_id", folderId, a); }

    /** Grava campos; pasta nova → vai pro fim dela; sort com clamp em [0,n-1] e moveSort se mudou. */
    public static void update(Context c, Favorite f) {
        SQLiteDatabase db = Db.get(c);
        db.beginTransaction();
        try {
            Favorite old = get(c, f.id);
            if (old == null) return;
            int sort = old.sort;
            if (!eq(old.folderId, f.folderId)) {
                List<String> a = new ArrayList<>();
                Db.compact(db, T, scope(old.folderId, a), Db.arr(a), old.sort);
                sort = count(c, f.folderId);
            }
            ContentValues v = values(f);
            v.put("sort", sort);
            db.update(T, v, "id=?", new String[]{f.id});
            if (f.sort != sort) {
                List<String> a = new ArrayList<>();
                Db.shift(db, T, scope(f.folderId, a), Db.arr(a), sort, f.sort);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        WidgetPing.requestUpdate(c);
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

    /** Ordem de sort decrescente (a compactação não invalida os próximos). */
    public static void deleteMany(Context c, Collection<String> ids) {
        SQLiteDatabase db = Db.get(c);
        db.beginTransaction();
        try {
            List<Favorite> l = getByIds(c, ids);
            Collections.sort(l, (x, y) -> Integer.compare(y.sort, x.sort));
            for (Favorite f : l) {
                db.delete(T, "id=?", new String[]{f.id});
                List<String> a = new ArrayList<>();
                Db.compact(db, T, scope(f.folderId, a), Db.arr(a), f.sort);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        WidgetPing.requestUpdate(c);
    }

    /** null: todos + pastas "favorites"; id: itens e subpastas da subárvore (a pasta fica). */
    public static void deleteAll(Context c, String folderId) {
        SQLiteDatabase db = Db.get(c);
        db.beginTransaction();
        try {
            if (folderId == null) {
                db.delete(T, null, null);
                db.delete(FolderDao.T, "type=?", new String[]{Folder.FAVORITES});
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
                Favorite f = get(c, id);
                if (f == null || eq(f.folderId, destFolderId)) continue;
                List<String> a = new ArrayList<>();
                Db.compact(db, T, scope(f.folderId, a), Db.arr(a), f.sort);
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

    /** Ids dos favoritos dentro das pastas (recursivo). */
    public static List<String> idsInFolders(Context c, Collection<String> folderIds) {
        return FolderDao.itemIdsIn(c, T, folderIds, null);
    }

    static boolean eq(String a, String b) { return a == null ? b == null : a.equals(b); }
}
