package com.pokelocation.app.ui.routes;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Resources;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.pokelocation.app.R;
import com.pokelocation.app.model.Folder;
import com.pokelocation.app.model.Route;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Pastas primeiro, depois rotas. Filtro em memória (cópia só enquanto há texto). */
final class RoutesAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    static final Object PAYLOAD_STATE = new Object();
    private static final int TYPE_FOLDER = 0, TYPE_ROUTE = 1;

    interface Listener {
        void onFolderClick(Folder f);
        void onRouteClick(Route r);
        boolean onItemLongClick(Object item);
        void onCopy(Route r);
        void onEdit(Route r);
        void onDelete(Route r);
        void onStartDrag(RecyclerView.ViewHolder vh);
    }

    private final LayoutInflater inflater;
    private final Resources res;
    private final Listener listener;
    private final Set<String> selFolders, selRoutes;
    private final int colorNormal, colorSelected;

    private List<Folder> folders = new ArrayList<>();
    private List<Route> routes = new ArrayList<>();
    private List<Folder> allFolders;   // != null só enquanto filtra
    private List<Route> allRoutes;
    private Map<String, Integer> counts = Collections.emptyMap();
    private String query = "";
    private boolean reorder;

    RoutesAdapter(Context c, Listener l, Set<String> selFolders, Set<String> selRoutes) {
        inflater = LayoutInflater.from(c);
        res = c.getResources();
        listener = l;
        this.selFolders = selFolders;
        this.selRoutes = selRoutes;
        colorNormal = ContextCompat.getColor(c, R.color.surface);
        colorSelected = ContextCompat.getColor(c, R.color.primary_container);
    }

    @SuppressLint("NotifyDataSetChanged")
    void setData(List<Folder> f, Map<String, Integer> c, List<Route> r) {
        folders = new ArrayList<>(f);   // mutável (o DAO pode devolver lista imutável)
        routes = new ArrayList<>(r);
        counts = c == null ? Collections.emptyMap() : c;
        allFolders = null;
        allRoutes = null;
        if (!query.isEmpty()) applyFilter();
        notifyDataSetChanged();
    }

    @SuppressLint("NotifyDataSetChanged")
    void filter(String text) {
        String q = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        if (q.equals(query)) return;
        query = q;
        if (q.isEmpty()) {
            if (allFolders != null) {
                folders = allFolders;
                routes = allRoutes;
                allFolders = null;
                allRoutes = null;
            }
        } else {
            applyFilter();
        }
        notifyDataSetChanged();
    }

    private void applyFilter() {
        if (allFolders == null) {
            allFolders = folders;
            allRoutes = routes;
        }
        folders = new ArrayList<>();
        for (Folder f : allFolders) if (matches(f.name)) folders.add(f);
        routes = new ArrayList<>();
        for (Route r : allRoutes) if (matches(r.name)) routes.add(r);
    }

    private boolean matches(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).contains(query);
    }

    boolean isEmptyAll() {
        return (allFolders != null ? allFolders : folders).isEmpty() && (allRoutes != null ? allRoutes : routes).isEmpty();
    }

    List<Folder> visibleFolders() { return folders; }
    List<Route> visibleRoutes() { return routes; }

    void addFolder(Folder f) {
        if (allFolders != null) {
            allFolders.add(f);
            if (!matches(f.name)) return;
        }
        folders.add(f);
        notifyItemInserted(folders.size() - 1);
    }

    void addRoute(Route r) {
        if (allRoutes != null) {
            allRoutes.add(r);
            if (!matches(r.name)) return;
        }
        routes.add(r);
        notifyItemInserted(folders.size() + routes.size() - 1);
    }

    void replaceFolder(Folder f) {
        if (allFolders != null) replaceIn(allFolders, f);
        int i = indexOfFolder(f.id);
        if (i >= 0) {
            folders.set(i, f);
            notifyItemChanged(i);
        }
    }

    /** false se a rota não estava na lista. */
    boolean removeRoute(String id) {
        if (allRoutes != null) removeIn(allRoutes, id);
        int i = indexOfRoute(id);
        if (i < 0) return false;
        routes.remove(i);
        notifyItemRemoved(folders.size() + i);
        return true;
    }

    /** false se a pasta não estava na lista. */
    boolean removeFolder(String id) {
        if (allFolders != null) removeIn(allFolders, id);
        int i = indexOfFolder(id);
        if (i < 0) return false;
        folders.remove(i);
        notifyItemRemoved(i);
        return true;
    }

    private static void replaceIn(List<Folder> list, Folder f) {
        for (int i = 0; i < list.size(); i++) if (list.get(i).id.equals(f.id)) { list.set(i, f); return; }
    }

    private static <T> void removeIn(List<T> list, String id) {
        for (int i = 0; i < list.size(); i++) {
            Object o = list.get(i);
            String oid = o instanceof Folder ? ((Folder) o).id : ((Route) o).id;
            if (oid.equals(id)) { list.remove(i); return; }
        }
    }

    private int indexOfFolder(String id) {
        for (int i = 0; i < folders.size(); i++) if (folders.get(i).id.equals(id)) return i;
        return -1;
    }

    private int indexOfRoute(String id) {
        for (int i = 0; i < routes.size(); i++) if (routes.get(i).id.equals(id)) return i;
        return -1;
    }

    void setReorder(boolean on) {
        reorder = on;
        refreshState();
    }

    void refreshState() {
        notifyItemRangeChanged(0, getItemCount(), PAYLOAD_STATE);
    }

    void refreshItem(Object item) {
        int p = item instanceof Folder ? indexOfFolder(((Folder) item).id) : folders.size() + indexOfRoute(((Route) item).id);
        if (p >= 0 && (item instanceof Folder || p >= folders.size())) notifyItemChanged(p, PAYLOAD_STATE);
    }

    void setDragging(RecyclerView.ViewHolder vh) {
        ((MaterialCardView) vh.itemView).setCardBackgroundColor(colorSelected);
    }

    void bindState(RecyclerView.ViewHolder vh) {
        if (vh instanceof FolderVH) ((FolderVH) vh).bindState();
        else if (vh instanceof RouteVH) ((RouteVH) vh).bindState();
    }

    boolean canDrag() { return reorder && allFolders == null; }

    boolean isFolderPos(int p) { return p < folders.size(); }

    int folderCount() { return folders.size(); }

    boolean sameType(int a, int b) {
        return a >= 0 && b >= 0 && a < getItemCount() && b < getItemCount() && isFolderPos(a) == isFolderPos(b);
    }

    void move(int from, int to) {
        if (isFolderPos(from)) Collections.swap(folders, from, to);
        else Collections.swap(routes, from - folders.size(), to - folders.size());
        notifyItemMoved(from, to);
    }

    @Override public int getItemCount() { return folders.size() + routes.size(); }

    @Override public int getItemViewType(int p) { return isFolderPos(p) ? TYPE_FOLDER : TYPE_ROUTE; }

    @NonNull @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
        return type == TYPE_FOLDER
                ? new FolderVH(inflater.inflate(R.layout.routes_item_folder, parent, false))
                : new RouteVH(inflater.inflate(R.layout.routes_item_route, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder vh, int p) {
        if (vh instanceof FolderVH) {
            Folder f = folders.get(p);
            FolderVH h = (FolderVH) vh;
            h.item = f;
            h.name.setText(f.name);
            Integer n = counts.get(f.id);
            int count = n == null ? 0 : n;
            h.count.setText(res.getQuantityString(R.plurals.routes_folder_routes, count, count));
            h.bindState();
        } else {
            Route r = routes.get(p - folders.size());
            RouteVH h = (RouteVH) vh;
            h.item = r;
            h.name.setText(r.name);
            h.count.setText(res.getQuantityString(R.plurals.routes_coordinates, r.count, r.count));
            h.bindState();
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder vh, int p, @NonNull List<Object> payloads) {
        if (payloads.contains(PAYLOAD_STATE)) bindState(vh);
        else onBindViewHolder(vh, p);
    }

    @SuppressLint("ClickableViewAccessibility")
    private View.OnTouchListener dragTouch(RecyclerView.ViewHolder vh) {
        return (v, e) -> {
            if (reorder && e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                listener.onStartDrag(vh);
                return true;
            }
            return false;
        };
    }

    final class FolderVH extends RecyclerView.ViewHolder {
        final MaterialCardView card;
        final TextView name, count;
        final ImageView end;
        Folder item;

        @SuppressLint("ClickableViewAccessibility")
        FolderVH(View v) {
            super(v);
            card = (MaterialCardView) v;
            name = v.findViewById(R.id.routes_folder_name);
            count = v.findViewById(R.id.routes_folder_count);
            end = v.findViewById(R.id.routes_folder_end);
            v.setOnClickListener(x -> { if (item != null) listener.onFolderClick(item); });
            v.setOnLongClickListener(x -> item != null && listener.onItemLongClick(item));
            end.setOnTouchListener(dragTouch(this));
        }

        void bindState() {
            card.setCardBackgroundColor(item != null && selFolders.contains(item.id) ? colorSelected : colorNormal);
            end.setImageResource(reorder ? R.drawable.ic_drag : R.drawable.ic_chevron_right);
            end.setImportantForAccessibility(reorder ? View.IMPORTANT_FOR_ACCESSIBILITY_YES : View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
    }

    final class RouteVH extends RecyclerView.ViewHolder {
        final MaterialCardView card;
        final TextView name, count;
        final View copy, edit, delete, drag;
        Route item;

        @SuppressLint("ClickableViewAccessibility")
        RouteVH(View v) {
            super(v);
            card = (MaterialCardView) v;
            name = v.findViewById(R.id.routes_route_name);
            count = v.findViewById(R.id.routes_route_count);
            copy = v.findViewById(R.id.routes_route_copy);
            edit = v.findViewById(R.id.routes_route_edit);
            delete = v.findViewById(R.id.routes_route_delete);
            drag = v.findViewById(R.id.routes_route_drag);
            v.setOnClickListener(x -> { if (item != null) listener.onRouteClick(item); });
            v.setOnLongClickListener(x -> item != null && listener.onItemLongClick(item));
            copy.setOnClickListener(x -> { if (item != null) listener.onCopy(item); });
            edit.setOnClickListener(x -> { if (item != null) listener.onEdit(item); });
            delete.setOnClickListener(x -> { if (item != null) listener.onDelete(item); });
            drag.setOnTouchListener(dragTouch(this));
        }

        void bindState() {
            card.setCardBackgroundColor(item != null && selRoutes.contains(item.id) ? colorSelected : colorNormal);
            int buttons = reorder ? View.GONE : View.VISIBLE;
            copy.setVisibility(buttons);
            edit.setVisibility(buttons);
            delete.setVisibility(buttons);
            drag.setVisibility(reorder ? View.VISIBLE : View.GONE);
        }
    }
}
