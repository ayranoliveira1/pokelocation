package com.pokelocation.app.map;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import com.pokelocation.app.Io;
import com.pokelocation.app.R;
import com.pokelocation.app.data.Prefs;
import com.pokelocation.app.geo.Geo;
import com.pokelocation.app.model.Coord;
import com.pokelocation.app.model.MarkerType;
import com.pokelocation.app.util.Ui;

import org.osmdroid.config.Configuration;
import org.osmdroid.config.IConfigurationProvider;
import org.osmdroid.events.DelayedMapListener;
import org.osmdroid.events.MapListener;
import org.osmdroid.events.ScrollEvent;
import org.osmdroid.events.ZoomEvent;
import org.osmdroid.tileprovider.tilesource.ITileSource;
import org.osmdroid.tileprovider.BitmapPool;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.tileprovider.tilesource.TileSourcePolicy;
import org.osmdroid.tileprovider.tilesource.XYTileSource;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.CustomZoomButtonsController;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.CopyrightOverlay;
import org.osmdroid.views.overlay.Polyline;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * osmdroid: config 1×/processo, tiles OSM Mapnik (padrão) / openstreetmap.de, pinos e linhas compartilhados.
 * O mapa NUNCA escurece no tema escuro. Pinos: âncora (ANCHOR_CENTER, ANCHOR_BOTTOM); coração e ícones de tipo: centro.
 */
public final class MapHelper {
    public static final double DEFAULT_ZOOM = 17, MIN_ZOOM = 3, MAX_ZOOM = 19;
    public static final int PIN_CURRENT = 0, PIN_START = 1, PIN_WAYPOINT = 2, PIN_NEXT = 3, PIN_MOVING = 4, PIN_FAVORITE = 5;
    public static final int LINE_ROUTE = 0, LINE_LOOP = 1, LINE_WALK = 2;
    public static final String[] ICON_KEYS = {"pin", "flag", "park", "star", "home", "heart"};
    public static final int DP_THRESHOLD = 2000, NUMBERED_MARKERS_MAX = 200, VIEWPORT_LIMIT = 100;
    public static final double VIEWPORT_SPAN_DEG = 0.025;

    private static final int[] PIN_COLORS = {0xFFC62828, 0xFF1565C0, 0xFF64B5F6, 0xFFFB8C00, 0xFFFFB300, 0xFFE53935};
    private static final int[] ICON_RES = {R.drawable.ic_pin, R.drawable.ic_flag, R.drawable.ic_park, R.drawable.ic_star,
            R.drawable.ic_home, R.drawable.ic_favorite};
    private static final double SIMPLIFY_TOLERANCE_M = 3;

    private static boolean sConfigured;
    private static ITileSource sOsmDe;
    private static final Drawable[] sPins = new Drawable[6];
    private static final Map<String, Drawable> sTypeIcons = new HashMap<>();
    private static final ArrayList<WeakReference<MapView>> sLive = new ArrayList<>();

    private MapHelper() {}

    /** 1×/processo: userAgent = packageName, cache cacheDir/tiles 50 MB (trim 40), 12 tiles em memória, 2+2 threads. */
    public static synchronized void configure(Context c) {
        if (sConfigured) return;
        IConfigurationProvider cfg = Configuration.getInstance();
        cfg.setUserAgentValue(c.getPackageName());
        File base = new File(c.getCacheDir(), "osmdroid");
        cfg.setOsmdroidBasePath(base);
        cfg.setOsmdroidTileCache(new File(c.getCacheDir(), "tiles"));
        cfg.setTileFileSystemCacheMaxBytes(50L * 1024 * 1024);
        cfg.setTileFileSystemCacheTrimBytes(40L * 1024 * 1024);
        cfg.setCacheMapTileCount((short) 12);
        cfg.setTileDownloadThreads((short) 2);
        cfg.setTileFileSystemThreads((short) 2);
        sConfigured = true;
    }

    public static final String TILES_OSM = "osm", TILES_OSM_DE = "osm_de";

