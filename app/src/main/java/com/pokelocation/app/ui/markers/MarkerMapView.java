package com.pokelocation.app.ui.markers;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.LongSparseArray;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.pokelocation.app.Io;
import com.pokelocation.app.R;
import com.pokelocation.app.data.MarkerDao;
import com.pokelocation.app.data.Prefs;
import com.pokelocation.app.geo.CoordParser;
import com.pokelocation.app.ipc.LiveStatus;
import com.pokelocation.app.map.MapHelper;
import com.pokelocation.app.model.Coord;
import com.pokelocation.app.model.MarkerType;
import com.pokelocation.app.util.Ui;

import org.osmdroid.api.IGeoPoint;
import org.osmdroid.events.MapEventsReceiver;
import org.osmdroid.events.MapListener;
import org.osmdroid.events.ScrollEvent;
import org.osmdroid.events.ZoomEvent;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.MapEventsOverlay;
import org.osmdroid.views.overlay.Marker;

import java.util.List;

/**
 * Editor dos marcadores de um tipo (F81, 05 §3.12 / 03 §3.16). Toque cria (ids ≥ 1e9 pelo DAO), toque no marcador abre
 * Move/Delete + "lat,lng" (tocar copia; substitui a InfoWindow do osmdroid). Carga por viewport (limite 100 se o span
 * passa de 0,025°) com diff por id. O MapView nasce no 1º attach; destroy() é obrigatório.
 */
public final class MarkerMapView extends FrameLayout {
    private final MarkerType type;
    private final Runnable onClose;
    private final Context app;
    private final FrameLayout container;
    private final View actions, moveBar, hint;
    private final TextView coords;

    private MapView mv;
    private Drawable icon;
    private LongSparseArray<Marker> shown = new LongSparseArray<>();
    private Io.Task loadTask;
    private int token;
    private boolean destroyed, moving;
    private Marker selected;
    private GeoPoint moveOrigin;
    private GeoPoint camOrigin;
    private Coord seen;

    public MarkerMapView(Context c, MarkerType type, Runnable onClose) {
        super(c);
        this.type = type;
        this.onClose = onClose;
        app = c.getApplicationContext();
        LayoutInflater.from(c).inflate(R.layout.mk_map_view, this, true);
        container = findViewById(R.id.mk_map_container);
        actions = findViewById(R.id.mk_actions);
        moveBar = findViewById(R.id.mk_move_bar);
        hint = findViewById(R.id.mk_hint);
        coords = findViewById(R.id.mk_coords);
        coords.setOnClickListener(v -> Ui.copy(getContext(), coords.getText(), true));
        findViewById(R.id.mk_move).setOnClickListener(v -> startMove());
        findViewById(R.id.mk_remove).setOnClickListener(v -> deleteSelected());
        findViewById(R.id.mk_move_ok).setOnClickListener(v -> acceptMove());
        findViewById(R.id.mk_move_cancel).setOnClickListener(v -> cancelMove());
    }

