package com.pokelocation.app.ui.favorites;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.widget.SearchView;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.pokelocation.app.Io;
import com.pokelocation.app.R;
import com.pokelocation.app.data.FavoriteDao;
import com.pokelocation.app.data.FolderDao;
import com.pokelocation.app.data.Prefs;
import com.pokelocation.app.geo.Cooldown;
import com.pokelocation.app.geo.CoordParser;
import com.pokelocation.app.ipc.Intents;
import com.pokelocation.app.model.Coord;
import com.pokelocation.app.model.Favorite;
import com.pokelocation.app.model.Folder;
import com.pokelocation.app.model.MapOptions;
import com.pokelocation.app.ui.base.BaseActivity;
import com.pokelocation.app.ui.base.StateView;
import com.pokelocation.app.ui.common.Breadcrumb;
import com.pokelocation.app.ui.map.RouteMapView;
import com.pokelocation.app.ui.panel.FolderPicker;
import com.pokelocation.app.ui.panel.NameFolderPanel;
import com.pokelocation.app.ui.panel.SelectPanel;
import com.pokelocation.app.ui.search.SearchActivity;
import com.pokelocation.app.util.Ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/**
 * Favoritos: pastas, breadcrumb, seleção, arrasto, filtro em memória, linha de distância,
 * select_mode e modo "adicionar" com RouteMapView(MODE_PICK_POINT) criado e destruído sob demanda.
 */
public class FavoritesActivity extends BaseActivity {
    private Context app;
    private Toolbar toolbar;
    private Drawable backIcon;
    private LinearLayout crumbs;
    private View crumbsScroll;
    private FrameLayout content;
    private StateView state;
    private RecyclerView list;
    private FloatingActionButton fab;
    private View selBar, selCopy, selEdit, selRename;
    private FavAdapter adapter;
    private ItemTouchHelper touchHelper;
    private Menu menu;
    private MenuItem searchItem;

    private boolean selectMode;     // chamado por outra tela para escolher ids/pastas
    private boolean searching;
    private String folderId;        // null = raiz
    private List<Folder> ancestors = Collections.emptyList();
    private Io.Task loadTask;
    private int dragFrom = -1;
    private boolean barShown, itemActions = true;

