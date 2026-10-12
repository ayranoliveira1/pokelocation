package com.pokelocation.app.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.pokelocation.app.ipc.WidgetPing;
import com.pokelocation.app.model.Folder;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Árvore de pastas (type "favorites"|"routes"), profundidade ≤ 50. Síncrono (chamar no Io). */
public final class FolderDao {
    public static final int MAX_DEPTH = 50;
    static final String T = "folder";
    private static final String[] COLS = {"id", "name", "parent_id", "type", "sort"};

    private FolderDao() {}

    private static Folder row(Cursor c, int depth) {
        return new Folder(c.getString(0), c.getString(1), c.isNull(2) ? null : c.getString(2), c.getString(3), c.getInt(4), depth);
    }

    private static List<Folder> query(Context c, String where, String[] args) {
        List<Folder> out = new ArrayList<>();
        try (Cursor cur = Db.get(c).query(T, COLS, where, args, null, null, "sort")) {
            while (cur.moveToNext()) out.add(row(cur, 0));
        }
        return out;
    }

    private static String scope(String type, String parentId, List<String> a) {
        a.add(type);
        return "type=? AND " + Db.eqOrNull("parent_id", parentId, a);
    }

    private static String itemTable(String type) { return Folder.ROUTES.equals(type) ? RouteDao.T : FavoriteDao.T; }

    /** ORDER BY sort. */
    public static List<Folder> children(Context c, String type, String parentId) {
        List<String> a = new ArrayList<>();
        return query(c, scope(type, parentId, a), Db.arr(a));
    }

    /** Filhos por pai (null = raiz), cada lista em ordem de sort. */
    private static Map<String, List<Folder>> byParent(Context c, String type) {
        Map<String, List<Folder>> m = new HashMap<>();
        for (Folder f : query(c, "type=?", new String[]{type})) {
            List<Folder> l = m.get(f.parentId);
            if (l == null) m.put(f.parentId, l = new ArrayList<>());
            l.add(f);
        }
        return m;
    }

    /** DFS, depth preenchido (0 = raiz), para em profundidade > 50. */
    public static List<Folder> tree(Context c, String type) {
        Map<String, List<Folder>> m = byParent(c, type);
        List<Folder> out = new ArrayList<>();
        dfs(m, null, 0, out, new HashSet<>());
        return out;
    }

    private static void dfs(Map<String, List<Folder>> m, String parent, int depth, List<Folder> out, Set<String> seen) {
        if (depth > MAX_DEPTH) return;
        List<Folder> kids = m.get(parent);
        if (kids == null) return;
        for (Folder f : kids) {
            if (!seen.add(f.id)) continue;
            out.add(new Folder(f.id, f.name, f.parentId, f.type, f.sort, depth));
            dfs(m, f.id, depth + 1, out, seen);
        }
    }

    /** Raiz → atual (inclui a atual); vazio se folderId null. */
    public static List<Folder> ancestors(Context c, String folderId) {
        List<Folder> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String id = folderId;
        while (id != null && out.size() <= MAX_DEPTH && seen.add(id)) {
            Folder f = get(c, id);
            if (f == null) break;
            out.add(f);
            id = f.parentId;
        }
        Collections.reverse(out);
        return out;
    }

    public static Folder get(Context c, String id) {
        if (id == null) return null;
        List<Folder> l = query(c, "id=?", new String[]{id});
        return l.isEmpty() ? null : l.get(0);
    }

    /** Case-insensitive entre os irmãos. */
    public static Folder findByName(Context c, String type, String parentId, String name) {
        if (name == null) return null;
        String n = name.trim();
        for (Folder f : children(c, type, parentId)) if (f.name.trim().equalsIgnoreCase(n)) return f;
        return null;
    }

    /** sort = nº de irmãos. */
    public static Folder create(Context c, String type, String parentId, String name) {
        Folder f = createNoPing(c, type, parentId, name);
        WidgetPing.requestUpdate(c);
        return f;
    }