    /**
     * Pref map_tile_source (sem chave de API): "osm" = OSM Mapnik (padrão), "osm_de" = tile.openstreetmap.de
     * (nomes latinos). Qualquer outro valor (ex.: "voyager" antigo) = Mapnik.
     */
    public static synchronized ITileSource tileSource(Context c) {
        if (!TILES_OSM_DE.equals(Prefs.getString(c, Prefs.MAP_TILE_SOURCE))) return TileSourceFactory.MAPNIK;
        if (sOsmDe == null) {
            // mesma política de uso do Mapnik: sem download em massa, UA significativo, 2 conexões
            sOsmDe = new XYTileSource("OpenStreetMapDE", 0, 19, 256, ".png",
                    new String[]{"https://tile.openstreetmap.de/"}, c.getString(R.string.map_attribution),
                    new TileSourcePolicy(2, TileSourcePolicy.FLAG_NO_BULK | TileSourcePolicy.FLAG_NO_PREVENTIVE
                            | TileSourcePolicy.FLAG_USER_AGENT_MEANINGFUL | TileSourcePolicy.FLAG_USER_AGENT_NORMALIZED));
        }
        return sOsmDe;
    }

    /** Depois do 1º layout, só fora da área visível: não desfaz o enquadramento do usuário. */
    public static void reveal(MapView mv, Coord c) {
        GeoPoint p = new GeoPoint(c.lat, c.lng);
        if (!mv.isLayoutOccurred()) mv.getController().setCenter(p);
        else if (!mv.getBoundingBox().contains(c.lat, c.lng)) mv.getController().animateTo(p);
    }

    public static MapView newMapView(Context c) {
        configure(c);
        MapView mv = new MapView(c);
        mv.setTileSource(tileSource(c));
        mv.setMultiTouchControls(true);
        mv.getZoomController().setVisibility(CustomZoomButtonsController.Visibility.NEVER);
        mv.setMinZoomLevel(MIN_ZOOM);
        mv.setMaxZoomLevel(MAX_ZOOM);
        mv.setHorizontalMapRepetitionEnabled(false);
        mv.setVerticalMapRepetitionEnabled(false);
        mv.setScrollableAreaLimitLatitude(MapView.getTileSystem().getMaxLatitude(), MapView.getTileSystem().getMinLatitude(), 0);
        mv.setTilesScaledToDpi(true);
        // fundo claro fixo, sem filtro: nunca escurece
        mv.getOverlayManager().getTilesOverlay().setColorFilter(null);
        mv.getOverlayManager().getTilesOverlay().setLoadingBackgroundColor(0xFFF2EFE9);
        mv.getOverlayManager().getTilesOverlay().setLoadingLineColor(0xFFE0DCD4);
        CopyrightOverlay co = new CopyrightOverlay(c);
        co.setTextColor(0xFF43474E);
        mv.getOverlays().add(co);
        mv.getController().setZoom(DEFAULT_ZOOM);
        synchronized (sLive) {
            sLive.add(new WeakReference<>(mv));
        }
        return mv;
    }

    /** BitmapDrawable cacheado por processo (compartilhe entre Markers). */
    public static synchronized Drawable pin(Context c, int kind) {
        int k = Math.max(0, Math.min(PIN_FAVORITE, kind));
        if (sPins[k] == null) {
            if (k == PIN_FAVORITE) {
                sPins[k] = render(c, R.drawable.ic_favorite, PIN_COLORS[k], 30, false);
            } else {
                sPins[k] = render(c, R.drawable.ic_pin, k == PIN_NEXT ? c.getColor(R.color.next_marker) : PIN_COLORS[k], 40, true);
            }
        }
        return sPins[k];
    }

    /** Vetor de ICON_KEYS + tint color, 45dp, cache por (id, icon, color). */
    public static synchronized Drawable typeIcon(Context c, MarkerType t) {
        String key = t.id + "|" + t.icon + "|" + t.color;
        Drawable d = sTypeIcons.get(key);
        if (d == null) {
            int res = R.drawable.ic_pin;
            for (int i = 0; i < ICON_KEYS.length; i++) if (ICON_KEYS[i].equals(t.icon)) res = ICON_RES[i];
            d = render(c, res, t.color, 45, false);
            sTypeIcons.put(key, d);
        }
        return d;
    }

    private static Drawable render(Context c, int res, int color, int sizeDp, boolean whiteDot) {
        int px = Ui.dp(c, sizeDp);
        Bitmap bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        Drawable v = c.getDrawable(res).mutate();
        v.setTint(color);
        v.setBounds(0, 0, px, px);
        v.draw(cv);
        if (whiteDot) {   // furo do ic_pin (centro 12,9 r 2.5 no viewport 24)
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(0xFFFFFFFF);
            cv.drawCircle(px * 12f / 24f, px * 9f / 24f, px * 3f / 24f, p);
        }
        return new BitmapDrawable(c.getResources(), bmp);
    }