    // Modo adicionar
    private RouteMapView map;
    private Favorite searchPlace;   // último resultado da busca, para o nome sugerido
    private Io.Task altTask;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            adapter.tickVisible(list);
            Io.main().postDelayed(this, 1000);
        }
    };

    private ActivityResultLauncher<Intent> editLauncher, searchLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.fav_activity);
        app = getApplicationContext();
        selectMode = Intents.getBool(getIntent(), Intents.EXTRA_SELECT_MODE, false);

        toolbar = setupToolbar(getString(R.string.fav_title), true);
        backIcon = toolbar.getNavigationIcon();
        toolbar.setNavigationOnClickListener(v -> back());
        applyInsets(findViewById(R.id.fav_root));

        crumbs = findViewById(R.id.fav_crumbs);
        crumbsScroll = findViewById(R.id.fav_crumbs_scroll);
        content = findViewById(R.id.fav_content);
        state = findViewById(R.id.fav_state);
        state.setEmptyText(getString(R.string.fav_empty));
        state.setOnRetry(() -> load(null));
        list = findViewById(R.id.fav_list);
        list.setHasFixedSize(true);
        adapter = new FavAdapter(this);
        list.setAdapter(adapter);
        touchHelper = new ItemTouchHelper(new DragCallback());
        touchHelper.attachToRecyclerView(list);

        fab = findViewById(R.id.fav_fab);
        fab.setOnClickListener(v -> showNewMenu());
        selBar = findViewById(R.id.fav_sel_bar);
        selCopy = findViewById(R.id.fav_sel_copy);
        selEdit = findViewById(R.id.fav_sel_edit);
        selRename = findViewById(R.id.fav_sel_rename);
        selCopy.setOnClickListener(v -> { Favorite f = onlyFav(); if (f != null) copyFavorite(f); });
        selEdit.setOnClickListener(v -> { Favorite f = onlyFav(); if (f != null) editFavorite(f); });
        selRename.setOnClickListener(v -> { Folder f = onlyFolder(); if (f != null) renameFolder(f); });
        findViewById(R.id.fav_sel_move).setOnClickListener(v -> moveSelection());
        findViewById(R.id.fav_sel_delete).setOnClickListener(v -> deleteSelection());

        editLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {
            if (r.getResultCode() == RESULT_OK) {
                clearSelection();
                load(null);
            }
        });
        searchLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {
            Intent d = r.getData();
            if (r.getResultCode() != RESULT_OK || d == null || map == null) return;
            searchPlace = new Favorite("", nz(d.getStringExtra(Intents.EXTRA_NAME)), nz(d.getStringExtra(Intents.EXTRA_ADDRESS)),
                    Intents.getDouble(d, Intents.EXTRA_LAT, 0), Intents.getDouble(d, Intents.EXTRA_LNG, 0),
                    Intents.getDouble(d, Intents.EXTRA_ALT, 0), 0, null);
            map.setStart(searchPlace.coord());
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { back(); }
        });

        updateChrome();
        load(null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (map != null) map.onResume();
        Coord ref = Prefs.lastPosition(this);
        boolean run = adapter.setLineSource(ref, Prefs.getLong(this, Prefs.LAST_MOCKED_TIME), Cooldown.visible(this));
        Io.main().removeCallbacks(ticker);
        if (run) Io.main().post(ticker);
    }

    @Override
    protected void onPause() {
        Io.main().removeCallbacks(ticker);
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
        Io.main().removeCallbacks(ticker);
        if (loadTask != null) loadTask.cancel();
        if (altTask != null) altTask.cancel();
        if (map != null) {
            map.destroy();
            map = null;
        }
        super.onDestroy();
    }

    private static final class Loaded {
        List<Folder> folders;
        List<Favorite> favs;
        Map<String, Integer> counts;
        List<Folder> ancestors;
    }

    /** scrollToId != null rola até o favorito depois de carregar. */
    void load(String scrollToId) {
        if (loadTask != null) loadTask.cancel();
        if (adapter.isEmpty()) state.setState(StateView.LOADING);
        final String folder = folderId;
        loadTask = Io.run(() -> {
            Loaded l = new Loaded();
            l.folders = FolderDao.children(app, Folder.FAVORITES, folder);
            l.favs = FavoriteDao.list(app, folder);
            l.counts = FolderDao.subtreeItemCounts(app, Folder.FAVORITES, l.folders);
            l.ancestors = folder == null ? Collections.emptyList() : FolderDao.ancestors(app, folder);
            return l;
        }, new Io.Callback<Loaded>() {
            @Override public void onResult(Loaded l) {
                if (isDestroyed()) return;
                ancestors = l.ancestors;
                Breadcrumb.bind(crumbs, ancestors, getString(R.string.fav_title), id -> openFolder(id));
                crumbsScroll.setVisibility(ancestors.isEmpty() ? View.GONE : View.VISIBLE);
                crumbsScroll.post(() -> crumbsScroll.scrollTo(crumbs.getWidth(), 0));
                adapter.setData(l.folders, l.favs, l.counts);
                showListState();
                if (scrollToId != null) {
                    int p = adapter.favPosition(scrollToId);
                    if (p >= 0) list.scrollToPosition(p);
                }
                updateChrome();
            }

            @Override public void onError(Throwable t) {
                if (!isDestroyed()) state.setState(StateView.ERROR);
            }
        });
    }

    private void showListState() {
        state.setState(adapter.isEmpty() ? StateView.EMPTY : StateView.CONTENT);
        if (menu != null) applyMenu();
    }

    private void openFolder(String id) {
        if (adapter.reorder) setReorder(false);
        folderId = id;
        if (searchItem != null && searching) searchItem.collapseActionView();
        load(null);
    }

    boolean showItemActions() {
        return !selectMode && adapter.selectedCount() == 0;
    }

    private boolean selecting() {
        return selectMode || adapter.selectedCount() > 0;
    }

    void onItemClick(Object item, int pos) {
        if (adapter.reorder) return;
        if (item instanceof Folder) {
            Folder f = (Folder) item;
            if (selecting()) folderInSelection(f, pos);
            else openFolder(f.id);
        } else if (selecting()) {
            adapter.toggle(item, pos);
            updateChrome();
        } else {
            Favorite f = (Favorite) item;
            Intent d = new Intent()
                    .putExtra(Intents.EXTRA_ID, f.id).putExtra(Intents.EXTRA_NAME, f.name)
                    .putExtra(Intents.EXTRA_ADDRESS, f.address).putExtra(Intents.EXTRA_LAT, f.lat)
                    .putExtra(Intents.EXTRA_LNG, f.lng).putExtra(Intents.EXTRA_ALT, f.alt);
            setResult(RESULT_OK, d);
            finish();
        }
    }

    boolean onItemLongClick(Object item, int pos) {
        adapter.toggle(item, pos);
        updateChrome();
        return true;
    }

    /** Pasta tocada em modo seleção: Enter Folder | Select/Deselect All Contents. */
    private void folderInSelection(Folder f, int pos) {
        boolean sel = adapter.selFolders.containsKey(f.id);
        CharSequence[] opts = {getString(R.string.fav_enter_folder),
                getString(sel ? R.string.fav_deselect_contents : R.string.fav_select_contents)};
        SelectPanel.show(this, getString(R.string.fav_open_folder), opts, i -> {
            if (i == 0) openFolder(f.id);
            else if (i == 1) {
                adapter.toggle(f, pos);
                updateChrome();
            }
        });
    }

    void startDrag(RecyclerView.ViewHolder h) {
        touchHelper.startDrag(h);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu m) {
        getMenuInflater().inflate(R.menu.fav_main, m);
        menu = m;
        searchItem = m.findItem(R.id.fav_menu_search);
        SearchView sv = (SearchView) searchItem.getActionView();
        if (sv != null) {
            sv.setMaxWidth(Integer.MAX_VALUE);
            sv.setQueryHint(getString(R.string.search));
            sv.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
                @Override public boolean onQueryTextSubmit(String q) { return true; }

                @Override public boolean onQueryTextChange(String q) {
                    adapter.setFilter(q);
                    if (!isDestroyed()) showListState();
                    return true;
                }
            });
        }
        searchItem.setOnActionExpandListener(new MenuItem.OnActionExpandListener() {
            @Override public boolean onMenuItemActionExpand(@NonNull MenuItem item) {
                searching = true;
                if (adapter.reorder) setReorder(false);
                updateChrome();
                return true;
            }

            @Override public boolean onMenuItemActionCollapse(@NonNull MenuItem item) {
                searching = false;
                adapter.setFilter("");
                showListState();
                updateChrome();
                // A Toolbar só reexibe os outros itens depois que o collapse termina: recria o menu em seguida
                toolbar.post(() -> { if (!isDestroyed()) invalidateOptionsMenu(); });
                return true;
            }
        });
        applyMenu();
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            back();
        } else if (id == R.id.fav_menu_reorder) {
            setReorder(!adapter.reorder);
        } else if (id == R.id.fav_menu_delete_all) {
            confirmDeleteAll();
        } else if (id == R.id.fav_menu_select_all) {
            adapter.selectAllVisible(!adapter.allVisibleSelected());
            updateChrome();
        } else if (id == R.id.fav_menu_confirm) {
            returnSelection();
        } else if (id == R.id.fav_menu_search_location) {
            searchLauncher.launch(new Intent(this, SearchActivity.class));
        } else {
            return super.onOptionsItemSelected(item);
        }
        return true;
    }

    /** Visibilidade direta dos itens (sem invalidateOptionsMenu, que fecharia a SearchView expandida). */
    private void applyMenu() {
        boolean adding = map != null;
        boolean sel = selecting();
        menu.findItem(R.id.fav_menu_search_location).setVisible(adding);
        searchItem.setVisible(!adding);
        menu.findItem(R.id.fav_menu_reorder).setVisible(!adding && !sel && !searching && !adapter.isEmpty())
                .setIcon(adapter.reorder ? R.drawable.ic_check : R.drawable.ic_sort)
                .setTitle(adapter.reorder ? R.string.done : R.string.fav_reorder);
        menu.findItem(R.id.fav_menu_select_all).setVisible(!adding && sel && !adapter.isEmpty());
        menu.findItem(R.id.fav_menu_confirm).setVisible(!adding && selectMode);
        menu.findItem(R.id.fav_menu_delete_all).setVisible(!adding && !sel && !searching && !adapter.reorder
                && !adapter.isEmpty());
    }

    private void updateChrome() {
        boolean adding = map != null;
        int n = adapter.selectedCount();
        boolean sel = selecting();
        if (adding) toolbar.setTitle(R.string.fav_add_title);
        else if (sel) toolbar.setTitle(getString(R.string.selected_count, n));
        else toolbar.setTitle(R.string.fav_title);
        toolbar.setNavigationIcon(!adding && n > 0 && !selectMode ? getDrawable(R.drawable.ic_close) : backIcon);

        boolean showFab = !adding && !sel && !searching && !adapter.reorder;
        if (showFab) fab.show(); else fab.hide();

        // Barra de seleção: Copy/Edit com exatamente 1 favorito, Rename com exatamente 1 pasta.
        boolean bar = !adding && !selectMode && n > 0;
        int nFav = adapter.selFavs.size(), nFold = adapter.selFolders.size();
        selCopy.setVisibility(nFav == 1 && nFold == 0 ? View.VISIBLE : View.GONE);
        selEdit.setVisibility(nFav == 1 && nFold == 0 ? View.VISIBLE : View.GONE);
        selRename.setVisibility(nFold == 1 && nFav == 0 ? View.VISIBLE : View.GONE);
        if (bar != barShown) {
            barShown = bar;
            selBar.animate().cancel();
            if (bar) {
                selBar.setVisibility(View.VISIBLE);
                selBar.animate().translationY(0).setDuration(shortAnim());
            } else {
                selBar.animate().translationY(Ui.dp(this, 56)).setDuration(shortAnim())
                        .withEndAction(() -> selBar.setVisibility(View.INVISIBLE));
            }
        }
        // Botões Copy/Edit/Delete dos cards somem durante a seleção: rebind só quando o estado muda.
        boolean actions = showItemActions();
        if (actions != itemActions) {
            itemActions = actions;
            adapter.notifyItemRangeChanged(0, adapter.getItemCount());
        }
        if (menu != null) applyMenu();
    }

    private long shortAnim() {
        return getResources().getInteger(android.R.integer.config_shortAnimTime);
    }

    private void setReorder(boolean on) {
        if (adapter.reorder == on) return;
        adapter.reorder = on;
        adapter.notifyItemRangeChanged(0, adapter.getItemCount());
        updateChrome();
    }

    /** Ordem: mapa → busca → seleção → reorder → pasta pai → fecha. */
    private void back() {
        if (map != null) {
            if (!map.onBackPressed()) closeMap();
            return;
        }
        if (searching && searchItem != null) {
            searchItem.collapseActionView();
            return;
        }
        if (!selectMode && adapter.selectedCount() > 0) {
            clearSelection();
            return;
        }
        if (adapter.reorder) {
            setReorder(false);
            return;
        }
        if (folderId != null) {
            openFolder(ancestors.size() >= 2 ? ancestors.get(ancestors.size() - 2).id : null);
            return;
        }
        finish();
    }

    private void clearSelection() {
        adapter.clearSelection();
        updateChrome();
    }

    private void returnSelection() {
        Intent d = new Intent()
                .putExtra(Intents.EXTRA_IDS, adapter.selFavs.keySet().toArray(new String[0]))
                .putExtra(Intents.EXTRA_FOLDER_IDS, adapter.selFolders.keySet().toArray(new String[0]));
        setResult(RESULT_OK, d);
        finish();
    }

    private Favorite onlyFav() {
        return adapter.selFavs.size() == 1 ? adapter.selFavs.values().iterator().next() : null;
    }

    private Folder onlyFolder() {
        return adapter.selFolders.size() == 1 ? adapter.selFolders.values().iterator().next() : null;
    }

    void copyFavorite(Favorite f) {
        NameFolderPanel.show(this, getString(R.string.fav_copy_title), f.name, null, null, (name, ignored) -> {
            final String folder = folderId;
            io(() -> FavoriteDao.insert(app, name, f.address, f.lat, f.lng, f.alt, folder), saved -> {
                clearSelection();
                afterInsert(saved);
            });
        });
    }

    void editFavorite(Favorite f) {
        editLauncher.launch(new Intent(this, EditFavoriteActivity.class).putExtra(Intents.EXTRA_ID, f.id));
    }

    void confirmDeleteFavorite(Favorite f) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.fav_delete_item_title)
                .setMessage(R.string.fav_delete_item_msg)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (d, w) -> io(() -> {
                    FavoriteDao.delete(app, f.id);
                    return Boolean.TRUE;
                }, ok -> {
                    adapter.selFavs.remove(f.id);
                    adapter.removeFav(f.id);
                    showListState();
                    updateChrome();
                }))
                .show();
    }

    private void renameFolder(Folder f) {
        NameFolderPanel.show(this, getString(R.string.fav_rename_folder), f.name, null, null, (name, ignored) ->
                io(() -> {
                    FolderDao.rename(app, f.id, name);
                    return Boolean.TRUE;
                }, ok -> {
                    clearSelection();
                    load(null);
                }));
    }

    private void createFolder() {
        NameFolderPanel.show(this, getString(R.string.fav_create_folder), "", null, null, (name, ignored) -> {
            final String parent = folderId;
            io(() -> FolderDao.create(app, Folder.FAVORITES, parent, name), f -> load(null));
        });
    }

    private void moveSelection() {
        final List<String> favIds = new ArrayList<>(adapter.selFavs.keySet());
        final List<String> folderIds = new ArrayList<>(adapter.selFolders.keySet());
        FolderPicker.show(this, Folder.FAVORITES, folderIds, dest -> io(() -> {
            if (!favIds.isEmpty()) FavoriteDao.moveTo(app, favIds, dest);
            return folderIds.isEmpty() || FolderDao.move(app, folderIds, dest);
        }, ok -> {
            if (!ok) Ui.toast(this, R.string.fav_move_cycle);
            clearSelection();
            load(null);
        }));
    }

    private void deleteSelection() {
        Folder onlyFolder = onlyFolder();
        Favorite onlyFav = onlyFav();
        if (onlyFolder != null && adapter.selFavs.isEmpty()) {
            confirmDeleteFolder(onlyFolder);
            return;
        }
        if (onlyFav != null && adapter.selFolders.isEmpty()) {
            confirmDeleteFavorite(onlyFav);
            return;
        }
        final List<String> favIds = new ArrayList<>(adapter.selFavs.keySet());
        final List<String> folderIds = new ArrayList<>(adapter.selFolders.keySet());
        int n = favIds.size() + folderIds.size();
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.delete)
                .setMessage(getResources().getQuantityString(R.plurals.fav_delete_many_msg, n, n))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (d, w) -> io(() -> {
                    if (!favIds.isEmpty()) FavoriteDao.deleteMany(app, favIds);
                    for (String id : folderIds) FolderDao.delete(app, id);
                    return Boolean.TRUE;
                }, ok -> {
                    clearSelection();
                    load(null);
                }))
                .show();
    }

    /** Pré-contagem {itens, subpastas} → confirmação → exclusão em cascata. */
    private void confirmDeleteFolder(Folder f) {
        io(() -> FolderDao.countForDelete(app, f.id), c -> {
            int items = c[0], subs = c[1];
            String msg = items == 0 && subs == 0 ? getString(R.string.fav_delete_folder_msg, f.name)
                    : getString(R.string.fav_delete_folder_msg_counts, f.name,
                    getResources().getQuantityString(R.plurals.fav_n_favorites, items, items),
                    getResources().getQuantityString(R.plurals.fav_n_subfolders, subs, subs));
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.fav_delete_folder_title)
                    .setMessage(msg)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.delete, (d, w) -> io(() -> {
                        FolderDao.delete(app, f.id);
                        return Boolean.TRUE;
                    }, ok -> {
                        clearSelection();
                        load(null);
                    }))
                    .show();
        });
    }

    private void confirmDeleteAll() {
        String msg = folderId == null || ancestors.isEmpty() ? getString(R.string.fav_delete_all_msg_root)
                : getString(R.string.fav_delete_all_msg_folder, ancestors.get(ancestors.size() - 1).name);
        final String folder = folderId;
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.fav_delete_all)
                .setMessage(msg)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (d, w) -> io(() -> {
                    FavoriteDao.deleteAll(app, folder);
                    return Boolean.TRUE;
                }, ok -> {
                    load(null);
                }))
                .show();
    }

    private void showNewMenu() {
        CharSequence[] opts = {getString(R.string.fav_new_favorite), getString(R.string.fav_new_folder)};
        SelectPanel.show(this, getString(R.string.fav_new), opts, i -> {
            if (i == 0) openPickMap();
            else if (i == 1) createFolder();
        });
    }

    private void openPickMap() {
        if (map != null) return;
        if (searching && searchItem != null) searchItem.collapseActionView();
        searchPlace = null;
        map = new RouteMapView(this, RouteMapView.MODE_PICK_POINT, new RouteMapView.Host() {
            @Override public void onPointPicked(Coord p) { pointPicked(p); }

            @Override public void onMapFailed() {
                Ui.toast(FavoritesActivity.this, R.string.fav_map_failed);
                Io.main().post(() -> closeMap());
            }
        }, MapOptions.load(this));
        content.addView(map, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        updateChrome();
    }

    /** Destrói e remove o mapa (nunca fica escondido). */
    private void closeMap() {
        if (map == null) return;
        map.destroy();
        content.removeView(map);
        map = null;
        updateChrome();
    }

    private void pointPicked(Coord p) {
        Favorite q = searchPlace;
        closeMap();
        if (q != null && q.coord().sameLatLng(p)) {
            askName(q.name, q.address, p.lat, p.lng, q.alt);
        } else if (p.hasAlt()) {
            askName(CoordParser.text(p.lat, p.lng), "", p.lat, p.lng, p.alt);
        } else {
            if (altTask != null) altTask.cancel();
            altTask = SearchActivity.altitude(this, p.lat, p.lng,
                    alt -> askName(CoordParser.text(p.lat, p.lng), "", p.lat, p.lng, alt));
        }
    }

    private void askName(String suggested, String address, double lat, double lng, double alt) {
        if (isDestroyed()) return;
        NameFolderPanel.show(this, getString(R.string.fav_add_title), suggested, Folder.FAVORITES, folderId,
                (name, dest) -> io(() -> FavoriteDao.insert(app, name, address, lat, lng, alt, dest), saved -> {
                    Ui.toast(this, R.string.item_saved);
                    if (eq(dest, folderId)) afterInsert(saved);
                }));
    }

    /** Inserção na pasta atual: granular sem filtro; com filtro recarrega. */
    private void afterInsert(Favorite saved) {
        if (!eq(saved.folderId, folderId)) return;
        if (adapter.filtering()) {
            load(saved.id);
            return;
        }
        int pos = adapter.appendFav(saved);
        showListState();
        list.scrollToPosition(pos);
        updateChrome();
    }

    private final class DragCallback extends ItemTouchHelper.Callback {
        @Override public boolean isLongPressDragEnabled() { return adapter.reorder; }

        @Override public boolean isItemViewSwipeEnabled() { return false; }

        @Override public int getMovementFlags(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder h) {
            return adapter.reorder && !adapter.filtering()
                    ? makeMovementFlags(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) : 0;
        }

        @Override public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder from,
                                        @NonNull RecyclerView.ViewHolder to) {
            if (from.getItemViewType() != to.getItemViewType()) return false;   // pasta só troca com pasta
            return adapter.move(from.getBindingAdapterPosition(), to.getBindingAdapterPosition());
        }

        @Override public void onSwiped(@NonNull RecyclerView.ViewHolder h, int dir) {}

        @Override public void onSelectedChanged(RecyclerView.ViewHolder h, int actionState) {
            super.onSelectedChanged(h, actionState);
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && h instanceof FavAdapter.BaseVH) {
                dragFrom = h.getBindingAdapterPosition();
                ((FavAdapter.BaseVH) h).setSelected(true);
            }
        }

        @Override public void clearView(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder h) {
            super.clearView(rv, h);
            if (h instanceof FavAdapter.BaseVH) ((FavAdapter.BaseVH) h).setSelected(false);
            int from = dragFrom, to = h.getBindingAdapterPosition();
            dragFrom = -1;
            if (from < 0 || to < 0 || from == to) return;
            final String folder = folderId;
            if (h.getItemViewType() == FavAdapter.T_FOLDER) {
                Io.run(() -> FolderDao.reorder(app, Folder.FAVORITES, folder, from, to));
            } else {
                int nf = adapter.folders.size();
                Io.run(() -> FavoriteDao.moveSort(app, folder, from - nf, to - nf));
            }
        }
    }

    /** DAO no Io; entrega só com a Activity viva; erro → toast genérico. */
    private <T> void io(Callable<T> work, Consumer<T> ok) {
        Io.run(work, new Io.Callback<T>() {
            @Override public void onResult(T v) { if (!isDestroyed()) ok.accept(v); }

            @Override public void onError(Throwable t) {
                if (!isDestroyed()) Ui.toast(FavoritesActivity.this, R.string.unknown_error);
            }
        });
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
