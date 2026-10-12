package com.pokelocation.app.ui.main;

import android.animation.ValueAnimator;
import android.content.SharedPreferences;
import android.location.Location;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;

import com.pokelocation.app.Io;
import com.pokelocation.app.R;
import com.pokelocation.app.data.Prefs;
import com.pokelocation.app.data.RouteDao;
import com.pokelocation.app.geo.CoordParser;
import com.pokelocation.app.model.Coord;
import com.pokelocation.app.model.Route;
import com.pokelocation.app.ui.panel.MessagePanel;
import com.pokelocation.app.util.Perms;
import com.pokelocation.app.util.Ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Estado e lógica do formulário da Main (03 §2.1, §3.2, §3.6, §3.7, §3.9, §3.12, §3.15). Só main thread. */
final class MainForm implements RadioGroup.OnCheckedChangeListener, AdapterView.OnItemSelectedListener {
    static final int SRC_LAST = 0, SRC_CURRENT = 1, SRC_SET = 2, SRC_ROUTE = 3, SRC_RECORD = 4;
    private static final int[] RADIOS = {R.id.main_rb_last, R.id.main_rb_current, R.id.main_rb_set,
            R.id.main_rb_route, R.id.main_rb_record};
    private static final int[] ROUTE_MODES = {R.id.main_rm_stop, R.id.main_rm_loop, R.id.main_rm_reverse};
    private static final int[] START_MODES = {R.id.main_sm_teleport, R.id.main_sm_walk};

    /** O que o Start manda ao serviço. */
    static final class Req {
        final double lat, lng, alt;
        String routeId;
        boolean record;
        Req(double lat, double lng, double alt) { this.lat = lat; this.lng = lng; this.alt = alt; }
    }

    private final MainActivity a;
    private final RadioGroup source, routeMode, startMode;
    private final View blockLast, blockCurrent, blockSet, blockRoute, quickBody;
    private final TextView lastLat, lastLng, lastAlt, curLat, curLng, curAlt;
    private final EditText coord, alt;
    private final Button addFav;
    private final Spinner spinner;
    private final CheckBox cbHide, cbDrawerHide;
    private final ImageView quickArrow;
    private final double[] tmp = new double[2];

    private Coord last, current;
    private List<Route> routes = Collections.emptyList();
    private String routeId, ptsRouteId;
    private double[] routePts;
    private Io.Task routesTask, ptsTask;
    private final CurrentLocation curLoc = new CurrentLocation();
    private ValueAnimator blink;
    private boolean startWhenCurrentReady;
    // Resultado da busca para "Add to Favorites" (F20)
    private String favName, favAddress;
    private Coord favCoord;

