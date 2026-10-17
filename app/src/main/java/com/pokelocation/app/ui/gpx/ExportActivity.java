package com.pokelocation.app.ui.gpx;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.pokelocation.app.Io;
import com.pokelocation.app.R;
import com.pokelocation.app.data.FavoriteDao;
import com.pokelocation.app.data.FolderDao;
import com.pokelocation.app.data.MarkerDao;
import com.pokelocation.app.data.RouteDao;
import com.pokelocation.app.geo.CoordParser;
import com.pokelocation.app.gpx.ExportItem;
import com.pokelocation.app.gpx.FolderTree;
import com.pokelocation.app.gpx.GpxWriter;
import com.pokelocation.app.gpx.KmlWriter;
import com.pokelocation.app.ipc.Intents;
import com.pokelocation.app.model.Favorite;
import com.pokelocation.app.model.MarkerType;
import com.pokelocation.app.model.Route;
import com.pokelocation.app.ui.base.BaseActivity;
import com.pokelocation.app.ui.favorites.FavoritesActivity;
import com.pokelocation.app.ui.markers.MarkerTypesActivity;
import com.pokelocation.app.ui.panel.SelectPanel;
import com.pokelocation.app.ui.routes.RoutesActivity;
import com.pokelocation.app.util.Ui;

import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * GPX Export: monta uma lista de favoritos, rotas e marcadores de um tipo (como uma rota) e grava GPX ou KML
 * via SAF. A lista guarda só entradas leves; os pontos de cada rota são lidos um a um na escrita.
 */
public final class ExportActivity extends BaseActivity {
    private static final int PICK_FAVORITES = 0, PICK_ROUTES = 1, PICK_MARKERS = 2;
    private static final String EMPTY = "empty";   // linha "No items selected!"
    private static final int T_SECTION = 0, T_FOLDER = 1, T_ITEM = 2, T_EMPTY = 3;

    private final LinkedHashMap<String, ExportItem> favs = new LinkedHashMap<>(), routes = new LinkedHashMap<>();
    private Adapter ad;
    private Io.Task task;
    private Dialog progress;
    private boolean busy, exportKml;
    private int pickKind;

