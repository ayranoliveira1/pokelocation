package com.pokelocation.app.widget;

import android.content.Context;

import com.pokelocation.app.data.FavoriteDao;
import com.pokelocation.app.data.Prefs;
import com.pokelocation.app.data.RouteDao;
import com.pokelocation.app.model.Favorite;
import com.pokelocation.app.model.Route;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Itens do widget: tokens "fav:&lt;id&gt;,route:&lt;id&gt;" em widget_items_&lt;id&gt; e cache JSON resolvido em widget_cache_&lt;id&gt;. */
final class WidgetData {
    static final int MAX_ITEMS = 100;
    static final String FAV = "fav:", ROUTE = "route:";

    static final class Item {
        final boolean route;
        final String id, name;
        final double lat, lng, alt;   // rotas: 0, 0, 0

        Item(boolean route, String id, String name, double lat, double lng, double alt) {
            this.route = route;
            this.id = id;
            this.name = name;
            this.lat = lat;
            this.lng = lng;
            this.alt = alt;
        }

        String token() { return (route ? ROUTE : FAV) + id; }
    }

    private WidgetData() {}

    /** Tokens válidos (trim, sem duplicados, na ordem), no máximo MAX_ITEMS. */
    static List<String> parseTokens(String raw) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (raw == null) return new ArrayList<>(out);
        for (String t : raw.split(",")) {
            if (out.size() >= MAX_ITEMS) break;
            t = t.trim();
            if ((t.startsWith(FAV) && t.length() > FAV.length()) || (t.startsWith(ROUTE) && t.length() > ROUTE.length())) out.add(t);
        }
        return new ArrayList<>(out);
    }

    static String joinTokens(List<Item> items) {
        StringBuilder sb = new StringBuilder();
        for (Item it : items) {
            if (sb.length() > 0) sb.append(',');
            sb.append(it.token());
        }
        return sb.toString();
    }

    static List<String> tokens(boolean route, Collection<String> ids) {
        List<String> out = new ArrayList<>(ids.size());
        for (String id : ids) if (id != null && !id.isEmpty()) out.add((route ? ROUTE : FAV) + id);
        return out;
    }

    /**
     * BLOQUEANTE (chamar no Io). Tokens → itens existentes, na ordem; apagados somem.
     * Rotas via RouteDao.listAll (sem o BLOB de pontos), nunca getByIds/get.
     */
    static List<Item> resolve(Context c, List<String> tokens) {
        List<String> favIds = new ArrayList<>();
        boolean anyRoute = false;
        for (String t : tokens) {
            if (t.startsWith(FAV)) favIds.add(t.substring(FAV.length()));
            else anyRoute = true;
        }
        Map<String, Favorite> favs = new HashMap<>();
        if (!favIds.isEmpty()) for (Favorite f : FavoriteDao.getByIds(c, favIds)) favs.put(f.id, f);
        Map<String, String> routes = new HashMap<>();
        if (anyRoute) for (Route r : RouteDao.listAll(c)) routes.put(r.id, r.name);

        List<Item> out = new ArrayList<>(tokens.size());
        for (String t : tokens) {
            if (t.startsWith(FAV)) {
                Favorite f = favs.get(t.substring(FAV.length()));
                if (f != null) out.add(new Item(false, f.id, f.name, f.lat, f.lng, f.alt));
            } else {
                String id = t.substring(ROUTE.length());
                String name = routes.get(id);
                if (name != null) out.add(new Item(true, id, name, 0, 0, 0));
            }
        }
        return out;
    }

    /**
     * BLOQUEANTE. Resolve widget_items_&lt;id&gt; e grava só o cache. Se a WidgetConfigActivity trocou a lista
     * durante a resolução, ela já gravou lista + cache novos: devolve esse cache em vez de sobrescrever.
     */
    static List<Item> refresh(Context c, int widgetId) {
        String key = Prefs.widgetKey(Prefs.WIDGET_ITEMS, widgetId);
        String raw = Prefs.getString(c, key, "");
        List<Item> items = resolve(c, parseTokens(raw));
        synchronized (WidgetData.class) {
            if (!raw.equals(Prefs.getString(c, key, ""))) return cached(c, widgetId);
            Prefs.edit(c).putString(Prefs.widgetKey(Prefs.WIDGET_CACHE, widgetId), toJson(items)).apply();
        }
        return items;
    }

    /** Grava tokens + cache JSON (apply). Só no processo main. */
    static void store(Context c, int widgetId, List<Item> items) {
        synchronized (WidgetData.class) {
            Prefs.edit(c)
                    .putString(Prefs.widgetKey(Prefs.WIDGET_ITEMS, widgetId), joinTokens(items))
                    .putString(Prefs.widgetKey(Prefs.WIDGET_CACHE, widgetId), toJson(items))
                    .apply();
        }
    }

    static List<Item> cached(Context c, int widgetId) {
        return fromJson(Prefs.getString(c, Prefs.widgetKey(Prefs.WIDGET_CACHE, widgetId), ""));
    }

    static String toJson(List<Item> items) {
        JSONArray a = new JSONArray();
        try {
            for (Item it : items) {
                a.put(new JSONObject().put("type", it.route ? "R" : "F").put("id", it.id).put("name", it.name)
                        .put("lat", it.lat).put("lng", it.lng).put("alt", it.alt));
            }
        } catch (JSONException ignored) {
            // só NaN/Infinity lançam; coordenadas do banco são finitas
        }
        return a.toString();
    }

    /** JSON do cache → itens; erro → lista vazia. */
    static List<Item> fromJson(String json) {
        List<Item> out = new ArrayList<>();
        if (json == null || json.isEmpty()) return out;
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Item("R".equals(o.optString("type")), o.getString("id"), o.optString("name"),
                        o.optDouble("lat", 0), o.optDouble("lng", 0), o.optDouble("alt", 0)));
            }
        } catch (JSONException e) {
            out.clear();
        }
        return out;
    }

    static boolean grouped(Context c, int widgetId) {
        return Prefs.getBool(c, Prefs.widgetKey(Prefs.WIDGET_GROUP_BY_TYPE, widgetId), true);
    }

    /** Aba atual (só tem efeito se agrupado). */
    static boolean showRoutes(Context c, int widgetId) {
        return grouped(c, widgetId) && Prefs.getBool(c, Prefs.widgetKey(Prefs.WIDGET_SHOW_ROUTES, widgetId), false);
    }

    /** Itens visíveis: todos, ou só os da aba atual se agrupado. */
    static List<Item> visible(Context c, int widgetId, List<Item> all) {
        if (!grouped(c, widgetId)) return all;
        boolean routes = showRoutes(c, widgetId);
        List<Item> out = new ArrayList<>(all.size());
        for (Item it : all) if (it.route == routes) out.add(it);
        return out;
    }

    static void delete(Context c, int widgetId) {
        Prefs.edit(c)
                .remove(Prefs.widgetKey(Prefs.WIDGET_ITEMS, widgetId))
                .remove(Prefs.widgetKey(Prefs.WIDGET_THEME, widgetId))
                .remove(Prefs.widgetKey(Prefs.WIDGET_GROUP_BY_TYPE, widgetId))
                .remove(Prefs.widgetKey(Prefs.WIDGET_SHOW_ROUTES, widgetId))
                .remove(Prefs.widgetKey(Prefs.WIDGET_CACHE, widgetId))
                .apply();
    }
}