    /** ROUTE #1E88E5 5dp redonda · LOOP #0D47A1 tracejada · WALK danger. Toques passam para o mapa. */
    public static Polyline line(MapView mv, int kind) {
        Polyline pl = new Polyline(mv);
        Paint p = pl.getOutlinePaint();
        float w = Ui.dp(mv.getContext(), 5);
        p.setStrokeWidth(w);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setAntiAlias(true);
        if (kind == LINE_LOOP) {
            p.setColor(mv.getContext().getColor(R.color.loop_line));
            p.setPathEffect(new DashPathEffect(new float[]{w * 2, w * 1.5f}, 0));
        } else if (kind == LINE_WALK) {
            p.setColor(0xFFC62828);
        } else {
            p.setColor(mv.getContext().getColor(R.color.route_line));
        }
        pl.setInfoWindow(null);
        pl.setOnClickListener((polyline, mapView, pos) -> false);
        return pl;
    }

    /** Geo.simplify só se n > DP_THRESHOLD (só desenho). */
    public static double[] forDrawing(double[] pts) {
        return pts != null && pts.length / 3 > DP_THRESHOLD ? Geo.simplify(pts, SIMPLIFY_TOLERANCE_M) : pts;
    }

    /** MapListener (scroll/zoom) com debounce 300 ms. */
    public static void onIdle(MapView mv, Runnable onIdle) {
        mv.addMapListener(new DelayedMapListener(new MapListener() {
            @Override
            public boolean onScroll(ScrollEvent e) {
                onIdle.run();
                return false;
            }

            @Override
            public boolean onZoom(ZoomEvent e) {
                onIdle.run();
                return false;
            }
        }, 300));
    }

    public static BoundingBox box(MapView mv) { return mv.getBoundingBox(); }

    /**
     * Limpa o cache de tiles em memória dos MapViews vivos e esvazia o BitmapPool do osmdroid (bitmaps de tile
     * guardados só para reuso). Sem mapa vivo, solta também os ícones de pino/tipo (recriados sob demanda).
     */
    public static void trimMemory(int level) {
        boolean anyLive = false;
        synchronized (sLive) {
            for (Iterator<WeakReference<MapView>> it = sLive.iterator(); it.hasNext(); ) {
                MapView mv = it.next().get();
                if (mv == null) {
                    it.remove();
                } else {
                    anyLive = true;
                    mv.getTileProvider().clearTileCache();
                }
            }
        }
        if (!sConfigured) return;   // osmdroid nunca usado neste processo: não carrega o BitmapPool
        BitmapPool.getInstance().clearBitmapPool();
        if (!anyLive) dropIcons();
    }

    private static synchronized void dropIcons() {
        java.util.Arrays.fill(sPins, null);
        sTypeIcons.clear();
    }

    /**
     * onPause(), onDetach() (solta tiles, overlays e o cache em disco do provider), overlays.clear(), remove dos vivos.
     * Sem outro mapa vivo, esvazia o BitmapPool: o onDetach devolve os tiles ao pool de forma assíncrona (executor do
     * osmdroid), então a limpeza roda agora e de novo depois que essa reciclagem terminou.
     */
    public static void destroy(MapView mv) {
        if (mv == null) return;
        try {
            mv.onPause();
            mv.onDetach();
            mv.getOverlays().clear();
        } catch (Exception ignored) {
        }
        boolean anyLive = false;
        synchronized (sLive) {
            for (Iterator<WeakReference<MapView>> it = sLive.iterator(); it.hasNext(); ) {
                MapView v = it.next().get();
                if (v == null || v == mv) it.remove();
                else anyLive = true;
            }
        }
        if (!anyLive) {
            RELEASE.run();
            Io.main().removeCallbacks(RELEASE);
            Io.main().postDelayed(RELEASE, 1500);
        }
    }

    private static final Runnable RELEASE = () -> {
        synchronized (sLive) {
            for (WeakReference<MapView> r : sLive) if (r.get() != null) return;   // um mapa novo nasceu nesse meio-tempo
        }
        BitmapPool.getInstance().clearBitmapPool();
        dropIcons();
    };
}
