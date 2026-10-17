package com.pokelocation.app.ui.map;

import android.content.Context;

import com.pokelocation.app.Io;
import com.pokelocation.app.data.FavoriteDao;
import com.pokelocation.app.data.MarkerDao;
import com.pokelocation.app.map.MapHelper;
import com.pokelocation.app.model.Favorite;
import com.pokelocation.app.model.MarkerType;

import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.infowindow.MarkerInfoWindow;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/** "Show Markers": favoritos + marcadores customizados só no viewport, com limite no zoom afastado e diff por id. */
final class MarkerLayer {
    /** Item visível; vai no relatedObject do Marker. */
    static final class Item {
        final String key, title;
        final double lat, lng, alt;
        final MarkerType type;     // null = favorito

        Item(String key, String title, double lat, double lng, double alt, MarkerType type) {
            this.key = key;
            this.title = title;
            this.lat = lat;
            this.lng = lng;
            this.alt = alt;
            this.type = type;
        }
    }

    private final Context ctx;
    private final Marker.OnMarkerClickListener click;
    private MapView mv;
    private HashMap<String, Marker> shown = new HashMap<>();
    private Io.Task task;
    private boolean enabled;

    MarkerLayer(Context ctx, MapView mv, Marker.OnMarkerClickListener click) {
        this.ctx = ctx;
        this.mv = mv;
        this.click = click;
    }

    void setEnabled(boolean on) {
        enabled = on;
        if (on) refresh();
        else clear();
    }

    /** Chamado pelo MapHelper.onIdle (debounce 300 ms). */
    void refresh() {
        if (!enabled || mv == null) return;
        if (task != null) task.cancel();
        BoundingBox b = MapHelper.box(mv);
        final double minLat = Math.min(b.getLatSouth(), b.getLatNorth()), maxLat = Math.max(b.getLatSouth(), b.getLatNorth());
        final double minLng = Math.min(b.getLonWest(), b.getLonEast()), maxLng = Math.max(b.getLonWest(), b.getLonEast());
        final boolean limited = maxLat - minLat > MapHelper.VIEWPORT_SPAN_DEG;
        final Context app = ctx.getApplicationContext();
        task = Io.run(() -> load(app, minLat, maxLat, minLng, maxLng, limited), new Io.Callback<List<Item>>() {
            @Override public void onResult(List<Item> items) { apply(items); }
        });
    }

    private static List<Item> load(Context c, double minLat, double maxLat, double minLng, double maxLng, boolean limited) {
        List<MarkerType> types = MarkerDao.types(c);
        int limit = limited ? Math.max(1, MapHelper.VIEWPORT_LIMIT / Math.max(1, types.size())) : 0;
        ArrayList<Item> out = new ArrayList<>();
        for (MarkerType t : types) {
            if (t.count == 0) continue;
            for (com.pokelocation.app.model.Marker m : MarkerDao.inBox(c, t.id, minLat, maxLat, minLng, maxLng, limit)) {
                String title = m.name == null || m.name.isEmpty() ? t.name : m.name;
                out.add(new Item("m" + m.id, title, m.lat, m.lng, m.alt, t));
            }
        }
        for (Favorite f : FavoriteDao.inBox(c, minLat, maxLat, minLng, maxLng, limit)) {
            out.add(new Item("f" + f.id, f.name, f.lat, f.lng, f.alt, null));
        }
        return out;
    }

    private void apply(List<Item> items) {
        if (mv == null || !enabled) return;
        HashMap<String, Marker> next = new HashMap<>(items.size() * 2);
        for (Item it : items) {
            Marker m = shown.remove(it.key);
            if (m == null) {
                m = new Marker(mv);
                m.setInfoWindow((MarkerInfoWindow) null);
                m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);   // coração e ícones de tipo: centro (MapHelper)
                m.setIcon(it.type == null ? MapHelper.pin(ctx, MapHelper.PIN_FAVORITE) : MapHelper.typeIcon(ctx, it.type));
                m.setOnMarkerClickListener(click);
                mv.getOverlays().add(m);
            }
            m.setRelatedObject(it);
            m.setTitle(it.title);
            m.setPosition(new GeoPoint(it.lat, it.lng));
            next.put(it.key, m);
        }
        for (Marker old : shown.values()) mv.getOverlays().remove(old);
        shown = next;
        mv.invalidate();
    }

    /** Remove todos os marcadores (switch desligado / onTrimMemory). */
    void clear() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (mv != null && !shown.isEmpty()) {
            for (Marker m : shown.values()) mv.getOverlays().remove(m);
            mv.invalidate();
        }
        shown.clear();
    }

    void destroy() {
        clear();
        enabled = false;
        mv = null;
    }
}