    private static Folder createNoPing(Context c, String type, String parentId, String name) {
        SQLiteDatabase db = Db.get(c);
        Folder f;
        db.beginTransaction();
        try {
            List<String> a = new ArrayList<>();
            int sort = Db.count(db, T, scope(type, parentId, a), Db.arr(a));
            f = new Folder(UUID.randomUUID().toString(), name, parentId, type, sort, 0);
            ContentValues v = new ContentValues(5);
            v.put("id", f.id);
            v.put("name", name);
            v.put("parent_id", parentId);
            v.put("type", type);
            v.put("sort", sort);
            db.insertOrThrow(T, null, v);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return f;
    }

    public static void rename(Context c, String id, String name) {
        ContentValues v = new ContentValues(1);
        v.put("name", name);
        Db.get(c).update(T, v, "id=?", new String[]{id});
        WidgetPing.requestUpdate(c);
    }

    /** Recusa ciclo (destino = a pasta ou uma descendente): false se alguma foi recusada. */
    public static boolean move(Context c, Collection<String> folderIds, String destParentId) {
        boolean ok = true;
        SQLiteDatabase db = Db.get(c);
        db.beginTransaction();
        try {
            for (String id : folderIds) {
                Folder f = get(c, id);
                if (f == null) continue;
                if (destParentId != null && descendantIds(c, id).contains(destParentId)) {
                    ok = false;
                    continue;
                }
                if (FavoriteDao.eq(f.parentId, destParentId)) continue;
                List<String> a = new ArrayList<>();
                Db.compact(db, T, scope(f.type, f.parentId, a), Db.arr(a), f.sort);
                List<String> b = new ArrayList<>();
                int sort = Db.count(db, T, scope(f.type, destParentId, b), Db.arr(b));
                ContentValues v = new ContentValues(2);
                v.put("parent_id", destParentId);
                v.put("sort", sort);
                db.update(T, v, "id=?", new String[]{id});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        WidgetPing.requestUpdate(c);
        return ok;
    }

    public static void reorder(Context c, String type, String parentId, int from, int to) {
        SQLiteDatabase db = Db.get(c);
        db.beginTransaction();
        try {
            List<String> a = new ArrayList<>();
            Db.shift(db, T, scope(type, parentId, a), Db.arr(a), from, to);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        WidgetPing.requestUpdate(c);
    }

    /** {itens na subárvore, subpastas}. */
    public static int[] countForDelete(Context c, String id) {
        Folder f = get(c, id);
        if (f == null) return new int[]{0, 0};
        List<String> desc = descendantIds(c, id);
        return new int[]{countItems(c, itemTable(f.type), desc), desc.size() - 1};
    }

    private static int countItems(Context c, String table, Collection<String> folderIds) {
        int n = 0;
        for (List<String> chunk : Db.chunks(folderIds)) {
            List<String> a = new ArrayList<>();
            String w = "folder_id IN " + Db.in(chunk, a);
            if (RouteDao.T.equals(table)) w += " AND id!='" + RouteDao.LAST_ROUTE_ID + "'";
            n += Db.count(Db.get(c), table, w, Db.arr(a));
        }
        return n;
    }

    /** Cascata: itens do tipo + subpastas; compacta os irmãos. */
    public static void delete(Context c, String id) {
        Folder f = get(c, id);
        if (f == null) return;
        SQLiteDatabase db = Db.get(c);
        db.beginTransaction();
        try {
            List<String> desc = descendantIds(c, id);
            String table = itemTable(f.type);
            for (List<String> chunk : Db.chunks(desc)) {
                List<String> a = new ArrayList<>();
                db.delete(table, "folder_id IN " + Db.in(chunk, a), Db.arr(a));
                List<String> b = new ArrayList<>();
                db.delete(T, "id IN " + Db.in(chunk, b), Db.arr(b));
            }
            List<String> a = new ArrayList<>();
            Db.compact(db, T, scope(f.type, f.parentId, a), Db.arr(a), f.sort);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        WidgetPing.requestUpdate(c);
    }

    /** folderId → nº de itens na subárvore, para cada pasta pedida. */
    public static Map<String, Integer> subtreeItemCounts(Context c, String type, List<Folder> folders) {
        Map<String, Integer> direct = new HashMap<>();
        String table = itemTable(type);
        String sql = "SELECT folder_id, COUNT(*) FROM " + table + " WHERE folder_id IS NOT NULL"
                + (RouteDao.T.equals(table) ? " AND id!='" + RouteDao.LAST_ROUTE_ID + "'" : "") + " GROUP BY folder_id";
        try (Cursor cur = Db.get(c).rawQuery(sql, null)) {
            while (cur.moveToNext()) direct.put(cur.getString(0), cur.getInt(1));
        }
        Map<String, List<Folder>> m = byParent(c, type);
        Map<String, Integer> out = new HashMap<>();
        for (Folder f : folders) {
            int n = 0;
            for (String id : descendants(m, f.id)) {
                Integer k = direct.get(id);
                if (k != null) n += k;
            }
            out.put(f.id, n);
        }
        return out;
    }

    /** Inclui o próprio id (primeiro). */
    public static List<String> descendantIds(Context c, String id) {
        Folder f = get(c, id);
        if (f == null) return new ArrayList<>(Collections.singletonList(id));
        return descendants(byParent(c, f.type), id);
    }

    private static List<String> descendants(Map<String, List<Folder>> m, String id) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        ArrayDeque<String> q = new ArrayDeque<>();
        q.add(id);
        while (!q.isEmpty()) {
            String cur = q.poll();
            if (!out.add(cur)) continue;
            List<Folder> kids = m.get(cur);
            if (kids != null) for (Folder k : kids) q.add(k.id);
        }
        return new ArrayList<>(out);
    }

    /** "A/B/C" ("" se id null). */
    public static String path(Context c, String id) {
        StringBuilder sb = new StringBuilder();
        for (Folder f : ancestors(c, id)) {
            if (sb.length() > 0) sb.append('/');
            sb.append(f.name);
        }
        return sb.toString();
    }

    /** Cria o que faltar em "A/B/C"; null se vazio. cache (opcional) chave = caminho normalizado. */
    public static String resolvePath(Context c, String type, String path, Map<String, String> cache) {
        if (path == null) return null;
        String parent = null;
        StringBuilder key = new StringBuilder(type).append(':');
        boolean created = false;
        for (String raw : path.split("/")) {
            String seg = raw.trim();
            if (seg.isEmpty()) continue;
            key.append('/').append(seg.toLowerCase(java.util.Locale.ROOT));
            String k = key.toString();
            String id = cache != null ? cache.get(k) : null;
            if (id == null) {
                Folder f = findByName(c, type, parent, seg);
                if (f == null) {
                    f = createNoPing(c, type, parent, seg);
                    created = true;
                }
                id = f.id;
                if (cache != null) cache.put(k, id);
            }
            parent = id;
        }
        if (created) WidgetPing.requestUpdate(c);
        return parent;
    }

    /** Ids dos itens (tabela favorite/route) nas pastas e subpastas. */
    static List<String> itemIdsIn(Context c, String table, Collection<String> folderIds, String extraWhere) {
        LinkedHashSet<String> all = new LinkedHashSet<>();
        for (String id : folderIds) all.addAll(descendantIds(c, id));
        List<String> out = new ArrayList<>();
        for (List<String> chunk : Db.chunks(all)) {
            List<String> a = new ArrayList<>();
            String w = "folder_id IN " + Db.in(chunk, a) + (extraWhere != null ? " AND " + extraWhere : "");
            try (Cursor cur = Db.get(c).query(table, new String[]{"id"}, w, Db.arr(a), null, null, "folder_id, sort")) {
                while (cur.moveToNext()) out.add(cur.getString(0));
            }
        }
        return out;
    }
}
