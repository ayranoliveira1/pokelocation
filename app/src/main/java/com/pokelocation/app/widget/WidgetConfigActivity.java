package com.pokelocation.app.widget;

import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.pokelocation.app.Io;
import com.pokelocation.app.R;
import com.pokelocation.app.data.FavoriteDao;
import com.pokelocation.app.data.Prefs;
import com.pokelocation.app.data.RouteDao;
import com.pokelocation.app.ipc.Intents;
import com.pokelocation.app.ui.base.BaseActivity;
import com.pokelocation.app.ui.favorites.FavoritesActivity;
import com.pokelocation.app.ui.panel.SelectPanel;
import com.pokelocation.app.ui.routes.RoutesActivity;
import com.pokelocation.app.util.Ui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * "Choose Widget Items" (F105): itens escolhidos via Favorites/Routes em select_mode, arrastar reordena,
 * deslizar ou X remove, limite de 100. Cada mudança grava widget_items_/widget_cache_&lt;id&gt; e re-renderiza o widget.
 */
public class WidgetConfigActivity extends BaseActivity {
    private static final int MENU_SETTINGS = 1;
    private static final int K_ITEM = 0, K_HEADER = 1, K_EMPTY = 2;

    private static final class Row {
        final int kind;
        final boolean routes;          // seção (HEADER/EMPTY) ou tipo do item
        final WidgetData.Item item;    // só K_ITEM

        Row(int kind, boolean routes, WidgetData.Item item) {
            this.kind = kind;
            this.routes = routes;
            this.item = item;
        }
    }

    private static final class Loaded {
        final List<WidgetData.Item> items;
        final boolean appEmpty;

        Loaded(List<WidgetData.Item> items, boolean appEmpty) {
            this.items = items;
            this.appEmpty = appEmpty;
        }
    }

    private int widgetId;
    private final List<WidgetData.Item> items = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    private final boolean[] collapsed = new boolean[2];   // [favoritos, rotas]
    private boolean grouped, loaded, dragged;
    private Io.Task loadTask, pickTask;

    private RecyclerView list;
    private View fab, empty, loading;
    private final Adapter adapter = new Adapter();

