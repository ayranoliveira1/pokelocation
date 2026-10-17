package com.pokelocation.app.pogo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.List;

public class PogoTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test public void errorDetailString() {
        Pogo.Error e = Pogo.error(401, "{\"detail\":\"Invalid token\"}", null);
        assertEquals(401, e.http);
        assertNull(e.error);
        assertEquals("Invalid token", e.message);
    }

    @Test public void errorDetailObjectPrefersMessage() {
        Pogo.Error e = Pogo.error(403, "{\"detail\":{\"error\":\"trainer_not_linked\",\"message\":\"Link your trainer code\"}}", null);
        assertEquals("trainer_not_linked", e.error);
        assertEquals("Link your trainer code", e.message);
        assertEquals("vip_required", Pogo.error(403, "{\"detail\":{\"error\":\"vip_required\"}}", null).message);
    }

    @Test public void retryAfterFromBodyThenHeader() {
        assertEquals(46, Pogo.error(429, "{\"detail\":{\"error\":\"rate_limited\",\"retry_after\":46}}", "10").retryAfterS);
        assertEquals(10, Pogo.error(429, "", " 10 ").retryAfterS);
        assertEquals(0, Pogo.error(502, "<html>", "soon").retryAfterS);
    }

    @Test public void revealCodeIsUrlEncoded() throws Exception {
        assertEquals("/reveal/a%2Bb%2Fc%3D", Pogo.revealPath("a+b/c="));
    }

    @Test public void lookupParsesItemsAndSkipsBroken() throws Exception {
        List<Pogo.Mon> l = Pogo.parseLookup("{\"mode\":\"pokemon\",\"results\":["
                + "{\"reveal_code\":\"X1\",\"pokemon_id\":255,\"pokemon_name\":\"Torchic\",\"form\":null,\"gender\":\"Male\","
                + "\"size\":\"M\",\"cp\":750,\"level\":24.0,\"iv_percent\":null,\"despawn_at_ms\":1760484676000,"
                + "\"apx_lat\":45.12,\"apx_long\":5.69},"
                + "{\"reveal_code\":\"X2\",\"pokemon_id\":1,\"apx_lat\":null,\"apx_long\":5}]}");
        assertEquals(1, l.size());
        Pogo.Mon m = l.get(0);
        assertEquals("X1", m.reveal);
        assertEquals(255, m.id);
        assertEquals("Torchic", m.name);
        assertNull(m.form);
        assertEquals(750, m.cp);
        assertEquals(24.0, m.level, 0);
        assertEquals(-1, m.iv, 0);
        assertEquals(1760484676000L, m.despawnMs);
        assertEquals(45.12, m.lat, 0);
        assertEquals(5.69, m.lng, 0);
    }

    @Test public void catalogDropsRepeatedPairs() throws Exception {
        List<Pogo.Species> l = Pogo.parseCatalog("[{\"pokemon_id\":19,\"form_id\":45,\"species_name\":\"Rattata\",\"form_name\":null},"
                + "{\"pokemon_id\":19,\"form_id\":46,\"species_name\":\"Rattata\",\"form_name\":\"Alola\"},"
                + "{\"pokemon_id\":19,\"form_id\":46,\"species_name\":\"Rattata\",\"form_name\":\"Alola\",\"costume_id\":3}]");
        assertEquals(2, l.size());
        assertNull(l.get(0).formName);
        assertEquals("Alola", l.get(1).formName);
    }

    @Test public void pendingLoginLastsTenMinutes() {
        long at = 1_000_000;
        assertTrue(Pogo.fresh(at, at));
        assertTrue(Pogo.fresh(at, at + 599_999));
        assertFalse(Pogo.fresh(at, at + 600_000));
        assertFalse(Pogo.fresh(at, at - 1));
        assertFalse(Pogo.fresh(0, at));
    }

    @Test public void activeTrainersLikeTheSite() throws Exception {
        assertEquals("[{\"trainer_code\":\"1\",\"trainer_name\":\"A\"}]", Pogo.activeTrainers(new JSONObject(
                "{\"trainers\":[{\"trainer_code\":\"1\",\"trainer_name\":\"A\",\"active\":true},"
                        + "{\"trainer_code\":\"2\",\"active\":false}],\"trainer\":{\"trainer_code\":\"9\"}}")).toString());
        assertEquals("[{\"trainer_code\":\"9\"}]",
                Pogo.activeTrainers(new JSONObject("{\"trainer\":{\"trainer_code\":\"9\"}}")).toString());
        assertEquals("[]", Pogo.activeTrainers(new JSONObject("{\"trainers\":null,\"trainer\":null}")).toString());
    }

    @Test public void activeTrainersKeepLevelAndTeam() throws Exception {
        JSONObject t = Pogo.activeTrainers(new JSONObject("{\"trainers\":[{\"trainer_code\":\"1\",\"trainer_name\":\"A\","
                + "\"team\":2,\"team_name\":\"Valor\",\"level\":39,\"linked_at\":\"x\",\"active\":true}]}")).getJSONObject(0);
        assertEquals(2, t.getInt("team"));
        assertEquals("Valor", t.getString("team_name"));
        assertEquals(39, t.getInt("level"));
        assertFalse(t.has("linked_at"));
    }

    @Test public void callbackNeedsExactUrlAndLoginState() {
        assertTrue(Pogo.isCallback("https", "pogocoordinates.com", "/callback", "login:google", "google"));
        assertFalse(Pogo.isCallback("http", "pogocoordinates.com", "/callback", "login:google", "google"));
        assertFalse(Pogo.isCallback("https", "evil.com", "/callback", "login:google", "google"));
        assertFalse(Pogo.isCallback("https", "pogocoordinates.com", "/callbackX", "login:google", "google"));
        assertFalse(Pogo.isCallback("https", "pogocoordinates.com", "/callback", null, "google"));
        assertFalse(Pogo.isCallback("https", "pogocoordinates.com", "/callback", "link:google", "google"));
        assertFalse(Pogo.isCallback("https", "pogocoordinates.com", "/callback", "login:discord", "google"));
    }

    @Test public void shinyOnlyWithAnActiveTrainer() throws Exception {
        JSONArray one = new JSONArray("[{\"trainer_code\":\"1\"}]");
        JSONObject f = new JSONObject("{\"sort\":\"IV\",\"shiny_only\":true,\"background_only\":true,\"shiny_account\":\"1\"}");
        Pogo.stripShiny(f, one);
        assertEquals(4, f.length());
        f.put("shiny_account", "2");
        Pogo.stripShiny(f, one);
        assertFalse(f.has("shiny_account"));
        assertTrue(f.has("shiny_only"));
        f.put("shiny_account", "1");
        Pogo.stripShiny(f, new JSONArray());
        assertEquals("{\"sort\":\"IV\"}", f.toString());
    }

    @Test public void trainerCodeKeepsOnlyTwelveDigits() {
        assertEquals("123456789012", Pogo.trainerCode(" 1234 5678-9012 "));
        assertNull(Pogo.trainerCode("12345678901"));
        assertNull(Pogo.trainerCode("1234567890123"));
        assertNull(Pogo.trainerCode(null));
    }

    @Test public void trainerLookupPreview() throws Exception {
        Pogo.Trainer t = Pogo.parseTrainer(new JSONObject("{\"trainer_code\":\"402655281272\",\"trainer_name\":\"ash\","
                + "\"level\":24,\"team\":1,\"team_name\":\"Mystic\",\"eligible\":false,\"min_level\":30}"));
        assertEquals("402655281272", t.code);
        assertEquals("ash", t.name);
        assertEquals("Mystic", t.teamName);
        assertEquals(24, t.level);
        assertEquals(30, t.minLevel);
        assertFalse(t.eligible);
    }

    @Test public void alreadyLinkedOnlyIfItIsOurs() throws Exception {
        assertTrue(Pogo.ownsTrainer(new JSONObject("{\"link\":{\"trainer_code\":\"1\"},\"accounts\":[]}"), "1"));
        assertTrue(Pogo.ownsTrainer(new JSONObject("{\"link\":null,\"accounts\":[{\"trainer_code\":\"2\"},{\"trainer_code\":\"1\"}]}"), "1"));
        assertFalse(Pogo.ownsTrainer(new JSONObject("{\"link\":null,\"accounts\":[],\"cap\":1}"), "1"));
    }

    @Test public void trainerErrors() {
        assertEquals("already_linked", Pogo.error(409, "{\"detail\":{\"error\":\"already_linked\"}}", null).error);
        Pogo.Error bad = Pogo.error(400, "{\"detail\":{\"error\":\"bad_code\",\"message\":\"A trainer code is 12 digits.\"}}", null);
        assertEquals("bad_code", bad.error);
        assertEquals("A trainer code is 12 digits.", bad.message);
        Pogo.Error busy = Pogo.error(503, "", null);
        assertEquals(503, busy.http);
        assertNull(busy.message);
        assertEquals(3540, Pogo.error(429, "{\"detail\":{\"error\":\"rate_limited\",\"retry_after\":3540}}", null).retryAfterS);
    }

    @Test public void discordCallbackOnlyWithDiscordState() {
        assertTrue(Pogo.isCallback("https", "pogocoordinates.com", "/callback", "login:discord", "discord"));
        assertFalse(Pogo.isCallback("https", "pogocoordinates.com", "/callback", "login:google", "discord"));
        assertFalse(Pogo.isCallback("https", "pogocoordinates.com", "/callback", "link:discord", "discord"));
        assertFalse(Pogo.isCallback("https", "pogocoordinates.com", "/callback", null, "discord"));
        assertFalse(Pogo.isCallback("https", "www.pogocoordinates.com", "/callback", "login:discord", "discord"));
    }

    @Test public void discordUrlIsTheSpecOne() {
        assertEquals("https://discord.com/oauth2/authorize"
                + "?client_id=1499296458753445998"
                + "&redirect_uri=https://pogocoordinates.com/callback"
                + "&response_type=code"
                + "&scope=identify%20applications.commands"
                + "&integration_type=1"
                + "&state=login:discord", Pogo.DISCORD_URL);
    }

    @Test public void webDataDirsFollowTheSuffix() {
        assertEquals("app_webview_pogoweb", Pogo.webDirs(28)[0]);
        assertEquals("cache/webview_pogoweb", Pogo.webDirs(36)[1]);
        assertEquals("app_webview", Pogo.webDirs(27)[0]);
        assertEquals("cache/WebView", Pogo.webDirs(24)[1]);
    }

    @Test public void deleteTreeRemovesEverything() throws Exception {
        File root = new File(tmp.getRoot(), "app_webview_pogoweb");
        File ls = new File(root, "Default/Local Storage/leveldb");
        assertTrue(ls.mkdirs());
        assertTrue(new File(ls, "000003.log").createNewFile());
        Pogo.deleteTree(root);
        assertFalse(root.exists());
        Pogo.deleteTree(root);
    }

    @Test public void deleteTreeDoesNotFollowLinks() throws Exception {
        File outside = tmp.newFolder("outside");
        File keep = new File(outside, "keep");
        assertTrue(keep.createNewFile());
        File root = tmp.newFolder("app_webview");
        java.nio.file.Files.createSymbolicLink(new File(root, "link").toPath(), outside.toPath());
        Pogo.deleteTree(root);
        assertFalse(root.exists());
        assertTrue(keep.exists());
    }

    @Test public void searchBodyRenamesIvAndKeepsTheRest() throws Exception {
        PogoFilter f = new PogoFilter();
        f.pokemonIds.add(133);
        f.speciesForms.add(new int[]{19, 46});
        f.ivMin = 90.0;
        f.ivMax = 100.0;
        f.cpMin = 500;
        f.levelMax = 30.0;
        f.gender = "Female";
        f.sizes.add("XXL");
        f.boosted = true;
        f.sort = "DSP";
        f.pvpMode = true;
        f.pvpLeague = "ultra";
        f.pvpTopRank = 10;
        f.shinyOnly = true;
        f.shinyAccount = "999";
        f.numResults = 500;
        JSONObject b = Pogo.searchBody(f, new JSONArray("[{\"trainer_code\":\"1\"}]"));
        assertEquals(90.0, b.getDouble("iv_percent_min"), 0);
        assertEquals(100.0, b.getDouble("iv_percent_max"), 0);
        assertEquals("percent", b.getString("iv_mode"));
        assertFalse(b.has("iv_min"));
        assertFalse(b.has("iv_max"));
        assertFalse(b.has("filters"));
        assertFalse(b.has("num_results"));
        assertFalse(b.has("shiny_account"));
        assertTrue(b.getBoolean("shiny_only"));
        assertEquals(500, b.getInt("cp_min"));
        assertEquals(30.0, b.getDouble("level_max"), 0);
        assertEquals("Female", b.getString("gender"));
        assertEquals("[\"XXL\"]", b.getJSONArray("sizes").toString());
        assertTrue(b.getBoolean("boosted"));
        assertEquals("DSP", b.getString("sort"));
        assertEquals("ultra", b.getString("pvp_league"));
        assertEquals(10, b.getInt("pvp_top_rank"));
        assertEquals("[133]", b.getJSONArray("pokemon_ids").toString());
        assertEquals(46, b.getJSONArray("species_forms").getJSONObject(0).getInt("form_id"));
        JSONObject anon = Pogo.searchBody(f, new JSONArray());
        assertFalse(anon.has("shiny_only"));
        JSONObject def = Pogo.searchBody(new PogoFilter(), new JSONArray());
        assertEquals(2, def.length());
        assertEquals("percent", def.getString("iv_mode"));
        assertEquals("IV", def.getString("sort"));
    }

    @Test public void searchItemsHaveLocationAndNoCoordinates() throws Exception {
        List<Pogo.Mon> l = Pogo.parseSearch("{\"results\":[{\"reveal_code\":\"C1\",\"pokemon_id\":255,"
                + "\"pokemon_name\":\"Torchic\",\"form\":null,\"cp\":750,\"level\":24.0,\"iv_percent\":100.0,"
                + "\"gender\":\"Male\",\"size\":\"M\",\"location\":\"Le Pont-de-Claix, France\","
                + "\"despawn_at\":\"2026-10-14T23:31:16Z\"},{\"pokemon_id\":1}],\"tier\":\"logged_in\",\"cap\":10}");
        assertEquals(1, l.size());
        Pogo.Mon m = l.get(0);
        assertEquals("C1", m.reveal);
        assertEquals("Le Pont-de-Claix, France", m.location);
        assertEquals(100.0, m.iv, 0);
        assertTrue(Double.isNaN(m.lat) && Double.isNaN(m.lng));
        assertEquals(1792020676000L, m.despawnMs);
    }

    @Test public void isoWithZoneOrOffset() {
        long z = 1792020676000L;
        assertEquals(z, Pogo.isoMs("2026-10-14T23:31:16Z"));
        assertEquals(z, Pogo.isoMs("2026-10-14T18:31:16-05:00"));
        assertEquals(z, Pogo.isoMs("2026-10-15T01:31:16+0200"));
        assertEquals(z + 462, Pogo.isoMs("2026-10-14T23:31:16.462565+00:00"));
        assertEquals(z + 500, Pogo.isoMs("2026-10-14T23:31:16.5Z"));
        assertEquals(0, Pogo.isoMs("2026-10-14 23:31:16"));
        assertEquals(0, Pogo.isoMs(null));
    }

    @Test public void onlyMainWritesAndOthersRereadPrefs() {
        assertTrue(Pogo.writer(com.pokelocation.app.App.PROC_MAIN));
        assertFalse(Pogo.writer(com.pokelocation.app.App.PROC_OVERLAY));
        assertFalse(Pogo.writer(com.pokelocation.app.App.PROC_MAP));
        assertFalse(Pogo.writer(com.pokelocation.app.App.PROC_POGOWEB));
        assertEquals(android.content.Context.MODE_PRIVATE, Pogo.prefsMode(com.pokelocation.app.App.PROC_MAIN));
        assertEquals(android.content.Context.MODE_MULTI_PROCESS, Pogo.prefsMode(com.pokelocation.app.App.PROC_OVERLAY));
        assertEquals(android.content.Context.MODE_MULTI_PROCESS, Pogo.prefsMode(com.pokelocation.app.App.PROC_MAP));
    }

    @Test public void cardFieldsFromLookupAndSearch() throws Exception {
        Pogo.Mon l = Pogo.parseLookup("{\"results\":[{\"reveal_code\":\"X\",\"pokemon_id\":25,\"costume\":\"HALLOWEEN_2017\","
                + "\"raw_iv\":\"15/14/13\",\"despawn_verified\":false,\"shiny\":true,\"apx_lat\":1,\"apx_long\":2}]}").get(0);
        assertEquals("HALLOWEEN_2017", l.costume);
        assertEquals("15/14/13", l.rawIv);
        assertFalse(l.verified);
        assertTrue(l.shiny);
        assertFalse(l.background);
        assertNull(l.fastMove);
        Pogo.Mon s = Pogo.parseSearch("{\"results\":[{\"reveal_code\":\"Y\",\"costume\":null,\"fast_move\":\"Ember\","
                + "\"charge_move\":\"Flame Charge\",\"background\":true}]}").get(0);
        assertNull(s.costume);
        assertEquals("Ember", s.fastMove);
        assertEquals("Flame Charge", s.chargeMove);
        assertTrue(s.verified);
        assertTrue(s.background);
    }

    @Test public void pvpObjectOrNothing() throws Exception {
        Pogo.Mon m = Pogo.parseSearch("{\"results\":[{\"reveal_code\":\"Z\",\"pvp\":{\"league\":\"great\",\"rank\":1,"
                + "\"pokemon_id\":6,\"pokemon_name\":\"Charizard\",\"cp\":1500,\"level\":19.5,\"percent\":100.0}},"
                + "{\"reveal_code\":\"W\",\"pvp\":null}]}").get(0);
        assertEquals("great", m.pvpLeague);
        assertEquals(1, m.pvpRank);
        assertEquals("Charizard", m.pvpName);
        assertEquals(1500, m.pvpCp);
        assertEquals(100.0, m.pvpPercent, 0);
        Pogo.Mon none = Pogo.parseSearch("{\"results\":[{\"reveal_code\":\"W\",\"pvp\":null}]}").get(0);
        assertNull(none.pvpLeague);
        assertNull(none.pvpName);
        assertEquals(-1, none.pvpRank);
        assertEquals(-1, none.pvpCp);
        assertEquals(-1, none.pvpPercent, 0);
    }

    @Test public void serverErrorsMapToPogoErrors() {
        Pogo.Error up = Pogo.fromApi(429, "upstream",
                "{\"error\":\"upstream\",\"status\":429,\"detail\":{\"error\":\"rate_limited\",\"retry_after\":46},\"retry_after\":46}", 60);
        assertEquals(429, up.http);
        assertEquals("rate_limited", up.error);
        assertEquals(46, up.retryAfterS);
        Pogo.Error str = Pogo.fromApi(404, "upstream", "{\"error\":\"upstream\",\"status\":404,\"detail\":\"Not Found\"}", 60);
        assertEquals("Not Found", str.message);
        Pogo.Error hdr = Pogo.fromApi(429, "upstream", "{\"error\":\"upstream\",\"status\":429,\"detail\":null,\"retry_after\":30}", 60);
        assertEquals(30, hdr.retryAfterS);
        Pogo.Error ours = Pogo.fromApi(429, "rate_limited", "{\"error\":\"rate_limited\"}", 3600);
        assertEquals("rate_limited", ours.error);
        assertEquals(3600, ours.retryAfterS);
        Pogo.Error relog = Pogo.fromApi(409, "pogo_relogin", "{\"error\":\"pogo_relogin\"}", 60);
        assertTrue(Pogo.relogin(relog));
        assertEquals(0, relog.retryAfterS);
        assertTrue(Pogo.ruleLimit(Pogo.fromApi(409, "too_many_rules", "{}", 60)));
        assertFalse(Pogo.relogin(new java.io.IOException("x")));
    }

    @Test public void ruleRoundTrip() throws Exception {
        Pogo.Rule r = new Pogo.Rule();
        r.name = "Dratini 100";
        r.filter = new PogoFilter();
        r.filter.pokemonIds.add(147);
        r.filter.ivMin = 100.0;
        r.lat = -23.55;
        r.lng = -46.63;
        r.radiusKm = 2;
        JSONObject j = Pogo.ruleJson(r);
        assertEquals("near", j.getString("scope"));
        assertEquals(2, j.getInt("radius_km"));
        assertEquals(100.0, j.getJSONObject("filters").getDouble("iv_min"), 0);
        assertFalse(j.has("id"));
        Pogo.Rule back = Pogo.parseRule(j.put("id", "u1").put("created_at", 1L));
        assertEquals("u1", back.id);
        assertEquals("[147]", back.filter.pokemonIds.toString());
        assertFalse(back.world);
        assertEquals(-46.63, back.lng, 0);
        r.world = true;
        JSONObject w = Pogo.ruleJson(r);
        assertEquals("world", w.getString("scope"));
        assertFalse(w.has("lat"));
        Pogo.Rule wb = Pogo.parseRule(new JSONObject("{\"id\":\"u2\",\"name\":\"x\",\"filters\":{},\"scope\":\"world\","
                + "\"lat\":null,\"lng\":null,\"radius_km\":null,\"enabled\":false}"));
        assertTrue(wb.world);
        assertTrue(Double.isNaN(wb.lat));
        assertFalse(wb.enabled);
    }

    @Test public void enrichAsksForTheSameSpeciesNearby() throws Exception {
        Pogo.Mon m = Pogo.parseLookup("{\"results\":[{\"encounter_id\":\"326\",\"reveal_code\":\"X\",\"pokemon_id\":147,"
                + "\"apx_lat\":-23.5,\"apx_long\":-46.6}]}").get(0);
        assertEquals("326", m.encounter);
        JSONObject b = Pogo.enrichBody(m);
        assertEquals("[147]", b.getJSONArray("pokemon_ids").toString());
        assertEquals(-23.5, b.getDouble("center_lat"), 0);
        assertEquals(-46.6, b.getDouble("center_lng"), 0);
        assertEquals(0.3, b.getDouble("radius_km"), 0);
        assertEquals("DISTANCE", b.getString("sort"));
        assertEquals("326", Pogo.parseSearch("{\"results\":[{\"encounter_id\":\"326\",\"reveal_code\":\"Y\"}]}").get(0).encounter);
    }

    @Test public void enrichWaitsOutA429() throws Exception {
        Pogo.Mon m = Pogo.parseLookup("{\"results\":[{\"encounter_id\":\"9\",\"reveal_code\":\"X\",\"pokemon_id\":1,"
                + "\"apx_lat\":1,\"apx_long\":2}]}").get(0);
        Pogo.searchLimited(Pogo.fromApi(429, "upstream", "{\"error\":\"upstream\",\"status\":429,\"retry_after\":46}", 60));
        assertNull(Pogo.enrich(null, m)); // sem Context: se fosse à rede, quebraria
    }

    @Test public void t22TrainerErrorsAndDisabledReason() throws Exception {
        assertTrue(Pogo.trainerRequired(Pogo.fromApi(422, "trainer_required", "{\"error\":\"trainer_required\"}", 60)));
        assertTrue(Pogo.shinyAccountUnavailable(Pogo.fromApi(422, "shiny_account_unavailable", "{}", 60)));
        assertFalse(Pogo.trainerRequired(Pogo.fromApi(422, "bad_request", "{}", 60)));
        Pogo.Rule off = Pogo.parseRule(new JSONObject("{\"id\":\"u\",\"name\":\"x\",\"filters\":{\"shiny_only\":true},"
                + "\"scope\":\"world\",\"enabled\":false,\"disabled_reason\":\"trainer_required\"}"));
        assertEquals("trainer_required", off.disabledReason);
        assertFalse(off.enabled);
        assertNull(Pogo.parseRule(new JSONObject("{\"id\":\"u\",\"scope\":\"world\",\"disabled_reason\":null}")).disabledReason);
        assertFalse(Pogo.ruleJson(off).has("disabled_reason"));
    }

    @Test public void slowIsTheProxyTimeoutOrTheDirectOne() {
        assertTrue(Pogo.slow(Pogo.fromApi(504, "upstream_timeout", "{\"error\":\"upstream_timeout\"}", 60)));
        assertTrue(Pogo.slow(new java.net.SocketTimeoutException("Read timed out")));
        assertFalse(Pogo.slow(Pogo.fromApi(502, "upstream_unavailable", "{}", 60)));
        assertFalse(Pogo.slow(new java.io.IOException("x")));
    }

    /** Sem o direto, esses viram "PoGo indisponível agora"; o lento e o limite têm aviso próprio. */
    @Test public void withoutDirectTheseAreUnavailable() {
        assertTrue(Pogo.unavailable(Pogo.fromApi(503, "pogo_disabled", "{}", 60)));
        assertTrue(Pogo.unavailable(Pogo.fromApi(409, "pogo_relogin", "{}", 60)));
        assertTrue(Pogo.unavailable(Pogo.fromApi(502, "upstream_unavailable", "{}", 60)));
        assertFalse(Pogo.unavailable(Pogo.fromApi(504, "upstream_timeout", "{}", 60)));
        assertFalse(Pogo.unavailable(Pogo.fromApi(429, "rate_limited", "{}", 60)));
        assertFalse(Pogo.unavailable(Pogo.fromApi(404, "not_found", "{}", 60)));
        assertFalse(Pogo.unavailable(new java.io.IOException("x")));
    }
}
