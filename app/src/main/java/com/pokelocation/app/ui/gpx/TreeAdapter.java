package com.pokelocation.app.ui.gpx;

import androidx.recyclerview.widget.RecyclerView;

import com.pokelocation.app.gpx.FolderTree;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Lista plana de cabeçalhos ({@link FolderTree.Header}) e itens com recolher/expandir.
 * Recolher REMOVE a faixa do cabeçalho da lista (guardada em Header.hidden).
 * Faixa = elementos seguintes com nível maior que o do cabeçalho.
 */
abstract class TreeAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    final ArrayList<Object> rows = new ArrayList<>();
    private final HashSet<String> collapsedKeys = new HashSet<>();

    /** Nível de um elemento que não é Header (item, linha vazia…). */
    abstract int itemLevel(Object o);

    final int level(Object o) {
        return o instanceof FolderTree.Header ? ((FolderTree.Header) o).level : itemLevel(o);
    }

    @Override
    public int getItemCount() { return rows.size(); }

    /** Troca a lista inteira reaplicando o recolhimento por chave (export, que remonta a cada mudança). */
    void setRows(List<Object> newRows) {
        rows.clear();
        rows.addAll(newRows);
        for (int i = rows.size() - 1; i >= 0; i--) {   // de trás para frente: internos recolhem antes dos externos
            Object o = rows.get(i);
            if (o instanceof FolderTree.Header && collapsedKeys.contains(((FolderTree.Header) o).key)) collapse(i);
        }
        notifyDataSetChanged();
    }

    private int rangeEnd(int pos) {
        int lvl = level(rows.get(pos)), i = pos + 1;
        while (i < rows.size() && level(rows.get(i)) > lvl) i++;
        return i;
    }

    private int collapse(int pos) {
        FolderTree.Header h = (FolderTree.Header) rows.get(pos);
        List<Object> range = rows.subList(pos + 1, rangeEnd(pos));
        h.hidden = new ArrayList<>(range);
        int n = range.size();
        range.clear();
        return n;
    }

    void toggle(int pos) {
        FolderTree.Header h = (FolderTree.Header) rows.get(pos);
        if (h.hidden == null) {
            int n = collapse(pos);
            collapsedKeys.add(h.key);
            if (n > 0) notifyItemRangeRemoved(pos + 1, n);
        } else {
            List<Object> back = h.hidden;
            h.hidden = null;
            collapsedKeys.remove(h.key);
            rows.addAll(pos + 1, back);
            if (!back.isEmpty()) notifyItemRangeInserted(pos + 1, back.size());
        }
        notifyItemChanged(pos);
    }

    /** Itens (não cabeçalhos) sob o cabeçalho em pos, inclusive os de subpastas recolhidas. */
    List<Object> collect(int pos) {
        ArrayList<Object> out = new ArrayList<>();
        FolderTree.Header h = (FolderTree.Header) rows.get(pos);
        if (h.hidden != null) collectAll(h.hidden, out);
        else collectAll(rows.subList(pos + 1, rangeEnd(pos)), out);
        return out;
    }

    private static void collectAll(List<Object> list, List<Object> out) {
        for (Object o : list) {
            if (o instanceof FolderTree.Header) {
                List<Object> hid = ((FolderTree.Header) o).hidden;
                if (hid != null) collectAll(hid, out);
            } else if (!(o instanceof String)) {   // String = linha "No items selected!"
                out.add(o);
            }
        }
    }

    /** Re-binda tudo (estado "salvo" mudou em vários itens); sem mexer na estrutura. */
    void refreshAll() {
        if (!rows.isEmpty()) notifyItemRangeChanged(0, rows.size());
    }
}