    MainForm(MainActivity a, View root) {
        this.a = a;
        source = root.findViewById(R.id.main_source);
        routeMode = root.findViewById(R.id.main_route_mode);
        startMode = root.findViewById(R.id.main_start_mode);
        blockLast = root.findViewById(R.id.main_block_last);
        blockCurrent = root.findViewById(R.id.main_block_current);
        blockSet = root.findViewById(R.id.main_block_set);
        blockRoute = root.findViewById(R.id.main_block_route);
        quickBody = root.findViewById(R.id.main_quick_body);
        quickArrow = root.findViewById(R.id.main_quick_arrow);
        lastLat = root.findViewById(R.id.main_last_lat);
        lastLng = root.findViewById(R.id.main_last_lng);
        lastAlt = root.findViewById(R.id.main_last_alt);
        curLat = root.findViewById(R.id.main_cur_lat);
        curLng = root.findViewById(R.id.main_cur_lng);
        curAlt = root.findViewById(R.id.main_cur_alt);
        coord = root.findViewById(R.id.main_coord);
        alt = root.findViewById(R.id.main_alt);
        addFav = root.findViewById(R.id.main_add_fav);
        spinner = root.findViewById(R.id.main_route_spinner);
        cbHide = root.findViewById(R.id.main_cb_hide);
        cbDrawerHide = root.findViewById(R.id.main_cb_drawer_hide);

        root.findViewById(R.id.main_quick_header).setOnClickListener(v -> {
            boolean show = quickBody.getVisibility() != View.VISIBLE;
            Prefs.edit(a).putBoolean(Prefs.OPTIONS_VISIBILITY, show).apply();
            applyQuickOptions(show);
        });
        root.findViewById(R.id.main_cooldown_link).setOnClickListener(v -> a.openCooldown());
        root.findViewById(R.id.main_fix_gps).setOnClickListener(v -> a.openGpsFix());
        root.findViewById(R.id.main_paste).setOnClickListener(v -> setText(coord, Ui.paste(a)));
        root.findViewById(R.id.main_format).setOnClickListener(v -> {
            String f = CoordParser.format(coord.getText().toString());
            if (f != null) setText(coord, f);
            else Ui.toast(a, R.string.invalid_location);
        });
        addFav.setOnClickListener(v -> {
            if (favCoord != null) a.addFavorite(favName, favAddress, favCoord);
        });

        TextView openMap = root.findViewById(R.id.main_open_map);
        SpannableStringBuilder sb = new SpannableStringBuilder(a.getString(R.string.main_open_map));
        sb.setSpan(new ForegroundColorSpan(a.getColor(R.color.accent)), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append(' ').append(a.getString(R.string.main_open_map_suffix));
        openMap.setText(sb);
        openMap.setOnClickListener(v -> a.setMode(1));

        routeMode.setOnCheckedChangeListener((g, id) -> writeMode(Prefs.ROUTE_MODE, ROUTE_MODES, id));
        startMode.setOnCheckedChangeListener((g, id) -> writeMode(Prefs.ROUTE_START_MODE, START_MODES, id));
        spinner.setOnItemSelectedListener(this);
        source.setOnCheckedChangeListener(this);
    }

    /** onCreate: restaura textos (F27), rádios de rota, Quick Options e o rádio de origem. */
    void init(int src, String savedRouteId) {
        routeId = savedRouteId;
        applyQuickOptions(Prefs.getBool(a, Prefs.OPTIONS_VISIBILITY));
        double lat = Prefs.getDouble(a, Prefs.LATITUDE_TEXT), lng = Prefs.getDouble(a, Prefs.LONGITUDE_TEXT);
        double al = Prefs.getDouble(a, Prefs.ALTITUDE_TEXT);
        if (!Double.isNaN(lat) && !Double.isNaN(lng)) setText(coord, CoordParser.text(lat, lng));
        if (!Double.isNaN(al)) setText(alt, fmt(al));
        refreshLast();
        syncRouteRadios();
        select(src);
        // Sem last e com FINE: marca "Get Current Location" (03 §3.2 item 8)
        if (src == SRC_LAST && last == null && Perms.hasFine(a)) source.post(() -> select(SRC_CURRENT));
        // Se já estiver em current e sem leitura, tenta obter agora
        if (src == SRC_CURRENT && current == null && !curLoc.active() && Perms.hasFine(a)) {
            readCurrent();
        }
    }

    // ---------------------------------------------------------------- origem

    int source() {
        int id = source.getCheckedRadioButtonId();
        for (int i = 0; i < RADIOS.length; i++) if (RADIOS[i] == id) return i;
        return SRC_LAST;
    }

    void select(int src) {
        if (source.getCheckedRadioButtonId() == RADIOS[src]) onSourceChanged();
        else source.check(RADIOS[src]);
    }

    @Override
    public void onCheckedChanged(RadioGroup group, int checkedId) {
        if (checkedId != -1) onSourceChanged();
    }

    private void onSourceChanged() {
        int s = source();
        blockLast.setVisibility(s == SRC_LAST ? View.VISIBLE : View.GONE);
        blockCurrent.setVisibility(s == SRC_CURRENT ? View.VISIBLE : View.GONE);
        blockSet.setVisibility(s == SRC_SET || s == SRC_RECORD ? View.VISIBLE : View.GONE);
        blockRoute.setVisibility(s == SRC_ROUTE ? View.VISIBLE : View.GONE);
        if (s == SRC_CURRENT) {
            readCurrent();
        } else {
            curLoc.cancel();
            startWhenCurrentReady = false;
        }
        a.onFormChanged();
    }

    // ---------------------------------------------------------------- last / current

    void refreshLast() {
        last = Prefs.lastPosition(a);
        if (last == null) {
            lastLat.setText(R.string.main_no_last);
            lastLng.setVisibility(View.GONE);
            lastAlt.setVisibility(View.GONE);
        } else {
            showCoord(last, lastLat, lastLng, lastAlt);
        }
    }

    Coord last() { return last; }

    /** Com o replay frio sem last: inicia assim que a posição real chegar (F97). */
    void startWhenCurrentReady() { startWhenCurrentReady = true; }

    /** Leitura do GPS só com a Activity visível (checklist CURRENT). */
    void onPause() { curLoc.cancel(); }

    void onResume() {
        if (source() == SRC_CURRENT && current == null && !curLoc.active() && Perms.hasFine(a)) readCurrent();
    }

    private void readCurrent() {
        current = null;
        if (!Perms.hasFine(a)) {
            showCurrentText(R.string.main_error_location);
            a.requestFine(() -> { if (source() == SRC_CURRENT) readCurrent(); });
            return;
        }
        showCurrentText(R.string.loading);
        curLoc.start(a, this::onCurrent);
    }

    private void onCurrent(Location l) {
        if (source() != SRC_CURRENT) return;
        if (l == null) {
            showCurrentText(R.string.main_error_location);
            startWhenCurrentReady = false;
        } else {
            current = new Coord(l.getLatitude(), l.getLongitude(), l.hasAltitude() ? l.getAltitude() : 0);
            showCoord(current, curLat, curLng, curAlt);
        }
        a.onFormChanged();
        if (current != null && startWhenCurrentReady) {
            startWhenCurrentReady = false;
            a.startFlow();
        }
    }

    private void showCurrentText(int res) {
        curLat.setText(res);
        curLng.setText(res);
        curAlt.setText(res);
        curLng.setVisibility(View.VISIBLE);
        curAlt.setVisibility(View.VISIBLE);
    }

    private void showCoord(Coord c, TextView lat, TextView lng, TextView al) {
        lat.setText(a.getString(R.string.main_lat_fmt, Double.toString(c.lat)));
        lng.setText(a.getString(R.string.main_lng_fmt, Double.toString(c.lng)));
        al.setText(a.getString(R.string.main_alt_fmt, fmt(c.altOr0())));
        lng.setVisibility(View.VISIBLE);
        al.setVisibility(View.VISIBLE);
    }

    // ---------------------------------------------------------------- rotas (03 §3.6)

    void syncRouteRadios() {
        int rm = Prefs.getInt(a, Prefs.ROUTE_MODE), sm = Prefs.getInt(a, Prefs.ROUTE_START_MODE);
        routeMode.check(ROUTE_MODES[rm >= 0 && rm < ROUTE_MODES.length ? rm : 0]);
        startMode.check(START_MODES[sm == 1 ? 1 : 0]);
    }

    private void writeMode(String key, int[] ids, int checkedId) {
        for (int i = 0; i < ids.length; i++) {
            if (ids[i] == checkedId) {
                if (Prefs.getInt(a, key) != i) Prefs.edit(a).putInt(key, i).apply();
                return;
            }
        }
    }

    /** Recarrega o spinner (só ids/nomes; a Last Route já vem excluída pelo DAO). */
    void loadRoutes() {
        if (routesTask != null) routesTask.cancel();
        spinner.setEnabled(false);
        routesTask = Io.run(() -> RouteDao.listAll(a), new Io.Callback<List<Route>>() {
            @Override public void onResult(List<Route> list) { onRoutes(list); }
            @Override public void onError(Throwable t) { onRoutes(Collections.emptyList()); }
        });
    }

    private void onRoutes(List<Route> list) {
        routes = list == null ? Collections.emptyList() : list;
        List<String> names = new ArrayList<>(Math.max(1, routes.size()));
        int sel = 0;
        if (routes.isEmpty()) {
            names.add(a.getString(R.string.main_no_routes));
            routeId = null;
        } else {
            for (int i = 0; i < routes.size(); i++) {
                Route r = routes.get(i);
                names.add(r.name);
                if (r.id.equals(routeId)) sel = i;
            }
            routeId = routes.get(sel).id;
        }
        ArrayAdapter<String> ad = new ArrayAdapter<>(a, android.R.layout.simple_spinner_item, names);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(ad);
        spinner.setSelection(sel, false);
        spinner.setEnabled(!routes.isEmpty());
        loadPts();
        a.onFormChanged();
    }

    @Override
    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        if (position < 0 || position >= routes.size()) return;
        String rid = routes.get(position).id;
        if (rid.equals(routeId)) return;
        routeId = rid;
        loadPts();
        a.onFormChanged();
    }

