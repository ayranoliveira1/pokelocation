package com.pokelocation.app.ui.gpx;

import android.Manifest;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.pokelocation.app.Io;
import com.pokelocation.app.R;
import com.pokelocation.app.data.FavoriteDao;
import com.pokelocation.app.data.FolderDao;
import com.pokelocation.app.data.MarkerDao;
import com.pokelocation.app.data.RouteDao;
import com.pokelocation.app.gpx.FolderTree;
import com.pokelocation.app.gpx.GpxReader;
import com.pokelocation.app.gpx.ImportData;
import com.pokelocation.app.gpx.ImportData.Item;
import com.pokelocation.app.gpx.KmlReader;
import com.pokelocation.app.ipc.Intents;
import com.pokelocation.app.model.Folder;
import com.pokelocation.app.model.MapOptions;
import com.pokelocation.app.model.MarkerType;
import com.pokelocation.app.model.Route;
import com.pokelocation.app.ui.base.BaseActivity;
import com.pokelocation.app.ui.base.StateView;

import org.xmlpull.v1.XmlPullParserException;
import com.pokelocation.app.ui.map.RouteMapView;
import com.pokelocation.app.ui.markers.MarkerTypesActivity;
import com.pokelocation.app.ui.panel.NameFolderPanel;
import com.pokelocation.app.ui.panel.SelectPanel;
import com.pokelocation.app.util.Perms;
import com.pokelocation.app.util.Ui;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * GPX/KML Import (F24, F82–F87). Entrada: ACTION_VIEW + data, ou nada (abre o seletor do sistema).
 * Resumo com 3 cartões; cada cartão empilha uma lista (View trocada, sem Fragments); o RouteMapView(MODE_IMPORT)
 * é criado só ao revisar uma rota e destruído ao fechar. Estado da sessão (dados + "já salvo") só em campos.
 */
public final class ImportActivity extends BaseActivity {
    private static final String[] PICK_MIME = {"application/gpx+xml", "application/gpx", "application/vnd.google-earth.kml+xml",
            "application/xml", "text/xml", "text/plain", "application/octet-stream"};

    private StateView state;
    private FrameLayout root;
    private ImportData data;
    private Io.Task task;
    private Dialog progress;
    private boolean busy, waitingSettings;
    private Uri pendingUri;
    private final ArrayList<Page> pages = new ArrayList<>();

    private RouteMapView map;
    private Object mapTarget;      // Item (rota) | Integer (segmento de mapTrack) | null ("Create Route From All")
    private Item mapTrack;

    private double[] pendingMarkerPts;
    private String[] pendingMarkerNames;

