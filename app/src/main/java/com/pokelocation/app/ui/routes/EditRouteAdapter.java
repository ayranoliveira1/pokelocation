package com.pokelocation.app.ui.routes;

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.textfield.TextInputLayout;
import com.pokelocation.app.R;
import com.pokelocation.app.geo.Cooldown;

import java.math.BigDecimal;
import java.util.List;

/**
 * Posição 0 = cabeçalho (Name, Sort Order 1-based, distância + ETA); 1..n = coordenadas.
 * Uma linha em edição por vez; o rascunho dela fica aqui (sobrevive à reciclagem da View).
 */
final class EditRouteAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private static final int TYPE_HEADER = 0, TYPE_ROW = 1;
    private static final Object PAYLOAD_INFO = new Object();

    interface Host {
        void onEdited();
        void onMessage(int resId);
    }

    private final Context ctx;
    private final LayoutInflater inflater;
    private final Host host;
    private final RoutePoints pts;
    private final float kmh;
    private double length;

    private String name, sort;
    private int editing = -1;
    private String dNum = "", dLat = "", dLng = "", dAlt = "";

    EditRouteAdapter(Context c, Host host, RoutePoints pts, String name, String sort, float kmh) {
        ctx = c;
        inflater = LayoutInflater.from(c);
        this.host = host;
        this.pts = pts;
        this.name = name == null ? "" : name;
        this.sort = sort;
        this.kmh = kmh;
        length = pts.length();
    }

    String name() { return name.trim(); }
    RoutePoints points() { return pts; }
    boolean isEditing() { return editing >= 0; }

    /** Sort Order digitado (1-based); -1 se inválido. */
    int sortValue() {
        try {
            int v = Integer.parseInt(sort.trim());
            return v >= 1 ? v : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    boolean canSave() { return editing < 0 && !name().isEmpty() && sortValue() > 0 && pts.size() > 0; }

    /** FAB: adiciona (0,0) no fim já em edição; devolve a posição no adapter ou -1. */
    int addPoint() {
        if (editing >= 0) {
            host.onMessage(R.string.routes_finish_editing);
            return -1;
        }
        pts.add(0, 0, Double.NaN);
        editing = pts.size() - 1;
        dNum = String.valueOf(pts.size());
        dLat = "0";
        dLng = "0";
        dAlt = "";
        notifyItemInserted(pts.size());
        pointsChanged();
        return pts.size();
    }

    private void pointsChanged() {
        length = pts.length();
        notifyItemChanged(0, PAYLOAD_INFO);
        host.onEdited();
    }

    private static String fmt(double d) {
        return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
    }

    private static double parse(String s) {
        try {
            double v = Double.parseDouble(s.trim().replace(',', '.'));
            return Double.isInfinite(v) ? Double.NaN : v;
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    @Override public int getItemCount() { return pts.size() + 1; }

    @Override public int getItemViewType(int p) { return p == 0 ? TYPE_HEADER : TYPE_ROW; }

    @NonNull @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
        return type == TYPE_HEADER
                ? new HeaderVH(inflater.inflate(R.layout.routes_edit_header, parent, false))
                : new RowVH(inflater.inflate(R.layout.routes_edit_row, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder vh, int p) {
        if (vh instanceof HeaderVH) ((HeaderVH) vh).bind();
        else ((RowVH) vh).bind(p - 1);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder vh, int p, @NonNull List<Object> payloads) {
        if (vh instanceof HeaderVH && payloads.contains(PAYLOAD_INFO)) ((HeaderVH) vh).bindInfo();
        else onBindViewHolder(vh, p);
    }

    private abstract static class Watcher implements TextWatcher {
        @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
        @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
    }

    final class HeaderVH extends RecyclerView.ViewHolder {
        final TextInputLayout nameLayout, sortLayout;
        final EditText nameEdit, sortEdit;
        final TextView info;
        boolean binding;

        HeaderVH(View v) {
            super(v);
            nameLayout = v.findViewById(R.id.routes_edit_name_layout);
            sortLayout = v.findViewById(R.id.routes_edit_sort_layout);
            nameEdit = v.findViewById(R.id.routes_edit_name);
            sortEdit = v.findViewById(R.id.routes_edit_sort);
            info = v.findViewById(R.id.routes_edit_info);
            nameEdit.addTextChangedListener(new Watcher() {
                @Override public void afterTextChanged(Editable s) {
                    if (binding) return;
                    name = s.toString();
                    validate();
                    host.onEdited();
                }
            });
            sortEdit.addTextChangedListener(new Watcher() {
                @Override public void afterTextChanged(Editable s) {
                    if (binding) return;
                    sort = s.toString();
                    validate();
                    host.onEdited();
                }
            });
        }

        void bind() {
            binding = true;
            nameEdit.setText(name);
            sortEdit.setText(sort);
            binding = false;
            validate();
            bindInfo();
        }

        void validate() {
            nameLayout.setError(name().isEmpty() ? ctx.getString(R.string.field_required) : null);
            sortLayout.setError(sortValue() > 0 ? null : ctx.getString(R.string.field_number));
        }

        void bindInfo() {
            info.setText(ctx.getString(R.string.routes_route_info, Cooldown.formatDistance(length),
                    Cooldown.formatDuration(RoutePoints.etaMs(length, kmh))));
        }
    }

    final class RowVH extends RecyclerView.ViewHolder {
        final EditText num, lat, lng, alt;
        final ImageButton edit, delete;
        boolean binding;

        RowVH(View v) {
            super(v);
            num = v.findViewById(R.id.routes_row_num);
            lat = v.findViewById(R.id.routes_row_lat);
            lng = v.findViewById(R.id.routes_row_lng);
            alt = v.findViewById(R.id.routes_row_alt);
            edit = v.findViewById(R.id.routes_row_edit);
            delete = v.findViewById(R.id.routes_row_delete);
            num.addTextChangedListener(draft(0));
            lat.addTextChangedListener(draft(1));
            lng.addTextChangedListener(draft(2));
            alt.addTextChangedListener(draft(3));
            edit.setOnClickListener(x -> onEditClick());
            delete.setOnClickListener(x -> onDeleteClick());
        }

        private TextWatcher draft(int field) {
            return new Watcher() {
                @Override public void afterTextChanged(Editable s) {
                    if (binding || index() != editing) return;
                    String t = s.toString();
                    if (field == 0) dNum = t;
                    else if (field == 1) dLat = t;
                    else if (field == 2) dLng = t;
                    else dAlt = t;
                }
            };
        }

        private int index() { return getBindingAdapterPosition() - 1; }

        void bind(int i) {
            boolean ed = i == editing;
            binding = true;
            if (ed) {
                num.setText(dNum);
                lat.setText(dLat);
                lng.setText(dLng);
                alt.setText(dAlt);
            } else {
                num.setText(String.valueOf(i + 1));
                lat.setText(fmt(pts.lat(i)));
                lng.setText(fmt(pts.lng(i)));
                double a = pts.alt(i);
                alt.setText(Double.isNaN(a) ? "" : fmt(a));
            }
            binding = false;
            num.setError(null);
            lat.setError(null);
            lng.setError(null);
            alt.setError(null);
            num.setEnabled(ed);
            lat.setEnabled(ed);
            lng.setEnabled(ed);
            alt.setEnabled(ed);
            edit.setImageResource(ed ? R.drawable.ic_check : R.drawable.ic_edit);
            edit.setContentDescription(ctx.getString(ed ? R.string.save : R.string.edit));
        }

        private void onEditClick() {
            int i = index();
            if (i < 0) return;
            if (i == editing) {
                commit(i);
                return;
            }
            if (editing >= 0) {
                host.onMessage(R.string.routes_finish_editing);
                return;
            }
            editing = i;
            dNum = String.valueOf(i + 1);
            dLat = fmt(pts.lat(i));
            dLng = fmt(pts.lng(i));
            double a = pts.alt(i);
            dAlt = Double.isNaN(a) ? "" : fmt(a);
            notifyItemChanged(i + 1);
            host.onEdited();
            lat.requestFocus();
        }

        private void commit(int i) {
            boolean ok = true;
            int n;
            try {
                n = Integer.parseInt(dNum.trim());
            } catch (NumberFormatException e) {
                n = 0;
            }
            if (n < 1) {
                num.setError(ctx.getString(R.string.field_number));
                ok = false;
            }
            double la = parse(dLat), lo = parse(dLng);
            if (Double.isNaN(la) || Math.abs(la) > 90) {
                lat.setError(ctx.getString(R.string.routes_invalid_lat));
                ok = false;
            }
            if (Double.isNaN(lo) || Math.abs(lo) > 180) {
                lng.setError(ctx.getString(R.string.routes_invalid_lng));
                ok = false;
            }
            double al = dAlt.trim().isEmpty() ? Double.NaN : parse(dAlt);
            if (!dAlt.trim().isEmpty() && Double.isNaN(al)) {
                alt.setError(ctx.getString(R.string.field_number));
                ok = false;
            }
            if (!ok) return;
            pts.set(i, la, lo, al);
            editing = -1;
            int to = pts.move(i, n - 1);   // clamp 1..n
            if (to == i) {
                notifyItemChanged(i + 1);
            } else {
                notifyItemMoved(i + 1, to + 1);
                notifyItemRangeChanged(Math.min(i, to) + 1, Math.abs(to - i) + 1);   // renumera
            }
            pointsChanged();
        }

        private void onDeleteClick() {
            int i = index();
            if (i < 0) return;
            pts.remove(i);
            if (editing == i) editing = -1;
            else if (editing > i) editing--;
            notifyItemRemoved(i + 1);
            if (i < pts.size()) notifyItemRangeChanged(i + 1, pts.size() - i);   // renumera os seguintes
            pointsChanged();
        }
    }
}