    @Override
    public void onNothingSelected(AdapterView<?> parent) {
        routeId = null;
        a.onFormChanged();
    }

    /** Pontos só da rota selecionada (alvo da distância e início sem last). */
    private void loadPts() {
        if (routeId == null) {
            routePts = null;
            ptsRouteId = null;
            return;
        }
        if (routeId.equals(ptsRouteId)) return;
        if (ptsTask != null) ptsTask.cancel();
        String id = routeId;
        routePts = null;
        ptsTask = Io.run(() -> RouteDao.get(a, id), r -> {
            if (!id.equals(routeId)) return;
            ptsRouteId = id;
            routePts = r == null ? null : r.pts;
            a.onFormChanged();
        });
    }

    /** Retorno de Routes (F26): marca Set Route com a rota escolhida. */
    void selectRoute(String id) {
        routeId = id;
        for (int i = 0; i < routes.size(); i++) {
            if (routes.get(i).id.equals(id)) {
                spinner.setSelection(i, false);
                break;
            }
        }
        loadPts();
        select(SRC_ROUTE);
        blink(blockRoute);
    }

    String routeId() { return routeId; }

    // ---------------------------------------------------------------- Set Location

    /** Preenche Set Location (mantém Record Route se marcado). */
    void fillLocation(Coord c, boolean flash) {
        if (source() != SRC_RECORD) select(SRC_SET);
        setText(coord, CoordParser.text(c.lat, c.lng));
        setText(alt, fmt(c.altOr0()));
        hideAddFavorite();
        if (flash) blink(blockSet);
        a.onFormChanged();
    }

