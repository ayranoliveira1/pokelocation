package com.pokelocation.app.ui.favorites;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.pokelocation.app.R;
import com.pokelocation.app.geo.Cooldown;
import com.pokelocation.app.geo.Geo;
import com.pokelocation.app.model.Coord;
import com.pokelocation.app.model.Favorite;
import com.pokelocation.app.model.Folder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Lista da FavoritesActivity: pastas primeiro, depois favoritos. Filtro em memória sobre o backup (05 §3.2). */
final class FavAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    static final int T_FOLDER = 0, T_FAV = 1;

    private final FavoritesActivity act;
    private final LayoutInflater inflater;
    private final int selColor;

    final ArrayList<Folder> folders = new ArrayList<>();
    final ArrayList<Favorite> favs = new ArrayList<>();
    private Map<String, Integer> counts = Collections.emptyMap();
    private ArrayList<Folder> backupFolders;   // != null = filtro ativo
    private ArrayList<Favorite> backupFavs;
    private String filter = "";

    final LinkedHashMap<String, Folder> selFolders = new LinkedHashMap<>();
    final LinkedHashMap<String, Favorite> selFavs = new LinkedHashMap<>();
    boolean reorder;

    // Linha de distância/cooldown (atualizada pelo ticker só nas linhas visíveis)
    private Coord ref;
    private long lastMocked;
    private boolean showLine;

    FavAdapter(FavoritesActivity act) {
        this.act = act;
        inflater = LayoutInflater.from(act);
        selColor = ContextCompat.getColor(act, R.color.primary_container);
    }

    // --- dados ---

    void setData(List<Folder> f, List<Favorite> v, Map<String, Integer> c) {
        counts = c != null ? c : Collections.emptyMap();
        if (backupFolders != null) {
            backupFolders = new ArrayList<>(f);
            backupFavs = new ArrayList<>(v);
            applyFilter();
        } else {
            folders.clear();
            folders.addAll(f);
            favs.clear();
            favs.addAll(v);
        }
        notifyDataSetChanged();
    }

    boolean filtering() { return backupFolders != null; }

    /** "" restaura a lista original sem I/O. */
    @SuppressLint("NotifyDataSetChanged")
    void setFilter(String q) {
        q = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        if (q.equals(filter)) return;
        filter = q;
        if (q.isEmpty()) {
            if (backupFolders != null) {
                folders.clear();
                folders.addAll(backupFolders);
                favs.clear();
                favs.addAll(backupFavs);
                backupFolders = null;
                backupFavs = null;
            }
        } else {
            if (backupFolders == null) {
                backupFolders = new ArrayList<>(folders);
                backupFavs = new ArrayList<>(favs);
            }
            applyFilter();
        }
        notifyDataSetChanged();
    }

    private void applyFilter() {
        folders.clear();
        for (Folder f : backupFolders) if (f.name.toLowerCase(Locale.ROOT).contains(filter)) folders.add(f);
        favs.clear();
        for (Favorite f : backupFavs) {
            if (f.name.toLowerCase(Locale.ROOT).contains(filter) || f.address.toLowerCase(Locale.ROOT).contains(filter)) favs.add(f);
        }
    }

    boolean isEmpty() { return folders.isEmpty() && favs.isEmpty(); }

    int favPosition(String id) {
        for (int i = 0; i < favs.size(); i++) if (favs.get(i).id.equals(id)) return folders.size() + i;
        return -1;
    }

    /** Remoção granular (delete de 1 favorito). */
    void removeFav(String id) {
        if (backupFavs != null) {
            for (int i = 0; i < backupFavs.size(); i++) if (backupFavs.get(i).id.equals(id)) { backupFavs.remove(i); break; }
        }
        int pos = favPosition(id);
        if (pos < 0) return;
        favs.remove(pos - folders.size());
        notifyItemRemoved(pos);
    }

    /** Inserção granular no fim (novo favorito/cópia na pasta atual, sem filtro). */
    int appendFav(Favorite f) {
        favs.add(f);
        int pos = folders.size() + favs.size() - 1;
        notifyItemInserted(pos);
        return pos;
    }

    /** Arrasto: só a lista em memória muda (grava no drop). */
    boolean move(int from, int to) {
        int nf = folders.size();
        if (from < nf && to < nf) folders.add(to, folders.remove(from));
        else if (from >= nf && to >= nf) favs.add(to - nf, favs.remove(from - nf));
        else return false;
        notifyItemMoved(from, to);
        return true;
    }

    // --- seleção ---

    int selectedCount() { return selFolders.size() + selFavs.size(); }

    void toggle(Object item, int pos) {
        if (item instanceof Folder) {
            Folder f = (Folder) item;
            if (selFolders.remove(f.id) == null) selFolders.put(f.id, f);
        } else {
            Favorite f = (Favorite) item;
            if (selFavs.remove(f.id) == null) selFavs.put(f.id, f);
        }
        if (pos >= 0) notifyItemChanged(pos);
    }

    boolean allVisibleSelected() {
        for (Folder f : folders) if (!selFolders.containsKey(f.id)) return false;
        for (Favorite f : favs) if (!selFavs.containsKey(f.id)) return false;
        return !isEmpty();
    }

    @SuppressLint("NotifyDataSetChanged")
    void selectAllVisible(boolean select) {
        for (Folder f : folders) { if (select) selFolders.put(f.id, f); else selFolders.remove(f.id); }
        for (Favorite f : favs) { if (select) selFavs.put(f.id, f); else selFavs.remove(f.id); }
        notifyItemRangeChanged(0, getItemCount());
    }

    @SuppressLint("NotifyDataSetChanged")
    void clearSelection() {
        if (selectedCount() == 0) return;
        selFolders.clear();
        selFavs.clear();
        notifyItemRangeChanged(0, getItemCount());
    }

    // --- linha de distância ---

    /** Chamado no onResume: referência e flag; retorna se o ticker deve rodar. */
    @SuppressLint("NotifyDataSetChanged")
    boolean setLineSource(Coord ref, long lastMocked, boolean visible) {
        boolean changed = visible != showLine;
        this.ref = ref;
        this.lastMocked = lastMocked;
        this.showLine = visible && ref != null;
        if (changed) notifyDataSetChanged();
        return showLine;
    }

    /** Atualiza só as linhas visíveis, sem notify. */
    void tickVisible(RecyclerView rv) {
        if (!showLine) return;
        LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
        if (lm == null) return;
        int first = lm.findFirstVisibleItemPosition(), last = lm.findLastVisibleItemPosition();
        if (first < 0) return;
        long now = System.currentTimeMillis();
        for (int p = Math.max(first, folders.size()); p <= last && p < getItemCount(); p++) {
            RecyclerView.ViewHolder h = rv.findViewHolderForAdapterPosition(p);
            if (h instanceof FavVH) bindLine((FavVH) h, favs.get(p - folders.size()), now);
        }
    }

    private void bindLine(FavVH h, Favorite f, long now) {
        if (!showLine) {
            h.line.setVisibility(View.GONE);
            return;
        }
        Context c = act;
        double m = Geo.distance(ref.lat, ref.lng, f.lat, f.lng);
        h.line.setText(Cooldown.line(c, m, lastMocked, now));
        h.line.setTextColor(Cooldown.color(c, m, lastMocked, now));
        h.line.setVisibility(View.VISIBLE);
    }

    // --- adapter ---

    @Override public int getItemCount() { return folders.size() + favs.size(); }

    @Override public int getItemViewType(int position) { return position < folders.size() ? T_FOLDER : T_FAV; }

    Object itemAt(int pos) {
        if (pos < 0 || pos >= getItemCount()) return null;
        return pos < folders.size() ? folders.get(pos) : favs.get(pos - folders.size());
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
        return type == T_FOLDER ? new FolderVH(inflater.inflate(R.layout.fav_item_folder, parent, false))
                : new FavVH(inflater.inflate(R.layout.fav_item, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int pos) {
        if (holder instanceof FolderVH) {
            FolderVH h = (FolderVH) holder;
            Folder f = folders.get(pos);
            h.name.setText(f.name);
            Integer n = counts.get(f.id);
            int count = n == null ? 0 : n;
            h.count.setText(act.getResources().getQuantityString(R.plurals.fav_n_favorites, count, count));
            h.open.setVisibility(reorder ? View.GONE : View.VISIBLE);
            h.drag.setVisibility(reorder ? View.VISIBLE : View.GONE);
            h.setSelected(selFolders.containsKey(f.id));
        } else {
            FavVH h = (FavVH) holder;
            Favorite f = favs.get(pos - folders.size());
            h.name.setText(f.name);
            h.address.setText(f.address);
            h.address.setVisibility(f.address.isEmpty() ? View.GONE : View.VISIBLE);
            h.coords.setText(act.getString(R.string.fav_coords,
                    String.valueOf(f.lat), String.valueOf(f.lng), String.valueOf(f.alt)));
            boolean actions = !reorder && act.showItemActions();
            int v = actions ? View.VISIBLE : View.GONE;
            h.copy.setVisibility(v);
            h.edit.setVisibility(v);
            h.delete.setVisibility(v);
            h.drag.setVisibility(reorder ? View.VISIBLE : View.GONE);
            h.setSelected(selFavs.containsKey(f.id));
            bindLine(h, f, System.currentTimeMillis());
        }
    }

    // --- view holders ---

    abstract class BaseVH extends RecyclerView.ViewHolder {
        final MaterialCardView card;
        private final ColorStateList normal;

        @SuppressLint("ClickableViewAccessibility")
        BaseVH(View v, View dragHandle) {
            super(v);
            card = (MaterialCardView) v;
            normal = card.getCardBackgroundColor();
            v.setOnClickListener(x -> {
                int p = getBindingAdapterPosition();
                Object it = itemAt(p);
                if (it != null) act.onItemClick(it, p);
            });
            v.setOnLongClickListener(x -> {
                if (reorder) return false;     // o ItemTouchHelper cuida do arrasto
                int p = getBindingAdapterPosition();
                Object it = itemAt(p);
                return it != null && act.onItemLongClick(it, p);
            });
            dragHandle.setOnTouchListener((x, e) -> {
                if (reorder && e.getActionMasked() == MotionEvent.ACTION_DOWN) act.startDrag(this);
                return false;
            });
        }

        void setSelected(boolean selected) {
            if (selected) card.setCardBackgroundColor(selColor);
            else card.setCardBackgroundColor(normal);
        }
    }

    final class FolderVH extends BaseVH {
        final TextView name, count;
        final ImageView open, drag;

        FolderVH(View v) {
            this(v, v.findViewById(R.id.fav_folder_drag));
        }

        private FolderVH(View v, ImageView drag) {
            super(v, drag);
            this.drag = drag;
            name = v.findViewById(R.id.fav_folder_name);
            count = v.findViewById(R.id.fav_folder_count);
            open = v.findViewById(R.id.fav_folder_open);
        }
    }

    final class FavVH extends BaseVH {
        final TextView name, address, coords, line;
        final View copy, edit, delete;
        final ImageView drag;

        FavVH(View v) {
            this(v, v.findViewById(R.id.fav_drag));
        }

        private FavVH(View v, ImageView drag) {
            super(v, drag);
            this.drag = drag;
            name = v.findViewById(R.id.fav_name);
            address = v.findViewById(R.id.fav_address);
            coords = v.findViewById(R.id.fav_coords);
            line = v.findViewById(R.id.fav_line);
            copy = v.findViewById(R.id.fav_copy);
            edit = v.findViewById(R.id.fav_edit);
            delete = v.findViewById(R.id.fav_delete);
            copy.setOnClickListener(x -> { Favorite f = fav(); if (f != null) act.copyFavorite(f); });
            edit.setOnClickListener(x -> { Favorite f = fav(); if (f != null) act.editFavorite(f); });
            delete.setOnClickListener(x -> { Favorite f = fav(); if (f != null) act.confirmDeleteFavorite(f); });
        }

        private Favorite fav() {
            Object it = itemAt(getBindingAdapterPosition());
            return it instanceof Favorite ? (Favorite) it : null;
        }
    }
}
