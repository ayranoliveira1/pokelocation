package com.pokelocation.app.pogo;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.util.JsonReader;
import android.util.JsonToken;

import com.pokelocation.app.App;
import com.pokelocation.app.BuildConfig;
import com.pokelocation.app.Io;
import com.pokelocation.app.social.Session;
import com.pokelocation.app.social.net.Api;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cliente do PoGo Coordinates. Tudo bloqueante: chamar fora da main. Vinculado (pogo_linked): tudo pelo nosso servidor
 * (/v1/pogo/*, que guarda o token deles); senão direto e anônimo. O app nunca vê o JWT do PoGo.
 */
public final class Pogo {
    static final String API = "https://coordinates-api.pokemongopro.com";
    static final String K_LINKED = "pogo_linked", K_NAME = "name", K_TRAINERS = "trainers", K_PENDING = "pending_at";
    public static final int MAX_RULES = 10;
    /** Spec §2.1: o client_id e o redirect são os do site; o app intercepta o redirect https (§18). */
    static final String LOGIN_URL = "https://accounts.google.com/o/oauth2/v2/auth"
            + "?client_id=903959815167-q595r9hdqgjdm1lu6t1es0n2qung2qci.apps.googleusercontent.com"
            + "&redirect_uri=https://pogocoordinates.com/callback&response_type=code&scope=openid%20email%20profile"
            + "&state=login:google&access_type=online&prompt=consent";
    /** Spec §2.1, idêntica: o Discord, ao contrário do Google, aceita WebView. */
    static final String DISCORD_URL = "https://discord.com/oauth2/authorize?client_id=1499296458753445998"
            + "&redirect_uri=https://pogocoordinates.com/callback&response_type=code"
            + "&scope=identify%20applications.commands&integration_type=1&state=login:discord";
    private static final long PENDING_MS = 10 * 60_000L;
    /** O lookup de treinador pode levar até 1 min (spec §14.2). */
    private static final int TIMEOUT_MS = 15_000, TRAINER_TIMEOUT_MS = 60_000, MAX_BODY = 64 * 1024,
            CATALOG_MAX = 512 * 1024, SEARCH_MAX = 256 * 1024, LOOKUP_MAX = 1024 * 1024, RULES_MAX = 128 * 1024;
    /** pogo_linked que este processo viu morrer (pogo_relogin/pogo_disabled): só o main apaga a pref; um novo vínculo muda o valor. */
    private static volatile long sDeadLink;

    private Pogo() {}

    /** message = o texto que o site mostraria (detail, detail.message ou detail.error); fora do getMessage, que o Io loga. */
    public static final class Error extends IOException {
        public final int http;
        public final String error, message;
        public final long retryAfterS;

        Error(int http, String error, String message, long retryAfterS) {
            super(http + " " + error);
            this.http = http;
            this.error = error;
            this.message = message;
            this.retryAfterS = retryAfterS;
        }
    }

    /**
     * iv = -1 sem IV; lat/lng aproximados (os exatos vêm do reveal), NaN no /search, que só traz location e golpes.
     * verified = false: despawn estimado. costume é o enum (HALLOWEEN_2017); rawIv é "15/15/15".
     * pvp* vêm do objeto "pvp" (lookup com pvp_mode, /search): a evolução e o rank alvo; null/-1 sem pvp.
     */
    public static final class Mon {
        public String reveal, name, form, gender, size, location, costume, rawIv, fastMove, chargeMove;
        public String pvpLeague, pvpName, encounter;
        public int id, cp, pvpRank = -1, pvpCp = -1;
        public double level, iv, lat, lng, pvpPercent = -1;
        public long despawnMs;
        public boolean verified, shiny, background;
        /** Chave do PogoIcons, montada 1× (o draw consulta por marcador e por frame). */
        String iconKey;
    }

    static final class Species {
        final int id, form;
        final String name, formName;

        Species(int id, int form, String name, String formName) {
            this.id = id;
            this.form = form;
            this.name = name;
            this.formName = formName;
        }
    }

    /** Só o main grava "pogo"; :overlay e :map relêem o arquivo para ver login, filtros e treinador frescos. */
    static SharedPreferences prefs(Context c) {
        SharedPreferences sp = c.getSharedPreferences("pogo", prefsMode(App.process()));
        if (writer(App.process()) && sp.contains("jwt")) dropLegacy(sp);
        return sp;
    }

    /** Builds de teste guardavam o JWT cifrado com a chave pl_pogo: agora o token fica no servidor. */
    private static void dropLegacy(SharedPreferences sp) {
        sp.edit().remove("jwt").apply();
        Io.run(() -> {
            try {
                KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
                ks.load(null);
                ks.deleteEntry("pl_pogo");
            } catch (Exception ignored) {
            }
        });
    }

    @SuppressWarnings("deprecation")
    static int prefsMode(int proc) { return writer(proc) ? Context.MODE_PRIVATE : Context.MODE_MULTI_PROCESS; }

    static boolean writer(int proc) { return proc == App.PROC_MAIN; }

    /** PoGo vinculado no nosso servidor (exige a conta PokeLocation). */
    public static boolean loggedIn(Context c) {
        long at = prefs(c).getLong(K_LINKED, 0);
        return at != 0 && at != sDeadLink && Session.loggedIn(c);
    }

    /** Alertas rodam no servidor com o vínculo PoGo e chegam por FCM da conta PokeLocation. */
    public static boolean canAlert(Context c) { return loggedIn(c); }

    public static List<Mon> lookup(Context c, double n, double w, double s, double e, PogoFilter f) throws IOException {
        try {
            return route(c, "POST", "/map/lookup", "/lookup", t -> {
                JSONObject filters = f.toJson();
                stripShiny(filters, t);
                return new JSONObject().put("mode", "pokemon").put("n_lat", n).put("w_lng", w).put("s_lat", s)
                        .put("e_lng", e).put("filters", filters).put("num_results", f.numResults);
            }, LOOKUP_MAX, TIMEOUT_MS, r -> results(r, true));
        } catch (JSONException x) {
            throw new IOException("bad response");
        }
    }

    /** POST /search (§4.1): o mundo inteiro, cap 5 anônimo / 10 logado / até 100 VIP, itens sem coordenada. */
    public static List<Mon> search(Context c, PogoFilter f) throws IOException {
        try {
            return route(c, "POST", "/search", "/search", t -> searchBody(f, t), SEARCH_MAX, TIMEOUT_MS, r -> results(r, false));
        } catch (JSONException x) {
            throw new IOException("bad response");
        } catch (Error e) {
            searchLimited(e);
            throw e;
        }
    }

    /** Até quando o /search está em 429: o enrich (1 por card) divide a cota com o "Mundo inteiro" e espera. */
    private static volatile long sSearchUntil;

    static void searchLimited(Error e) {
        if (e.http == 429) sSearchUntil = System.currentTimeMillis() + Math.max(e.retryAfterS, 60) * 1000;
    }

    /** Os filtros do mapa com os nomes do /search: só o IV muda (iv_percent_*, iv_mode). O corpo não é aninhado. */
    static JSONObject searchBody(PogoFilter f, JSONArray trainers) throws JSONException {
        JSONObject o = f.toJson();
        stripShiny(o, trainers);
        Object min = o.remove("iv_min"), max = o.remove("iv_max");
        return o.putOpt("iv_percent_min", min).putOpt("iv_percent_max", max).put("iv_mode", "percent");
    }

    /** Shiny sem treinador ativo dá 403 em toda busca (e o switch some da tela): só vai o que a conta pode usar. */
    static void stripShiny(JSONObject filters, JSONArray trainers) {
        if (trainers.length() == 0) {
            filters.remove("shiny_only");
            filters.remove("background_only");
        }
        String account = filters.optString("shiny_account", null);
        for (int i = 0; account != null && i < trainers.length(); i++)
            if (account.equals(str(trainers.optJSONObject(i), "trainer_code"))) return;
        filters.remove("shiny_account");
    }

    /** encounter_id → o Mon do /search (golpes e local), ou NONE se não veio; só a última centena. */
    private static final Map<String, Mon> ENRICHED = new LinkedHashMap<String, Mon>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Mon> e) { return size() > 100; }
    };
    private static final Mon NONE = new Mon();

    /**
     * O /map/lookup não traz golpes nem local: um /search estreito (mesma espécie, 300 m da coordenada aproximada) acha o
     * mesmo encounter_id. Uma chamada por card aberto. Não mexe no m; null = não achou.
     */
    public static Mon enrich(Context c, Mon m) throws IOException {
        if (m.encounter == null || Double.isNaN(m.lat) || System.currentTimeMillis() < sSearchUntil) return null;
        synchronized (ENRICHED) {
            Mon hit = ENRICHED.get(m.encounter);
            if (hit != null) return hit == NONE ? null : hit;
        }
        Mon found = null;
        try {
            for (Mon x : route(c, "POST", "/search", "/search", t -> enrichBody(m), SEARCH_MAX, TIMEOUT_MS, r -> results(r, false)))
                if (m.encounter.equals(x.encounter)) found = x;
        } catch (JSONException x) {
            throw new IOException("bad response");
        } catch (Error e) {
            searchLimited(e);
            throw e;
        }
        synchronized (ENRICHED) {
            ENRICHED.put(m.encounter, found != null ? found : NONE);
        }
        return found;
    }

    /** Junto do trim dos ícones: o :overlay solta tudo ao fechar o painel. */
    static void forgetEnriched() {
        synchronized (ENRICHED) {
            ENRICHED.clear();
        }
    }

    static JSONObject enrichBody(Mon m) throws JSONException {
        return new JSONObject().put("pokemon_ids", new JSONArray().put(m.id)).put("center_lat", m.lat)
                .put("center_lng", m.lng).put("radius_km", 0.3).put("sort", "DISTANCE");
    }

    /** {lat, lng} exatos. 404/410 = spawn expirado. */
    public static double[] reveal(Context c, String code) throws IOException {
        String path = revealPath(code);
        try {
            double[] ll = route(c, "GET", path, path, t -> null, MAX_BODY, TIMEOUT_MS, Pogo::latLng);
            if (Double.isNaN(ll[0]) || Double.isNaN(ll[1])) throw new IOException("bad response");
            return ll;
        } catch (JSONException x) {
            throw new IOException("bad request");
        }
    }

    static List<Species> catalog(Context c) throws IOException {
        return http(c, "GET", "/pokemon/catalog?costumes=true", null, CATALOG_MAX, TIMEOUT_MS, in -> json(in, Pogo::species));
    }

    /** Marca o login em curso: o state do site é fixo (sem nonce), então só um callback logo depois deste conta. */
    static Uri beginLogin(Context c) {
        prefs(c).edit().putLong(K_PENDING, System.currentTimeMillis()).apply();
        return Uri.parse(LOGIN_URL);
    }

    static boolean isCallback(Uri u, String provider) {
        return isCallback(u.getScheme(), u.getHost(), u.getPath(), u.getQueryParameter("state"), provider);
    }

    /** O state de "link" ou de outro provedor é de outro fluxo do site: o code dele não vale para /auth/{provider}. */
    static boolean isCallback(String scheme, String host, String path, String state, String provider) {
        return "https".equals(scheme) && "pogocoordinates.com".equals(host) && "/callback".equals(path)
                && ("login:" + provider).equals(state);
    }

    /** Uma vez só: some da memória já aqui, para um recreate ou um link repetido não trocar o code de novo. */
    static boolean consumePending(Context c) {
        long at = prefs(c).getLong(K_PENDING, 0);
        prefs(c).edit().remove(K_PENDING).apply();
        return fresh(at, System.currentTimeMillis());
    }

    static boolean fresh(long at, long now) { return at > 0 && now >= at && now - at < PENDING_MS; }

    /** O code vai para o nosso servidor, que troca pelo JWT do PoGo e guarda; aqui só volta o perfil. */
    static void login(Context c, String provider, String code) throws IOException {
        try {
            saveProfile(c, proxy(c, "POST", "/login", new JSONObject().put("provider", provider).put("code", code),
                    MAX_BODY, TIMEOUT_MS), true);
        } catch (JSONException x) {
            throw new IOException("bad response");
        }
    }

    /** Atualiza nome e treinadores ativos. */
    static void me(Context c) throws IOException {
        if (!loggedIn(c)) return;
        try {
            saveProfile(c, proxy(c, "GET", "/me", null, MAX_BODY, TIMEOUT_MS), false);
        } catch (JSONException x) {
            throw new IOException("bad response");
        }
    }

    /** Perfil do servidor: display_name (já com o fallback do Discord) e trainers já filtrados (active != false). */
    private static void saveProfile(Context c, String text, boolean link) throws JSONException {
        JSONObject o = new JSONObject(text);
        if (!writer(App.process())) return;
        SharedPreferences.Editor e = prefs(c).edit().putString(K_NAME, str(o, "display_name"))
                .putString(K_TRAINERS, activeTrainers(o).toString());
        if (link) e.putLong(K_LINKED, System.currentTimeMillis());
        e.apply();
    }

    static String name(Context c) { return prefs(c).getString(K_NAME, null); }

    /** [{trainer_code, trainer_name}] dos ativos, do último /users/me. */
    static JSONArray trainers(Context c) {
        try {
            return new JSONArray(prefs(c).getString(K_TRAINERS, "[]"));
        } catch (JSONException x) {
            return new JSONArray();
        }
    }

    /** Como o site: trainers ?? [trainer], só active != false. */
    static JSONArray activeTrainers(JSONObject me) throws JSONException {
        JSONArray all = me.optJSONArray("trainers");
        if (all == null) all = new JSONArray().put(me.opt("trainer"));
        JSONArray out = new JSONArray();
        for (int i = 0; i < all.length(); i++) {
            JSONObject t = all.optJSONObject(i);
            if (t == null || str(t, "trainer_code") == null || Boolean.FALSE.equals(t.opt("active"))) continue;
            out.put(new JSONObject().put("trainer_code", str(t, "trainer_code")).putOpt("trainer_name", str(t, "trainer_name")));
        }
        return out;
    }

    /** Prévia do POST /trainer/lookup. */
    static final class Trainer {
        String code, name, teamName;
        int level, minLevel;
        boolean eligible;
    }

    /** Só os dígitos (o jogo mostra "1234 5678 9012"); null se não forem 12. */
    static String trainerCode(String typed) {
        String d = typed == null ? "" : typed.replaceAll("[^0-9]", "");
        return d.length() == 12 ? d : null;
    }

    /** Não vincula; gasta uma consulta da hora (o mesmo código repetido não conta). */
    static Trainer lookupTrainer(Context c, String code) throws IOException {
        try {
            return parseTrainer(new JSONObject(proxy(c, "POST", "/trainer/lookup",
                    new JSONObject().put("trainer_code", code), MAX_BODY, TRAINER_TIMEOUT_MS)));
        } catch (JSONException x) {
            throw new IOException("bad response");
        }
    }

    /** Vínculo permanente. already_linked na própria conta conta como sucesso (como o site). */
    static void confirmTrainer(Context c, String code) throws IOException {
        try {
            proxy(c, "POST", "/trainer/confirm", new JSONObject().put("trainer_code", code), MAX_BODY, TRAINER_TIMEOUT_MS);
        } catch (Error e) {
            if (!"already_linked".equals(e.error)) throw e;
            try {
                if (!ownsTrainer(new JSONObject(proxy(c, "GET", "/trainer", null, MAX_BODY, TIMEOUT_MS)), code)) throw e;
            } catch (JSONException x) {
                throw e;
            }
        } catch (JSONException x) {
            throw new IOException("bad request");
        }
        me(c);
    }

    static Trainer parseTrainer(JSONObject o) {
        Trainer t = new Trainer();
        t.code = str(o, "trainer_code");
        t.name = str(o, "trainer_name");
        t.teamName = str(o, "team_name");
        t.level = o.optInt("level");
        t.minLevel = o.optInt("min_level", 30);
        t.eligible = o.optBoolean("eligible", true);
        return t;
    }

    /** GET /trainer: o código é o link ou uma das accounts. */
    static boolean ownsTrainer(JSONObject o, String code) {
        JSONObject link = o.optJSONObject("link");
        if (link != null && code.equals(str(link, "trainer_code"))) return true;
        JSONArray a = o.optJSONArray("accounts");
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject t = a.optJSONObject(i);
            if (t != null && code.equals(str(t, "trainer_code"))) return true;
        }
        return false;
    }

    /**
     * Dados da WebView do :pogoweb (sufixo "pogoweb" na API 28+; antes disso o diretório padrão, que só ela usa no app),
     * relativos ao dataDir: o deleteAllData não termina antes do killProcess e a sessão do Discord sobraria em disco.
     */
    static String[] webDirs(int sdk) {
        return sdk >= 28 ? new String[]{"app_webview_pogoweb", "cache/webview_pogoweb"}
                : new String[]{"app_webview", "cache/WebView"};
    }

    /** Bloqueante: chamar fora da main. Diretório em uso agora é limpo na próxima abertura da conta. */
    static void wipeWebData(Context c) {
        for (String d : webDirs(Build.VERSION.SDK_INT)) deleteTree(new File(c.getDataDir(), d));
    }

    /**
     * O resultado chega antes do onDestroy do :pogoweb, que ainda regrava cookies e leveldb: espera o processo sumir
     * (até 10 s) numa thread própria, sem prender o Io.
     */
    static void wipeWebDataWhenGone(Context c) {
        new Thread(() -> {
            ActivityManager am = c.getSystemService(ActivityManager.class);
            for (int i = 0; i < 50 && webAlive(am); i++) SystemClock.sleep(200);
            wipeWebData(c);
        }, "pl-pogo-wipe").start();
    }

    private static boolean webAlive(ActivityManager am) {
        List<ActivityManager.RunningAppProcessInfo> l = am.getRunningAppProcesses();
        for (int i = 0; l != null && i < l.size(); i++) if (l.get(i).processName.endsWith(":pogoweb")) return true;
        return false;
    }

    /** Não desce em symlink: apaga o link, nunca o destino. */
    static void deleteTree(File f) {
        File[] kids = isLink(f) ? null : f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    /** Sem java.nio.file (API 26): pai canônico + nome, comparado com o canônico do próprio arquivo. */
    private static boolean isLink(File f) {
        try {
            File parent = f.getAbsoluteFile().getParentFile();
            File here = parent == null ? f.getAbsoluteFile() : new File(parent.getCanonicalFile(), f.getName());
            return !here.getCanonicalFile().equals(here);
        } catch (IOException e) {
            return true;
        }
    }

    /** Desfaz o vínculo no servidor (204 sempre) e o local. Só o main; não toca no social nem nos filtros. */
    public static void logout(Context c) {
        if (!writer(App.process())) return;
        try {
            if (Session.loggedIn(c)) proxy(c, "DELETE", "/login", null, MAX_BODY, TIMEOUT_MS);
        } catch (IOException ignored) {
            // o vínculo local some mesmo assim; um novo login substitui o do servidor
        }
        unlink(c);
    }

    private static void unlink(Context c) {
        prefs(c).edit().remove(K_LINKED).remove(K_NAME).remove(K_TRAINERS).apply();
    }

    public static boolean relogin(Throwable t) { return code(t, "pogo_relogin"); }

    public static boolean ruleLimit(Throwable t) { return code(t, "too_many_rules"); }

    /** 422 ao salvar regra com shiny sem treinador ativo (T22): o mesmo aviso de "Vincular treinador". */
    public static boolean trainerRequired(Throwable t) { return code(t, "trainer_required"); }

    /** 422: o shiny_account da regra não é um treinador ativo da conta. */
    public static boolean shinyAccountUnavailable(Throwable t) { return code(t, "shiny_account_unavailable"); }

    private static boolean code(Throwable t, String code) { return t instanceof Error && code.equals(((Error) t).error); }

    /**
     * Regra de alerta (contrato §4). world = scope "world"; near usa lat/lng/radiusKm. disabledReason: por que o servidor
     * a desligou (ex.: "trainer_required"); só leitura, null se ligada pelo usuário.
     */
    public static final class Rule {
        public String id, name, disabledReason;
        public PogoFilter filter;
        public boolean world, enabled = true;
        public double lat = Double.NaN, lng = Double.NaN;
        public int radiusKm;
    }

    public static List<Rule> rules(Context c) throws IOException {
        try {
            JSONArray a = new JSONObject(proxy(c, "GET", "/rules", null, RULES_MAX, TIMEOUT_MS)).getJSONArray("rules");
            List<Rule> out = new ArrayList<>(a.length());
            for (int i = 0; i < a.length(); i++) out.add(parseRule(a.getJSONObject(i)));
            return out;
        } catch (JSONException x) {
            throw new IOException("bad response");
        }
    }

    /** Devolve a regra com o id do servidor. */
    public static Rule addRule(Context c, Rule r) throws IOException {
        try {
            return parseRule(new JSONObject(proxy(c, "POST", "/rules", ruleJson(r), MAX_BODY, TIMEOUT_MS)));
        } catch (JSONException x) {
            throw new IOException("bad response");
        }
    }

    /** PUT da regra inteira (o switch troca r.enabled e chama aqui). Devolve a do servidor: todo PUT limpa o disabled_reason. */
    public static Rule updateRule(Context c, Rule r) throws IOException {
        try {
            return parseRule(new JSONObject(proxy(c, "PUT", "/rules/" + URLEncoder.encode(r.id, "UTF-8"), ruleJson(r),
                    MAX_BODY, TIMEOUT_MS)));
        } catch (JSONException x) {
            throw new IOException("bad response");
        }
    }

    public static void deleteRule(Context c, String id) throws IOException {
        proxy(c, "DELETE", "/rules/" + URLEncoder.encode(id, "UTF-8"), null, MAX_BODY, TIMEOUT_MS);
    }

    /** O alerta ignora sort e num_results; o servidor guarda os filters como vêm. */
    static JSONObject ruleJson(Rule r) throws JSONException {
        JSONObject o = new JSONObject().put("name", r.name).put("filters", r.filter.toJson())
                .put("scope", r.world ? "world" : "near").put("enabled", r.enabled);
        if (!r.world) o.put("lat", r.lat).put("lng", r.lng).put("radius_km", r.radiusKm);
        return o;
    }

    static Rule parseRule(JSONObject o) {
        Rule r = new Rule();
        r.id = str(o, "id");
        r.name = str(o, "name");
        JSONObject f = o.optJSONObject("filters");
        r.filter = f != null ? PogoFilter.fromJson(f) : new PogoFilter();
        r.world = "world".equals(str(o, "scope"));
        r.lat = o.optDouble("lat", Double.NaN);
        r.lng = o.optDouble("lng", Double.NaN);
        r.radiusKm = o.optInt("radius_km");
        r.enabled = o.optBoolean("enabled", true);
        r.disabledReason = str(o, "disabled_reason");
        return r;
    }

    private interface Body { JSONObject of(JSONArray trainers) throws JSONException; }

    /**
     * Vinculado: pelo servidor; com pogo_relogin/pogo_disabled cai para o direto anônimo na mesma chamada. A resposta é
     * lida em fluxo (JsonReader): sem árvore JSONObject e, no direto, sem a String inteira (lookup de até 1 MB a cada 60 s).
     */
    private static <T> T route(Context c, String method, String direct, String proxied, Body body, int max, int timeout,
                               Pull<T> pull) throws IOException, JSONException {
        if (loggedIn(c)) {
            try {
                return json(new StringReader(proxy(c, method, proxied, body.of(trainers(c)), max, timeout)), pull);
            } catch (Error e) {
                if (!"pogo_relogin".equals(e.error) && !"pogo_disabled".equals(e.error)) throw e;
            }
        }
        return http(c, method, direct, body.of(new JSONArray()), max, timeout, in -> json(in, pull));
    }

    interface Pull<T> { T read(JsonReader r) throws IOException; }

    private interface Text<T> { T read(Reader in) throws IOException; }

    static <T> T json(Reader in, Pull<T> pull) throws IOException {
        try (JsonReader r = new JsonReader(in)) {
            return pull.read(r);
        } catch (IllegalStateException | NumberFormatException e) {
            throw new IOException("bad response"); // tipo inesperado no JSON
        }
    }

    /** Nosso servidor; o timeout tem folga de 5 s sobre o dele (contrato §3). */
    static String proxy(Context c, String method, String path, JSONObject body, int max, int timeout) throws IOException {
        try {
            return Api.pogo(c, method, "/v1/pogo" + path, body, max, timeout + 5_000);
        } catch (Api.Error e) {
            Error p = fromApi(e.http, e.code, e.body, path.startsWith("/trainer/") ? 3600 : 60);
            if ("pogo_relogin".equals(p.error) || "pogo_disabled".equals(p.error)) {
                sDeadLink = prefs(c).getLong(K_LINKED, 0);
                if ("pogo_relogin".equals(p.error) && writer(App.process())) unlink(c);
            }
            throw p;
        }
    }

    /** upstream = o 4xx do PoGo no envelope (§1.2), lido como o erro deles; o rate_limited nosso vem sem retry_after. */
    static Error fromApi(int http, String code, String body, long ourRetryS) {
        if ("upstream".equals(code)) return error(http, body, null);
        return new Error(http, code, null, "rate_limited".equals(code) ? ourRetryS : 0);
    }

    static String revealPath(String code) throws IOException {
        return "/reveal/" + URLEncoder.encode(code, "UTF-8");
    }

    static List<Mon> parseLookup(String text) throws IOException { return json(new StringReader(text), r -> results(r, true)); }

    static List<Mon> parseSearch(String text) throws IOException { return json(new StringReader(text), r -> results(r, false)); }

    /** {results:[…]}: no mapa (lookup) só entra quem tem apx_lat/apx_long; no /search não há coordenada. */
    static List<Mon> results(JsonReader r, boolean map) throws IOException {
        List<Mon> out = new ArrayList<>();
        r.beginObject();
        while (r.hasNext()) {
            if (!"results".equals(r.nextName()) || r.peek() != JsonToken.BEGIN_ARRAY) {
                r.skipValue();
                continue;
            }
            r.beginArray();
            while (r.hasNext()) {
                Mon m = mon(r);
                if (m.reveal == null || map && (Double.isNaN(m.lat) || Double.isNaN(m.lng))) continue;
                if (!map) m.lat = m.lng = Double.NaN;
                out.add(m);
            }
            r.endArray();
        }
        r.endObject();
        return out;
    }

    /** Campos do /map/lookup e do /search (despawn em ms no primeiro, ISO no segundo; golpes e local só no segundo). */
    private static Mon mon(JsonReader r) throws IOException {
        Mon m = new Mon();
        m.iv = -1;
        m.verified = true;
        m.lat = m.lng = Double.NaN;
        r.beginObject();
        while (r.hasNext()) {
            switch (r.nextName()) {
                case "reveal_code": m.reveal = str(r); break;
                case "encounter_id": m.encounter = str(r); break;
                case "pokemon_id": m.id = (int) num(r, 0); break;
                case "pokemon_name": m.name = str(r); break;
                case "form": m.form = str(r); break;
                case "gender": m.gender = str(r); break;
                case "size": m.size = str(r); break;
                case "cp": m.cp = (int) num(r, 0); break;
                case "level": m.level = num(r, 0); break;
                case "iv_percent": m.iv = num(r, -1); break;
                case "costume": m.costume = str(r); break;
                case "raw_iv": m.rawIv = str(r); break;
                case "despawn_verified": m.verified = bool(r, true); break;
                case "shiny": m.shiny = bool(r, false); break;
                case "background": m.background = bool(r, false); break;
                case "despawn_at_ms": m.despawnMs = (long) num(r, 0); break;
                case "despawn_at": m.despawnMs = isoMs(str(r)); break;
                case "apx_lat": m.lat = num(r, Double.NaN); break;
                case "apx_long": m.lng = num(r, Double.NaN); break;
                case "location": m.location = str(r); break;
                case "fast_move": m.fastMove = str(r); break;
                case "charge_move": m.chargeMove = str(r); break;
                case "pvp": pvp(r, m); break;
                default: r.skipValue();
            }
        }
        r.endObject();
        return m;
    }

    private static void pvp(JsonReader r, Mon m) throws IOException {
        if (r.peek() != JsonToken.BEGIN_OBJECT) {
            r.skipValue();
            return;
        }
        r.beginObject();
        while (r.hasNext()) {
            switch (r.nextName()) {
                case "league": m.pvpLeague = str(r); break;
                case "pokemon_name": m.pvpName = str(r); break;
                case "rank": m.pvpRank = (int) num(r, -1); break;
                case "cp": m.pvpCp = (int) num(r, -1); break;
                case "percent": m.pvpPercent = num(r, -1); break;
                default: r.skipValue();
            }
        }
        r.endObject();
    }

    /** /reveal: {coords, lat, lng}. */
    private static double[] latLng(JsonReader r) throws IOException {
        double[] ll = {Double.NaN, Double.NaN};
        r.beginObject();
        while (r.hasNext()) {
            String k = r.nextName();
            if ("lat".equals(k)) ll[0] = num(r, Double.NaN);
            else if ("lng".equals(k)) ll[1] = num(r, Double.NaN);
            else r.skipValue();
        }
        r.endObject();
        return ll;
    }

    static String str(JsonReader r) throws IOException {
        switch (r.peek()) {
            case STRING:
            case NUMBER: return r.nextString();
            case BOOLEAN: return String.valueOf(r.nextBoolean());
            case NULL: r.nextNull(); return null;
            default: r.skipValue(); return null;
        }
    }

    static double num(JsonReader r, double def) throws IOException {
        switch (r.peek()) {
            case NUMBER: return r.nextDouble();
            case STRING:
                try {
                    return Double.parseDouble(r.nextString());
                } catch (NumberFormatException e) {
                    return def;
                }
            case NULL: r.nextNull(); return def;
            default: r.skipValue(); return def;
        }
    }

    static boolean bool(JsonReader r, boolean def) throws IOException {
        switch (r.peek()) {
            case BOOLEAN: return r.nextBoolean();
            case STRING: String v = r.nextString(); return "true".equals(v) || !"false".equals(v) && def;
            case NULL: r.nextNull(); return def;
            default: r.skipValue(); return def;
        }
    }

    /** "2026-10-14T23:31:16Z" ou com offset ("-05:00") e fração; 0 se não reconhecer. Sem java.time (desugar pesa). */
    static long isoMs(String s) {
        Matcher m = s == null ? null : Iso.P.matcher(s);
        if (m == null || !m.matches()) return 0;
        GregorianCalendar g = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
        g.clear();
        g.set(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)) - 1, Integer.parseInt(m.group(3)),
                Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)), Integer.parseInt(m.group(6)));
        long ms = g.getTimeInMillis();
        if (m.group(7) != null) ms += Integer.parseInt((m.group(7) + "00").substring(0, 3));
        if (m.group(8) != null) {
            long off = (Integer.parseInt(m.group(9)) * 60L + Integer.parseInt(m.group(10))) * 60_000L;
            ms += m.group(8).equals("+") ? -off : off;
        }
        return ms;
    }

    /** Holder: só o /search compila o regex, não todo processo que toca o Pogo. */
    private static final class Iso {
        static final Pattern P = Pattern.compile(
                "(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})(?:\\.(\\d{1,9}))?(?:Z|([+-])(\\d{2}):?(\\d{2}))");
    }

    static List<Species> parseCatalog(String text) throws IOException { return json(new StringReader(text), Pogo::species); }

    /** Sem repetir (pokemon_id, form_id): com costumes=true o mesmo par pode vir mais de uma vez. */
    static List<Species> species(JsonReader r) throws IOException {
        List<Species> out = new ArrayList<>(1500);
        Set<Long> seen = new HashSet<>();
        r.beginArray();
        while (r.hasNext()) {
            double id = Double.NaN, form = Double.NaN;
            String name = null, formName = null;
            r.beginObject();
            while (r.hasNext()) {
                switch (r.nextName()) {
                    case "pokemon_id": id = num(r, Double.NaN); break;
                    case "form_id": form = num(r, Double.NaN); break;
                    case "species_name": name = str(r); break;
                    case "form_name": formName = str(r); break;
                    default: r.skipValue();
                }
            }
            r.endObject();
            if (Double.isNaN(id) || Double.isNaN(form)) continue;
            if (seen.add(((long) id << 32) | ((int) form & 0xFFFFFFFFL))) out.add(new Species((int) id, (int) form, name, formName));
        }
        r.endArray();
        return out;
    }

    static Error error(int http, String text, String retryHeader) {
        String error = null, message = null;
        long retry = 0;
        try {
            JSONObject root = new JSONObject(text);
            retry = root.optLong("retry_after");
            Object d = root.opt("detail");
            if (d instanceof String) {
                message = (String) d;
            } else if (d instanceof JSONObject) {
                JSONObject o = (JSONObject) d;
                error = str(o, "error");
                message = str(o, "message");
                if (message == null) message = error;
                if (o.optLong("retry_after") > 0) retry = o.optLong("retry_after");
            }
        } catch (JSONException ignored) {
        }
        if (retry <= 0 && retryHeader != null) {
            try {
                retry = Long.parseLong(retryHeader.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return new Error(http, error, message, Math.max(0, retry));
    }

    static String str(JSONObject o, String k) {
        return o.isNull(k) ? null : o.optString(k);
    }

    /** Direto no PoGo, anônimo (o token fica no nosso servidor). O corpo de sucesso vai ao leitor com teto de bytes. */
    private static <T> T http(Context c, String method, String path, JSONObject body, int maxBody, int timeoutMs, Text<T> read)
            throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(API + path).openConnection();
        try {
            con.setConnectTimeout(timeoutMs);
            con.setReadTimeout(timeoutMs);
            con.setInstanceFollowRedirects(false);
            con.setUseCaches(false);
            con.setRequestMethod(method);
            con.setRequestProperty("User-Agent", c.getPackageName() + "/" + BuildConfig.VERSION_NAME);
            con.setRequestProperty("Accept", "application/json");
            if (App.process() != App.PROC_MAIN) con.setRequestProperty("Connection", "close");
            if (body != null) {
                byte[] b = body.toString().getBytes(StandardCharsets.UTF_8);
                con.setDoOutput(true);
                con.setFixedLengthStreamingMode(b.length);
                con.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = con.getOutputStream()) {
                    out.write(b);
                }
            }
            int status = con.getResponseCode();
            if (status >= 400) {
                InputStream es = con.getErrorStream();
                String text = es == null ? "" : text(new InputStreamReader(new Capped(es, MAX_BODY), StandardCharsets.UTF_8));
                throw error(status, text, con.getHeaderField("Retry-After"));
            }
            try (Reader in = new InputStreamReader(new Capped(con.getInputStream(), maxBody), StandardCharsets.UTF_8)) {
                return read.read(in);
            }
        } catch (Error e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            con.disconnect();
            throw e;
        }
    }

    private static String text(Reader in) throws IOException {
        StringBuilder b = new StringBuilder(1024);
        char[] buf = new char[2048];
        int r;
        try (Reader i = in) {
            while ((r = i.read(buf)) > 0) b.append(buf, 0, r);
        }
        return b.toString();
    }

    /** Teto em bytes no próprio stream: a resposta nunca é lida inteira para ser medida. */
    private static final class Capped extends FilterInputStream {
        private long left;

        Capped(InputStream in, long max) {
            super(in);
            left = max;
        }

        @Override public int read() throws IOException {
            int b = super.read();
            if (b >= 0 && --left < 0) throw new IOException("response too large");
            return b;
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0 && (left -= n) < 0) throw new IOException("response too large");
            return n;
        }
    }
}
