package com.pokelocation.app.ui.map;

import android.content.Context;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;

import com.pokelocation.app.Io;
import com.pokelocation.app.R;
import com.pokelocation.app.data.FavoriteDao;
import com.pokelocation.app.data.MarkerDao;
import com.pokelocation.app.data.Prefs;
import com.pokelocation.app.data.RouteDao;
import com.pokelocation.app.engine.MockEngine;
import com.pokelocation.app.geo.CoordParser;
import com.pokelocation.app.geo.Cooldown;
import com.pokelocation.app.geo.Geo;
import com.pokelocation.app.map.MapHelper;
import com.pokelocation.app.model.Coord;
import com.pokelocation.app.model.Favorite;
import com.pokelocation.app.model.Folder;
import com.pokelocation.app.model.MapOptions;
import com.pokelocation.app.model.MarkerType;
import com.pokelocation.app.model.Route;
import com.pokelocation.app.net.Elevation;
import com.pokelocation.app.social.Session;
import com.pokelocation.app.social.ui.FriendLayer;
import com.pokelocation.app.ui.panel.ItemListPanel;
import com.pokelocation.app.ui.panel.NameFolderPanel;
import com.pokelocation.app.ui.panel.PanelHost;
import com.pokelocation.app.ui.panel.SelectPanel;
import com.pokelocation.app.ui.panel.TeleportPanel;
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
import org.osmdroid.views.overlay.Polyline;
import org.osmdroid.views.overlay.infowindow.MarkerInfoWindow;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Editor/visualizador de rota sobre osmdroid (PLAN §3.11). Só widgets do framework: roda no main e no :map.
 * O MapView nasce no 1º attach e morre no destroy(); setters antes disso só guardam estado.
 */
public class RouteMapView extends FrameLayout {
    public static final int MODE_MAIN = 0;
    public static final int MODE_EDIT_ROUTE = 1;
    public static final int MODE_IMPORT = 2;
    public static final int MODE_PICK_POINT = 3;
    public static final int MODE_FLOATING = 4;

    public interface Host {
        default void onTeleportClick(Coord target) {}
        default void onStartRouteClick(double[] pts) {}
        default void onResumeRouteClick(double[] pts, int nextIndex, boolean reversed) {}
        default void onStopClick() {}
        default void onRecordClick(Coord start) {}
        default void onResumeRecordClick(double[] pts) {}
        default void onRecordSaved(Route saved) {}
        default void onRouteSaved(Route saved) {}
        default void onPointPicked(Coord point) {}
        default void onCloseClick() {}
        default void onMapFailed() {}
        default void onOptionsChanged(MapOptions o) {}
    }

    private static final double FOCUS_ZOOM = MapHelper.DEFAULT_ZOOM;
    private static final Object CURRENT_TAG = new Object();
    private static final Host NO_HOST = new Host() {};

    private final int mode;
    private Host host;
    private final MapOptions options;
    private final RouteModel model = new RouteModel();
    private final boolean distanceEnabled;
    private Coord current;
    private boolean routeOn, reversed, running, recording, saveMode, teleportWalk;
    private long lastMockedTime;
    private Route editRoute;
    private String targetFolder;
    private boolean destroyed, mapTried, resumed = true, attached, centerOnStart;

    // mapa
    private MapView mv;
    private Polyline routeLine, loopLine, walkLine;
    private Marker startMarker, currentMarker, nextOnlyMarker;
    private final ArrayList<Marker> wpMarkers = new ArrayList<>();
    private MarkerLayer layer;
    private FriendLayer friends;   // null sem login: nada social carregado
    private MapPoiLayer pogo;      // null com a camada desligada: nada da PoGo carregado (ver PoiLayers)
    private final ArrayList<GeoPoint> linePts = new ArrayList<>();
    private boolean lineSimplified;

    // seleção / Move
    private int selIndex = -1;
    private MarkerLayer.Item selItem;
    private Marker selMarker;
    private boolean moving;
    private double moveLat, moveLng;

    // views
    private final View mapContainer, closeBtn, routeBox, routeOptions, routeOptionsBody, actions, movePanel;
    private final TextView optionsTitle, btnStartRoute, btnGenerate, btnLoad, btnSave, btnClear, btnSetRoute, btnSetLocation,
            btnRecord, btnStart, btnTeleport, distance, actTitle, actCoords, actMove, actCopy, actDelete, actAddMarker;
    private final Switch swMarkers, swPogo;
    private final View pogoBox;

