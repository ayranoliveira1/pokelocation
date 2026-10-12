package com.pokelocation.app.ui.markers;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.textfield.TextInputEditText;
import com.pokelocation.app.Io;
import com.pokelocation.app.R;
import com.pokelocation.app.data.MarkerDao;
import com.pokelocation.app.ipc.Intents;
import com.pokelocation.app.map.MapHelper;
import com.pokelocation.app.model.MarkerType;
import com.pokelocation.app.ui.base.BaseActivity;
import com.pokelocation.app.ui.base.StateView;
import com.pokelocation.app.ui.gpx.ImportActivity;
import com.pokelocation.app.util.Ui;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/**
 * Tipos de marcador (F80, 05 §2.4/§3.13): lista com ícone/nome/contagem, criar/editar (nome + ícone local + cor),
 * excluir (1 e 2 só zeram os marcadores), select_mode → type_ids int[] (§3.12), editor no mapa (MarkerMapView, F81).
 */
public class MarkerTypesActivity extends BaseActivity {
    /** Paleta fixa dos tipos (azuis + cores de contraste; sem roxo). */
    private static final int[] COLORS = {0xFF1E88E5, 0xFF0D47A1, 0xFF43A047, 0xFF00897B,
            0xFFFB8C00, 0xFFE53935, 0xFFFDD835, 0xFF6D4C41};