    /** Resultado assíncrono da elevação: só aplica se o campo ainda mostra o mesmo ponto. */
    void setAltitudeFor(double lat, double lng, double value) {
        if (CoordParser.parse(coord.getText().toString(), tmp) && tmp[0] == lat && tmp[1] == lng) setText(alt, fmt(value));
    }

    void showAddFavorite(String name, String address, Coord c) {
        favName = name;
        favAddress = address == null ? "" : address;
        favCoord = c;
        addFav.setVisibility(View.VISIBLE);
    }

    void hideAddFavorite() {
        favCoord = null;
        addFav.setVisibility(View.GONE);
    }

    /** "lat, lng" digitado + altitude (NaN se vazia/inválida); null se o texto não é uma coordenada. */
    Coord typedCoord() {
        if (!CoordParser.parse(coord.getText().toString(), tmp)) return null;
        double al;
        try {
            al = Double.parseDouble(alt.getText().toString().trim());
        } catch (NumberFormatException e) {
            al = Double.NaN;
        }
        return new Coord(tmp[0], tmp[1], al);
    }

    // ---------------------------------------------------------------- Start

    /** Destino do rádio atual (linha de distância e CooldownSheet); null se desconhecido. */
    Coord target() {
        switch (source()) {
            case SRC_LAST: return last;
            case SRC_CURRENT: return current;
            case SRC_ROUTE: return routePts == null || routePts.length < 3 ? null : Route.at(routePts, 0);
            default: return typedCoord();
        }
    }

    boolean canStart() {
        switch (source()) {
            case SRC_LAST: return last != null;
            case SRC_CURRENT: return current != null;
            case SRC_ROUTE: return routeId != null;
            default: return true;
        }
    }