    private Io.Task elevTask, loadTask, countTask;   // só leituras (canceláveis)
    private Object panel;   // PanelHost / ItemListPanel / TeleportPanel aberto por este mapa

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            updateDistance();
            if (attached && !destroyed) postDelayed(this, 1000);
        }
    };

    private final Marker.OnMarkerClickListener routeClick = (m, map) -> {
        if (moving) return true;
        Object tag = m.getRelatedObject();
        if (tag instanceof Integer) selectPoint((Integer) tag, m);
        else if (tag == CURRENT_TAG && current != null)
            selectItem(new MarkerLayer.Item("current", str(R.string.map_current_location), current.lat, current.lng, current.alt, null));
        return true;
    };

    private final Marker.OnMarkerClickListener layerClick = (m, map) -> {
        if (!moving && m.getRelatedObject() instanceof MarkerLayer.Item) selectItem((MarkerLayer.Item) m.getRelatedObject());
        return true;
    };

    private final MapEventsReceiver taps = new MapEventsReceiver() {
        @Override public boolean singleTapConfirmedHelper(GeoPoint p) {
            if (moving) return true;
            hideActions();
            if (routeOn && mode != MODE_PICK_POINT) {
                boolean wasEmpty = !model.hasStart();
                model.add(p.getLatitude(), p.getLongitude(), Double.NaN);
                if (wasEmpty) fetchStartElevation();
                onPointAppended();
            } else {
                moveStart(p.getLatitude(), p.getLongitude(), Double.NaN);
            }
            return true;
        }

        @Override public boolean longPressHelper(GeoPoint p) {
            if (moving || !model.hasStart()) return false;
            int last = model.size() - 1;
            focus(last);
            selectPoint(last, markerFor(last));
            return true;
        }
    };

    private final MapListener scroll = new MapListener() {
        @Override public boolean onScroll(ScrollEvent e) {
            if (moving && mv != null && selMarker != null) {
                IGeoPoint c = mv.getMapCenter();
                model.move(selIndex, c.getLatitude(), c.getLongitude());
                selMarker.setPosition(new GeoPoint(c.getLatitude(), c.getLongitude()));
                movedPointLine(selIndex);
            }
            return false;
        }

        @Override public boolean onZoom(ZoomEvent e) { return false; }
    };

    public RouteMapView(Context c, int mode, Host host, MapOptions options) {
        super(c);
        this.mode = mode;
        this.host = host != null ? host : NO_HOST;
        this.options = options != null ? options : MapOptions.load(c);
        teleportWalk = Prefs.getBool(c, Prefs.TELEPORT_WALK_MODE);
        lastMockedTime = Prefs.getLong(c, Prefs.LAST_MOCKED_TIME);
        distanceEnabled = Prefs.getBool(c, Prefs.DISPLAY_DISTANCE_INFO);
        routeOn = mode == MODE_EDIT_ROUTE || mode == MODE_IMPORT;

        LayoutInflater.from(c).inflate(R.layout.map_route_view, this, true);
        mapContainer = findViewById(R.id.map_container);
        closeBtn = findViewById(R.id.map_close);
        routeBox = findViewById(R.id.map_route_box);
        routeOptions = findViewById(R.id.map_route_options);
        routeOptionsBody = findViewById(R.id.map_route_options_body);
        optionsTitle = findViewById(R.id.map_route_options_title);
        btnStartRoute = findViewById(R.id.map_btn_start_route);
        btnGenerate = findViewById(R.id.map_btn_generate);
        btnLoad = findViewById(R.id.map_btn_load);
        btnSave = findViewById(R.id.map_btn_save);
        btnClear = findViewById(R.id.map_btn_clear);
        btnSetRoute = findViewById(R.id.map_btn_set_route);
        btnSetLocation = findViewById(R.id.map_btn_set_location);
        btnRecord = findViewById(R.id.map_btn_record);
        btnStart = findViewById(R.id.map_btn_start);
        btnTeleport = findViewById(R.id.map_btn_teleport);
        swMarkers = findViewById(R.id.map_sw_markers);
        swPogo = findViewById(R.id.map_sw_pogo);
        pogoBox = findViewById(R.id.map_pogo_box);
        distance = findViewById(R.id.map_distance);
        actions = findViewById(R.id.map_actions);
        actTitle = findViewById(R.id.map_act_title);
        actCoords = findViewById(R.id.map_act_coords);
        actMove = findViewById(R.id.map_act_move);
        actCopy = findViewById(R.id.map_act_copy);
        actDelete = findViewById(R.id.map_act_delete);
        actAddMarker = findViewById(R.id.map_act_add_marker);
        movePanel = findViewById(R.id.map_move);

        String to = str(R.string.map_teleport_to), loc = str(R.string.map_location);
        SpannableString tele = new SpannableString(to + " " + loc);
        tele.setSpan(new ForegroundColorSpan(c.getColor(R.color.accent)), to.length() + 1, tele.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        btnTeleport.setText(tele);

        bindOptions();
        closeBtn.setOnClickListener(v -> this.host.onCloseClick());
        btnSetRoute.setOnClickListener(v -> {
            routeOn = true;
            updateControls();
            rebuildLines();
        });
        btnSetLocation.setOnClickListener(v -> openTeleport());
        btnRecord.setOnClickListener(v -> onRecordButton());
        btnStartRoute.setOnClickListener(v -> onStartRouteButton());
        btnGenerate.setOnClickListener(v -> openGenerate());
        btnLoad.setOnClickListener(v -> openLoad());
        btnSave.setOnClickListener(v -> saveRoute());
        btnClear.setOnClickListener(v -> clearRoute());
        btnStart.setOnClickListener(v -> onStartButton());
        btnTeleport.setOnClickListener(v -> {
            if (model.hasStart()) this.host.onTeleportClick(startCoord());
            else Ui.toast(getContext(), R.string.map_tap_to_set);
        });
        actCoords.setOnClickListener(v -> Ui.copy(getContext(), actCoords.getText(), true));
        actMove.setOnClickListener(v -> startMove());
        actCopy.setOnClickListener(v -> copyPoint());
        actDelete.setOnClickListener(v -> deletePoint());
        actAddMarker.setOnClickListener(v -> addSelectedItem());
        findViewById(R.id.map_act_save).setOnClickListener(v -> openSave());
        findViewById(R.id.map_act_share).setOnClickListener(v -> Ui.share(getContext(), actCoords.getText().toString()));
        findViewById(R.id.map_move_ok).setOnClickListener(v -> finishMove(true));
        findViewById(R.id.map_move_cancel).setOnClickListener(v -> finishMove(false));
        updateControls();
    }

    // ------------------------------------------------------------------ API pública (§3.11)

    public void setCurrentPosition(Coord c) {
        boolean moved = !c.sameLatLng(current);
        current = c;
        if (mv == null) return;
        syncCurrentMarker();
        rebuildLines();
        updateDistance();
        if (moved && !moving) MapHelper.reveal(mv, c);   // no Move o ponto segue a câmera
    }

    public void setStart(Coord c) {
        if (c == null) return;
        moveStart(c.lat, c.lng, c.alt);
        if (mv != null) mv.getController().animateTo(new GeoPoint(c.lat, c.lng));
    }

    public Coord getStart() { return model.hasStart() ? startCoord() : null; }

    public void loadRoute(double[] pts, int nextIndex) {
        hideActions();
        model.load(pts, nextIndex);
        if (model.hasWaypoints()) routeOn = true;
        onPointsChanged();
        if (mv != null) focus(0);
        else centerOnStart = true;
    }

    public void clearRoute() {
        hideActions();
        model.clearWaypoints();
        if ((mode == MODE_MAIN || mode == MODE_FLOATING) && !recording) routeOn = false;
        onPointsChanged();
    }

    public double[] getPoints() { return model.toArray(); }

    public void setEditRoute(Route r) {
        editRoute = r;
        if (r != null && r.pts != null) loadRoute(r.pts, -1);
    }

    public void setTargetFolder(String folderId) { targetFolder = folderId; }

    public void setRecording(boolean recording, boolean saveMode) {
        this.recording = recording;
        this.saveMode = recording && saveMode;
        if (recording) routeOn = true;
        updateControls();
        if (this.saveMode && mv != null) post(this::saveRoute);
    }

    public void setReversed(boolean reversed) { this.reversed = reversed; }

    public void setRunning(boolean running) {
        this.running = running;
        updateControls();
    }

    public MapOptions getOptions() { return options; }

    /** Aditivo (W2-api.md): o :map recebe o valor da sessão por extra. */
    public void setTeleportWalkMode(boolean walk) {
        teleportWalk = walk;
        rebuildLines();
    }

    /** Aditivo (W2-api.md): last_mocked_time da sessão para a linha de distância no :map. */
    public void setLastMockedTime(long time) {
        lastMockedTime = time;
        updateDistance();
    }

    public boolean onBackPressed() {
        if (moving) {
            finishMove(false);
            return true;
        }
        if (actions.getVisibility() == VISIBLE) {
            hideActions();
            return true;
        }
        return false;
    }

    public void onResume() {
        resumed = true;
        if (mv != null) mv.onResume();
        // Login feito com o mapa já criado.
        if (friends == null && mode == MODE_MAIN && mv != null && Session.loggedIn(getContext())) friends = new FriendLayer(getContext(), mv);
        if (friends != null) friends.resume();
        if (pogo != null) pogo.resume();
    }

    public void onPause() {
        resumed = false;
        if (mv != null) mv.onPause();
        if (friends != null) friends.pause();
        if (pogo != null) pogo.pause();
    }

    /** Os marcadores customizados já são só os do viewport; aqui basta o cache de tiles. */
    public void onTrimMemory(int level) {
        MapHelper.trimMemory(level);
    }

    public void destroy() {
        if (destroyed) return;
        destroyed = true;
        removeCallbacks(ticker);
        cancel(elevTask);
        cancel(loadTask);
        cancel(countTask);
        elevTask = loadTask = countTask = null;
        closePanel();
        if (layer != null) layer.destroy();
        layer = null;
        if (friends != null) friends.destroy();
        friends = null;
        if (pogo != null) pogo.destroy();
        pogo = null;
        if (mv != null) {
            mv.removeMapListener(scroll);
            MapHelper.destroy(mv);
            ((ViewGroup) mapContainer).removeAllViews();
        }
        mv = null;
        routeLine = loopLine = walkLine = null;
        startMarker = currentMarker = nextOnlyMarker = selMarker = null;
        wpMarkers.clear();
        linePts.clear();
        selItem = null;
        editRoute = null;
        host = NO_HOST;
    }

    // ------------------------------------------------------------------ ciclo de vida da view

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        if (!mapTried && !destroyed) {
            mapTried = true;
            post(this::createMap);
        }
        if (distanceEnabled) {
            removeCallbacks(ticker);
            post(ticker);
        }
    }

    @Override protected void onDetachedFromWindow() {
        attached = false;
        removeCallbacks(ticker);
        super.onDetachedFromWindow();
    }

    private void createMap() {
        if (destroyed || mv != null) return;
        Context c = getContext();
        try {
            mv = MapHelper.newMapView(c);
        } catch (Throwable t) {
            mv = null;
            host.onMapFailed();
            return;
        }
        ((ViewGroup) mapContainer).addView(mv, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        mv.getOverlays().add(0, new MapEventsOverlay(taps));
        routeLine = addLine(MapHelper.LINE_ROUTE);
        loopLine = addLine(MapHelper.LINE_LOOP);
        walkLine = addLine(MapHelper.LINE_WALK);
        layer = new MarkerLayer(c, mv, layerClick);
        mv.addMapListener(scroll);
        MapHelper.onIdle(mv, () -> {
            if (layer != null) layer.refresh();
            if (pogo != null) pogo.boxChanged();
        });
        if (mode == MODE_MAIN && Session.loggedIn(c)) {
            friends = new FriendLayer(c, mv);
            if (resumed) friends.resume();
        }
        if (Prefs.pogoLayer(c)) setPogo(true);
        if (!model.hasStart()) {
            Coord s = current != null ? current : Prefs.lastPosition(c);
            if (s != null) model.setStart(s.lat, s.lng, s.alt);
        }
        if (model.hasStart()) mv.getController().setCenter(new GeoPoint(model.lat(0), model.lng(0)));
        else if (current != null) mv.getController().setCenter(new GeoPoint(current.lat, current.lng));
        mv.getController().setZoom(model.hasStart() || current != null ? FOCUS_ZOOM : MapHelper.MIN_ZOOM);
        syncCurrentMarker();
        onPointsChanged();
        if (resumed) mv.onResume();
        mv.addOnFirstLayoutListener((v, l, t, r, b) -> {
            if (destroyed || mv == null) return;
            initialCamera();
            if (swMarkers.isChecked()) layer.setEnabled(true);
            if (pogo != null) pogo.boxChanged();
        });
        final Context app = c.getApplicationContext();
        countTask = Io.run(() -> FavoriteDao.countAll(app) + MarkerDao.totalMarkers(app), new Io.Callback<Integer>() {
            @Override public void onResult(Integer n) { swMarkers.setEnabled(n > 0); }
        });
        if (saveMode) post(this::saveRoute);
    }

    /** MapHelper.line já desliga InfoWindow e deixa o toque cair no mapa. Linhas antes dos pinos = por baixo. */
    private Polyline addLine(int kind) {
        Polyline l = MapHelper.line(mv, kind);
        mv.getOverlays().add(l);
        return l;
    }

    /** 03 §3.5: edição → zoom 17 no início; senão enquadra atual+início se 250 m < d < 5000 km. */
    private void initialCamera() {
        if (!model.hasStart()) return;
        GeoPoint s = new GeoPoint(model.lat(0), model.lng(0));
        boolean edit = mode == MODE_EDIT_ROUTE || mode == MODE_IMPORT || mode == MODE_PICK_POINT;
        if (!edit && !centerOnStart && current != null) {
            double d = Geo.distance(current.lat, current.lng, s.getLatitude(), s.getLongitude());
            if (d > 250 && d < 5_000_000) {
                BoundingBox box = BoundingBox.fromGeoPoints(Arrays.asList(new GeoPoint(current.lat, current.lng), s));
                mv.zoomToBoundingBox(box, false, Ui.dp(getContext(), 50));
                return;
            }
        }
        mv.getController().setZoom(FOCUS_ZOOM);
        mv.getController().setCenter(s);
    }

    // ------------------------------------------------------------------ desenho

    private void onPointsChanged() {
        rebuildMarkers();
        rebuildLines();
        updateControls();
    }

    /** Add no fim: anexa só o marcador e o ponto da linha; senão recria. */
    private void onPointAppended() {
        int n = model.size();
        if (mv == null || n < 2 || n - 1 > MapHelper.NUMBERED_MARKERS_MAX || wpMarkers.size() != n - 2 || startMarker == null) {
            onPointsChanged();
            return;
        }
        int i = n - 1;
        Marker m = newMarker(MapHelper.PIN_WAYPOINT, String.valueOf(i), i);
        m.setPosition(new GeoPoint(model.lat(i), model.lng(i)));
        wpMarkers.add(m);
        if (n > MapHelper.DP_THRESHOLD || linePts.size() != n - 1) {
            rebuildLines();
        } else {
            double lat = model.lat(i), lng = model.lng(i);
            GeoPoint prev = linePts.get(i - 1);
            if (lat == prev.getLatitude() && lng == prev.getLongitude()) lat += 1e-7;
            linePts.add(new GeoPoint(lat, lng));
            routeLine.setPoints(linePts);
            rebuildAuxLines();
        }
        updateControls();
    }

    /** Delete: tira só o marcador i e renumera os seguintes; a linha é recriada (lista reaproveitada). */
    private void onPointDeleted(int i) {
        if (mv == null || i < 1 || i - 1 >= wpMarkers.size() || wpMarkers.size() != model.size()) {
            onPointsChanged();
            return;
        }
        mv.getOverlays().remove(wpMarkers.remove(i - 1));
        for (int j = i - 1; j < wpMarkers.size(); j++) {
            Marker m = wpMarkers.get(j);
            m.setRelatedObject(j + 1);
            m.setTitle(String.valueOf(j + 1));
        }
        rebuildLines();
        updateControls();
    }

    private void syncCurrentMarker() {
        if (mv == null) return;
        boolean show = current != null && mode != MODE_EDIT_ROUTE && mode != MODE_IMPORT && mode != MODE_PICK_POINT;
        if (!show) {
            if (currentMarker != null) mv.getOverlays().remove(currentMarker);
            currentMarker = null;
        } else {
            if (currentMarker == null) currentMarker = newMarker(MapHelper.PIN_CURRENT, str(R.string.map_current_location), CURRENT_TAG);
            currentMarker.setPosition(new GeoPoint(current.lat, current.lng));
        }
        mv.invalidate();
    }

    /** Recria início + waypoints (n ≤ 200 numerados; acima disso só o próximo). Chamado só em mudança estrutural. */
    private void rebuildMarkers() {
        if (mv == null) return;
        List<org.osmdroid.views.overlay.Overlay> ov = mv.getOverlays();
        for (Marker m : wpMarkers) ov.remove(m);
        wpMarkers.clear();
        if (nextOnlyMarker != null) ov.remove(nextOnlyMarker);
        nextOnlyMarker = null;
        int n = model.size();
        if (n == 0) {
            if (startMarker != null) ov.remove(startMarker);
            startMarker = null;
        } else {
            if (startMarker == null) startMarker = newMarker(MapHelper.PIN_START, str(R.string.map_start_location), 0);
            startMarker.setIcon(MapHelper.pin(getContext(), model.next == 0 ? MapHelper.PIN_NEXT : MapHelper.PIN_START));
            startMarker.setPosition(new GeoPoint(model.lat(0), model.lng(0)));
            if (n - 1 <= MapHelper.NUMBERED_MARKERS_MAX) {
                for (int i = 1; i < n; i++) {
                    Marker m = newMarker(i == model.next ? MapHelper.PIN_NEXT : MapHelper.PIN_WAYPOINT, String.valueOf(i), i);
                    m.setPosition(new GeoPoint(model.lat(i), model.lng(i)));
                    wpMarkers.add(m);
                }
            } else if (model.next >= 1) {
                nextOnlyMarker = newMarker(MapHelper.PIN_NEXT, String.valueOf(model.next), model.next);
                nextOnlyMarker.setPosition(new GeoPoint(model.lat(model.next), model.lng(model.next)));
            }
        }
        mv.invalidate();
    }

    private Marker newMarker(int pin, String title, Object tag) {
        Marker m = new Marker(mv);
        m.setInfoWindow((MarkerInfoWindow) null);
        m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
        m.setIcon(MapHelper.pin(getContext(), pin));
        m.setTitle(title);
        m.setRelatedObject(tag);
        m.setOnMarkerClickListener(routeClick);
        mv.getOverlays().add(m);
        return m;
    }

    /** Uma Polyline de rota (DP acima de 2000, só no desenho) + loop + walk, sempre reaproveitadas. */
    private void rebuildLines() {
        if (mv == null) return;
        int n = model.size();
        ArrayList<GeoPoint> pts = linePts;
        pts.clear();
        lineSimplified = n > MapHelper.DP_THRESHOLD;
        if (n > 1) {
            double[] src = n > MapHelper.DP_THRESHOLD ? MapHelper.forDrawing(model.toArray()) : null;
            int m = src != null ? src.length / 3 : n;
            double pl = Double.NaN, pg = Double.NaN;
            for (int i = 0; i < m; i++) {
                double lat = src != null ? src[i * 3] : model.lat(i), lng = src != null ? src[i * 3 + 1] : model.lng(i);
                if (lat == pl && lng == pg) lat += 1e-7;   // osmdroid ignora segmento de comprimento zero
                pts.add(new GeoPoint(lat, lng));
                pl = lat;
                pg = lng;
            }
        }
        routeLine.setPoints(pts);
        rebuildAuxLines();
    }

    /** Move: só o ponto i muda; sem DP atualiza o GeoPoint na lista reaproveitada. */
    private void movedPointLine(int i) {
        if (lineSimplified || linePts.size() != model.size()) {
            rebuildLines();
            return;
        }
        linePts.get(i).setCoords(model.lat(i), model.lng(i));
        routeLine.setPoints(linePts);
        rebuildAuxLines();
    }

    /** Loop (só em Loop) e walk (03 §3.5): segmentos de 2 pontos nas mesmas Polylines. */
    private void rebuildAuxLines() {
        int n = model.size();
        boolean loop = options.routeMode == MockEngine.ROUTE_LOOP && n > 1;
        loopLine.setEnabled(loop);
        if (loop) loopLine.setPoints(segment(model.lat(n - 1), model.lng(n - 1), model.lat(0), model.lng(0)));

        boolean walk = current != null && model.hasStart() && mode != MODE_PICK_POINT
                && RouteModel.showWalkLine(routeOn, teleportWalk, model.next >= 0, options.routeStartMode);
        walkLine.setEnabled(walk);
        if (walk) {
            int t = model.next >= 0 ? model.next : 0;
            walkLine.setPoints(segment(current.lat, current.lng, model.lat(t), model.lng(t)));
        }
        mv.invalidate();
    }

    /** Segmento de 2 pontos; pontos idênticos ganham +1e-7 na latitude (só no desenho). */
    private static List<GeoPoint> segment(double lat1, double lng1, double lat2, double lng2) {
        if (lat1 == lat2 && lng1 == lng2) lat2 += 1e-7;
        return Arrays.asList(new GeoPoint(lat1, lng1), new GeoPoint(lat2, lng2));
    }

    private void updateControls() {
        boolean mainLike = mode == MODE_MAIN || mode == MODE_FLOATING;
        boolean floating = mode == MODE_FLOATING;
        closeBtn.setVisibility(floating ? VISIBLE : GONE);
        vis(btnSetRoute, mainLike && !routeOn && !recording);
        vis(btnSetLocation, mainLike && !recording);
        vis(btnRecord, mainLike && (!routeOn || recording));
        btnRecord.setText(recording ? R.string.map_resume_record : R.string.map_record_route);
        vis(routeBox, routeOn || recording);
        vis(routeOptions, mainLike && !recording);
        vis(btnStartRoute, floating && !recording);
        btnStartRoute.setText(model.next >= 0 ? R.string.map_resume_route : R.string.map_start_route);
        vis(btnGenerate, !recording);
        vis(btnLoad, !recording);
        vis(btnSave, model.hasWaypoints());
        vis(btnClear, model.hasWaypoints());
        vis(btnStart, mode == MODE_MAIN || mode == MODE_PICK_POINT);
        btnStart.setText(mode == MODE_PICK_POINT ? R.string.select : running ? R.string.map_stop : R.string.map_start);
        vis(btnTeleport, floating && !recording);
        int top = Ui.dp(getContext(), floating ? 50 : 10);
        setTopMargin(actions, top);
        setTopMargin(movePanel, top);
    }

    private void updateDistance() {
        boolean show = distanceEnabled && lastMockedTime != 0 && current != null && model.hasStart();
        if (!show) {
            distance.setVisibility(GONE);
            return;
        }
        Context c = getContext();
        double m = Geo.distance(current.lat, current.lng, model.lat(0), model.lng(0));
        long now = System.currentTimeMillis();
        distance.setText(Cooldown.line(c, m, lastMockedTime, now));
        distance.setBackgroundColor(Cooldown.color(c, m, lastMockedTime, now));
        distance.setVisibility(VISIBLE);
    }

    // ------------------------------------------------------------------ botões

    private void bindOptions() {
        RadioGroup rgMode = findViewById(R.id.map_rg_mode), rgStart = findViewById(R.id.map_rg_start);
        rgMode.check(options.routeMode == MockEngine.ROUTE_LOOP ? R.id.map_rb_loop
                : options.routeMode == MockEngine.ROUTE_REVERSE ? R.id.map_rb_reverse : R.id.map_rb_stop);
        rgStart.check(options.routeStartMode == 1 ? R.id.map_rb_walk : R.id.map_rb_teleport);
        rgMode.setOnCheckedChangeListener((g, id) -> {
            options.routeMode = id == R.id.map_rb_loop ? MockEngine.ROUTE_LOOP : id == R.id.map_rb_reverse ? MockEngine.ROUTE_REVERSE : MockEngine.ROUTE_STOP;
            optionsChanged();
            rebuildLines();
        });
        rgStart.setOnCheckedChangeListener((g, id) -> {
            options.routeStartMode = id == R.id.map_rb_walk ? 1 : 0;
            optionsChanged();
            rebuildLines();
        });
        showOptionsBody(options.routeOptionsVisible);
        optionsTitle.setOnClickListener(v -> {
            options.routeOptionsVisible = !options.routeOptionsVisible;
            showOptionsBody(options.routeOptionsVisible);
            optionsChanged();
        });
        swMarkers.setChecked(options.customMarkersEnabled);
        swMarkers.setOnCheckedChangeListener((b, on) -> {
            options.customMarkersEnabled = on;
            optionsChanged();
            if (layer != null) layer.setEnabled(on);
        });
        vis(swPogo, mode == MODE_MAIN || mode == MODE_FLOATING);
        swPogo.setChecked(Prefs.pogoLayer(getContext()));
        swPogo.setOnCheckedChangeListener((b, on) -> {
            Prefs.setPogoLayer(getContext(), on);
            setPogo(on);
        });
    }

    /** Botão do card, só depois do /reveal: no main "Definir destino" = toque no mapa na coordenada exata; no :map teleporta. */
    private void setPogo(boolean on) {
        if (on && pogo == null && mv != null && (mode == MODE_MAIN || mode == MODE_FLOATING)) {
            boolean floating = mode == MODE_FLOATING;
            pogo = PoiLayers.pogo(getContext(), mv, this, pogoBox, floating ? R.string.teleport : R.string.pogo_card_set_dest, (lat, lng) -> {
                if (destroyed || moving) return;
                if (floating) {
                    goTo(lat, lng, Double.NaN, false);
                    return;
                }
                taps.singleTapConfirmedHelper(new GeoPoint(lat, lng));
                if (mv != null) mv.getController().animateTo(new GeoPoint(lat, lng));
            });
            if (resumed) pogo.resume();
        } else if (!on && pogo != null) {
            pogo.destroy();
            pogo = null;
        }
    }

    private void showOptionsBody(boolean show) {
        routeOptionsBody.setVisibility(show ? VISIBLE : GONE);
        optionsTitle.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, show ? R.drawable.ic_expand_less : R.drawable.ic_expand_more, 0);
    }

    private void optionsChanged() { host.onOptionsChanged(options); }

    /** 03 §3.5 onStartClick. */
    private void onStartButton() {
        if (running && mode == MODE_MAIN) {
            host.onStopClick();
            return;
        }
        if (!model.hasStart()) {
            Ui.toast(getContext(), R.string.map_tap_to_set);
            return;
        }
        switch (RouteModel.startAction(mode == MODE_PICK_POINT, model.hasWaypoints(), teleportWalk, routeOn, options.routeStartMode)) {
            case RouteModel.START_PICK: host.onPointPicked(startCoord()); break;
            case RouteModel.START_ROUTE: host.onStartRouteClick(model.toArray()); break;
            case RouteModel.START_TELEPORT: host.onTeleportClick(startCoord()); break;
            default: walkTo(startCoord());
        }
    }

    /** Anda da posição atual até o alvo (rota de 2 pontos); sem posição atual, teleporta. */
    private void walkTo(Coord target) {
        if (current == null) host.onTeleportClick(target);
        else host.onStartRouteClick(new double[]{current.lat, current.lng, current.alt, target.lat, target.lng, target.alt});
    }

    private void onStartRouteButton() {
        if (!model.hasWaypoints()) {
            Ui.toast(getContext(), R.string.map_tap_to_set);
            return;
        }
        if (model.next >= 0) host.onResumeRouteClick(model.toArray(), model.next, reversed);
        else host.onStartRouteClick(model.toArray());
    }

    private void onRecordButton() {
        if (recording) host.onResumeRecordClick(model.toArray());
        else if (model.hasStart()) host.onRecordClick(startCoord());
        else Ui.toast(getContext(), R.string.map_tap_to_set);
    }

    private void openTeleport() {
        closePanel();
        TeleportPanel.Args a = new TeleportPanel.Args();
        a.current = current;
        a.text = model.hasStart() ? CoordParser.text(model.lat(0), model.lng(0)) : "";
        a.walkMode = teleportWalk;
        a.showBack = false;
        a.canGoBack = false;
        a.showFavorites = true;
        a.showDistance = current != null;
        a.lastMockedTime = lastMockedTime;
        panel = TeleportPanel.show(getContext(), a, new TeleportPanel.Callback() {
            @Override public void onGo(double lat, double lng, boolean walk) {
                closePanel();
                goTo(lat, lng, Double.NaN, walk);
            }

            @Override public void onFavorites() {
                closePanel();
                openFavorites();
            }

            @Override public void onWalkModeChanged(boolean walk) {
                teleportWalk = walk;
                rebuildLines();
            }
        });
    }

    /** Set Location / favorito: posiciona o início e dispara teleporte (ou walk). */
    private void goTo(double lat, double lng, double alt, boolean walk) {
        if (destroyed) return;
        teleportWalk = walk;
        moveStart(lat, lng, alt);
        if (mv != null) mv.getController().animateTo(new GeoPoint(lat, lng));
        if (walk) walkTo(startCoord());
        else host.onTeleportClick(startCoord());
    }

    private void openFavorites() {
        final Context app = getContext().getApplicationContext();
        panel = ItemListPanel.show(getContext(), str(R.string.favorites), Folder.FAVORITES, folder -> {
            List<Favorite> list = ItemListPanel.ALL.equals(folder) ? FavoriteDao.listAll(app) : FavoriteDao.list(app, folder);
            ArrayList<ItemListPanel.Item> out = new ArrayList<>(list.size());
            for (Favorite f : list)
                out.add(new ItemListPanel.Item(f, f.name, f.address.isEmpty() ? CoordParser.snippet(f.lat, f.lng) : f.address));
            return out;
        }, tag -> {
            closePanel();
            Favorite f = (Favorite) tag;
            goTo(f.lat, f.lng, f.alt, teleportWalk);
        }, null, null);
    }

    private void openGenerate() {
        closePanel();
        panel = GenerateRoutePanel.show(getContext(), options, (genMode, count, offset) -> {
            options.generateMode = genMode;
            options.generateCount = count;
            options.generateOffset = offset;
            optionsChanged();
            Coord o = current != null ? current : getStart();
            if (o == null) {
                Ui.toast(getContext(), R.string.map_tap_to_set);
                return;
            }
            loadRoute(Geo.generate(o.lat, o.lng, genMode, count, offset), -1);   // 04 §3.2: 1º gerado vira o início
        });
    }

    private void openLoad() {
        closePanel();
        final Context app = getContext().getApplicationContext();
        panel = ItemListPanel.show(getContext(), str(R.string.map_routes), Folder.ROUTES, folder -> {
            List<Route> list = ItemListPanel.ALL.equals(folder) ? RouteDao.listAll(app) : RouteDao.list(app, folder);
            ArrayList<ItemListPanel.Item> out = new ArrayList<>(list.size());
            for (Route r : list)
                out.add(new ItemListPanel.Item(r.id, r.name, app.getResources().getQuantityString(R.plurals.map_coordinates, r.count, r.count)));
            return out;
        }, tag -> {
            closePanel();
            final String id = (String) tag;
            cancel(loadTask);
            loadTask = Io.run(() -> RouteDao.get(app, id), new Io.Callback<Route>() {
                @Override public void onResult(Route r) {
                    if (r != null && r.pts != null) loadRoute(r.pts, -1);
                }
            });
        }, null, null);
    }

    private void saveRoute() {
        if (destroyed) return;
        if (!model.hasWaypoints()) {
            Ui.toast(getContext(), R.string.map_tap_to_set);
            return;
        }
        closePanel();
        final double[] pts = model.toArray();
        final Route edit = editRoute;
        final boolean rec = recording;
        final Context app = getContext().getApplicationContext();
        panel = NameFolderPanel.show(getContext(), str(R.string.map_save_route), edit != null ? edit.name : "",
                edit != null ? null : Folder.ROUTES, targetFolder, (name, folder) -> {
                    Io.run(() -> {   // escrita: nunca cancelável (destroy não pode perder a rota)
                        Route r;
                        if (edit != null) {
                            r = new Route(edit.id, name, edit.folderId, edit.sort, Route.count(pts), pts);
                            RouteDao.update(app, r);
                        } else {
                            r = RouteDao.insert(app, name, folder, pts);
                        }
                        return r;
                    }, new Io.Callback<Route>() {
                        @Override public void onResult(Route r) {
                            Ui.toast(app, R.string.map_route_saved);
                            if (destroyed) return;
                            if (edit != null) editRoute = r;
                            if (rec) host.onRecordSaved(r);
                            else host.onRouteSaved(r);
                        }

                        @Override public void onError(Throwable t) { Ui.toast(app, R.string.map_route_save_failed); }
                    });
                });
    }

    // ------------------------------------------------------------------ seleção e ações do marcador

    private Marker markerFor(int i) {
        if (i == 0) return startMarker;
        if (i - 1 < wpMarkers.size()) return wpMarkers.get(i - 1);
        return i == model.next ? nextOnlyMarker : null;
    }

    private void selectPoint(int i, Marker m) {
        if (i < 0 || i >= model.size()) return;
        selIndex = i;
        selItem = null;
        selMarker = m;
        actTitle.setText(i == 0 ? str(R.string.map_start_location) : String.valueOf(i));
        actCoords.setText(CoordParser.snippet(model.lat(i), model.lng(i)));
        vis(actMove, m != null);
        vis(actCopy, true);
        vis(actDelete, true);
        vis(actAddMarker, false);
        enable(actCopy, i >= 1);
        enable(actDelete, model.canDelete(i));
        movePanel.setVisibility(GONE);
        actions.setVisibility(VISIBLE);
    }

    private void selectItem(MarkerLayer.Item it) {
        selIndex = -1;
        selItem = it;
        selMarker = null;
        actTitle.setText(it.title);
        actCoords.setText(CoordParser.snippet(it.lat, it.lng));
        vis(actMove, false);
        vis(actCopy, false);
        vis(actDelete, false);
        vis(actAddMarker, true);
        movePanel.setVisibility(GONE);
        actions.setVisibility(VISIBLE);
    }

    private void hideActions() {
        actions.setVisibility(GONE);
        selIndex = -1;
        selItem = null;
        selMarker = null;
    }

    private void focus(int i) {
        if (mv == null || i < 0 || i >= model.size()) return;
        mv.getController().animateTo(new GeoPoint(model.lat(i), model.lng(i)), FOCUS_ZOOM, null);
    }

    private void startMove() {
        if (selIndex < 0 || selMarker == null || mv == null) return;
        moving = true;
        if (pogo != null) pogo.setTapsEnabled(false);
        moveLat = model.lat(selIndex);
        moveLng = model.lng(selIndex);
        selMarker.setIcon(MapHelper.pin(getContext(), MapHelper.PIN_MOVING));
        actions.setVisibility(GONE);
        movePanel.setVisibility(VISIBLE);
        mv.getController().setCenter(new GeoPoint(moveLat, moveLng));
        mv.invalidate();
    }

    private void finishMove(boolean ok) {
        if (!moving) return;
        moving = false;
        if (pogo != null) pogo.setTapsEnabled(true);
        int i = selIndex;
        movePanel.setVisibility(GONE);
        if (ok) {
            model.setAlt(i, Double.NaN);
            if (i == 0) fetchStartElevation();
        } else {
            model.move(i, moveLat, moveLng);
            if (mv != null) mv.getController().setCenter(new GeoPoint(moveLat, moveLng));
        }
        onPointsChanged();
        selectPoint(i, markerFor(i));
    }

    private void copyPoint() {
        int j = model.insertMid(selIndex);
        if (j < 0) return;
        onPointsChanged();
        focus(j);
        selectPoint(j, markerFor(j));
    }

    private void deletePoint() {
        int i = selIndex;
        if (!model.delete(i)) return;
        hideActions();
        onPointDeleted(i);
    }

    /** "Add Marker" num favorito/marcador: vira waypoint em modo rota; senão move o início. */
    private void addSelectedItem() {
        MarkerLayer.Item it = selItem;
        hideActions();
        if (it == null) return;
        if (routeOn && mode != MODE_PICK_POINT) {
            model.add(it.lat, it.lng, it.alt);
            onPointsChanged();
        } else {
            moveStart(it.lat, it.lng, it.alt);
        }
    }

    private void openSave() {
        final double lat, lng, alt;
        final String name;
        if (selItem != null) {
            lat = selItem.lat;
            lng = selItem.lng;
            alt = selItem.alt;
            name = selItem.title;
        } else if (selIndex >= 0 && selIndex < model.size()) {
            lat = model.lat(selIndex);
            lng = model.lng(selIndex);
            alt = model.alt(selIndex);
            name = CoordParser.text(lat, lng);
        } else {
            return;
        }
        hideActions();
        closePanel();
        Context c = getContext();
        panel = SelectPanel.show(c, str(R.string.save),
                new CharSequence[]{str(R.string.map_add_to_favorites), str(R.string.map_add_to_marker)},
                index -> {
                    closePanel();
                    if (index == 0) saveFavorite(name, lat, lng, alt);
                    else if (index == 1) saveMarker(name, lat, lng, alt);
                });
    }

    private void saveFavorite(String name, double lat, double lng, double alt) {
        final Context app = getContext().getApplicationContext();
        panel = NameFolderPanel.show(getContext(), str(R.string.map_add_to_favorites), name, Folder.FAVORITES, null, (nm, folder) -> {
            Io.run(() -> {   // escrita: nunca cancelável
                FavoriteDao.insert(app, nm, "", lat, lng, Double.isNaN(alt) ? 0 : alt, folder);
                return Boolean.TRUE;
            }, savedCallback(app));
        });
    }

    private void saveMarker(String name, double lat, double lng, double alt) {
        final Context app = getContext().getApplicationContext();
        panel = ItemListPanel.show(getContext(), str(R.string.map_markers), null, folder -> {
            List<MarkerType> types = MarkerDao.types(app);
            ArrayList<ItemListPanel.Item> out = new ArrayList<>(types.size());
            for (MarkerType t : types) out.add(new ItemListPanel.Item(t.id, t.name, null));
            return out;
        }, tag -> {
            closePanel();
            final int typeId = (Integer) tag;
            Io.run(() -> {   // escrita: nunca cancelável
                MarkerDao.insert(app, typeId, name, lat, lng, alt);
                return Boolean.TRUE;
            }, savedCallback(app));
        }, null, null);
    }

    private Io.Callback<Boolean> savedCallback(Context app) {
        return new Io.Callback<Boolean>() {
            @Override public void onResult(Boolean ok) {
                Ui.toast(app, R.string.item_saved);
                if (destroyed) return;
                swMarkers.setEnabled(true);
                if (layer != null) layer.refresh();
            }

            @Override public void onError(Throwable t) { Ui.toast(app, R.string.unknown_error); }
        };
    }

    // ------------------------------------------------------------------ utilitários

    private void moveStart(double lat, double lng, double alt) {
        model.setStart(lat, lng, alt);
        if (Double.isNaN(alt)) fetchStartElevation();
        onPointsChanged();
        updateDistance();
    }

    /** Altitude do início (F29): Io + Elevation, só se habilitado; a anterior é cancelada. */
    private void fetchStartElevation() {
        cancel(elevTask);
        elevTask = null;
        Context c = getContext();
        if (!model.hasStart() || !Prefs.getBool(c, Prefs.ELEVATION_LOOKUP_ENABLED)) return;
        final double lat = model.lat(0), lng = model.lng(0);
        final Context app = c.getApplicationContext();
        elevTask = Io.run(() -> Elevation.fetch(app, lat, lng), new Io.Callback<Double>() {
            @Override public void onResult(Double alt) {
                if (model.hasStart() && model.lat(0) == lat && model.lng(0) == lng && alt != null && !alt.isNaN()) model.setAlt(0, alt);
            }
        });
    }

    private Coord startCoord() { return new Coord(model.lat(0), model.lng(0), model.alt(0)); }

    private void closePanel() {
        Object p = panel;
        panel = null;
        if (p instanceof PanelHost) ((PanelHost) p).dismiss();
        else if (p instanceof ItemListPanel) ((ItemListPanel) p).dismiss();
        else if (p instanceof TeleportPanel) ((TeleportPanel) p).dismiss();
    }

    private String str(int id) { return getContext().getString(id); }

    private static void cancel(Io.Task t) { if (t != null) t.cancel(); }

    private static void vis(View v, boolean show) { v.setVisibility(show ? VISIBLE : GONE); }

    private static void enable(View v, boolean on) {
        v.setEnabled(on);
        v.setAlpha(on ? 1f : 0.4f);
    }

    private static void setTopMargin(View v, int px) {
        LayoutParams lp = (LayoutParams) v.getLayoutParams();
        if (lp.topMargin != px) {
            lp.topMargin = px;
            v.setLayoutParams(lp);
        }
    }
}