    private Context app;
    private Toolbar toolbar;
    private FrameLayout content;
    private StateView state;
    private FloatingActionButton fab;
    private TypeAdapter adapter;
    private Menu menu;
    private boolean selectMode;
    private final LinkedHashSet<Integer> selected = new LinkedHashSet<>();
    private MarkerMapView mapView;
    private Io.Task loadTask;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.mk_activity);
        app = getApplicationContext();
        selectMode = Intents.getBool(getIntent(), Intents.EXTRA_SELECT_MODE, false);
        toolbar = setupToolbar(getString(selectMode ? R.string.mk_select_title : R.string.mk_title), true);
        toolbar.setNavigationOnClickListener(v -> back());
        applyInsets(findViewById(R.id.mk_root));

        content = findViewById(R.id.mk_content);
        state = findViewById(R.id.mk_state);
        state.setOnRetry(this::load);
        RecyclerView list = findViewById(R.id.mk_list);
        list.setHasFixedSize(true);
        adapter = new TypeAdapter();
        list.setAdapter(adapter);
        fab = findViewById(R.id.mk_fab);
        fab.setOnClickListener(v -> showTypeDialog(null));

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { back(); }
        });
        updateChrome();
        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mapView != null) mapView.onResume();
    }

    @Override
    protected void onPause() {
        if (mapView != null) mapView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (loadTask != null) loadTask.cancel();
        if (mapView != null) {
            mapView.destroy();
            mapView = null;
        }
        super.onDestroy();
    }

    private void load() {
        if (adapter.items.isEmpty()) state.setState(StateView.LOADING);
        if (loadTask != null) loadTask.cancel();
        loadTask = Io.run(() -> MarkerDao.types(app), new Io.Callback<List<MarkerType>>() {
            @Override public void onResult(List<MarkerType> types) {
                if (isDestroyed()) return;
                adapter.set(types);
                state.setState(types.isEmpty() ? StateView.EMPTY : StateView.CONTENT);
            }

            @Override public void onError(Throwable t) {
                if (!isDestroyed()) state.setState(StateView.ERROR);
            }
        });
    }

    // ------------------------------------------------------------------ toolbar

    @Override
    public boolean onCreateOptionsMenu(Menu m) {
        getMenuInflater().inflate(R.menu.mk_main, m);
        menu = m;
        applyMenu();
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            back();
        } else if (id == R.id.mk_menu_confirm) {
            returnSelection();
        } else if (id == R.id.mk_menu_import) {
            startActivity(new Intent(this, ImportActivity.class));
        } else {
            return super.onOptionsItemSelected(item);
        }
        return true;
    }

    private void applyMenu() {
        boolean map = mapView != null;
        menu.findItem(R.id.mk_menu_confirm).setVisible(!map && selectMode);
        menu.findItem(R.id.mk_menu_import).setVisible(!map && !selectMode);
    }

    private void updateChrome() {
        if (mapView != null) toolbar.setTitle(mapView.type().name);
        else toolbar.setTitle(selectMode ? R.string.mk_select_title : R.string.mk_title);
        if (mapView == null && !selectMode) fab.show(); else fab.hide();
        if (menu != null) applyMenu();
    }

    private void back() {
        if (mapView != null) {
            if (!mapView.onBackPressed()) closeMap();
            return;
        }
        finish();
    }

    private void returnSelection() {
        if (selected.isEmpty()) {
            Ui.toast(this, R.string.mk_none_selected);
            return;
        }
        int[] ids = new int[selected.size()];
        int i = 0;
        for (Integer id : selected) ids[i++] = id;
        setResult(RESULT_OK, new Intent().putExtra(Intents.EXTRA_TYPE_IDS, ids));
        finish();
    }

    // ------------------------------------------------------------------ mapa do tipo (F81)

    private void openMap(MarkerType t) {
        if (mapView != null) return;
        mapView = new MarkerMapView(this, t, this::closeMap);
        content.addView(mapView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        updateChrome();
    }

    /** Destrói e remove (nunca GONE); recontagem do tipo e atualização da linha. */
    private void closeMap() {
        if (mapView == null) return;
        final MarkerType t = mapView.type();
        mapView.destroy();
        content.removeView(mapView);
        mapView = null;
        updateChrome();
        io(() -> MarkerDao.countMarkers(app, t.id),
                n -> adapter.replace(new MarkerType(t.id, t.name, t.icon, t.color, n)));
    }

    // ------------------------------------------------------------------ CRUD de tipos

    private void confirmDelete(MarkerType t) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.mk_delete_title)
                .setMessage(getString(t.deletable() ? R.string.mk_delete_msg : R.string.mk_delete_default_msg, t.name))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (d, w) -> io(() -> {
                    MarkerDao.deleteType(app, t.id);
                    return Boolean.TRUE;
                }, ok -> {
                    selected.remove(t.id);
                    load();
                }))
                .show();
    }

    /** Diálogo de tipo: nome obrigatório + ícone (MapHelper.ICON_KEYS) + cor. t == null cria. */
    private void showTypeDialog(MarkerType t) {
        View v = getLayoutInflater().inflate(R.layout.mk_type_dialog, null);
        TextInputEditText name = v.findViewById(R.id.mk_type_name);
        LinearLayout icons = v.findViewById(R.id.mk_type_icons);
        LinearLayout colors = v.findViewById(R.id.mk_type_colors);
        final String[] icon = {t != null ? t.icon : MapHelper.ICON_KEYS[0]};
        final int[] color = {t != null ? t.color : COLORS[0]};
        if (t != null) name.setText(t.name);

        int size = Ui.dp(this, 44), pad = Ui.dp(this, 8), margin = Ui.dp(this, 4);
        int ring = ContextCompat.getColor(this, R.color.on_surface);
        Runnable[] refresh = new Runnable[1];
        for (String key : MapHelper.ICON_KEYS) {
            ImageView iv = new ImageView(this);
            iv.setImageResource(iconRes(key));
            iv.setPadding(pad, pad, pad, pad);
            iv.setContentDescription(getString(iconLabel(key)));
            iv.setTag(key);
            iv.setOnClickListener(x -> { icon[0] = key; refresh[0].run(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, size, 1f);
            lp.setMargins(margin / 2, margin, margin / 2, margin);
            icons.addView(iv, lp);
        }
        for (int c : COLORS) {
            View sw = new View(this);
            sw.setTag(c);
            sw.setOnClickListener(x -> { color[0] = c; refresh[0].run(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 32), Ui.dp(this, 32));
            lp.setMargins(margin * 2, margin * 2, margin * 2, margin * 2);
            colors.addView(sw, lp);
        }
        refresh[0] = () -> {
            for (int i = 0; i < icons.getChildCount(); i++) {
                ImageView iv = (ImageView) icons.getChildAt(i);
                boolean on = icon[0].equals(iv.getTag());
                iv.setImageTintList(ColorStateList.valueOf(color[0]));
                iv.setBackground(on ? ring(ring) : null);
            }
            for (int i = 0; i < colors.getChildCount(); i++) {
                View sw = colors.getChildAt(i);
                int c = (Integer) sw.getTag();
                GradientDrawable g = new GradientDrawable();
                g.setShape(GradientDrawable.OVAL);
                g.setColor(c);
                if (c == color[0]) g.setStroke(Ui.dp(this, 3), ring);
                sw.setBackground(g);
            }
        };
        refresh[0].run();

        AlertDialog d = new MaterialAlertDialogBuilder(this)
                .setTitle(t == null ? R.string.mk_add_type : R.string.mk_edit_type)
                .setView(v)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, (dlg, w) -> {
                    String n = name.getText() == null ? "" : name.getText().toString().trim();
                    if (n.isEmpty()) return;
                    io(() -> {
                        if (t == null) MarkerDao.insertType(app, n, icon[0], color[0]);
                        else MarkerDao.updateType(app, new MarkerType(t.id, n, icon[0], color[0], t.count));
                        return Boolean.TRUE;
                    }, ok -> load());
                })
                .create();
        d.show();
        Button save = d.getButton(AlertDialog.BUTTON_POSITIVE);
        Runnable check = () -> save.setEnabled(name.getText() != null && name.getText().toString().trim().length() > 0);
        check.run();
        name.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}

            @Override public void afterTextChanged(Editable s) { check.run(); }
        });
    }

    /** Anel em volta do ícone escolhido. */
    private GradientDrawable ring(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(0);
        g.setStroke(Ui.dp(this, 2), color);
        return g;
    }

    private static int iconRes(String key) {
        switch (key) {
            case "flag": return R.drawable.ic_flag;
            case "park": return R.drawable.ic_park;
            case "star": return R.drawable.ic_star;
            case "home": return R.drawable.ic_home;
            case "heart": return R.drawable.ic_favorite;
            default: return R.drawable.ic_pin;
        }
    }

    private static int iconLabel(String key) {
        switch (key) {
            case "flag": return R.string.mk_icon_flag;
            case "park": return R.string.mk_icon_park;
            case "star": return R.string.mk_icon_star;
            case "home": return R.string.mk_icon_home;
            case "heart": return R.string.mk_icon_heart;
            default: return R.string.mk_icon_pin;
        }
    }

    // ------------------------------------------------------------------ util

    private <T> void io(Callable<T> work, Consumer<T> ok) {
        Io.run(work, new Io.Callback<T>() {
            @Override public void onResult(T v) { if (!isDestroyed()) ok.accept(v); }

            @Override public void onError(Throwable t) {
                if (!isDestroyed()) Ui.toast(MarkerTypesActivity.this, R.string.unknown_error);
            }
        });
    }

    // ------------------------------------------------------------------ lista

    private final class TypeAdapter extends RecyclerView.Adapter<TypeAdapter.VH> {
        final ArrayList<MarkerType> items = new ArrayList<>();
        private final LayoutInflater inflater = getLayoutInflater();
        private final int selColor = ContextCompat.getColor(MarkerTypesActivity.this, R.color.primary_container);

        @SuppressWarnings("NotifyDataSetChanged")
        void set(List<MarkerType> types) {
            items.clear();
            items.addAll(types);
            notifyDataSetChanged();
        }

        void replace(MarkerType t) {
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).id == t.id) {
                    items.set(i, t);
                    notifyItemChanged(i);
                    return;
                }
            }
        }

        @Override public int getItemCount() { return items.size(); }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            return new VH(inflater.inflate(R.layout.mk_item, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            MarkerType t = items.get(pos);
            Drawable d = MapHelper.typeIcon(MarkerTypesActivity.this, t);
            Drawable.ConstantState cs = d == null ? null : d.getConstantState();
            h.icon.setImageDrawable(cs != null ? cs.newDrawable(getResources()) : d);   // bitmap compartilhado
            h.name.setText(t.name);
            h.count.setText(getResources().getQuantityString(R.plurals.mk_n_markers, t.count, t.count));
            int v = selectMode ? View.GONE : View.VISIBLE;
            h.map.setVisibility(v);
            h.edit.setVisibility(v);
            h.delete.setVisibility(v);
            if (selectMode && selected.contains(t.id)) h.card.setCardBackgroundColor(selColor);
            else h.card.setCardBackgroundColor(h.normal);
        }

        final class VH extends RecyclerView.ViewHolder {
            final MaterialCardView card;
            final ColorStateList normal;
            final ImageView icon;
            final TextView name, count;
            final View map, edit, delete;

            VH(View v) {
                super(v);
                card = (MaterialCardView) v;
                normal = card.getCardBackgroundColor();
                icon = v.findViewById(R.id.mk_icon);
                name = v.findViewById(R.id.mk_name);
                count = v.findViewById(R.id.mk_count);
                map = v.findViewById(R.id.mk_map);
                edit = v.findViewById(R.id.mk_edit);
                delete = v.findViewById(R.id.mk_delete);
                v.setOnClickListener(x -> {
                    MarkerType t = item();
                    if (t == null) return;
                    if (selectMode) {
                        if (!selected.remove(t.id)) selected.add(t.id);
                        notifyItemChanged(getBindingAdapterPosition());
                    } else {
                        openMap(t);
                    }
                });
                map.setOnClickListener(x -> { MarkerType t = item(); if (t != null) openMap(t); });
                edit.setOnClickListener(x -> { MarkerType t = item(); if (t != null) showTypeDialog(t); });
                delete.setOnClickListener(x -> { MarkerType t = item(); if (t != null) confirmDelete(t); });
            }

            private MarkerType item() {
                int p = getBindingAdapterPosition();
                return p >= 0 && p < items.size() ? items.get(p) : null;
            }
        }
    }
}