    private final ActivityResultLauncher<Intent> picker = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), r -> {
                Uri uri = r.getData() == null ? null : r.getData().getData();
                if (r.getResultCode() == RESULT_OK && uri != null) load(uri, true);
                else showError(R.string.gpx_pick_failed);
            });

    private final ActivityResultLauncher<Intent> markerTypes = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), r -> {
                double[] pts = pendingMarkerPts;
                String[] names = pendingMarkerNames;
                pendingMarkerPts = null;
                pendingMarkerNames = null;
                if (pts == null || r.getResultCode() != RESULT_OK || r.getData() == null) return;
                int[] ids = r.getData().getIntArrayExtra(Intents.EXTRA_TYPE_IDS);
                if (ids != null && ids.length > 0) insertMarkers(ids[0], pts, names);
            });

    private final ActivityResultLauncher<String> storagePerm = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) load(pendingUri, false);
                else permissionDialog();
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.gpx_import);
        setupToolbar(getString(R.string.gpx_import_title), true);
        applyInsets(findViewById(R.id.gpx_screen));
        state = findViewById(R.id.gpx_state);
        root = findViewById(R.id.gpx_root);
        state.setOnRetry(this::openPicker);
        findViewById(R.id.gpx_card_points).setOnClickListener(v -> pushPage(ImportListAdapter.POINTS, null));
        findViewById(R.id.gpx_card_lines).setOnClickListener(v -> pushPage(ImportListAdapter.LINES, null));
        findViewById(R.id.gpx_card_tracks).setOnClickListener(v -> pushPage(ImportListAdapter.TRACKS, null));
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (!goBack()) finish();
            }
        });

        Intent i = getIntent();
        if (Intent.ACTION_VIEW.equals(i.getAction()) && i.getData() != null) start(i.getData());
        else openPicker();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (map != null) map.onResume();
        if (waitingSettings) {   // voltou das configurações do app (permissão negada para sempre)
            waitingSettings = false;
            if (hasStorage()) load(pendingUri, false);
            else permissionDialog();
        }
    }

    @Override
    protected void onPause() {
        if (map != null) map.onPause();
        super.onPause();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (map != null) map.onTrimMemory(level);
    }

    @Override
    protected void onDestroy() {
        if (task != null) task.cancel();
        closeMap();
        GpxUi.dismiss(progress);
        progress = null;
        super.onDestroy();
    }

    // ---------------------------------------------------------------- entrada e leitura

    private void start(Uri uri) {
        state.setState(StateView.LOADING);
        if ("file".equals(uri.getScheme()) && Build.VERSION.SDK_INT < 29 && !hasStorage()) {
            pendingUri = uri;
            storagePerm.launch(Manifest.permission.READ_EXTERNAL_STORAGE);
        } else {
            load(uri, false);
        }
    }

    private boolean hasStorage() {
        return Build.VERSION.SDK_INT >= 29
                || checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void permissionDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.gpx_perm_title)
                .setMessage(R.string.gpx_perm_msg)
                .setCancelable(false)
                .setPositiveButton(R.string.ok, (d, w) -> {
                    if (shouldShowRequestPermissionRationale(Manifest.permission.READ_EXTERNAL_STORAGE)) {
                        storagePerm.launch(Manifest.permission.READ_EXTERNAL_STORAGE);
                    } else {
                        waitingSettings = true;
                        startActivity(Perms.appDetails(this));
                    }
                })
                .setNegativeButton(R.string.cancel, (d, w) -> finish())
                .show();
    }

    private void openPicker() {
        state.setState(StateView.LOADING);
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, PICK_MIME);
        try {
            picker.launch(i);
        } catch (ActivityNotFoundException e) {
            try {
                picker.launch(new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"));
            } catch (ActivityNotFoundException e2) {
                showError(R.string.gpx_pick_failed);
            }
        }
    }

    private static final class WrongTypeException extends IOException {}

    /** Conteúdo não é GPX/KML válido (QA fase 1): mensagem própria em vez do erro genérico. */
    private static final class InvalidFileException extends IOException {
        final boolean kml;
        InvalidFileException(boolean kml) { this.kml = kml; }
    }

    private void load(Uri uri, boolean fromPicker) {
        if (uri == null) {
            showError(R.string.gpx_pick_failed);
            return;
        }
        closeAllPages();
        data = null;
        state.setState(StateView.LOADING);
        if (task != null) task.cancel();
        Context app = getApplicationContext();
        task = Io.run(() -> {
            String name = displayName(app, uri);
            String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
            boolean kml = lower.endsWith(".kml");
            int dot = lower.lastIndexOf('.');
            if (!kml && fromPicker && dot >= 0 && !lower.substring(dot).equals(".gpx")) throw new WrongTypeException();
            try (InputStream in = app.getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IOException("No stream");
                try {
                    return kml ? KmlReader.read(in) : GpxReader.read(in);
                } catch (XmlPullParserException | RuntimeException e) {
                    throw new InvalidFileException(kml);
                }
            }
        }, new Io.Callback<ImportData>() {
            @Override
            public void onResult(ImportData d) {
                if (isFinishing()) return;
                if (d.isEmpty()) showError(R.string.gpx_no_data);
                else showSummary(d);
            }

            @Override
            public void onError(Throwable t) {
                if (isFinishing()) return;
                if (t instanceof WrongTypeException) showError(R.string.gpx_wrong_type);
                else if (t instanceof InvalidFileException) {
                    showError(((InvalidFileException) t).kml ? R.string.gpx_invalid_kml : R.string.gpx_invalid_gpx);
                } else showError(R.string.unknown_error);
            }
        });
    }

    /** OpenableColumns.DISPLAY_NAME (cursor fechado na hora) ou o último segmento do caminho. */
    private static String displayName(Context c, Uri uri) {
        if ("content".equals(uri.getScheme())) {
            try (Cursor cur = c.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cur != null && cur.moveToFirst() && !cur.isNull(0)) return cur.getString(0);
            } catch (Exception ignored) {
                // provider sem a coluna: cai no caminho
            }
        }
        return uri.getLastPathSegment();
    }

    private void showError(int msgRes) {
        closeAllPages();
        state.setErrorText(getString(msgRes));
        state.setState(StateView.ERROR);
    }

    private void showSummary(ImportData d) {
        data = d;
        card(R.id.gpx_card_points, R.id.gpx_count_points, d.points.size());
        card(R.id.gpx_card_lines, R.id.gpx_count_lines, d.lines.size());
        card(R.id.gpx_card_tracks, R.id.gpx_count_tracks, d.kml ? 0 : d.tracks.size());
        state.setState(StateView.CONTENT);
        if (d.skipped > 0) Ui.toast(this, getResources().getQuantityString(R.plurals.gpx_skipped, d.skipped, d.skipped));
    }

    private void card(int cardId, int countId, int n) {
        findViewById(cardId).setVisibility(n > 0 ? View.VISIBLE : View.GONE);
        ((TextView) findViewById(countId)).setText(String.valueOf(n));
    }

    // ---------------------------------------------------------------- páginas (listas)

    /** Uma lista empilhada sobre o resumo; trata as ações dos itens dela. */
    private final class Page implements ImportListAdapter.Listener {
        final int mode;
        final Item track;
        final View view;
        final FloatingActionButton fab;
        final ImportListAdapter ad;

        Page(int mode, Item track) {
            this.mode = mode;
            this.track = track;
            view = getLayoutInflater().inflate(R.layout.gpx_list, root, false);
            RecyclerView rv = view.findViewById(R.id.gpx_recycler);
            rv.setLayoutManager(new LinearLayoutManager(ImportActivity.this));
            rv.setHasFixedSize(true);
            ad = new ImportListAdapter(ImportActivity.this, mode, track, rows(), this);
            rv.setAdapter(ad);
            fab = view.findViewById(R.id.gpx_fab);
            if (mode == ImportListAdapter.TRACKS) fab.setVisibility(View.GONE);
            else fab.setOnClickListener(v -> bulk(all()));
        }

        private List<Object> rows() {
            switch (mode) {
                case ImportListAdapter.POINTS: return FolderTree.flatten(data.points, it -> it.folder, "");
                case ImportListAdapter.LINES: return FolderTree.flatten(data.lines, it -> it.folder, "");
                case ImportListAdapter.TRACKS: return FolderTree.flatten(data.tracks, it -> it.folder, "");
                default: return all();
            }
        }

        /** Todos os itens da página (alvo do FAB). */
        private List<Object> all() {
            switch (mode) {
                case ImportListAdapter.POINTS: return new ArrayList<>(data.points);
                case ImportListAdapter.LINES: return new ArrayList<>(data.lines);
                case ImportListAdapter.SEGMENTS: {
                    ArrayList<Object> out = new ArrayList<>(track.segs.size());
                    for (int s = 0; s < track.segs.size(); s++) out.add(s);
                    return out;
                }
                default: return new ArrayList<>(data.tracks);
            }
        }

        @Override
        public void onAction(Object row) {
            if (busy) return;
            if (mode == ImportListAdapter.POINTS) {
                Item it = (Item) row;
                if (data.kml) {
                    saveFavorites(Collections.singletonList(it));
                    return;
                }
                SelectPanel.show(ImportActivity.this, title(it.name), new CharSequence[]{
                        getString(R.string.gpx_add_to_favorites), getString(R.string.gpx_add_to_marker)}, i -> {
                    if (i == 0) {
                        if (it.saved) {
                            Ui.toast(ImportActivity.this, R.string.gpx_already_saved);
                            return;
                        }
                        NameFolderPanel.show(ImportActivity.this, getString(R.string.gpx_add_to_favorites), title(it.name),
                                null, null, (name, folderId) -> {
                                    it.name = name;
                                    saveFavorites(Collections.singletonList(it));
                                });
                    } else {
                        pickMarkerType(Collections.<Object>singletonList(it));
                    }
                });
            } else if (mode == ImportListAdapter.LINES) {
                Item it = (Item) row;
                if (data.kml) {
                    saveRoutes(Collections.singletonList(it));
                    return;
                }
                routeOptions(title(it.name), i -> {
                    if (i == 0) openMap(it.pts, it.folder, it, null);
                    else if (i == 1) pickMarkerType(it.pts, null);
                    else newMarkerType(it.name, it.pts, null);
                });
            } else if (mode == ImportListAdapter.SEGMENTS) {
                int s = (Integer) row;
                double[] pts = track.segs.get(s);
                routeOptions(title(track.name), i -> {
                    if (i == 0) openMap(pts, null, s, track);
                    else if (i == 1) pickMarkerType(pts, null);
                    else newMarkerType(track.name, pts, null);
                });
            }
        }

        private void routeOptions(String title, SelectPanel.Callback cb) {
            SelectPanel.show(ImportActivity.this, title, new CharSequence[]{getString(R.string.gpx_add_to_routes),
                    getString(R.string.gpx_add_to_marker), getString(R.string.gpx_add_new_marker)}, cb);
        }

        @Override
        public void onClick(Object row) {
            if (mode == ImportListAdapter.TRACKS) pushPage(ImportListAdapter.SEGMENTS, (Item) row);
        }

        @Override
        public void onAddAllInFolder(List<Object> items) {
            if (busy || items.isEmpty()) return;
            if (mode == ImportListAdapter.POINTS && !data.kml) saveFavorites(items);
            else bulk(items);
        }

        /** Opções em massa do FAB / "Add all in folder" (06 §2.3). */
        private void bulk(List<Object> items) {
            if (busy || items.isEmpty()) return;
            String title = getString(R.string.gpx_options);
            if (mode == ImportListAdapter.POINTS) {
                SelectPanel.show(ImportActivity.this, title, new CharSequence[]{getString(R.string.gpx_add_all_favorites),
                        getString(R.string.gpx_add_all_marker), getString(R.string.gpx_add_all_new_marker),
                        getString(R.string.gpx_create_route_all)}, i -> {
                    if (i == 0) saveFavorites(items);
                    else if (i == 1) pickMarkerType(items);
                    else if (i == 2) newMarkerType("", pointsOf(items), namesOf(items));
                    else openMap(pointsOf(items), null, null, null);
                });
            } else {
                SelectPanel.show(ImportActivity.this, title, new CharSequence[]{getString(R.string.gpx_add_all_routes),
                        getString(R.string.gpx_create_route_all)}, i -> {
                    if (i == 0) {
                        if (mode == ImportListAdapter.SEGMENTS) saveSegments(track, items);
                        else saveRoutes(items);
                    } else {
                        openMap(linesOf(items), null, null, null);
                    }
                });
            }
        }

        /** Rotas (Item) ou segmentos (Integer) concatenados. */
        private double[] linesOf(List<Object> items) {
            ArrayList<double[]> parts = new ArrayList<>(items.size());
            for (Object o : items) parts.add(o instanceof Integer ? track.segs.get((Integer) o) : ((Item) o).pts);
            return ImportData.concat(parts);
        }
    }

    private String title(String name) { return name == null ? getString(R.string.untitled) : name; }

    private static double[] pointsOf(List<Object> items) {
        double[] out = new double[items.size() * 3];
        int o = 0;
        for (Object x : items) {
            System.arraycopy(((Item) x).pts, 0, out, o, 3);
            o += 3;
        }
        return out;
    }

    private static String[] namesOf(List<Object> items) {
        String[] out = new String[items.size()];
        for (int i = 0; i < out.length; i++) out[i] = ((Item) items.get(i)).name;
        return out;
    }

    private Page top() { return pages.isEmpty() ? null : pages.get(pages.size() - 1); }

    private void pushPage(int mode, Item track) {
        if (data == null || map != null) return;
        Page p = new Page(mode, track);
        Page under = top();
        root.addView(p.view);
        if (under != null) under.view.setVisibility(View.GONE);
        else state.setVisibility(View.GONE);
        pages.add(p);
    }

    /** Back: mapa → página do topo → (false) sair. */
    private boolean goBack() {
        if (map != null) {
            closeMap();
            return true;
        }
        Page p = top();
        if (p == null) return false;
        pages.remove(pages.size() - 1);
        root.removeView(p.view);
        Page under = top();
        if (under != null) {
            under.view.setVisibility(View.VISIBLE);
            under.ad.refreshAll();   // tracks: ícone "salvo" após salvar segmentos
        } else {
            state.setVisibility(View.VISIBLE);
        }
        return true;
    }

    private void closeAllPages() {
        closeMap();
        for (Page p : pages) root.removeView(p.view);
        pages.clear();
        state.setVisibility(View.VISIBLE);
    }

    private void refreshPages() {
        for (Page p : pages) p.ad.refreshAll();
    }

    // ---------------------------------------------------------------- mapa (sob demanda)

    private final RouteMapView.Host mapHost = new RouteMapView.Host() {
        @Override
        public void onRouteSaved(Route saved) {
            Object t = mapTarget;
            if (t instanceof Item) {
                ((Item) t).saved = true;
                if (saved != null && saved.name != null) ((Item) t).name = saved.name;
            } else if (t instanceof Integer && mapTrack != null) {
                mapTrack.markSeg((Integer) t);
            }
            refreshPages();
            root.post(ImportActivity.this::closeMap);   // fora do callback do próprio mapa
        }

        @Override
        public void onMapFailed() {
            Ui.toast(ImportActivity.this, R.string.unknown_error);
            root.post(ImportActivity.this::closeMap);
        }

        @Override
        public void onCloseClick() {
            root.post(ImportActivity.this::closeMap);
        }
    };

    private void openMap(double[] pts, String folderPath, Object target, Item track) {
        if (map != null || pts == null || pts.length < 3) return;
        RouteMapView m;
        try {
            m = new RouteMapView(this, RouteMapView.MODE_IMPORT, mapHost, MapOptions.load(this));
        } catch (Exception e) {
            Ui.toast(this, R.string.unknown_error);
            return;
        }
        map = m;
        mapTarget = target;
        mapTrack = track;
        m.loadRoute(pts, -1);
        Page p = top();
        if (p != null && p.mode != ImportListAdapter.TRACKS) p.fab.hide();
        root.addView(m, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        m.onResume();
        if (folderPath != null) {
            Context app = getApplicationContext();
            Io.run(() -> FolderDao.resolvePath(app, Folder.ROUTES, folderPath, new HashMap<>()), id -> {
                if (map == m && id != null) m.setTargetFolder(id);
            });
        }
    }

    /** destroy() + removeView + null (06 §5.1). Idempotente. */
    private void closeMap() {
        RouteMapView m = map;
        if (m == null) return;
        map = null;
        mapTarget = null;
        mapTrack = null;
        try {
            m.onPause();
            m.destroy();
        } catch (Exception ignored) {
            // o mapa pode já ter falhado ao nascer
        } finally {
            root.removeView(m);
        }
        Page p = top();
        if (p != null && p.mode != ImportListAdapter.TRACKS) p.fab.show();
    }

    // ---------------------------------------------------------------- salvamento (lote sequencial no Io)

    private interface Work { List<Object> run(Context app) throws Exception; }

    /**
     * Roda no executor único (sequencial: sem sort duplicado nem pasta criada duas vezes). Erro de um item não
     * aborta: Work devolve só o que salvou. Progresso único se houver mais de um item.
     */
    private void runJob(int total, Work work, Consumer<List<Object>> onSaved) {
        if (busy) return;
        busy = true;
        if (total > 1 && progress == null) progress = GpxUi.progress(this, R.string.gpx_importing);
        Context app = getApplicationContext();
        task = Io.run(() -> work.run(app), new Io.Callback<List<Object>>() {
            @Override
            public void onResult(List<Object> ok) {
                endJob();
                onSaved.accept(ok);
                refreshPages();
                if (ok.size() < total) Ui.toast(ImportActivity.this, R.string.gpx_save_failed);
            }

            @Override
            public void onError(Throwable t) {
                endJob();
                Ui.toast(ImportActivity.this, R.string.gpx_save_failed);
            }
        });
    }

    private void endJob() {
        busy = false;
        GpxUi.dismiss(progress);
        progress = null;
    }

    /** 1º passo do lote: cria as pastas na ordem do arquivo e aquece o cache (06 §3.7). */
    private static void precreate(Context app, String type, List<Item> items, HashMap<String, String> cache) {
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        for (Item it : items) if (it.folder != null) paths.add(it.folder);
        for (String p : paths) {
            try {
                FolderDao.resolvePath(app, type, p, cache);
            } catch (Exception ignored) {
                // o item tenta de novo e, se falhar, conta como erro
            }
        }
    }

    private static ArrayList<Item> unsaved(List<?> rows) {
        ArrayList<Item> out = new ArrayList<>(rows.size());
        for (Object o : rows) if (o instanceof Item && !((Item) o).saved) out.add((Item) o);
        return out;
    }

    private void saveFavorites(List<?> rows) {
        ArrayList<Item> todo = unsaved(rows);
        if (todo.isEmpty()) {
            Ui.toast(this, R.string.gpx_already_saved);
            return;
        }
        String untitled = getString(R.string.untitled);
        runJob(todo.size(), app -> {
            HashMap<String, String> cache = new HashMap<>();
            precreate(app, Folder.FAVORITES, todo, cache);
            ArrayList<Object> ok = new ArrayList<>(todo.size());
            for (Item it : todo) {
                try {
                    String fid = it.folder == null ? null : FolderDao.resolvePath(app, Folder.FAVORITES, it.folder, cache);
                    String name = it.name == null || it.name.trim().isEmpty() ? untitled : it.name;
                    double alt = Double.isNaN(it.pts[2]) ? 0 : it.pts[2];
                    FavoriteDao.insert(app, name, it.desc == null ? "" : it.desc, it.pts[0], it.pts[1], alt, fid);
                    ok.add(it);
                } catch (Exception ignored) {
                    // item com erro não aborta o lote
                }
            }
            return ok;
        }, ok -> {
            for (Object o : ok) ((Item) o).saved = true;
        });
    }

    private void saveRoutes(List<?> rows) {
        ArrayList<Item> todo = unsaved(rows);
        if (todo.isEmpty()) {
            Ui.toast(this, R.string.gpx_already_saved);
            return;
        }
        String untitled = getString(R.string.untitled);
        runJob(todo.size(), app -> {
            HashMap<String, String> cache = new HashMap<>();
            precreate(app, Folder.ROUTES, todo, cache);
            ArrayList<Object> ok = new ArrayList<>(todo.size());
            for (Item it : todo) {
                try {
                    String fid = it.folder == null ? null : FolderDao.resolvePath(app, Folder.ROUTES, it.folder, cache);
                    RouteDao.insert(app, it.name == null || it.name.trim().isEmpty() ? untitled : it.name, fid, it.pts);
                    ok.add(it);
                } catch (Exception ignored) {
                    // item com erro não aborta o lote
                }
            }
            return ok;
        }, ok -> {
            for (Object o : ok) ((Item) o).saved = true;
        });
    }

    /** "Add All To Routes" de segmentos: cada um vira uma rota com o nome do track, sem pasta (06 §3.6). */
    private void saveSegments(Item track, List<Object> segs) {
        ArrayList<Integer> todo = new ArrayList<>(segs.size());
        for (Object o : segs) if (!track.segIsSaved((Integer) o)) todo.add((Integer) o);
        if (todo.isEmpty()) {
            Ui.toast(this, R.string.gpx_already_saved);
            return;
        }
        String name = track.name == null || track.name.trim().isEmpty() ? getString(R.string.untitled) : track.name;
        runJob(todo.size(), app -> {
            ArrayList<Object> ok = new ArrayList<>(todo.size());
            for (Integer s : todo) {
                try {
                    RouteDao.insert(app, name, null, track.segs.get(s));
                    ok.add(s);
                } catch (Exception ignored) {
                    // item com erro não aborta o lote
                }
            }
            return ok;
        }, ok -> {
            for (Object o : ok) track.markSeg((Integer) o);
        });
    }

    // ---------------------------------------------------------------- marcadores (F82)

    private void pickMarkerType(List<Object> pointItems) {
        pickMarkerType(pointsOf(pointItems), namesOf(pointItems));
    }

    private void pickMarkerType(double[] pts, String[] names) {
        if (busy) return;
        pendingMarkerPts = pts;
        pendingMarkerNames = names;
        markerTypes.launch(new Intent(this, MarkerTypesActivity.class).putExtra(Intents.EXTRA_SELECT_MODE, true));
    }

    private void insertMarkers(int typeId, double[] pts, String[] names) {
        runJob(pts.length / 3, app -> {
            MarkerDao.insertAll(app, typeId, pts, names);
            return allOk(pts);
        }, ok -> Ui.toast(this, R.string.item_saved));
    }

    /** insertAll é uma transação só: sucesso = todos os pontos (runJob compara com o total). */
    private static List<Object> allOk(double[] pts) {
        return Collections.nCopies(pts.length / 3, (Object) Boolean.TRUE);
    }

    private void newMarkerType(String name, double[] pts, String[] names) {
        if (busy) return;
        NewMarkerTypeDialog.show(this, name == null ? "" : name, (n, icon, color) -> runJob(pts.length / 3, app -> {
            MarkerType t = MarkerDao.insertType(app, n, icon, color);
            MarkerDao.insertAll(app, t.id, pts, names);
            return allOk(pts);
        }, ok -> Ui.toast(this, R.string.item_saved)));
    }
}
