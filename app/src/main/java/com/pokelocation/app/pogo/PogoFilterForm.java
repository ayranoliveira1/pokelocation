package com.pokelocation.app.pogo;

import android.content.Context;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckedTextView;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import com.pokelocation.app.Io;
import com.pokelocation.app.R;
import com.pokelocation.app.util.Ui;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Todos os filtros do §6.1 + o escopo, na ordem do docs/pogo/filtros-design.md (Onde, Ordenar, Espécies, Qualidade,
 * Aparência, Shiny, Avançado). Só views de framework por código: serve à PogoFiltersActivity e ao PogoFilterSheet no
 * :overlay. Edita o PogoFilter ao vivo e chama onChanged.
 */
public final class PogoFilterForm {
    private final Context c;
    private final PogoFilter f;
    private final Runnable onChanged, linkTrainer;
    private final Consumer<Boolean> focusable;
    private final FrameLayout root;
    private final ScrollView scroll;
    private final LinearLayout body;
    private final int pad;
    private View page;
    private boolean advanced;
    private List<Pogo.Species> catalog;
    private Io.Task task;

    /** focusable: liga/desliga o teclado da janela do :overlay ao editar (null no app); linkTrainer null = sem atalho. */
    public PogoFilterForm(Context c, PogoFilter f, Runnable onChanged, Consumer<Boolean> focusable, Runnable linkTrainer) {
        this.c = c;
        this.f = f;
        this.onChanged = onChanged;
        this.focusable = focusable;
        this.linkTrainer = linkTrainer;
        pad = Ui.dp(c, 16);
        root = new FrameLayout(c);
        scroll = new ScrollView(c);
        body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(pad, 0, pad, pad);
        scroll.addView(body);
        root.addView(scroll);
        render();
    }

    public View view() { return root; }

    /** true se fechou a subtela de espécies. */
    public boolean back() {
        if (page == null) return false;
        root.removeView(page);
        page = null;
        focus(false);
        scroll.setVisibility(View.VISIBLE);
        render();
        return true;
    }

    /** Volta ao padrão sem mexer no escopo. */
    public void reset() {
        PogoFilter d = new PogoFilter();
        d.km = f.km;
        f.set(d);
        changed();
        render();
    }

    public void destroy() {
        if (task != null) task.cancel();
        task = null;
        catalog = null;
    }