    private final ActivityResultLauncher<Intent> picker = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), r -> {
                if (r.getResultCode() == RESULT_OK && r.getData() != null) onPicked(pickKind, r.getData());
            });

    private final ActivityResultLauncher<Intent> creator = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), r -> {
                Uri uri = r.getData() == null ? null : r.getData().getData();
                if (r.getResultCode() == RESULT_OK && uri != null) write(uri, exportKml);
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.gpx_export);
        setupToolbar(getString(R.string.gpx_export_title), true);
        applyInsets(findViewById(R.id.gpx_screen));
        RecyclerView rv = findViewById(R.id.gpx_recycler);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setHasFixedSize(true);
        ad = new Adapter();
        rv.setAdapter(ad);
        findViewById(R.id.gpx_fab).setOnClickListener(v -> addItems());
        rebuild();
    }

    @Override
    protected void onDestroy() {
        if (task != null) task.cancel();
        GpxUi.dismiss(progress);
        progress = null;
        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.gpx_export, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem it = menu.findItem(R.id.gpx_action_export);
        if (it != null) it.setEnabled(!favs.isEmpty() || !routes.isEmpty());
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.gpx_action_export) {
            chooseFormat();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void addItems() {
        if (busy) return;
        SelectPanel.show(this, getString(R.string.gpx_add_items), new CharSequence[]{getString(R.string.favorites),
                getString(R.string.gpx_routes), getString(R.string.gpx_markers)}, i -> {
            pickKind = i;
            Class<?> target = i == PICK_FAVORITES ? FavoritesActivity.class
                    : i == PICK_ROUTES ? RoutesActivity.class : MarkerTypesActivity.class;
            picker.launch(new Intent(this, target).putExtra(Intents.EXTRA_SELECT_MODE, true));
        });
    }

    private static String[] ids(Intent data) {
        String[] ids = data.getStringArrayExtra(Intents.EXTRA_IDS);
        if (ids == null && data.getStringExtra(Intents.EXTRA_ID) != null) ids = new String[]{data.getStringExtra(Intents.EXTRA_ID)};
        return ids == null ? new String[0] : ids;
    }

    private void onPicked(int kind, Intent data) {
        Context app = getApplicationContext();
        String[] ids = ids(data);
        String[] folderIds = data.getStringArrayExtra(Intents.EXTRA_FOLDER_IDS);
        int[] typeIds = data.getIntArrayExtra(Intents.EXTRA_TYPE_IDS);
        if (kind == PICK_MARKERS ? typeIds == null || typeIds.length == 0 : ids.length == 0 && (folderIds == null || folderIds.length == 0)) {
            return;
        }
        int errRes = kind == PICK_FAVORITES ? R.string.gpx_err_favorites
                : kind == PICK_ROUTES ? R.string.gpx_err_routes : R.string.gpx_err_marker;
        busy = true;
        progress = GpxUi.progress(this, R.string.gpx_retrieving);
        task = Io.run(() -> {
            ArrayList<ExportItem> out = new ArrayList<>();
            if (kind == PICK_MARKERS) {
                MarkerType t = MarkerDao.getType(app, typeIds[0]);
                if (t == null) throw new IOException("Marker type not found");
                out.add(ExportItem.markers(t.id, t.name, MarkerDao.countMarkers(app, t.id)));
                return out;
            }
            LinkedHashSet<String> set = new LinkedHashSet<>(Arrays.asList(ids));
            List<String> folders = folderIds == null ? null : Arrays.asList(folderIds);
            HashMap<String, String> paths = new HashMap<>();
            if (kind == PICK_FAVORITES) {
                if (folders != null && !folders.isEmpty()) set.addAll(FavoriteDao.idsInFolders(app, folders));
                for (Favorite f : FavoriteDao.getByIds(app, set)) {
                    out.add(ExportItem.favorite(f.id, f.name, f.address, f.lat, f.lng, f.alt, path(app, f.folderId, paths)));
                }
            } else {
                if (folders != null && !folders.isEmpty()) set.addAll(RouteDao.idsInFolders(app, folders));
                HashMap<String, Route> byId = new HashMap<>();
                for (Route r : RouteDao.listAll(app)) if (set.contains(r.id)) byId.put(r.id, r);   // sem pontos
                for (String id : set) {
                    Route r = byId.get(id);
                    if (r != null) out.add(ExportItem.route(r.id, r.name, r.count, path(app, r.folderId, paths)));
                }
            }
            return out;
        }, new Io.Callback<ArrayList<ExportItem>>() {
            @Override
            public void onResult(ArrayList<ExportItem> items) {
                endBusy();
                for (ExportItem it : items) {
                    Map<String, ExportItem> m = it.isRoute() ? routes : favs;
                    if (!m.containsKey(it.id)) m.put(it.id, it);   // sem duplicar
                }
                rebuild();
            }

            @Override
            public void onError(Throwable t) {
                endBusy();
                Ui.toast(ExportActivity.this, errRes);
            }
        });
    }

    /** Caminho "Pai/Filho" da pasta (cache por lote); pasta que falhar é omitida. */
    private static String path(Context app, String folderId, Map<String, String> cache) {
        if (folderId == null) return null;
        if (cache.containsKey(folderId)) return cache.get(folderId);
        String p;
        try {
            p = FolderDao.path(app, folderId);
        } catch (Exception e) {
            p = null;
        }
        cache.put(folderId, p);
        return p;
    }

    private void endBusy() {
        busy = false;
        GpxUi.dismiss(progress);
        progress = null;
    }

    private void rebuild() {
        ArrayList<Object> rows = new ArrayList<>(favs.size() + routes.size() + 4);
        section(rows, "s:fav", R.string.gpx_waypoints, favs, "f:");
        section(rows, "s:rte", R.string.gpx_routes, routes, "r:");
        ad.setRows(rows);
        invalidateOptionsMenu();
    }

    private void section(ArrayList<Object> rows, String key, int titleRes, LinkedHashMap<String, ExportItem> items, String prefix) {
        FolderTree.Header h = new FolderTree.Header(key, null, getString(titleRes), -1);
        h.count = items.size();
        rows.add(h);
        if (items.isEmpty()) rows.add(EMPTY);
        else rows.addAll(FolderTree.flatten(new ArrayList<>(items.values()), it -> it.folder, prefix));
    }

    private void remove(List<Object> items) {
        for (Object o : items) {
            ExportItem it = (ExportItem) o;
            (it.isRoute() ? routes : favs).remove(it.id);
        }
        rebuild();
    }

    private void chooseFormat() {
        if (busy || (favs.isEmpty() && routes.isEmpty())) return;
        SelectPanel.show(this, getString(R.string.gpx_export_format), new CharSequence[]{getString(R.string.gpx_format_gpx),
                getString(R.string.gpx_format_kml)}, i -> {
            exportKml = i == 1;
            String name = "pokelocation_" + new SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(new Date())
                    + (exportKml ? ".kml" : ".gpx");
            try {
                creator.launch(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("*/*").putExtra(Intent.EXTRA_TITLE, name));
            } catch (Exception e) {
                Ui.toast(this, R.string.gpx_export_failed);
            }
        });
    }

    private void write(Uri uri, boolean kml) {
        if (busy) return;
        busy = true;
        progress = GpxUi.progress(this, R.string.gpx_exporting);
        Context app = getApplicationContext();
        List<ExportItem> f = new ArrayList<>(favs.values()), r = new ArrayList<>(routes.values());
        task = Io.run(() -> {
            ExportItem.Loader loader = it -> {
                if (it.kind == ExportItem.MARKERS) return MarkerDao.typeAsRoute(app, it.typeId);
                Route rt = RouteDao.get(app, it.id);
                return rt == null ? null : rt.pts;
            };
            try (OutputStream os = open(app, uri)) {
                if (os == null) throw new IOException("No stream");
                if (kml) KmlWriter.write(os, f, r, loader);
                else GpxWriter.write(os, f, r, loader);
            }
            return Boolean.TRUE;
        }, new Io.Callback<Boolean>() {
            @Override
            public void onResult(Boolean ok) {
                endBusy();
                GpxUi.message(ExportActivity.this, R.string.success, R.string.gpx_export_success);
            }

            @Override
            public void onError(Throwable t) {
                endBusy();
                Ui.toast(ExportActivity.this, R.string.gpx_export_failed);
            }
        });
    }

    /** "wt" trunca; providers que não aceitam o modo caem no "w". */
    private static OutputStream open(Context app, Uri uri) throws IOException {
        try {
            return app.getContentResolver().openOutputStream(uri, "wt");
        } catch (IllegalArgumentException | IOException e) {
            return app.getContentResolver().openOutputStream(uri, "w");
        }
    }

    private final class Adapter extends TreeAdapter {
        private final LayoutInflater inflater = getLayoutInflater();
        private final int base = Ui.dp(ExportActivity.this, 16), step = Ui.dp(ExportActivity.this, 24);

        @Override
        int itemLevel(Object o) {
            return o instanceof ExportItem ? FolderTree.depth(((ExportItem) o).folder) : 0;
        }

        @Override
        public int getItemViewType(int position) {
            Object o = rows.get(position);
            if (o instanceof FolderTree.Header) return ((FolderTree.Header) o).level < 0 ? T_SECTION : T_FOLDER;
            return o instanceof ExportItem ? T_ITEM : T_EMPTY;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            int layout = viewType == T_SECTION ? R.layout.gpx_section : viewType == T_FOLDER ? R.layout.gpx_header
                    : viewType == T_ITEM ? R.layout.gpx_item : R.layout.gpx_empty;
            return new VH(inflater.inflate(layout, parent, false), viewType);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            VH h = (VH) holder;
            Object o = rows.get(position);
            if (h.type == T_SECTION) {
                FolderTree.Header hd = (FolderTree.Header) o;
                h.title.setText(hd.title);
                h.count.setText(String.valueOf(hd.count));
                h.arrow.setImageResource(hd.hidden == null ? R.drawable.ic_expand_less : R.drawable.ic_expand_more);
            } else if (h.type == T_FOLDER) {
                FolderTree.Header hd = (FolderTree.Header) o;
                h.itemView.setPaddingRelative(Ui.dp(ExportActivity.this, 8) + hd.level * step, 0, h.itemView.getPaddingEnd(), 0);
                h.title.setText(hd.title);
                h.arrow.setImageResource(hd.hidden == null ? R.drawable.ic_expand_less : R.drawable.ic_expand_more);
            } else if (h.type == T_ITEM) {
                ExportItem it = (ExportItem) o;
                h.itemView.setPaddingRelative(base + itemLevel(it) * step, h.itemView.getPaddingTop(),
                        h.itemView.getPaddingEnd(), h.itemView.getPaddingBottom());
                h.title.setText(it.name);
                if (it.isRoute()) {
                    show(h.sub, null);
                    show(h.sub2, getResources().getQuantityString(R.plurals.gpx_coordinates, it.count, it.count));
                } else {
                    show(h.sub, it.desc);
                    show(h.sub2, CoordParser.text(it.lat, it.lng));
                }
            }
        }

        private void show(TextView tv, CharSequence s) {
            if (s == null || s.length() == 0) {
                tv.setVisibility(View.GONE);
            } else {
                tv.setText(s);
                tv.setVisibility(View.VISIBLE);
            }
        }

        private final class VH extends RecyclerView.ViewHolder {
            final int type;
            final TextView title, count, sub, sub2;
            final ImageView arrow;

            VH(View v, int type) {
                super(v);
                this.type = type;
                title = v.findViewById(R.id.gpx_title);
                count = v.findViewById(R.id.gpx_count);
                sub = v.findViewById(R.id.gpx_sub);
                sub2 = v.findViewById(R.id.gpx_sub2);
                arrow = v.findViewById(R.id.gpx_arrow);
                if (type == T_SECTION || type == T_FOLDER) {
                    v.setOnClickListener(x -> {
                        int p = getBindingAdapterPosition();
                        if (p != RecyclerView.NO_POSITION) toggle(p);
                    });
                }
                if (type == T_FOLDER) {
                    View del = v.findViewById(R.id.gpx_header_delete);
                    del.setVisibility(View.VISIBLE);
                    del.setOnClickListener(x -> {
                        int p = getBindingAdapterPosition();
                        if (p != RecyclerView.NO_POSITION) remove(collect(p));
                    });
                } else if (type == T_ITEM) {
                    ImageButton act = v.findViewById(R.id.gpx_action);
                    act.setImageResource(R.drawable.ic_close);
                    act.setContentDescription(getString(R.string.gpx_cd_remove));
                    act.setOnClickListener(x -> {
                        int p = getBindingAdapterPosition();
                        if (p != RecyclerView.NO_POSITION) remove(Collections.singletonList(rows.get(p)));
                    });
                }
            }
        }
    }
}