    /** Valida o rádio e grava last_* / *_text com commit (o :overlay lê o arquivo ao nascer). null = não inicia. */
    Req collect() {
        SharedPreferences.Editor e = Prefs.edit(a);
        Req r;
        int s = source();
        switch (s) {
            case SRC_LAST:
                if (last == null) return null;
                r = new Req(last.lat, last.lng, last.alt);
                break;
            case SRC_CURRENT:
                if (current == null) return null;
                putLast(e, current.lat, current.lng, current.alt);
                r = new Req(current.lat, current.lng, current.alt);
                break;
            case SRC_ROUTE: {
                if (routeId == null) return null;
                Coord st = last != null ? last : target();
                if (st == null) {           // sem last e pontos ainda carregando
                    Ui.toast(a, R.string.loading);
                    return null;
                }
                r = new Req(st.lat, st.lng, st.alt);
                r.routeId = routeId;
                break;
            }
            default: {
                String t = coord.getText().toString().trim(), at = alt.getText().toString().trim();
                if (t.isEmpty() || at.isEmpty()) return message(R.string.main_woops);
                if (!CoordParser.parse(t, tmp)) return message(R.string.invalid_location);
                double al;
                try {
                    al = Double.parseDouble(at);
                } catch (NumberFormatException ex) {
                    return message(R.string.main_invalid_altitude);
                }
                if (Double.isNaN(al) || Double.isInfinite(al)) return message(R.string.main_invalid_altitude);
                Prefs.putDouble(e, Prefs.LATITUDE_TEXT, tmp[0]);
                Prefs.putDouble(e, Prefs.LONGITUDE_TEXT, tmp[1]);
                Prefs.putDouble(e, Prefs.ALTITUDE_TEXT, al);
                putLast(e, tmp[0], tmp[1], al);
                r = new Req(tmp[0], tmp[1], al);
                r.record = s == SRC_RECORD;
            }
        }
        e.commit();
        return r;
    }

    private static void putLast(SharedPreferences.Editor e, double lat, double lng, double al) {
        Prefs.putDouble(e, Prefs.LAST_LATITUDE, lat);
        Prefs.putDouble(e, Prefs.LAST_LONGITUDE, lng);
        Prefs.putDouble(e, Prefs.LAST_ALTITUDE, al);
    }

    private Req message(int res) {
        MessagePanel.show(a, null, a.getString(res), a.getString(R.string.ok), null, null);
        return null;
    }

    // ---------------------------------------------------------------- checkboxes (F16, 10 §2.5)

    void setupHide() {
        boolean drawer = "drawer".equals(Prefs.getString(a, Prefs.SETTINGS_JOYSTICK_TYPE));
        cbHide.setOnCheckedChangeListener(null);
        cbDrawerHide.setOnCheckedChangeListener(null);
        cbHide.setText(drawer ? R.string.main_hide_overlay : R.string.main_hide_joystick);
        cbHide.setChecked(Prefs.getBool(a, Prefs.HIDE_JOYSTICK));
        cbDrawerHide.setVisibility(drawer ? View.VISIBLE : View.GONE);
        cbDrawerHide.setChecked(Prefs.getBool(a, Prefs.DRAWER_HIDE_JOYSTICK));
        cbHide.setOnCheckedChangeListener((b, c) -> Prefs.edit(a).putBoolean(Prefs.HIDE_JOYSTICK, c).apply());
        cbDrawerHide.setOnCheckedChangeListener((b, c) -> Prefs.edit(a).putBoolean(Prefs.DRAWER_HIDE_JOYSTICK, c).apply());
    }

    // ---------------------------------------------------------------- UX

    private void applyQuickOptions(boolean show) {
        quickBody.setVisibility(show ? View.VISIBLE : View.GONE);
        quickArrow.setImageResource(show ? R.drawable.ic_expand_less : R.drawable.ic_expand_more);
    }

    /** Pisca o bloco (03 §3.15): transparente → destaque → transparente, atraso 400 ms, 800 ms. */
    private void blink(View block) {
        if (blink != null) blink.cancel();
        int hi = a.getColor(R.color.primary_container), clear = hi & 0x00FFFFFF;
        blink = ValueAnimator.ofArgb(clear, hi, clear);
        blink.setStartDelay(400);
        blink.setDuration(800);
        blink.setInterpolator(new DecelerateInterpolator());
        blink.addUpdateListener(an -> block.setBackgroundColor((Integer) an.getAnimatedValue()));
        blink.start();
    }

    void cancelAll() {
        if (routesTask != null) routesTask.cancel();
        if (ptsTask != null) ptsTask.cancel();
        if (blink != null) blink.cancel();
        curLoc.cancel();
    }

    private static void setText(EditText et, String s) {
        et.setText(s);
        et.setSelection(et.length());
    }

    /** Altitude sem ".0" sobrando. */
    static String fmt(double v) {
        return v == Math.rint(v) && Math.abs(v) < 1e15 ? Long.toString((long) v) : Double.toString(v);
    }
}
