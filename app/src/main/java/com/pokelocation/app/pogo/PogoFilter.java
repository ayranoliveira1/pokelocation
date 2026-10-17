package com.pokelocation.app.pogo;

import android.content.Context;
import android.content.SharedPreferences;

import com.pokelocation.app.App;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Filtros do modo "pokemon" do /map/lookup, salvos só no aparelho. null = sem filtro. */
public final class PogoFilter {
    static final List<String> SORTS = Arrays.asList("IV", "DSP", "CP", "LEVEL");
    static final List<String> LEAGUES = Arrays.asList("great", "ultra");
    static final List<String> GENDERS = Arrays.asList("Male", "Female", "Genderless");
    static final List<String> SIZES = Arrays.asList("XXS", "XS", "S", "M", "L", "XL", "XXL");
    static final int[] NUM_RESULTS = {100, 250, 500}, KMS = {1, 2, 5, 10};
    private static final String KEY = "filters", AT = "filters_at", OV_FILE = "pogo_ov", SAVED = "saved", SAVED_AT = "saved_at";
    static final int MAX_SAVED = 20;

    public final List<Integer> pokemonIds = new ArrayList<>();
    /** {pokemon_id, form_id} */
    public final List<int[]> speciesForms = new ArrayList<>();
    public Double ivMin, ivMax, levelMin, levelMax;
    public Integer cpMin, cpMax, pvpTopRank, pvpMinCp;
    public String gender;
    public final List<String> sizes = new ArrayList<>();
    public Boolean boosted;
    public String sort = "IV";
    public boolean pvpMode;
    public String pvpLeague = "great";
    public boolean pvpIncludePreEvolutions = true;
    public boolean shinyOnly, backgroundOnly;
    public String shinyAccount;
    public int numResults = 100;
    /** Escopo da lista: raio de "Perto de mim" em km; 0 = Mundo inteiro (o padrão). Só local, não vai para a API. */
    public int km = 0;
    /** A pessoa escolheu o escopo no form: antes do padrão Mundo, todo filtro salvo levava km=1 sem ter escolhido. */
    boolean kmSet;
    /** Rótulo das espécies escolhidas ("id|form" → "Rattata · Alola"), para os chips sem baixar o catálogo. Só local. */
    public final Map<String, String> labels = new HashMap<>();

    /** O mais recente entre o "pogo" (main) e o "pogo_ov" (:overlay), pelo filters_at de cada um. */
    public static PogoFilter load(Context c) {
        SharedPreferences main = Pogo.prefs(c), ov = ov(c);
        String s = newest(main.getString(KEY, null), main.getLong(AT, 0), ov.getString(KEY, null), ov.getLong(AT, 0));
        if (s == null) return new PogoFilter();
        try {
            return fromJson(new JSONObject(s));
        } catch (JSONException e) {
            return new PogoFilter();
        }
    }

    /**
     * Um escritor por arquivo (sem broadcast, que acordaria o main durante o jogo): o main grava no "pogo", o :overlay
     * no "pogo_ov"; os outros processos não gravam. O load fica com o mais novo.
     */
    public void save(Context c) {
        int p = App.process();
        SharedPreferences sp = Pogo.writer(p) ? Pogo.prefs(c) : p == App.PROC_OVERLAY ? ov(c) : null;
        if (sp != null) sp.edit().putString(KEY, stored().toString()).putLong(AT, System.currentTimeMillis()).apply();
    }

    public static final class Saved {
        public final String name;
        public final PogoFilter filter;

        public Saved(String name, PogoFilter filter) {
            this.name = name;
            this.filter = filter;
        }
    }

    /** Os filtros salvos com nome, no mesmo esquema de arquivos do filtro atual (a lista inteira, a mais nova vence). */
    public static List<Saved> saved(Context c) {
        SharedPreferences main = Pogo.prefs(c), ov = ov(c);
        return savedFrom(newest(main.getString(SAVED, null), main.getLong(SAVED_AT, 0), ov.getString(SAVED, null), ov.getLong(SAVED_AT, 0)));
    }

    public static void storeSaved(Context c, List<Saved> l) {
        int p = App.process();
        SharedPreferences sp = Pogo.writer(p) ? Pogo.prefs(c) : p == App.PROC_OVERLAY ? ov(c) : null;
        if (sp != null) sp.edit().putString(SAVED, savedJson(l)).putLong(SAVED_AT, System.currentTimeMillis()).apply();
    }

