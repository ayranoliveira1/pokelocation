package com.pokelocation.app.ui.routes;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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
import androidx.lifecycle.Lifecycle;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.pokelocation.app.Io;
import com.pokelocation.app.R;
import com.pokelocation.app.data.FolderDao;
import com.pokelocation.app.data.Prefs;
import com.pokelocation.app.data.RouteDao;
import com.pokelocation.app.ipc.Intents;
import com.pokelocation.app.model.Folder;
import com.pokelocation.app.model.MapOptions;
import com.pokelocation.app.model.Route;
import com.pokelocation.app.ui.base.BaseActivity;
import com.pokelocation.app.ui.base.StateView;
import com.pokelocation.app.ui.common.Breadcrumb;
import com.pokelocation.app.ui.map.RouteMapView;
import com.pokelocation.app.ui.panel.FolderPicker;
import com.pokelocation.app.ui.panel.MessagePanel;
import com.pokelocation.app.ui.panel.NameFolderPanel;
import com.pokelocation.app.ui.panel.PanelHost;
import com.pokelocation.app.ui.panel.SelectPanel;
import com.pokelocation.app.util.Ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Lista de rotas (F66), nova rota no mapa (F67), edição Map/Advanced (F68). Last Route nunca aparece (F71: o DAO exclui). */
public final class RoutesActivity extends BaseActivity implements RoutesAdapter.Listener {
    private static final String STATE_FOLDER = "routes_folder";

    private final LinkedHashMap<String, Route> selRoutes = new LinkedHashMap<>();
    private final LinkedHashMap<String, Folder> selFolders = new LinkedHashMap<>();

    private boolean selectMode;
    private String folderId;          // null = raiz
    private Folder folder;            // pasta atual (null na raiz)
    private Io.Task loadTask;

    private Toolbar toolbar;
    private StateView state;
    private RecyclerView list;
    private RoutesAdapter adapter;
    private ItemTouchHelper touchHelper;
    private View breadcrumbScroll;
    private LinearLayout breadcrumb;
    private FrameLayout content;
    private FloatingActionButton fab;
    private View selectionBar, barCopy, barEdit, barRename, barMove, barDelete;

    private MenuItem searchItem;
    private boolean searchOpen, reorder;
    // Seleção na própria toolbar (como em Favoritos): o ActionMode do AppCompat empurrava a toolbar e pintava a status bar (QA nº 4)
    private boolean selecting;
    private Menu menu;
    private Drawable backIcon;
    private RouteMapView map;