    private final ActivityResultLauncher<Intent> pickFavorites =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> onPicked(r, false));
    private final ActivityResultLauncher<Intent> pickRoutes =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> onPicked(r, true));

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        widgetId = getIntent().getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish();
            return;
        }
        setContentView(R.layout.widget_config);
        setupToolbar(getString(R.string.widget_config_title), true);
        applyInsets(findViewById(R.id.widget_config_root));

        list = findViewById(R.id.widget_config_list);
        fab = findViewById(R.id.widget_config_fab);
        empty = findViewById(R.id.widget_config_empty);
        loading = findViewById(R.id.widget_config_loading);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setHasFixedSize(true);
        list.setAdapter(adapter);
        new ItemTouchHelper(new TouchCallback()).attachToRecyclerView(list);
        fab.setOnClickListener(v -> SelectPanel.show(this, getString(R.string.widget_add_items),
                new CharSequence[]{getString(R.string.widget_tab_favorites), getString(R.string.widget_tab_routes)},
                i -> {
                    if (i == 0 || i == 1) (i == 0 ? pickFavorites : pickRoutes).launch(
                            new Intent(this, i == 0 ? FavoritesActivity.class : RoutesActivity.class)
                                    .putExtra(Intents.EXTRA_SELECT_MODE, true));
                }));

        grouped = WidgetData.grouped(this, widgetId);
        Context app = getApplicationContext();
        int id = widgetId;
        loadTask = Io.run(() -> {
            List<WidgetData.Item> its = WidgetData.resolve(app,
                    WidgetData.parseTokens(Prefs.getString(app, Prefs.widgetKey(Prefs.WIDGET_ITEMS, id), "")));
            boolean appEmpty = its.isEmpty() && FavoriteDao.countAll(app) == 0 && RouteDao.listAll(app).isEmpty();
            return new Loaded(its, appEmpty);
        }, new Io.Callback<Loaded>() {
            @Override
            public void onResult(Loaded l) {
                if (isFinishing() || isDestroyed()) return;
                loaded = true;
                loading.setVisibility(View.GONE);
                if (l.appEmpty) {
                    empty.setVisibility(View.VISIBLE);
                    return;
                }
                items.addAll(l.items);
                list.setVisibility(View.VISIBLE);
                fab.setVisibility(View.VISIBLE);
                build();
            }

            @Override
            public void onError(Throwable t) {
                if (isFinishing() || isDestroyed()) return;
                loading.setVisibility(View.GONE);
                Ui.toast(WidgetConfigActivity.this, R.string.unknown_error);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean g = WidgetData.grouped(this, widgetId);   // pode ter mudado na WidgetSettingsActivity
        if (g != grouped) {
            grouped = g;
            if (loaded) build();
        }
    }

    @Override
    protected void onDestroy() {
        if (loadTask != null) loadTask.cancel();
        if (pickTask != null) pickTask.cancel();
        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(Menu.NONE, MENU_SETTINGS, Menu.NONE, R.string.widget_settings_title)
                .setIcon(R.drawable.ic_settings).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == MENU_SETTINGS) {
            startActivity(new Intent(this, WidgetSettingsActivity.class)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // --- dados ---

    private void onPicked(ActivityResult r, boolean routes) {
        Intent d = r.getData();
        if (r.getResultCode() != RESULT_OK || d == null) return;
        String[] ids = d.getStringArrayExtra(Intents.EXTRA_IDS);
        if (ids == null && d.getStringExtra(Intents.EXTRA_ID) != null) ids = new String[]{d.getStringExtra(Intents.EXTRA_ID)};
        String[] folders = d.getStringArrayExtra(Intents.EXTRA_FOLDER_IDS);
        List<String> picked = ids == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(ids));
        List<String> folderIds = folders == null ? Collections.emptyList() : Arrays.asList(folders);
        if (picked.isEmpty() && folderIds.isEmpty()) return;
        Context app = getApplicationContext();
        pickTask = Io.run(() -> {
            if (!folderIds.isEmpty()) {
                picked.addAll(routes ? RouteDao.idsInFolders(app, folderIds) : FavoriteDao.idsInFolders(app, folderIds));
            }
            return WidgetData.resolve(app, WidgetData.tokens(routes, picked));
        }, its -> {
            if (!isFinishing() && !isDestroyed()) add(its);
        });
    }

    private void add(List<WidgetData.Item> picked) {
        Set<String> have = new HashSet<>();
        for (WidgetData.Item it : items) have.add(it.token());
        int added = 0;
        boolean cut = false;
        for (WidgetData.Item it : picked) {
            if (!have.add(it.token())) continue;
            if (items.size() >= WidgetData.MAX_ITEMS) {
                cut = true;
                break;
            }
            items.add(it);
            added++;
        }
        if (cut) {
            Ui.toast(this, getString(added == 0 ? R.string.widget_max_items : R.string.widget_max_items_cut,
                    WidgetData.MAX_ITEMS));
        }
        if (added > 0) {
            save();
            build();
        }
    }

    private void remove(WidgetData.Item it) {
        items.remove(it);
        save();
        build();
    }

    /** Grava na hora (processo main) e re-renderiza o widget a partir da lista em memória (sem banco). */
    private void save() {
        WidgetData.store(this, widgetId, items);
        Context app = getApplicationContext();
        int id = widgetId;
        List<WidgetData.Item> copy = new ArrayList<>(items);
        Io.run(() -> QuickActionsWidgetProvider.render(app, id, copy));
    }

    private void build() {
        rows.clear();
        if (!grouped) {
            for (WidgetData.Item it : items) rows.add(new Row(K_ITEM, it.route, it));
            if (items.isEmpty()) rows.add(new Row(K_EMPTY, false, null));
        } else {
            for (int s = 0; s < 2; s++) {
                boolean routes = s == 1;
                rows.add(new Row(K_HEADER, routes, null));
                if (collapsed[s]) continue;
                int n = 0;
                for (WidgetData.Item it : items) {
                    if (it.route != routes) continue;
                    rows.add(new Row(K_ITEM, routes, it));
                    n++;
                }
                if (n == 0) rows.add(new Row(K_EMPTY, routes, null));
            }
        }
        adapter.notifyDataSetChanged();
    }

    private int count(boolean routes) {
        int n = 0;
        for (WidgetData.Item it : items) if (it.route == routes) n++;
        return n;
    }

    // --- lista ---

    private final class Holder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView title;
        final ImageButton action;

        Holder(View v) {
            super(v);
            icon = v.findViewById(R.id.widget_config_icon);
            title = v.findViewById(R.id.widget_config_title);
            action = v.findViewById(R.id.widget_config_action);
        }
    }

    private final class Adapter extends RecyclerView.Adapter<Holder> {
        @Override
        public Holder onCreateViewHolder(ViewGroup parent, int type) {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.widget_config_item, parent, false));
        }

        @Override
        public int getItemViewType(int position) { return rows.get(position).kind; }

        @Override
        public int getItemCount() { return rows.size(); }

        @Override
        public void onBindViewHolder(Holder h, int position) {
            Row r = rows.get(position);
            h.itemView.setOnClickListener(null);
            h.itemView.setClickable(false);
            h.action.setOnClickListener(null);
            if (r.kind == K_ITEM) {
                h.icon.setVisibility(View.VISIBLE);
                h.icon.setImageResource(r.item.route ? R.drawable.ic_route : R.drawable.ic_favorite);
                h.title.setText(r.item.name);
                h.title.setAlpha(1f);
                h.action.setVisibility(View.VISIBLE);
                h.action.setImageResource(R.drawable.ic_close);
                h.action.setContentDescription(getString(R.string.widget_remove));
                h.action.setOnClickListener(v -> remove(r.item));
            } else if (r.kind == K_HEADER) {
                int s = r.routes ? 1 : 0;
                h.icon.setVisibility(View.GONE);
                h.title.setText(getString(R.string.widget_section,
                        getString(r.routes ? R.string.widget_tab_routes : R.string.widget_tab_favorites), count(r.routes)));
                h.title.setAlpha(1f);
                h.action.setVisibility(View.VISIBLE);
                h.action.setImageResource(collapsed[s] ? R.drawable.ic_expand_more : R.drawable.ic_expand_less);
                h.action.setContentDescription(getString(R.string.widget_expand));
                View.OnClickListener toggle = v -> {
                    collapsed[s] = !collapsed[s];
                    build();
                };
                h.itemView.setOnClickListener(toggle);
                h.action.setOnClickListener(toggle);
            } else {
                h.icon.setVisibility(View.GONE);
                h.title.setText(R.string.widget_no_items);
                h.title.setAlpha(0.6f);
                h.action.setVisibility(View.GONE);
            }
        }
    }

    /** Arrastar reordena (agrupado: só dentro da seção); deslizar remove. Grava uma vez ao soltar. */
    private final class TouchCallback extends ItemTouchHelper.Callback {
        @Override
        public int getMovementFlags(RecyclerView rv, RecyclerView.ViewHolder vh) {
            int p = vh.getBindingAdapterPosition();
            if (p < 0 || p >= rows.size() || rows.get(p).kind != K_ITEM) return 0;
            return makeMovementFlags(ItemTouchHelper.UP | ItemTouchHelper.DOWN, ItemTouchHelper.START | ItemTouchHelper.END);
        }

        @Override
        public boolean onMove(RecyclerView rv, RecyclerView.ViewHolder from, RecyclerView.ViewHolder to) {
            int a = from.getBindingAdapterPosition(), b = to.getBindingAdapterPosition();
            if (a < 0 || b < 0 || a >= rows.size() || b >= rows.size()) return false;
            Row ra = rows.get(a), rb = rows.get(b);
            if (rb.kind != K_ITEM || (grouped && ra.item.route != rb.item.route)) return false;
            Collections.swap(items, items.indexOf(ra.item), items.indexOf(rb.item));
            Collections.swap(rows, a, b);
            adapter.notifyItemMoved(a, b);
            dragged = true;
            return true;
        }

        @Override
        public void onSwiped(RecyclerView.ViewHolder vh, int direction) {
            int p = vh.getBindingAdapterPosition();
            if (p >= 0 && p < rows.size() && rows.get(p).kind == K_ITEM) remove(rows.get(p).item);
            else build();
        }

        @Override
        public void clearView(RecyclerView rv, RecyclerView.ViewHolder vh) {
            super.clearView(rv, vh);
            if (dragged) {
                dragged = false;
                save();
            }
        }
    }
}
