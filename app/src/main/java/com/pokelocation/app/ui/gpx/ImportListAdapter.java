package com.pokelocation.app.ui.gpx;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.pokelocation.app.R;
import com.pokelocation.app.geo.CoordParser;
import com.pokelocation.app.gpx.FolderTree;
import com.pokelocation.app.gpx.ImportData;
import com.pokelocation.app.util.Ui;

import java.util.List;

/** Lista de uma página do import: waypoints/points, rotas/lines, tracks ou segmentos de um track. */
final class ImportListAdapter extends TreeAdapter {
    static final int POINTS = 0, LINES = 1, TRACKS = 2, SEGMENTS = 3;
    private static final int T_HEADER = 0, T_ITEM = 1;

    interface Listener {
        void onAction(Object row);                 // botão "+" do item
        void onClick(Object row);                  // toque no item (tracks → segmentos)
        void onAddAllInFolder(List<Object> items); // "Add all in folder"
    }

    private final Context ctx;
    private final LayoutInflater inflater;
    private final int mode;
    private final ImportData.Item track;   // SEGMENTS
    private final Listener listener;
    private final int base, step;
    private final String untitled;

    ImportListAdapter(Context ctx, int mode, ImportData.Item track, List<Object> rows, Listener listener) {
        this.ctx = ctx;
        this.inflater = LayoutInflater.from(ctx);
        this.mode = mode;
        this.track = track;
        this.listener = listener;
        this.base = Ui.dp(ctx, 16);
        this.step = Ui.dp(ctx, 24);
        this.untitled = ctx.getString(R.string.untitled);
        this.rows.addAll(rows);
    }

    @Override
    int itemLevel(Object o) {
        return o instanceof ImportData.Item ? FolderTree.depth(((ImportData.Item) o).folder) : 0;
    }

    @Override
    public int getItemViewType(int position) {
        return rows.get(position) instanceof FolderTree.Header ? T_HEADER : T_ITEM;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return viewType == T_HEADER ? new HeaderVH(inflater.inflate(R.layout.gpx_header, parent, false))
                : new ItemVH(inflater.inflate(R.layout.gpx_item, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Object o = rows.get(position);
        if (holder instanceof HeaderVH) bindHeader((HeaderVH) holder, (FolderTree.Header) o);
        else bindItem((ItemVH) holder, o);
    }

    private void bindHeader(HeaderVH h, FolderTree.Header hd) {
        h.itemView.setPaddingRelative(Ui.dp(ctx, 8) + hd.level * step, 0, h.itemView.getPaddingEnd(), 0);
        h.title.setText(hd.title);
        h.arrow.setImageResource(hd.hidden == null ? R.drawable.ic_expand_less : R.drawable.ic_expand_more);
        h.action.setVisibility(mode == POINTS || mode == LINES ? View.VISIBLE : View.GONE);
    }

    private void bindItem(ItemVH h, Object o) {
        int level = itemLevel(o);
        h.itemView.setPaddingRelative(base + level * step, h.itemView.getPaddingTop(), h.itemView.getPaddingEnd(),
                h.itemView.getPaddingBottom());
        boolean saved, showAdd;
        if (mode == SEGMENTS) {
            int s = (Integer) o;
            int n = track.segs.get(s).length / 3;
            h.title.setText(ctx.getResources().getQuantityString(R.plurals.gpx_coordinates, n, n));
            gone(h.sub);
            gone(h.sub2);
            saved = track.segIsSaved(s);
            showAdd = !saved;
        } else {
            ImportData.Item it = (ImportData.Item) o;
            h.title.setText(it.name == null ? untitled : it.name);
            text(h.sub, it.desc);
            if (mode == POINTS) {
                String coords = CoordParser.text(it.pts[0], it.pts[1]);
                double alt = it.pts[2];
                text(h.sub2, Double.isNaN(alt) ? coords
                        : ctx.getString(R.string.gpx_point_line, coords, String.valueOf(alt)));
                saved = it.saved;
                showAdd = true;
            } else if (mode == LINES) {
                int n = it.count();
                text(h.sub2, ctx.getResources().getQuantityString(R.plurals.gpx_coordinates, n, n));
                saved = it.saved;
                showAdd = !saved;
            } else {   // TRACKS
                int n = it.count();
                text(h.sub2, ctx.getResources().getQuantityString(R.plurals.gpx_segments, n, n));
                saved = it.allSegsSaved();
                showAdd = false;
            }
        }
        h.saved.setVisibility(saved ? View.VISIBLE : View.GONE);
        h.action.setVisibility(showAdd ? View.VISIBLE : View.GONE);
        h.itemView.setClickable(mode == TRACKS);
    }

    private static void text(TextView tv, CharSequence s) {
        if (s == null || s.length() == 0) {
            tv.setVisibility(View.GONE);
        } else {
            tv.setText(s);
            tv.setVisibility(View.VISIBLE);
        }
    }

    private static void gone(View v) { v.setVisibility(View.GONE); }

    private final class HeaderVH extends RecyclerView.ViewHolder {
        final TextView title;
        final ImageView arrow;
        final View action;

        HeaderVH(View v) {
            super(v);
            title = v.findViewById(R.id.gpx_title);
            arrow = v.findViewById(R.id.gpx_arrow);
            action = v.findViewById(R.id.gpx_header_action);
            v.setOnClickListener(x -> {
                int p = getBindingAdapterPosition();
                if (p != RecyclerView.NO_POSITION) toggle(p);
            });
            action.setOnClickListener(x -> {
                int p = getBindingAdapterPosition();
                if (p != RecyclerView.NO_POSITION) listener.onAddAllInFolder(collect(p));
            });
        }
    }

    private final class ItemVH extends RecyclerView.ViewHolder {
        final TextView title, sub, sub2;
        final ImageView saved;
        final ImageButton action;

        ItemVH(View v) {
            super(v);
            title = v.findViewById(R.id.gpx_title);
            sub = v.findViewById(R.id.gpx_sub);
            sub2 = v.findViewById(R.id.gpx_sub2);
            saved = v.findViewById(R.id.gpx_saved);
            action = v.findViewById(R.id.gpx_action);
            action.setOnClickListener(x -> {
                int p = getBindingAdapterPosition();
                if (p != RecyclerView.NO_POSITION) listener.onAction(rows.get(p));
            });
            v.setOnClickListener(x -> {
                int p = getBindingAdapterPosition();
                if (p != RecyclerView.NO_POSITION && mode == TRACKS) listener.onClick(rows.get(p));
            });
        }
    }
}