    public void render() {
        body.removeAllViews();
        title(R.string.pogo_f_where);
        seg(new String[]{c.getString(R.string.pogo_f_near), c.getString(R.string.pogo_panel_world)}, f.km == 0 ? 1 : 0, w -> {
            f.km = w == 1 ? 0 : 1;
            body.post(this::render);
        });
        if (f.km != 0) {
            String[] km = new String[PogoFilter.KMS.length];
            for (int i = 0; i < km.length; i++) km[i] = c.getString(R.string.pogo_panel_km, PogoFilter.KMS[i]);
            seg(km, indexOf(PogoFilter.KMS, f.km), w -> f.km = PogoFilter.KMS[w]);
        }
        title(R.string.pogo_f_sort);
        seg(c.getResources().getStringArray(R.array.pogo_sorts), PogoFilter.SORTS.indexOf(f.sort), w -> f.sort = PogoFilter.SORTS.get(w));

        title(R.string.pogo_f_species);
        int n = f.pokemonIds.size() + f.speciesForms.size();
        link((n == 0 ? c.getString(R.string.all) : c.getString(R.string.selected_count, n)) + "  ›", v -> species());
        if (n > 0) {
            PogoChips.Flow chips = new PogoChips.Flow(c);
            List<String> keys = new ArrayList<>();
            for (int id : f.pokemonIds) keys.add(PogoFilter.labelKey(id, -1));
            for (int[] sf : f.speciesForms) keys.add(PogoFilter.labelKey(sf[0], sf[1]));
            for (int i = 0; i < Math.min(6, keys.size()); i++) {
                String k = keys.get(i), l = f.labels.get(k);
                TextView t = PogoChips.chip(c, (l != null ? l : "#" + k.replace("|-1", "").replace('|', '·')) + "  ×", false);
                t.setOnClickListener(v -> {
                    toggle(k, null);
                    changed();
                    render();
                });
                chips.addView(t);
            }
            if (keys.size() > 6) {
                TextView more = PogoChips.chip(c, "+" + (keys.size() - 6), false);
                more.setOnClickListener(v -> species());
                chips.addView(more);
            }
            body.addView(chips);
        }

        title(R.string.pogo_f_quality);
        boolean any = f.ivMin == null && f.ivMax == null;
        int iv = any ? 0 : f.ivMax == null && Double.valueOf(90).equals(f.ivMin) ? 1 : Double.valueOf(100).equals(f.ivMin) ? 2 : -1;
        seg(new String[]{c.getString(R.string.pogo_any), "≥ 90", "100"}, iv, w -> {
            f.ivMin = w == 0 ? null : w == 1 ? 90.0 : 100.0;
            f.ivMax = null;
            body.post(this::render);
        });
        pair(R.string.pogo_f_iv, true, f.ivMin, f.ivMax, (a, b) -> { f.ivMin = a; f.ivMax = b; });
        pair(R.string.pogo_f_cp, false, dbl(f.cpMin), dbl(f.cpMax), (a, b) -> { f.cpMin = integer(a); f.cpMax = integer(b); });
        pair(R.string.pogo_f_level, true, f.levelMin, f.levelMax, (a, b) -> { f.levelMin = a; f.levelMax = b; });

        title(R.string.pogo_f_look);
        label(R.string.pogo_f_gender);
        seg(new String[]{c.getString(R.string.pogo_any), "♂", "♀", "⚲"}, PogoFilter.GENDERS.indexOf(f.gender) + 1,
                w -> f.gender = w == 0 ? null : PogoFilter.GENDERS.get(w - 1));
        label(R.string.pogo_f_sizes);
        PogoChips.Flow sizes = new PogoChips.Flow(c);
        for (String s : PogoFilter.SIZES) {
            TextView t = PogoChips.chip(c, s, f.sizes.contains(s));
            t.setOnClickListener(v -> {
                PogoChips.select(t, !t.isSelected());
                f.sizes.remove(s);
                if (t.isSelected()) f.sizes.add(s);
                changed();
            });
            sizes.addView(t);
        }
        body.addView(sizes);
        sw(R.string.pogo_f_boosted, f.boosted != null && f.boosted, on -> f.boosted = on ? Boolean.TRUE : null);

        title(R.string.pogo_f_shiny);
        JSONArray trainers = Pogo.loggedIn(c) ? Pogo.trainers(c) : new JSONArray();
        if (!Pogo.loggedIn(c)) {
            text(c.getString(R.string.pogo_shiny_login));
        } else if (trainers.length() == 0) {
            text(c.getString(R.string.pogo_no_trainer));
            if (linkTrainer != null) link(c.getString(R.string.pogo_t_link) + "  ›", v -> linkTrainer.run());
        } else {
            sw(R.string.pogo_f_shiny_only, f.shinyOnly, on -> f.shinyOnly = on);
            sw(R.string.pogo_f_background, f.backgroundOnly, on -> f.backgroundOnly = on);
            // o 1º ativo é o padrão da API: vai como null; outro vai pelo trainer_code
            String[] names = new String[trainers.length()];
            int sel = 0;
            for (int i = 0; i < names.length; i++) {
                String code = Pogo.str(trainers.optJSONObject(i), "trainer_code"), name = Pogo.str(trainers.optJSONObject(i), "trainer_name");
                names[i] = name != null ? name : code;
                if (code.equals(f.shinyAccount)) sel = i;
            }
            if (names.length == 1) {
                text(c.getString(R.string.pogo_f_account) + ": " + names[0]);
            } else {
                label(R.string.pogo_f_account);
                seg(names, sel, w -> f.shinyAccount = w == 0 ? null : Pogo.str(trainers.optJSONObject(w), "trainer_code"));
            }
        }

        link(c.getString(R.string.set_cat_advanced) + (advanced ? "  ▴" : "  ▾"), v -> {
            advanced = !advanced;
            render();
        }).setTypeface(Typeface.DEFAULT_BOLD);
        if (advanced) {
            sw(R.string.pogo_f_pvp_mode, f.pvpMode, on -> {
                f.pvpMode = on;
                body.post(this::render);
            });
            if (f.pvpMode) {
                seg(c.getResources().getStringArray(R.array.pogo_leagues), PogoFilter.LEAGUES.indexOf(f.pvpLeague),
                        w -> f.pvpLeague = PogoFilter.LEAGUES.get(w));
                LinearLayout r = new LinearLayout(c);
                field(r, R.string.pogo_f_top_rank, false, dbl(f.pvpTopRank), x -> f.pvpTopRank = integer(x), null);
                field(r, R.string.pogo_f_pvp_min_cp, false, dbl(f.pvpMinCp), x -> f.pvpMinCp = integer(x), null);
                body.addView(r);
                sw(R.string.pogo_f_pre_evo, f.pvpIncludePreEvolutions, on -> f.pvpIncludePreEvolutions = on);
            }
            label(R.string.pogo_f_results);
            String[] counts = new String[PogoFilter.NUM_RESULTS.length];
            for (int i = 0; i < counts.length; i++) counts[i] = String.valueOf(PogoFilter.NUM_RESULTS[i]);
            seg(counts, indexOf(PogoFilter.NUM_RESULTS, f.numResults), w -> f.numResults = PogoFilter.NUM_RESULTS[w]);
        }
    }