    public MarkerType type() { return type; }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (mv == null && !destroyed) createMap();
    }

    private void createMap() {
        try {
            mv = MapHelper.newMapView(getContext());
        } catch (Throwable t) {
            mv = null;
            Ui.toast(getContext(), R.string.mk_map_failed);
            post(onClose);
            return;
        }
        container.addView(mv, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        icon = MapHelper.typeIcon(getContext(), type);
        Coord last = seen = Prefs.lastPosition(getContext());
        if (last != null) {
            mv.getController().setZoom(MapHelper.DEFAULT_ZOOM);
            mv.getController().setCenter(new GeoPoint(last.lat, last.lng));
        } else {
            mv.getController().setZoom(MapHelper.MIN_ZOOM);
        }
        showCurrent();
        // Eventos no índice 0: os marcadores (por cima) recebem o toque primeiro.
        mv.getOverlays().add(0, new MapEventsOverlay(new MapEventsReceiver() {
            @Override public boolean singleTapConfirmedHelper(GeoPoint p) {
                if (moving) return true;
                if (selected != null) {
                    deselect();
                    return true;
                }
                addMarker(p.getLatitude(), p.getLongitude());
                return true;
            }

            @Override public boolean longPressHelper(GeoPoint p) { return false; }
        }));
        mv.addMapListener(new MapListener() {
            @Override public boolean onScroll(ScrollEvent e) { follow(); return false; }

            @Override public boolean onZoom(ZoomEvent e) { follow(); return false; }
        });
        MapHelper.onIdle(mv, this::refresh);
        mv.addOnFirstLayoutListener((v, l, t, r, b) -> refresh());   // bbox só é válida após o 1º layout
    }

    public void onResume() {
        if (mv == null) return;
        mv.onResume();
        showCurrent();
    }

    private void showCurrent() {
        LiveStatus.request(getContext(), c -> {
            if (destroyed || mv == null || c == null || moving || c.sameLatLng(seen)) return;   // no Move o marcador segue a câmera
            seen = c;
            MapHelper.reveal(mv, c);
        });
    }

    public void onPause() { if (mv != null) mv.onPause(); }

    /** Move/seleção consomem o Back antes da Activity. */
    public boolean onBackPressed() {
        if (moving) {
            cancelMove();
            return true;
        }
        if (selected != null) {
            deselect();
            return true;
        }
        return false;
    }

    /** OBRIGATÓRIO ao fechar: cancela tasks, destrói o MapView e solta referências. */
    public void destroy() {
        if (destroyed) return;
        destroyed = true;
        token++;
        if (loadTask != null) loadTask.cancel();
        selected = null;
        shown.clear();
        if (mv != null) {
            MapHelper.destroy(mv);
            container.removeView(mv);
            mv = null;
        }
        icon = null;
    }

    // ------------------------------------------------------------------ viewport

    private void refresh() {
        if (destroyed || mv == null) return;
        BoundingBox b = MapHelper.box(mv);
        final double minLat = b.getLatSouth(), maxLat = b.getLatNorth(), minLng = b.getLonWest(), maxLng = b.getLonEast();
        final int limit = maxLat - minLat > MapHelper.VIEWPORT_SPAN_DEG ? MapHelper.VIEWPORT_LIMIT : 0;
        final int my = ++token;
        final int typeId = type.id;
        if (loadTask != null) loadTask.cancel();
        loadTask = Io.run(() -> MarkerDao.inBox(app, typeId, minLat, maxLat, minLng, maxLng, limit),
                new Io.Callback<List<com.pokelocation.app.model.Marker>>() {
                    @Override public void onResult(List<com.pokelocation.app.model.Marker> list) {
                        if (my == token && !destroyed && mv != null) apply(list);
                    }
                });
    }

    /** Diff por id: reaproveita os marcadores que continuam visíveis; o selecionado nunca sai. */
    private void apply(List<com.pokelocation.app.model.Marker> list) {
        LongSparseArray<Marker> next = new LongSparseArray<>(list.size() + 1);
        for (com.pokelocation.app.model.Marker m : list) {
            Marker mk = shown.get(m.id);
            if (mk == null) mk = addToMap(m);
            else shown.remove(m.id);
            next.put(m.id, mk);
        }
        for (int i = 0; i < shown.size(); i++) {
            Marker mk = shown.valueAt(i);
            if (mk == selected) next.put(shown.keyAt(i), mk);
            else mv.getOverlays().remove(mk);
        }
        shown = next;
        mv.invalidate();
    }

    private Marker addToMap(com.pokelocation.app.model.Marker m) {
        Marker mk = new Marker(mv);
        mk.setPosition(new GeoPoint(m.lat, m.lng));
        mk.setIcon(icon);
        mk.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
        mk.setInfoWindow(null);
        mk.setRelatedObject(m);
        mk.setOnMarkerClickListener((marker, map) -> {
            if (!moving) select(marker);
            return true;
        });
        mv.getOverlays().add(mk);
        return mk;
    }

    // ------------------------------------------------------------------ edição

    private void addMarker(double lat, double lng) {
        final int typeId = type.id;
        Io.run(() -> MarkerDao.insert(app, typeId, null, lat, lng, Double.NaN),
                new Io.Callback<com.pokelocation.app.model.Marker>() {
                    @Override public void onResult(com.pokelocation.app.model.Marker m) {
                        if (destroyed || mv == null || m == null || shown.get(m.id) != null) return;
                        shown.put(m.id, addToMap(m));
                        mv.invalidate();
                    }

                    @Override public void onError(Throwable t) {
                        if (!destroyed) Ui.toast(getContext(), R.string.unknown_error);
                    }
                });
    }

    private void select(Marker mk) {
        selected = mk;
        GeoPoint p = mk.getPosition();
        coords.setText(CoordParser.snippet(p.getLatitude(), p.getLongitude()));
        actions.setVisibility(VISIBLE);
        hint.setVisibility(GONE);
    }

    private void deselect() {
        selected = null;
        actions.setVisibility(GONE);
        moveBar.setVisibility(GONE);
        hint.setVisibility(VISIBLE);
    }

    private void startMove() {
        if (selected == null || mv == null) return;
        moving = true;
        moveOrigin = selected.getPosition().clone();
        IGeoPoint c = mv.getMapCenter();
        camOrigin = new GeoPoint(c.getLatitude(), c.getLongitude());
        mv.getController().setCenter(moveOrigin);
        actions.setVisibility(GONE);
        moveBar.setVisibility(VISIBLE);
    }

    /** Durante o Move o marcador segue o centro da câmera. */
    private void follow() {
        if (!moving || selected == null || mv == null) return;
        IGeoPoint c = mv.getMapCenter();
        selected.setPosition(new GeoPoint(c.getLatitude(), c.getLongitude()));
    }

    private void acceptMove() {
        if (selected == null) return;
        Object rel = selected.getRelatedObject();
        GeoPoint p = selected.getPosition();
        moving = false;
        deselect();
        if (!(rel instanceof com.pokelocation.app.model.Marker)) return;
        final long id = ((com.pokelocation.app.model.Marker) rel).id;
        final double lat = p.getLatitude(), lng = p.getLongitude();
        Io.run(() -> MarkerDao.move(app, id, lat, lng));
    }

    private void cancelMove() {
        if (selected != null && moveOrigin != null) selected.setPosition(moveOrigin);
        if (mv != null && camOrigin != null) mv.getController().setCenter(camOrigin);
        moving = false;
        deselect();
        if (mv != null) mv.invalidate();
    }

    private void deleteSelected() {
        Marker mk = selected;
        if (mk == null || mv == null) return;
        Object rel = mk.getRelatedObject();
        deselect();
        if (!(rel instanceof com.pokelocation.app.model.Marker)) return;
        final long id = ((com.pokelocation.app.model.Marker) rel).id;
        token++;                         // descarta viewport em voo (não ressuscita o apagado)
        shown.remove(id);
        mv.getOverlays().remove(mk);
        mv.invalidate();
        Io.run(() -> MarkerDao.delete(app, id));
    }
}
