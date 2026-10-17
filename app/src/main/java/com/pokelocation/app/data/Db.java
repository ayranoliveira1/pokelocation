package com.pokelocation.app.data;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * SQLiteOpenHelper puro (WAL), lazy por processo. Db não estende SQLiteOpenHelper: o close() estático colidiria
 * com AutoCloseable.close().
 */
public final class Db {
    public static final String NAME = "pokelocation.db";
    public static final int VERSION = 1;

    private static Helper sHelper;
    private static SQLiteDatabase sDb;

    private Db() {}

    private static final class Helper extends SQLiteOpenHelper {
        Helper(Context c) {
            super(c.getApplicationContext(), NAME, null, VERSION);
            setWriteAheadLoggingEnabled(true);
        }

        @Override
        public void onCreate(SQLiteDatabase db) { createSchema(db); }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}
    }

    public static synchronized SQLiteDatabase get(Context c) {
        if (sDb == null || !sDb.isOpen()) {
            if (sHelper == null) sHelper = new Helper(c);
            sDb = sHelper.getWritableDatabase();
        }
        return sDb;
    }

    /** Fecha e anula (restore; onDestroy do :overlay). */
    public static synchronized void close() {
        if (sHelper != null) sHelper.close();
        sHelper = null;
        sDb = null;
    }

    public static File file(Context c) { return c.getDatabasePath(NAME); }

    public static void checkpoint(Context c) {
        try (Cursor cur = get(c).rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null)) {
            cur.moveToFirst();
        }
    }

    private static void createSchema(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE folder(id TEXT PRIMARY KEY, name TEXT NOT NULL, parent_id TEXT, type TEXT NOT NULL, sort INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX ix_folder ON folder(type, parent_id, sort)");
        db.execSQL("CREATE TABLE favorite(id TEXT PRIMARY KEY, name TEXT NOT NULL, address TEXT NOT NULL DEFAULT '',"
                + " lat REAL NOT NULL, lng REAL NOT NULL, alt REAL NOT NULL DEFAULT 0, sort INTEGER NOT NULL, folder_id TEXT)");
        db.execSQL("CREATE INDEX ix_fav_folder ON favorite(folder_id, sort)");
        db.execSQL("CREATE INDEX ix_fav_geo ON favorite(lat, lng)");
        db.execSQL("CREATE TABLE route(id TEXT PRIMARY KEY, name TEXT NOT NULL, sort INTEGER NOT NULL, folder_id TEXT,"
                + " n_points INTEGER NOT NULL, points BLOB NOT NULL)");
        db.execSQL("CREATE INDEX ix_route_folder ON route(folder_id, sort)");
        db.execSQL("CREATE TABLE marker_type(id INTEGER PRIMARY KEY, name TEXT NOT NULL, icon TEXT NOT NULL DEFAULT 'pin', color INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE marker(id INTEGER PRIMARY KEY, name TEXT, type_id INTEGER NOT NULL, lat REAL NOT NULL, lng REAL NOT NULL, alt REAL)");
        db.execSQL("CREATE INDEX ix_marker_geo ON marker(type_id, lat, lng)");
        db.execSQL("INSERT INTO marker_type(id,name,icon,color) VALUES (1,'Landmark','flag'," + 0xFF1E88E5
                + "), (2,'Recreational Area','park'," + 0xFF43A047 + ")");
    }

    /** ORDER BY da distância² ao centro do bbox: com LIMIT, pega os mais próximos do centro, não os de menor id.
     *  Números gerados aqui (não vêm do usuário). ponytail: graus planos e centro ingênuo no antimeridiano; suficiente para escolher o que desenhar. */
    static String nearCenter(double minLat, double maxLat, double minLng, double maxLng) {
        double cLat = (minLat + maxLat) / 2, cLng = (minLng + maxLng) / 2;
        return "((lat-(" + cLat + "))*(lat-(" + cLat + "))+(lng-(" + cLng + "))*(lng-(" + cLng + ")))";
    }

    static String eqOrNull(String col, String v, List<String> args) {
        if (v == null) return col + " IS NULL";
        args.add(v);
        return col + "=?";
    }

    static String[] arr(List<String> args) { return args.toArray(new String[0]); }

    static String in(Collection<String> values, List<String> args) {
        StringBuilder sb = new StringBuilder("(");
        for (String v : values) {
            if (sb.length() > 1) sb.append(',');
            sb.append('?');
            args.add(v);
        }
        return sb.append(')').toString();
    }

    static int count(SQLiteDatabase db, String table, String where, String[] args) {
        try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + table + " WHERE " + where, args)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    /** Compacta após remover o item de sort 'removed' do escopo. */
    static void compact(SQLiteDatabase db, String table, String where, String[] args, int removed) {
        String[] a = concat(args, String.valueOf(removed));
        db.execSQL("UPDATE " + table + " SET sort=sort-1 WHERE " + where + " AND sort>?", a);
    }

    /** Move from→to (to com clamp em [0,n-1]) deslocando só a faixa. Chamar dentro de transação. */
    static void shift(SQLiteDatabase db, String table, String where, String[] args, int from, int to) {
        int n = count(db, table, where, args);
        if (n == 0) return;
        if (to < 0) to = 0;
        if (to > n - 1) to = n - 1;
        if (from == to) return;
        String id = null;
        try (Cursor c = db.rawQuery("SELECT id FROM " + table + " WHERE " + where + " AND sort=? LIMIT 1",
                concat(args, String.valueOf(from)))) {
            if (c.moveToFirst()) id = c.getString(0);
        }
        if (id == null) return;
        if (to > from) {
            db.execSQL("UPDATE " + table + " SET sort=sort-1 WHERE " + where + " AND sort>? AND sort<=?",
                    concat(concat(args, String.valueOf(from)), String.valueOf(to)));
        } else {
            db.execSQL("UPDATE " + table + " SET sort=sort+1 WHERE " + where + " AND sort>=? AND sort<?",
                    concat(concat(args, String.valueOf(to)), String.valueOf(from)));
        }
        db.execSQL("UPDATE " + table + " SET sort=? WHERE id=?", new Object[]{to, id});
    }

    static String[] concat(String[] a, String v) {
        String[] r = new String[a.length + 1];
        System.arraycopy(a, 0, r, 0, a.length);
        r[a.length] = v;
        return r;
    }

    /** Divide coleções grandes para não estourar o limite de 999 variáveis do SQLite. */
    static <T> List<List<T>> chunks(Collection<T> all) {
        List<List<T>> out = new ArrayList<>();
        List<T> cur = new ArrayList<>();
        for (T t : all) {
            cur.add(t);
            if (cur.size() == 500) {
                out.add(cur);
                cur = new ArrayList<>();
            }
        }
        if (!cur.isEmpty()) out.add(cur);
        return out;
    }
}