    static String savedJson(List<Saved> l) {
        JSONArray a = new JSONArray();
        try {
            for (Saved x : l) a.put(new JSONObject().put("name", x.name).put("filter", x.filter.stored()));
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return a.toString();
    }

    static List<Saved> savedFrom(String s) {
        List<Saved> out = new ArrayList<>();
        JSONArray a;
        try {
            a = s != null ? new JSONArray(s) : new JSONArray();
        } catch (JSONException e) {
            return out;
        }
        for (int i = 0; i < a.length() && out.size() < MAX_SAVED; i++) {
            JSONObject o = a.optJSONObject(i), f = o != null ? o.optJSONObject("filter") : null;
            String name = o != null ? o.optString("name").trim() : "";
            if (f != null && !name.isEmpty()) out.add(new Saved(name, fromJson(f)));
        }
        return out;
    }

    /** Nome repetido não vale: compara sem maiúsculas e sem os espaços das pontas. */
    static boolean taken(List<Saved> l, String name) {
        String n = name.trim().toLowerCase(Locale.ROOT);
        for (Saved x : l) if (x.name.trim().toLowerCase(Locale.ROOT).equals(n)) return true;
        return false;
    }

    static boolean canSave(List<Saved> l) { return l.size() < MAX_SAVED; }

    /** O salvo igual ao filtro (o valor do select); null = Nenhum. */
    static String chosen(List<Saved> l, PogoFilter f) {
        for (Saved x : l) if (x.filter.same(f)) return x.name;
        return null;
    }

    /** O mesmo filtro (os rótulos das espécies são só exibição). */
    boolean same(PogoFilter o) {
        JSONObject a = stored(), b = o.stored();
        a.remove("labels");
        b.remove("labels");
        return a.toString().equals(b.toString());
    }

    @SuppressWarnings("deprecation")
    private static SharedPreferences ov(Context c) { return c.getSharedPreferences(OV_FILE, Context.MODE_MULTI_PROCESS); }

    /** Empate fica com o do main. */
    static String newest(String main, long mainAt, String ov, long ovAt) {
        if (ov == null) return main;
        return main == null || ovAt > mainAt ? ov : main;
    }

    public PogoFilter copy() { return fromJson(stored()); }

    /** Filtros ativos para o badge e o "Mostrar resultados (N)"; escopo, ordem e nº de resultados não contam. */
    public int activeCount() {
        int n = 0;
        for (Object v : new Object[]{ivMin != null || ivMax != null ? 1 : null, cpMin != null || cpMax != null ? 1 : null,
                levelMin != null || levelMax != null ? 1 : null, gender, boosted, pvpMode ? 1 : null,
                shinyOnly ? 1 : null, backgroundOnly ? 1 : null})
            if (v != null) n++;
        if (!pokemonIds.isEmpty() || !speciesForms.isEmpty()) n++;
        if (!sizes.isEmpty()) n++;
        if (km != 0) n++; // o padrão Mundo não conta
        return n;
    }

    static String labelKey(int id, int form) { return id + "|" + form; }

    /** Troca o conteúdo sem trocar o objeto (quem tem a referência vê a mudança). */
    public void set(PogoFilter o) {
        pokemonIds.clear();
        pokemonIds.addAll(o.pokemonIds);
        speciesForms.clear();
        speciesForms.addAll(o.speciesForms);
        sizes.clear();
        sizes.addAll(o.sizes);
        ivMin = o.ivMin;
        ivMax = o.ivMax;
        levelMin = o.levelMin;
        levelMax = o.levelMax;
        cpMin = o.cpMin;
        cpMax = o.cpMax;
        pvpTopRank = o.pvpTopRank;
        pvpMinCp = o.pvpMinCp;
        gender = o.gender;
        boosted = o.boosted;
        sort = o.sort;
        pvpMode = o.pvpMode;
        pvpLeague = o.pvpLeague;
        pvpIncludePreEvolutions = o.pvpIncludePreEvolutions;
        shinyOnly = o.shinyOnly;
        backgroundOnly = o.backgroundOnly;
        shinyAccount = o.shinyAccount;
        numResults = o.numResults;
        km = o.km;
        kmSet = o.kmSet;
        labels.clear();
        labels.putAll(o.labels);
    }

    /** O que vai para as prefs: os filtros + num_results + km. */
    JSONObject stored() {
        try {
            return toJson().put("num_results", numResults).put("km", km).put("km_set", kmSet).put("labels", new JSONObject(labels));
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** O objeto "filters": só o que difere do default (sort vai sempre). */
    JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        if (!pokemonIds.isEmpty()) o.put("pokemon_ids", new JSONArray(pokemonIds));
        if (!speciesForms.isEmpty()) {
            JSONArray a = new JSONArray();
            for (int[] sf : speciesForms) a.put(new JSONObject().put("pokemon_id", sf[0]).put("form_id", sf[1]));
            o.put("species_forms", a);
        }
        o.putOpt("iv_min", ivMin).putOpt("iv_max", ivMax).putOpt("cp_min", cpMin).putOpt("cp_max", cpMax)
                .putOpt("level_min", levelMin).putOpt("level_max", levelMax).putOpt("gender", gender)
                .putOpt("boosted", boosted);
        if (!sizes.isEmpty()) o.put("sizes", new JSONArray(sizes));
        o.put("sort", sort);
        if (pvpMode) o.put("pvp_mode", true);
        if (!"great".equals(pvpLeague)) o.put("pvp_league", pvpLeague);
        o.putOpt("pvp_top_rank", pvpTopRank);
        if (!pvpIncludePreEvolutions) o.put("pvp_include_pre_evolutions", false);
        o.putOpt("pvp_min_cp", pvpMinCp);
        if (shinyOnly) o.put("shiny_only", true);
        o.putOpt("shiny_account", shinyAccount);
        if (backgroundOnly) o.put("background_only", true);
        return o;
    }

    /** O servidor não valida enums: valor fora da lista cai no default em vez de filtrar nada. */
    static PogoFilter fromJson(JSONObject o) {
        PogoFilter f = new PogoFilter();
        JSONArray a = o.optJSONArray("pokemon_ids");
        for (int i = 0; a != null && i < a.length(); i++) if (a.opt(i) instanceof Number) f.pokemonIds.add(a.optInt(i));
        a = o.optJSONArray("species_forms");
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject sf = a.optJSONObject(i);
            if (sf != null && sf.opt("pokemon_id") instanceof Number && sf.opt("form_id") instanceof Number)
                f.speciesForms.add(new int[]{sf.optInt("pokemon_id"), sf.optInt("form_id")});
        }
        f.ivMin = dbl(o, "iv_min");
        f.ivMax = dbl(o, "iv_max");
        f.levelMin = dbl(o, "level_min");
        f.levelMax = dbl(o, "level_max");
        f.cpMin = integer(o, "cp_min");
        f.cpMax = integer(o, "cp_max");
        f.pvpTopRank = integer(o, "pvp_top_rank");
        f.pvpMinCp = integer(o, "pvp_min_cp");
        f.gender = oneOf(o, "gender", GENDERS, null);
        a = o.optJSONArray("sizes");
        for (int i = 0; a != null && i < a.length(); i++) {
            String s = a.optString(i);
            if (SIZES.contains(s) && !f.sizes.contains(s)) f.sizes.add(s);
        }
        if (o.opt("boosted") instanceof Boolean) f.boosted = o.optBoolean("boosted");
        f.sort = oneOf(o, "sort", SORTS, "IV");
        f.pvpMode = o.optBoolean("pvp_mode");
        f.pvpLeague = oneOf(o, "pvp_league", LEAGUES, "great");
        f.pvpIncludePreEvolutions = o.optBoolean("pvp_include_pre_evolutions", true);
        f.shinyOnly = o.optBoolean("shiny_only");
        f.backgroundOnly = o.optBoolean("background_only");
        f.shinyAccount = o.opt("shiny_account") instanceof String ? o.optString("shiny_account") : null;
        int n = o.optInt("num_results", 100);
        f.numResults = n == 250 || n == 500 ? n : 100;
        JSONObject l = o.optJSONObject("labels");
        for (Iterator<String> it = l != null ? l.keys() : null; it != null && it.hasNext(); ) {
            String k = it.next();
            f.labels.put(k, l.optString(k));
        }
        int km = o.optInt("km", 0);
        f.kmSet = o.optBoolean("km_set");
        f.km = km == 1 || km == 2 || km == 5 || km == 10 ? km : 0;
        if (f.km == 1 && !f.kmSet) f.km = 0; // o 1 km antigo era o padrão, não uma escolha
        return f;
    }

    private static Double dbl(JSONObject o, String k) {
        Object v = o.opt(k);
        return v instanceof Number ? ((Number) v).doubleValue() : null;
    }

    private static Integer integer(JSONObject o, String k) {
        Object v = o.opt(k);
        return v instanceof Number ? ((Number) v).intValue() : null;
    }

    private static String oneOf(JSONObject o, String k, List<String> allowed, String def) {
        Object v = o.opt(k);
        return allowed.contains(v) ? (String) v : def;
    }
}