    private final ActivityResultLauncher<Intent> advanced = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), r -> { if (r.getResultCode() == RESULT_OK) load(); });

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.routes_activity);
        selectMode = Intents.getBool(getIntent(), Intents.EXTRA_SELECT_MODE, false);
        if (b != null) folderId = b.getString(STATE_FOLDER);

        toolbar = setupToolbar(getString(R.string.routes_title), true);
        backIcon = toolbar.getNavigationIcon();
        applyInsets(findViewById(R.id.routes_root));

        breadcrumbScroll = findViewById(R.id.routes_breadcrumb_scroll);
        breadcrumb = findViewById(R.id.routes_breadcrumb);
        content = findViewById(R.id.routes_content);
        state = findViewById(R.id.routes_state);
        list = findViewById(R.id.routes_list);
        fab = findViewById(R.id.routes_fab);
        selectionBar = findViewById(R.id.routes_selection_bar);
        barCopy = findViewById(R.id.routes_bar_copy);
        barEdit = findViewById(R.id.routes_bar_edit);
        barRename = findViewById(R.id.routes_bar_rename);
        barMove = findViewById(R.id.routes_bar_move);
        barDelete = findViewById(R.id.routes_bar_delete);

        adapter = new RoutesAdapter(this, this, selFolders.keySet(), selRoutes.keySet());
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setHasFixedSize(true);
        list.setAdapter(adapter);
        touchHelper = new ItemTouchHelper(new DragCallback());
        touchHelper.attachToRecyclerView(list);

        state.setEmptyText(getString(R.string.routes_empty));
        state.setErrorText(getString(R.string.routes_load_error));
        state.setOnRetry(this::load);

        fab.setOnClickListener(v -> showNew());
        barCopy.setOnClickListener(v -> { Route r = singleRoute(); if (r != null) onCopy(r); });
        barEdit.setOnClickListener(v -> { Route r = singleRoute(); if (r != null) onEdit(r); });
        barRename.setOnClickListener(v -> { Folder f = singleFolder(); if (f != null) renameFolder(f); });
        barMove.setOnClickListener(v -> moveSelection());
        barDelete.setOnClickListener(v -> deleteSelection());

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { if (!handleBack()) finish(); }
        });

        if (selectMode) list.post(this::startSelection);
        updateFab();
        load();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        out.putString(STATE_FOLDER, folderId);
    }

    @Override protected void onResume() {
        super.onResume();
        if (map != null) map.onResume();
    }

    @Override protected void onPause() {
        if (map != null) map.onPause();
        super.onPause();
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (map != null) map.onTrimMemory(level);
    }

    @Override protected void onDestroy() {
        if (loadTask != null) loadTask.cancel();
        closeMap();
        PanelHost.closeAll();
        super.onDestroy();
    }

    private boolean alive() { return !isFinishing() && !isDestroyed(); }

    // ---------------------------------------------------------------- carga

    private static final class Page {
        Folder folder;
        List<Folder> ancestors, folders;
        Map<String, Integer> counts;
        List<Route> routes;

        static Page load(Context c, String fid) {
            Page p = new Page();
            if (fid != null) {
                p.folder = FolderDao.get(c, fid);
                if (p.folder == null) fid = null;   // pasta apagada: volta à raiz
            }
            p.ancestors = fid == null ? Collections.emptyList() : FolderDao.ancestors(c, fid);
            p.folders = FolderDao.children(c, Folder.ROUTES, fid);
            p.counts = FolderDao.subtreeItemCounts(c, Folder.ROUTES, p.folders);
            p.routes = RouteDao.list(c, fid);   // sem pts; exclui a Last Route
            return p;
        }
    }

    private void load() {
        if (loadTask != null) loadTask.cancel();
        if (adapter.getItemCount() == 0) state.setState(StateView.LOADING);
        final Context app = getApplicationContext();
        final String fid = folderId;
        loadTask = Io.run(() -> Page.load(app, fid), new Io.Callback<Page>() {
            @Override public void onResult(Page p) {
                if (!alive()) return;
                folder = p.folder;
                folderId = p.folder == null ? null : p.folder.id;
                Breadcrumb.bind(breadcrumb, p.ancestors, getString(R.string.routes_title), RoutesActivity.this::openFolder);
                if (map == null) breadcrumbScroll.setVisibility(folderId == null ? View.GONE : View.VISIBLE);
                adapter.setData(p.folders, p.counts, p.routes);
                showListState();
                invalidateOptionsMenu();
            }

            @Override public void onError(Throwable t) {
                if (alive()) state.setState(StateView.ERROR);
            }
        });
    }

    private void showListState() {
        state.setState(adapter.isEmptyAll() ? StateView.EMPTY : StateView.CONTENT);
    }

    private void openFolder(String id) {
        if (Objects.equals(id, folderId)) return;
        folderId = id;
        list.scrollToPosition(0);
        load();
    }

    private boolean handleBack() {
        if (map != null) {
            if (!map.onBackPressed()) closeMap();
            return true;
        }
        if (selecting && !selectMode) {
            endSelection();
            return true;
        }
        if (folderId != null) {
            openFolder(folder == null ? null : folder.parentId);
            return true;
        }
        if (searchOpen && searchItem != null) {
            searchItem.collapseActionView();
            return true;
        }
        if (selectMode) setResult(RESULT_CANCELED);
        return false;
    }

    // ---------------------------------------------------------------- menu

    @Override
    public boolean onCreateOptionsMenu(Menu m) {
        getMenuInflater().inflate(R.menu.routes_list, m);
        getMenuInflater().inflate(R.menu.routes_select, m);
        menu = m;
        searchItem = m.findItem(R.id.routes_menu_search);
        SearchView sv = (SearchView) searchItem.getActionView();
        if (sv != null) {
            sv.setQueryHint(getString(R.string.search));
            sv.setMaxWidth(Integer.MAX_VALUE);
            sv.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
                @Override public boolean onQueryTextSubmit(String q) { return false; }
                @Override public boolean onQueryTextChange(String q) {
                    adapter.filter(q);
                    return true;
                }
            });
        }
        searchItem.setOnActionExpandListener(new MenuItem.OnActionExpandListener() {
            @Override public boolean onMenuItemActionExpand(@NonNull MenuItem item) {
                searchOpen = true;
                if (reorder) toggleReorder();
                updateFab();
                return true;
            }

            @Override public boolean onMenuItemActionCollapse(@NonNull MenuItem item) {
                searchOpen = false;
                adapter.filter("");
                updateFab();
                return true;
            }
        });
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu m) {
        applyMenu();
        return super.onPrepareOptionsMenu(m);
    }

    /** Visibilidade direta dos itens (sem invalidateOptionsMenu, que fecharia a SearchView expandida). */
    private void applyMenu() {
        if (menu == null) return;
        boolean free = map == null;
        menu.findItem(R.id.routes_menu_search).setVisible(free);
        MenuItem re = menu.findItem(R.id.routes_menu_reorder);
        re.setVisible(free && !selectMode && !selecting);
        re.setIcon(reorder ? R.drawable.ic_check : R.drawable.ic_sort);
        re.setTitle(reorder ? R.string.done : R.string.routes_reorder);
        menu.findItem(R.id.routes_menu_delete_all).setVisible(free && !selectMode && !selecting && !adapter.isEmptyAll());
        menu.findItem(R.id.routes_menu_select_all).setVisible(free && selecting);
        menu.findItem(R.id.routes_menu_done).setVisible(free && selecting && selectMode);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.routes_menu_reorder) {
            if (searchOpen) searchItem.collapseActionView();
            toggleReorder();
            return true;
        }
        if (id == R.id.routes_menu_select_all) {
            toggleSelectAll();
            return true;
        }
        if (id == R.id.routes_menu_done) {
            returnSelection();
            return true;
        }
        if (id == R.id.routes_menu_delete_all) {
            confirmDeleteAll();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void toggleReorder() {
        reorder = !reorder;
        adapter.setReorder(reorder);
        invalidateOptionsMenu();
    }

    private void updateFab() {
        if (map == null && !searchOpen && !selecting && !selectMode) fab.show();
        else fab.hide();
    }

    // ---------------------------------------------------------------- cliques da lista

    @Override public void onFolderClick(Folder f) {
        if (!selecting) {
            openFolder(f.id);
            return;
        }
        CharSequence toggle = getString(selFolders.containsKey(f.id) ? R.string.routes_deselect_contents : R.string.routes_select_contents);
        SelectPanel.show(this, getString(R.string.routes_open_folder),
                new CharSequence[]{getString(R.string.routes_enter_folder), toggle},
                i -> { if (i == 0) openFolder(f.id); else toggle(f); });
    }

    @Override public void onRouteClick(Route r) {
        if (selecting) {
            toggle(r);
            return;
        }
        setResult(RESULT_OK, new Intent().putExtra(Intents.EXTRA_ID, r.id).putExtra(Intents.EXTRA_NAME, r.name));
        finish();
    }

    @Override public boolean onItemLongClick(Object item) {
        if (reorder || map != null) return false;
        toggle(item);
        return true;
    }

    @Override public void onStartDrag(RecyclerView.ViewHolder vh) {
        if (adapter.canDrag()) touchHelper.startDrag(vh);
    }

    // ---------------------------------------------------------------- seleção

    private void startSelection() {
        if (selecting || !alive()) return;
        if (reorder) toggleReorder();
        selecting = true;
        updateSelection();
    }

    private void toggle(Object item) {
        if (item instanceof Folder) {
            Folder f = (Folder) item;
            if (selFolders.remove(f.id) == null) selFolders.put(f.id, f);
        } else {
            Route r = (Route) item;
            if (selRoutes.remove(r.id) == null) selRoutes.put(r.id, r);
        }
        adapter.refreshItem(item);
        if (!selecting) startSelection();
        else updateSelection();
    }

    private void toggleSelectAll() {
        boolean all = true;
        for (Folder f : adapter.visibleFolders()) if (!selFolders.containsKey(f.id)) { all = false; break; }
        if (all) for (Route r : adapter.visibleRoutes()) if (!selRoutes.containsKey(r.id)) { all = false; break; }
        if (all) {
            selFolders.clear();
            selRoutes.clear();
        } else {
            for (Folder f : adapter.visibleFolders()) selFolders.put(f.id, f);
            for (Route r : adapter.visibleRoutes()) selRoutes.put(r.id, r);
        }
        adapter.refreshState();
        updateSelection();
    }

    private void updateSelection() {
        if (!selecting) return;
        int n = selRoutes.size() + selFolders.size();
        if (n == 0 && !selectMode) {
            endSelection();
            return;
        }
        toolbar.setTitle(getString(R.string.selected_count, n));
        toolbar.setNavigationIcon(selectMode ? backIcon : getDrawable(R.drawable.ic_close));
        if (selectMode) {
            selectionBar.setVisibility(View.GONE);
        } else {
            selectionBar.setVisibility(View.VISIBLE);
            int oneRoute = singleRoute() != null ? View.VISIBLE : View.GONE;
            barCopy.setVisibility(oneRoute);
            barEdit.setVisibility(oneRoute);
            barRename.setVisibility(singleFolder() != null ? View.VISIBLE : View.GONE);
            barMove.setVisibility(View.VISIBLE);
            barDelete.setVisibility(View.VISIBLE);
        }
        applyMenu();
        updateFab();
    }

    private Route singleRoute() {
        return selRoutes.size() == 1 && selFolders.isEmpty() ? selRoutes.values().iterator().next() : null;
    }

    private Folder singleFolder() {
        return selFolders.size() == 1 && selRoutes.isEmpty() ? selFolders.values().iterator().next() : null;
    }

    private void endSelection() {
        if (!selecting || selectMode) return;
        selecting = false;
        selRoutes.clear();
        selFolders.clear();
        adapter.refreshState();
        selectionBar.setVisibility(View.GONE);
        if (map == null) toolbar.setTitle(R.string.routes_title);
        toolbar.setNavigationIcon(backIcon);
        applyMenu();
        updateFab();
    }

    private void returnSelection() {
        if (selRoutes.isEmpty() && selFolders.isEmpty()) {
            setResult(RESULT_CANCELED);
        } else {
            setResult(RESULT_OK, new Intent()
                    .putExtra(Intents.EXTRA_IDS, selRoutes.keySet().toArray(new String[0]))
                    .putExtra(Intents.EXTRA_FOLDER_IDS, selFolders.keySet().toArray(new String[0])));
        }
        finish();
    }

    // ---------------------------------------------------------------- ações de rota

    @Override public void onCopy(Route r) {
        final Context app = getApplicationContext();
        NameFolderPanel.show(this, getString(R.string.routes_copy_route), r.name, null, null, (name, ignored) -> {
            final String fid = folderId;
            Io.run(() -> {
                Route full = RouteDao.get(app, r.id);
                if (full == null || full.pts == null) throw new IllegalStateException("route not found");
                Route c = RouteDao.insert(app, name, fid, full.pts);
                return new Route(c.id, c.name, c.folderId, c.sort, Route.count(full.pts), null);   // sem pts na lista
            }, new Io.Callback<Route>() {
                @Override public void onResult(Route c) {
                    if (!alive()) return;
                    Ui.toast(RoutesActivity.this, R.string.item_saved);
                    endSelection();
                    if (Objects.equals(fid, folderId)) {
                        adapter.addRoute(c);
                        showListState();
                        invalidateOptionsMenu();
                    }
                }

                @Override public void onError(Throwable t) {
                    if (alive()) Ui.toast(RoutesActivity.this, R.string.routes_save_error);
                }
            });
        });
    }

    @Override public void onEdit(Route r) {
        SelectPanel.show(this, getString(R.string.routes_edit_mode),
                new CharSequence[]{getString(R.string.routes_mode_map), getString(R.string.routes_mode_advanced)},
                i -> {
                    endSelection();
                    if (i == 0) openMapEditor(r.id);
                    else advanced.launch(new Intent(this, EditRouteActivity.class).putExtra(Intents.EXTRA_ID, r.id));
                });
    }

    @Override public void onDelete(Route r) {
        MessagePanel.show(this, getString(R.string.routes_delete_item), getString(R.string.routes_delete_item_msg),
                getString(R.string.delete), getString(R.string.cancel),
                () -> deleteItems(Collections.singletonList(r.id), Collections.emptyList()));
    }

    // ---------------------------------------------------------------- pastas e lote

    private void showNew() {
        SelectPanel.show(this, getString(R.string.routes_new),
                new CharSequence[]{getString(R.string.routes_route), getString(R.string.routes_folder)},
                i -> { if (i == 0) openMap(null); else createFolder(); });
    }

    private void createFolder() {
        final Context app = getApplicationContext();
        NameFolderPanel.show(this, getString(R.string.routes_create_folder), "", null, null, (name, ignored) -> {
            final String fid = folderId;
            Io.run(() -> FolderDao.create(app, Folder.ROUTES, fid, name), new Io.Callback<Folder>() {
                @Override public void onResult(Folder f) {
                    if (!alive() || f == null || !Objects.equals(fid, folderId)) return;
                    adapter.addFolder(f);
                    showListState();
                    invalidateOptionsMenu();
                }

                @Override public void onError(Throwable t) {
                    if (alive()) Ui.toast(RoutesActivity.this, R.string.unknown_error);
                }
            });
        });
    }

    private void renameFolder(Folder f) {
        final Context app = getApplicationContext();
        NameFolderPanel.show(this, getString(R.string.routes_rename_folder), f.name, null, null, (name, ignored) ->
                Io.run(() -> {
                    FolderDao.rename(app, f.id, name);
                    return new Folder(f.id, name, f.parentId, f.type, f.sort, f.depth);
                }, new Io.Callback<Folder>() {
                    @Override public void onResult(Folder renamed) {
                        if (!alive()) return;
                        endSelection();
                        adapter.replaceFolder(renamed);
                    }

                    @Override public void onError(Throwable t) {
                        if (alive()) Ui.toast(RoutesActivity.this, R.string.unknown_error);
                    }
                }));
    }

    private void confirmDeleteFolder(Folder f) {
        final Context app = getApplicationContext();
        Io.run(() -> FolderDao.countForDelete(app, f.id), new Io.Callback<int[]>() {
            @Override public void onResult(int[] c) {
                if (!alive()) return;
                int items = c == null ? 0 : c[0], subs = c == null ? 0 : c[1];
                String msg = items == 0 && subs == 0
                        ? getString(R.string.routes_delete_folder_msg, f.name)
                        : getString(R.string.routes_delete_folder_full_msg, f.name,
                                getResources().getQuantityString(R.plurals.routes_items, items, items),
                                getResources().getQuantityString(R.plurals.routes_subfolders, subs, subs));
                MessagePanel.show(RoutesActivity.this, getString(R.string.routes_delete_folder), msg,
                        getString(R.string.delete), getString(R.string.cancel),
                        () -> deleteItems(Collections.emptyList(), Collections.singletonList(f.id)));
            }

            @Override public void onError(Throwable t) {
                if (alive()) Ui.toast(RoutesActivity.this, R.string.unknown_error);
            }
        });
    }

    private void deleteSelection() {
        Folder f = singleFolder();
        if (f != null) {
            confirmDeleteFolder(f);
            return;
        }
        Route r = singleRoute();
        if (r != null) {
            onDelete(r);
            return;
        }
        final List<String> routeIds = new ArrayList<>(selRoutes.keySet());
        final List<String> folderIds = new ArrayList<>(selFolders.keySet());
        int n = routeIds.size() + folderIds.size();
        if (n == 0) return;
        MessagePanel.show(this, getString(R.string.delete),
                getResources().getQuantityString(R.plurals.routes_delete_many_msg, n, n),
                getString(R.string.delete), getString(R.string.cancel), () -> deleteItems(routeIds, folderIds));
    }

    private void deleteItems(List<String> routeIds, List<String> folderIds) {
        final Context app = getApplicationContext();
        Io.run(() -> {
            if (!routeIds.isEmpty()) RouteDao.deleteMany(app, routeIds);
            for (String id : folderIds) FolderDao.delete(app, id);
            return Boolean.TRUE;
        }, new Io.Callback<Boolean>() {
            @Override public void onResult(Boolean ok) {
                if (!alive()) return;
                boolean allVisible = true;
                for (String id : routeIds) allVisible &= adapter.removeRoute(id);
                for (String id : folderIds) allVisible &= adapter.removeFolder(id);
                selRoutes.keySet().removeAll(routeIds);
                selFolders.keySet().removeAll(folderIds);
                endSelection();
                if (selecting) updateSelection();
                if (allVisible) {
                    showListState();
                    invalidateOptionsMenu();
                } else {
                    load();   // item de outra pasta: contagens de subárvore mudaram
                }
            }

            @Override public void onError(Throwable t) {
                if (!alive()) return;
                Ui.toast(RoutesActivity.this, R.string.unknown_error);
                load();
            }
        });
    }

    private void moveSelection() {
        final Context app = getApplicationContext();
        final List<String> routeIds = new ArrayList<>(selRoutes.keySet());
        final List<String> folderIds = new ArrayList<>(selFolders.keySet());
        FolderPicker.show(this, Folder.ROUTES, folderIds, dest -> Io.run(() -> {
            if (!routeIds.isEmpty()) RouteDao.moveTo(app, routeIds, dest);
            return folderIds.isEmpty() || FolderDao.move(app, folderIds, dest);
        }, new Io.Callback<Boolean>() {
            @Override public void onResult(Boolean ok) {
                if (!alive()) return;
                if (!ok) Ui.toast(RoutesActivity.this, R.string.routes_move_cycle);
                endSelection();
                load();
            }

            @Override public void onError(Throwable t) {
                if (!alive()) return;
                Ui.toast(RoutesActivity.this, R.string.unknown_error);
                load();
            }
        }));
    }

    private void confirmDeleteAll() {
        final Context app = getApplicationContext();
        final String fid = folderId;
        String msg = fid == null || folder == null
                ? getString(R.string.routes_delete_all_root_msg)
                : getString(R.string.routes_delete_all_folder_msg, folder.name);
        MessagePanel.show(this, getString(R.string.routes_delete_all), msg, getString(R.string.delete), getString(R.string.cancel),
                () -> Io.run(() -> {
                    RouteDao.deleteAll(app, fid);
                    return Boolean.TRUE;
                }, new Io.Callback<Boolean>() {
                    @Override public void onResult(Boolean ok) { if (alive()) load(); }

                    @Override public void onError(Throwable t) {
                        if (!alive()) return;
                        Ui.toast(RoutesActivity.this, R.string.unknown_error);
                        load();
                    }
                }));
    }

    // ---------------------------------------------------------------- mapa (criado sob demanda, destruído ao fechar)

    private final RouteMapView.Host mapHost = new RouteMapView.Host() {
        // post: nunca destruir o mapa de dentro do próprio callback dele
        @Override public void onRouteSaved(Route saved) {
            content.post(() -> {
                closeMap();
                load();
            });
        }

        @Override public void onCloseClick() { content.post(RoutesActivity.this::closeMap); }

        @Override public void onMapFailed() {
            content.post(() -> {
                if (map == null) return;
                Ui.toast(RoutesActivity.this, R.string.routes_map_failed);
                closeMap();
            });
        }

        @Override public void onOptionsChanged(MapOptions o) {
            SharedPreferences.Editor e = Prefs.edit(RoutesActivity.this);
            o.save(e);
            e.apply();
        }
    };

    private void openMapEditor(String routeId) {
        final Context app = getApplicationContext();
        Io.run(() -> RouteDao.get(app, routeId), new Io.Callback<Route>() {
            @Override public void onResult(Route r) {
                if (!alive()) return;
                if (r == null) {
                    Ui.toast(RoutesActivity.this, R.string.unknown_error);
                    load();
                    return;
                }
                openMap(r);
            }

            @Override public void onError(Throwable t) {
                if (alive()) Ui.toast(RoutesActivity.this, R.string.unknown_error);
            }
        });
    }

    /** editOrNull null = nova rota na pasta atual; senão edição (setEditRoute já carrega os pontos). */
    private void openMap(Route editOrNull) {
        if (map != null) return;
        endSelection();
        if (searchOpen && searchItem != null) searchItem.collapseActionView();
        if (reorder) toggleReorder();
        RouteMapView m;
        try {
            m = new RouteMapView(this, RouteMapView.MODE_EDIT_ROUTE, mapHost, MapOptions.load(this));
        } catch (RuntimeException e) {
            Ui.toast(this, R.string.routes_map_failed);
            return;
        }
        map = m;
        if (editOrNull != null) m.setEditRoute(editOrNull);
        else m.setTargetFolder(folderId);
        content.addView(m, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) m.onResume();
        state.setVisibility(View.INVISIBLE);
        breadcrumbScroll.setVisibility(View.GONE);
        toolbar.setTitle(editOrNull == null ? R.string.routes_new_route : R.string.routes_edit_route);
        updateFab();
        invalidateOptionsMenu();
    }

    private void closeMap() {
        if (map == null) return;
        RouteMapView m = map;
        map = null;
        m.destroy();
        content.removeView(m);
        if (isDestroyed()) return;
        state.setVisibility(View.VISIBLE);
        breadcrumbScroll.setVisibility(folderId == null ? View.GONE : View.VISIBLE);
        toolbar.setTitle(R.string.routes_title);
        updateFab();
        invalidateOptionsMenu();
    }

    // ---------------------------------------------------------------- arrasto (grava uma vez no drop)

    private final class DragCallback extends ItemTouchHelper.Callback {
        private int from = -1, to = -1;

        @Override public int getMovementFlags(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh) {
            return adapter.canDrag() ? makeMovementFlags(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) : 0;
        }

        @Override public boolean isLongPressDragEnabled() { return false; }
        @Override public boolean isItemViewSwipeEnabled() { return false; }

        @Override public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder src,
                                        @NonNull RecyclerView.ViewHolder target) {
            int a = src.getBindingAdapterPosition(), b = target.getBindingAdapterPosition();
            if (!adapter.sameType(a, b)) return false;
            if (from < 0) from = a;
            adapter.move(a, b);
            to = b;
            return true;
        }

        @Override public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int direction) {}

        @Override public void onSelectedChanged(RecyclerView.ViewHolder vh, int actionState) {
            super.onSelectedChanged(vh, actionState);
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && vh != null) adapter.setDragging(vh);
        }

        @Override public void clearView(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh) {
            super.clearView(rv, vh);
            adapter.bindState(vh);
            if (from >= 0 && to >= 0 && from != to) persist(from, to);
            from = to = -1;
        }

        private void persist(int a, int b) {
            final Context app = getApplicationContext();
            final String fid = folderId;
            final boolean isFolder = adapter.isFolderPos(b);
            final int off = isFolder ? 0 : adapter.folderCount();
            final int f = a - off, t = b - off;
            Io.run(() -> {
                if (isFolder) FolderDao.reorder(app, Folder.ROUTES, fid, f, t);
                else RouteDao.moveSort(app, fid, f, t);
                return Boolean.TRUE;
            }, new Io.Callback<Boolean>() {
                @Override public void onResult(Boolean ok) {}

                @Override public void onError(Throwable e) {
                    if (!alive()) return;
                    Ui.toast(RoutesActivity.this, R.string.unknown_error);
                    load();
                }
            });
        }
    }
}
