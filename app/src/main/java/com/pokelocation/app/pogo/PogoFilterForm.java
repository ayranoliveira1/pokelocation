package com.pokelocation.app.pogo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.CheckBox;
import android.widget.ImageView;
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
 * Todos os filtros + o escopo, na ordem Onde, Ordenar, Espécies, Qualidade,
 * Aparência, Shiny, Avançado. Só views de framework por código: serve à PogoFiltersActivity e ao PogoFilterSheet no
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

    /** Volta ao padrão, inclusive o escopo (Mundo inteiro). */
    public void reset() {
        f.set(new PogoFilter());
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
        // o bloco que lê como uma unidade: seletor e raio no mesmo container; o raio só no "Perto"
        LinearLayout where = new LinearLayout(c);
        where.setOrientation(LinearLayout.VERTICAL);
        where.setBackground(PogoChips.round(c, 16, c.getColor(R.color.pogo_surface_variant), 0));
        where.addView(new PogoChips.Segmented(c, new String[]{c.getString(R.string.pogo_f_near),
                c.getString(R.string.pogo_panel_world)}, f.km == 0 ? 1 : 0, w -> {
            f.km = w == 1 ? 0 : 1;
            f.kmSet = true;
            changed();
            body.post(this::render);
        }));
        if (f.km != 0) {
            String[] km = new String[PogoFilter.KMS.length];
            for (int i = 0; i < km.length; i++) km[i] = c.getString(R.string.pogo_panel_km, PogoFilter.KMS[i]);
            where.addView(new PogoChips.Segmented(c, km, indexOf(PogoFilter.KMS, f.km), w -> {
                f.km = PogoFilter.KMS[w];
                f.kmSet = true;
                changed();
            }));
        }
        body.addView(where);
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
                chips.addView(PogoChips.summaryChip(c, l != null ? l : "#" + k.replace("|-1", "").replace('|', '·'),
                        v -> species(), v -> {
                            toggle(k, null);
                            changed();
                            render();
                        }));
            }
            if (keys.size() > 6) chips.addView(PogoChips.summaryChip(c, "+" + (keys.size() - 6), v -> species(), null));
            body.addView(chips);
        }

        title(R.string.pogo_f_quality);
        boolean any = f.ivMin == null && f.ivMax == null;
        int iv = any ? 0 : f.ivMax == null && Double.valueOf(90).equals(f.ivMin) ? 1 : Double.valueOf(100).equals(f.ivMin) ? 2 : -1;
        // atalhos: o mesmo ChoiceGrid dos tamanhos, com escolha única (o render reaplica o estado)
        body.addView(new PogoChips.ChoiceGrid(c, new String[]{c.getString(R.string.pogo_any), "≥ 90", "100"},
                new boolean[]{iv == 0, iv == 1, iv == 2}, (w, v) -> {
            f.ivMin = w == 0 ? null : w == 1 ? 90.0 : 100.0;
            f.ivMax = null;
            changed();
            body.post(this::render);
        }));
        pair(R.string.pogo_f_iv, true, f.ivMin, f.ivMax, (a, b) -> { f.ivMin = a; f.ivMax = b; });
        pair(R.string.pogo_f_cp, false, dbl(f.cpMin), dbl(f.cpMax), (a, b) -> { f.cpMin = integer(a); f.cpMax = integer(b); });
        pair(R.string.pogo_f_level, true, f.levelMin, f.levelMax, (a, b) -> { f.levelMin = a; f.levelMax = b; });

        title(R.string.pogo_f_look);
        label(R.string.pogo_f_gender);
        seg(new String[]{c.getString(R.string.pogo_any), "♂", "♀", "⚲"}, PogoFilter.GENDERS.indexOf(f.gender) + 1,
                w -> f.gender = w == 0 ? null : PogoFilter.GENDERS.get(w - 1));
        label(R.string.pogo_f_sizes);
        String[] all = PogoFilter.SIZES.toArray(new String[0]);
        boolean[] picked = new boolean[all.length];
        for (int i = 0; i < all.length; i++) picked[i] = f.sizes.contains(all[i]);
        body.addView(new PogoChips.ChoiceGrid(c, all, picked, (i, v) -> {
            f.sizes.remove(all[i]);
            if (v) f.sizes.add(all[i]);
            changed();
        }));
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
        }).setTypeface(PogoChips.MEDIUM);
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

    /** Título de seção: 16sp medium, 24dp acima. */
    private void title(int res) {
        TextView t = label(res);
        t.setTextSize(16);
        t.setTypeface(PogoChips.MEDIUM);
        t.setTextColor(c.getColor(R.color.on_surface));
        t.setPadding(0, Ui.dp(c, 24), 0, Ui.dp(c, 8));
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

    /** Linha inteira de 56dp. */
    private TextView link(String s, View.OnClickListener click) {
        TextView t = text(s);
        t.setMinHeight(Ui.dp(c, 56));
        t.setOnClickListener(click);
        return t;
    }

    /** Escolha única: largura igual, uma linha (2×2 em tela estreita ou fonte grande, dentro do Segmented). */
    private void seg(String[] labels, int checked, IntConsumer pick) {
        body.addView(new PogoChips.Segmented(c, labels, checked, w -> {
            pick.accept(w);
            changed();
        }));
    }

    private void sw(int res, boolean on, Consumer<Boolean> set) {
        Switch s = new Switch(c); // linha inteira de 56dp: texto à esquerda, chave à direita, toda clicável
        s.setText(res);
        s.setTextSize(16);
        s.setTextColor(c.getColor(R.color.on_surface));
        s.setMinHeight(Ui.dp(c, 56));
        s.setChecked(on);
        s.setOnCheckedChangeListener((x, v) -> {
            set.accept(v);
            changed();
        });
        body.addView(s, new LinearLayout.LayoutParams(-1, -2));
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

    /** Coluna de peso 1 (12dp entre as duas): rótulo de 14sp acima e a caixa de 52dp. */
    private void field(LinearLayout r, int label, boolean decimal, Double v, Consumer<Double> set, Runnable after) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView l = new TextView(c);
        l.setText(label);
        l.setTextSize(14);
        l.setTextColor(c.getColor(R.color.on_surface_variant));
        l.setPadding(0, Ui.dp(c, 4), 0, Ui.dp(c, 4));
        col.addView(l);
        EditText e = edit(0);
        e.setInputType(decimal ? InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL : InputType.TYPE_CLASS_NUMBER);
        e.setText(value(v));
        watch(e, s -> {
            set.accept(parse(s));
            if (after != null) after.run();
            changed();
        });
        col.addView(e, new LinearLayout.LayoutParams(-1, Ui.dp(c, 52)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        if (r.getChildCount() > 0) lp.setMarginStart(Ui.dp(c, 12));
        r.addView(col, lp);
    }

    /** A janela do :overlay só fica focável enquanto um campo está em edição. */
    private EditText edit(int hint) {
        EditText e = new EditText(c);
        if (hint != 0) e.setHint(hint);
        e.setSingleLine(true);
        e.setTextSize(16);
        e.setMinHeight(Ui.dp(c, 52));
        GradientDrawable box = new GradientDrawable();
        box.setCornerRadius(Ui.dp(c, 12));
        box.setStroke(Ui.dp(c, 1), c.getColor(R.color.outline)); // controle interativo: ~4,5:1 (o pogo_outline é decorativo)
        e.setBackground(box);
        e.setPadding(Ui.dp(c, 12), 0, Ui.dp(c, 12), 0);
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

    /** Linha da lista de espécies: views da linha reciclada + a chave atual (descarta foto de pedido antigo). */
    private static final class Holder {
        ImageView img;
        TextView name, dex;
        CheckBox box;
        Pogo.Mon mon;
        /** 1× por linha: aplica a foto do Mon atual (linha reciclada já cai no novo, sem lambda por bind). */
        final Consumer<Bitmap> photo = b -> img.setImageBitmap(PogoIcons.cached(mon));
    }

    /**
     * Subtela empilhada: busca fixa, escolhidas como avatares removíveis e uma linha de 64dp com
     * foto por espécie ("qualquer forma") e por forma quando há mais de uma. Fotos só das linhas visíveis (PogoIcons).
     */
    private void speciesPage() {
        List<String> keys = new ArrayList<>(), labels = new ArrayList<>(), titles = new ArrayList<>(), dex = new ArrayList<>();
        List<Pogo.Mon> icons = new ArrayList<>();
        for (int i = 0; i < catalog.size(); i++) {
            Pogo.Species s = catalog.get(i);
            String name = s.name != null ? s.name : "#" + s.id;
            boolean several = (i > 0 && catalog.get(i - 1).id == s.id) || (i + 1 < catalog.size() && catalog.get(i + 1).id == s.id);
            if (i == 0 || catalog.get(i - 1).id != s.id) {
                keys.add(PogoFilter.labelKey(s.id, -1));
                labels.add(several ? c.getString(R.string.pogo_f_any_form, name) : name);
                titles.add(several ? c.getString(R.string.pogo_f_any_form, name) : name);
                icons.add(icon(s.id, name, null));
                dex.add(String.format(Locale.ROOT, "#%03d", s.id));
            }
            if (several) {
                String form = s.formName != null ? s.formName : "#" + s.form;
                keys.add(PogoFilter.labelKey(s.id, s.form));
                labels.add(name + " · " + form);
                titles.add(name + " · " + form);
                icons.add(icon(s.id, name, s.formName));
                dex.add(dex.get(dex.size() - 1));
            }
        }
        List<Integer> shown = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) shown.add(i);
        int dp = Ui.dp(c, 1);
        LinearLayout p = new LinearLayout(c);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(16 * dp, 0, 16 * dp, 0);

        LinearLayout head = new LinearLayout(c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = new TextView(c);
        back.setText("‹  " + c.getString(R.string.pogo_f_species));
        back.setTextSize(16);
        back.setTypeface(PogoChips.MEDIUM);
        back.setTextColor(c.getColor(R.color.on_surface));
        back.setGravity(Gravity.CENTER_VERTICAL);
        back.setOnClickListener(v -> back());
        head.addView(back, new LinearLayout.LayoutParams(0, 48 * dp, 1));
        TextView clear = new TextView(c);
        clear.setText(R.string.pogo_f_clear);
        clear.setTextSize(16);
        clear.setTextColor(c.getColor(R.color.primary));
        clear.setGravity(Gravity.CENTER);
        clear.setPadding(12 * dp, 0, 0, 0);
        head.addView(clear, new LinearLayout.LayoutParams(-2, 48 * dp));
        p.addView(head);

        EditText search = edit(R.string.pogo_f_search);
        search.setBackground(PogoChips.round(c, 24, c.getColor(R.color.pogo_surface_variant), 0));
        search.setPadding(16 * dp, 0, 16 * dp, 0);
        p.addView(search, new LinearLayout.LayoutParams(-1, 48 * dp));

        HorizontalScrollView chosenBar = new HorizontalScrollView(c);
        chosenBar.setHorizontalScrollBarEnabled(false);
        LinearLayout chosen = new LinearLayout(c);
        chosen.setPadding(0, 8 * dp, 0, 8 * dp);
        chosenBar.addView(chosen);
        p.addView(chosenBar);

        BaseAdapter[] adapter = new BaseAdapter[1];
        boolean[] flinging = {false}; // no fling só o cache: um fling de ponta a ponta não enfileira ~1.455 downloads
        Runnable[] refresh = new Runnable[1]; // o avatar removido remonta a faixa (e a esconde se ficou vazia)
        refresh[0] = () -> {
            chosen.removeAllViews();
            for (int i = 0; i < keys.size(); i++) {
                if (!selected(keys.get(i))) continue;
                int idx = i;
                chosen.addView(avatar(icons.get(i), titles.get(i), v -> {
                    toggle(keys.get(idx), null);
                    changed();
                    adapter[0].notifyDataSetChanged();
                    v.post(refresh[0]);
                }));
            }
            chosenBar.setVisibility(chosen.getChildCount() > 0 ? View.VISIBLE : View.GONE);
        };
        adapter[0] = new BaseAdapter() {
            @Override public int getCount() { return shown.size(); }
            @Override public Object getItem(int pos) { return keys.get(shown.get(pos)); }
            @Override public long getItemId(int pos) { return shown.get(pos); }
            @Override public View getView(int pos, View v, ViewGroup parent) {
                Holder h = v != null ? (Holder) v.getTag() : null;
                if (h == null) {
                    h = new Holder();
                    v = row(h);
                }
                int i = shown.get(pos);
                h.mon = icons.get(i);
                h.name.setText(labels.get(i));
                h.dex.setText(dex.get(i));
                h.box.setChecked(selected(keys.get(i)));
                Bitmap b = PogoIcons.cached(h.mon);
                h.img.setImageBitmap(b);
                if (b == null && !flinging[0]) PogoIcons.load(c, h.mon, h.photo);
                return v;
            }
        };
        ListView lv = new ListView(c);
        lv.setDivider(null);
        lv.setAdapter(adapter[0]);
        lv.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView view, int state) {
                flinging[0] = state == SCROLL_STATE_FLING;
                if (state != SCROLL_STATE_IDLE) return;
                for (int k = 0; k < view.getChildCount(); k++) { // parou: só as linhas visíveis
                    Holder h = (Holder) view.getChildAt(k).getTag();
                    if (h != null && h.mon != null && PogoIcons.cached(h.mon) == null) PogoIcons.load(c, h.mon, h.photo);
                }
            }
            @Override public void onScroll(AbsListView view, int first, int count, int total) {}
        });
        lv.setOnItemClickListener((x, v, pos, id) -> {
            int i = shown.get(pos);
            toggle(keys.get(i), selected(keys.get(i)) ? null : labels.get(i));
            changed();
            adapter[0].notifyDataSetChanged();
            refresh[0].run();
        });
        p.addView(lv, new LinearLayout.LayoutParams(-1, 0, 1));
        clear.setOnClickListener(v -> {
            for (String k : new ArrayList<>(keys)) if (selected(k)) toggle(k, null);
            changed();
            adapter[0].notifyDataSetChanged();
            refresh[0].run();
        });
        watch(search, s -> {
            String q = s.trim().toLowerCase(Locale.ROOT);
            shown.clear();
            for (int i = 0; i < keys.size(); i++)
                if (q.isEmpty() || labels.get(i).toLowerCase(Locale.ROOT).contains(q) || keys.get(i).startsWith(q + "|"))
                    shown.add(i);
            adapter[0].notifyDataSetChanged();
        });
        refresh[0].run();
        scroll.setVisibility(View.GONE);
        page = p;
        root.addView(p, new FrameLayout.LayoutParams(-1, -1));
    }

    /** Mon só para o PogoIcons (id, nome e forma em rótulo): a foto da espécie ou da forma. */
    private static Pogo.Mon icon(int id, String name, String form) {
        Pogo.Mon m = new Pogo.Mon();
        m.id = id;
        m.name = name;
        m.form = form;
        return m;
    }

    /** 64dp: foto de 40dp num disco de 48dp, nome + #dex, checkbox (a linha toda é o alvo). */
    private View row(Holder h) {
        int dp = Ui.dp(c, 1);
        LinearLayout r = new LinearLayout(c);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(64 * dp);
        r.setLayoutParams(new ListView.LayoutParams(-1, 64 * dp));
        h.img = disc(r, 48, 40);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(12 * dp, 0, 12 * dp, 0);
        h.name = new TextView(c);
        h.name.setTextSize(16);
        h.name.setTextColor(c.getColor(R.color.on_surface));
        h.name.setSingleLine(true);
        h.name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        h.dex = new TextView(c);
        h.dex.setTextSize(14);
        h.dex.setTextColor(c.getColor(R.color.on_surface_variant));
        h.dex.setFontFeatureSettings("tnum");
        col.addView(h.name);
        col.addView(h.dex);
        r.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        h.box = new CheckBox(c);
        h.box.setClickable(false);
        h.box.setFocusable(false);
        r.addView(h.box);
        r.setTag(h);
        return r;
    }

    /** Avatar de 40dp das escolhidas, com ✕ no canto; um toque remove. */
    private View avatar(Pogo.Mon m, String label, View.OnClickListener remove) {
        int dp = Ui.dp(c, 1);
        FrameLayout f = new FrameLayout(c);
        ImageView img = disc(f, 40, 32);
        Bitmap b = PogoIcons.cached(m);
        if (b != null) img.setImageBitmap(b);
        else PogoIcons.load(c, m, bmp -> { if (bmp != null) img.setImageBitmap(bmp); });
        TextView x = new TextView(c);
        x.setText("✕");
        x.setTextSize(10);
        x.setGravity(Gravity.CENTER);
        x.setTextColor(c.getColor(R.color.on_primary));
        x.setBackground(PogoChips.round(c, 8, c.getColor(R.color.primary), 0));
        f.addView(x, new FrameLayout.LayoutParams(16 * dp, 16 * dp, Gravity.TOP | Gravity.END));
        f.setContentDescription(label);
        f.setOnClickListener(remove);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(44 * dp, 44 * dp);
        lp.setMarginEnd(8 * dp);
        f.setLayoutParams(lp);
        return f;
    }

    /** Disco surface_variant de discDp com a foto de imgDp centralizada; devolve a ImageView. */
    private ImageView disc(ViewGroup parent, int discDp, int imgDp) {
        int dp = Ui.dp(c, 1);
        FrameLayout d = new FrameLayout(c);
        GradientDrawable o = new GradientDrawable();
        o.setShape(GradientDrawable.OVAL);
        o.setColor(c.getColor(R.color.pogo_surface_variant));
        d.setBackground(o);
        ImageView img = new ImageView(c);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        d.addView(img, new FrameLayout.LayoutParams(imgDp * dp, imgDp * dp, Gravity.CENTER));
        parent.addView(d, new ViewGroup.LayoutParams(discDp * dp, discDp * dp));
        return img;
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