    private void changed() { if (onChanged != null) onChanged.run(); }

    private void focus(boolean on) { if (focusable != null) focusable.accept(on); }

    /** Título de seção: 14sp, negrito, cor primária, 16dp acima. */
    private void title(int res) {
        TextView t = label(res);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(c.getColor(R.color.primary));
        t.setPadding(0, pad, 0, 0);
    }

    private TextView label(int res) {
        TextView t = new TextView(c);
        t.setText(res);
        t.setTextSize(14);
        t.setTextColor(c.getColor(R.color.on_surface_variant));
        t.setPadding(0, pad / 2, 0, 0);
        body.addView(t);
        return t;
    }

    private TextView text(String s) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(16);
        t.setTextColor(c.getColor(R.color.on_surface));
        t.setMinHeight(Ui.dp(c, 48));
        t.setGravity(Gravity.CENTER_VERTICAL);
        body.addView(t);
        return t;
    }

    private TextView link(String s, View.OnClickListener click) {
        TextView t = text(s);
        t.setOnClickListener(click);
        return t;
    }

    /** Segmentado = chips com uma seleção só. */
    private void seg(String[] labels, int checked, IntConsumer pick) {
        PogoChips.Flow g = new PogoChips.Flow(c);
        for (int i = 0; i < labels.length; i++) {
            TextView t = PogoChips.chip(c, labels[i], i == checked);
            int w = i;
            t.setOnClickListener(v -> {
                for (int k = 0; k < g.getChildCount(); k++) PogoChips.select((TextView) g.getChildAt(k), k == w);
                pick.accept(w);
                changed();
            });
            g.addView(t);
        }
        body.addView(g);
    }

    private void sw(int res, boolean on, Consumer<Boolean> set) {
        Switch s = new Switch(c);
        s.setText(res);
        s.setTextSize(16);
        s.setTextColor(c.getColor(R.color.on_surface));
        s.setMinHeight(Ui.dp(c, 48));
        s.setChecked(on);
        s.setOnCheckedChangeListener((x, v) -> {
            set.accept(v);
            changed();
        });
        body.addView(s);
    }

    private interface Pair { void set(Double min, Double max); }

    /** Rótulo visível + Mín/Máx lado a lado + erro inline se mín > máx. Vazio ou inválido = sem limite. */
    private void pair(int res, boolean decimal, Double min, Double max, Pair set) {
        label(res);
        LinearLayout r = new LinearLayout(c);
        body.addView(r);
        TextView err = new TextView(c);
        err.setText(R.string.pogo_f_range_error);
        err.setTextColor(c.getColor(R.color.danger));
        err.setVisibility(View.GONE);
        body.addView(err);
        Double[] v = {min, max};
        Runnable check = () -> err.setVisibility(v[0] != null && v[1] != null && v[0] > v[1] ? View.VISIBLE : View.GONE);
        field(r, R.string.pogo_f_min, decimal, min, x -> set.set(v[0] = x, v[1]), check);
        field(r, R.string.pogo_f_max, decimal, max, x -> set.set(v[0], v[1] = x), check);
        check.run();
    }

    private void field(LinearLayout r, int hint, boolean decimal, Double v, Consumer<Double> set, Runnable after) {
        EditText e = edit(hint);
        e.setInputType(decimal ? InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL : InputType.TYPE_CLASS_NUMBER);
        e.setText(value(v));
        watch(e, s -> {
            set.accept(parse(s));
            if (after != null) after.run();
            changed();
        });
        r.addView(e, new LinearLayout.LayoutParams(0, -2, 1));
    }

    /** A janela do :overlay só fica focável enquanto um campo está em edição. */
    private EditText edit(int hint) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setMinHeight(Ui.dp(c, 48));
        e.setOnTouchListener((v, ev) -> {
            if (ev.getAction() == MotionEvent.ACTION_DOWN) focus(true);
            return false;
        });
        // o foco só troca no ACTION_UP: Mín → Máx tiraria o foco da janela com o Máx em edição
        e.setOnFocusChangeListener((v, has) -> {
            if (!has) root.post(() -> { if (root.findFocus() == null) focus(false); });
        });
        return e;
    }

    private static void watch(EditText e, Consumer<String> after) {
        e.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int n) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int n) {}
            @Override public void afterTextChanged(Editable s) { after.accept(s.toString()); }
        });
    }

    /** label null = remover; senão marca e guarda o rótulo para o chip. */
    private void toggle(String key, String label) {
        int id = id(key), form = form(key);
        if (form == -1) f.pokemonIds.remove((Integer) id);
        else f.speciesForms.removeIf(sf -> sf[0] == id && sf[1] == form);
        f.labels.remove(key);
        if (label == null) return;
        if (form == -1) f.pokemonIds.add(id);
        else f.speciesForms.add(new int[]{id, form});
        f.labels.put(key, label);
    }

    private boolean selected(String key) {
        int id = id(key), form = form(key);
        if (form == -1) return f.pokemonIds.contains(id);
        for (int[] sf : f.speciesForms) if (sf[0] == id && sf[1] == form) return true;
        return false;
    }

    private static int id(String key) { return Integer.parseInt(key.substring(0, key.indexOf('|'))); }

    private static int form(String key) { return Integer.parseInt(key.substring(key.indexOf('|') + 1)); }

    private void species() {
        if (catalog != null) {
            speciesPage();
            return;
        }
        if (task != null) return;
        Context app = c.getApplicationContext();
        task = Io.run(() -> Pogo.catalog(app), new Io.Callback<List<Pogo.Species>>() {
            @Override public void onResult(List<Pogo.Species> l) {
                task = null;
                l.sort((x, y) -> Integer.compare(x.id, y.id)); // agrupa as formas de cada espécie
                catalog = l;
                speciesPage();
            }
            @Override public void onError(Throwable t) {
                task = null;
                Ui.toast(c, R.string.pogo_catalog_failed);
            }
        });
    }

    /** Subtela empilhada: "‹" volta; uma linha "qualquer forma" por espécie e uma por forma quando há mais de uma. */
    private void speciesPage() {
        List<String> keys = new ArrayList<>(), labels = new ArrayList<>();
        for (int i = 0; i < catalog.size(); i++) {
            Pogo.Species s = catalog.get(i);
            String name = s.name != null ? s.name : "#" + s.id;
            if (i == 0 || catalog.get(i - 1).id != s.id) {
                keys.add(PogoFilter.labelKey(s.id, -1));
                labels.add(c.getString(R.string.pogo_f_any_form, name));
            }
            if ((i > 0 && catalog.get(i - 1).id == s.id) || (i + 1 < catalog.size() && catalog.get(i + 1).id == s.id)) {
                keys.add(PogoFilter.labelKey(s.id, s.form));
                labels.add(name + " · " + (s.formName != null ? s.formName : "#" + s.form));
            }
        }
        List<Integer> shown = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) shown.add(i);
        LayoutInflater inf = LayoutInflater.from(c);
        BaseAdapter adapter = new BaseAdapter() {
            @Override public int getCount() { return shown.size(); }
            @Override public Object getItem(int p) { return keys.get(shown.get(p)); }
            @Override public long getItemId(int p) { return shown.get(p); }
            @Override public View getView(int p, View v, ViewGroup parent) {
                CheckedTextView t = (CheckedTextView) (v != null ? v
                        : inf.inflate(android.R.layout.simple_list_item_multiple_choice, parent, false));
                t.setText(labels.get(shown.get(p)));
                t.setChecked(selected(keys.get(shown.get(p))));
                return t;
            }
        };
        LinearLayout p = new LinearLayout(c);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(pad, 0, pad, 0);
        TextView back = new TextView(c);
        back.setText("‹  " + c.getString(R.string.pogo_f_species));
        back.setTextSize(16);
        back.setTypeface(Typeface.DEFAULT_BOLD);
        back.setTextColor(c.getColor(R.color.on_surface));
        back.setMinHeight(Ui.dp(c, 48));
        back.setGravity(Gravity.CENTER_VERTICAL);
        back.setOnClickListener(v -> back());
        p.addView(back);
        EditText search = edit(R.string.pogo_f_search);
        p.addView(search);
        ListView lv = new ListView(c);
        lv.setAdapter(adapter);
        lv.setOnItemClickListener((x, v, pos, id) -> {
            int i = shown.get(pos);
            toggle(keys.get(i), selected(keys.get(i)) ? null : labels.get(i));
            changed();
            adapter.notifyDataSetChanged();
        });
        p.addView(lv, new LinearLayout.LayoutParams(-1, 0, 1));
        watch(search, s -> {
            String q = s.trim().toLowerCase(Locale.ROOT);
            shown.clear();
            for (int i = 0; i < keys.size(); i++)
                if (q.isEmpty() || labels.get(i).toLowerCase(Locale.ROOT).contains(q) || keys.get(i).startsWith(q + "|"))
                    shown.add(i);
            adapter.notifyDataSetChanged();
        });
        scroll.setVisibility(View.GONE);
        page = p;
        root.addView(p, new FrameLayout.LayoutParams(-1, -1));
    }

    private static int indexOf(int[] a, int v) {
        for (int i = 0; i < a.length; i++) if (a[i] == v) return i;
        return -1;
    }

    static String value(Double v) {
        if (v == null) return "";
        return v == Math.rint(v) ? String.valueOf(v.longValue()) : String.valueOf(v);
    }

    static Double parse(String s) {
        try {
            double v = Double.parseDouble(s.trim().replace(',', '.'));
            return Double.isNaN(v) || Double.isInfinite(v) ? null : v;
        } catch (NumberFormatException x) {
            return null;
        }
    }

    private static Double dbl(Integer v) { return v == null ? null : v.doubleValue(); }

    private static Integer integer(Double v) { return v == null ? null : (int) Math.round(v); }
}
