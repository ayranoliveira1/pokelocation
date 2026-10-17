package com.pokelocation.app.ui.main;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.google.android.material.navigation.NavigationView;
import com.pokelocation.app.Io;
import com.pokelocation.app.R;
import com.pokelocation.app.data.FavoriteDao;
import com.pokelocation.app.data.Prefs;
import com.pokelocation.app.data.RouteDao;
import com.pokelocation.app.geo.Cooldown;
import com.pokelocation.app.geo.Geo;
import com.pokelocation.app.ipc.Intents;
import com.pokelocation.app.ipc.LiveStatus;
import com.pokelocation.app.ipc.WidgetPing;
import com.pokelocation.app.model.Coord;
import com.pokelocation.app.model.Folder;
import com.pokelocation.app.model.MapOptions;
import com.pokelocation.app.net.Elevation;
import com.pokelocation.app.social.Session;
import com.pokelocation.app.social.ui.AccountActivity;
import com.pokelocation.app.social.ui.chat.ChatListActivity;
import com.pokelocation.app.social.ui.FriendsActivity;
import com.pokelocation.app.social.ui.LoginActivity;
import com.pokelocation.app.ui.about.AboutActivity;
import com.pokelocation.app.ui.base.BaseActivity;
import com.pokelocation.app.ui.favorites.FavoritesActivity;
import com.pokelocation.app.ui.gpx.ExportActivity;
import com.pokelocation.app.ui.gpx.ImportActivity;
import com.pokelocation.app.ui.map.RouteMapView;
import com.pokelocation.app.ui.markers.MarkerTypesActivity;
import com.pokelocation.app.ui.panel.MessagePanel;
import com.pokelocation.app.ui.panel.NameFolderPanel;
import com.pokelocation.app.ui.panel.SelectPanel;
import com.pokelocation.app.ui.routes.RoutesActivity;
import com.pokelocation.app.ui.search.SearchActivity;
import com.pokelocation.app.ui.settings.SettingsActivity;
import com.pokelocation.app.ui.setup.SetupWizardActivity;
import com.pokelocation.app.util.Ui;

/**
 * Tela principal: formulário OU mapa (o mapa só existe no modo 1 e é destruído ao sair),
 * fluxo Start/Stop, drawer, links externos e replay de automação.
 */
public final class MainActivity extends BaseActivity {
    private static final String K_SOURCE = "main.source", K_ROUTE = "main.route", K_STARTING = "main.starting";
    private static final long RESTART_DELAY_MS = 500;   // deixa o :overlay antigo morrer (killProcess) antes do novo start

    private final Handler ui = Io.main();
    private MainForm form;
    private StartChecks startChecks;
    private DrawerLayout drawer;
    private Toolbar toolbar;
    private View formScroll, progress;
    private FrameLayout mapContainer;
    private TextView progressText, distance;
    private Button startBtn;
    private RouteMapView map;
    private int mode;
    private boolean running, starting, restartAfterStop, resumed;
    private MainForm.Req pendingReq;
    private Intent pendingReplay;          // ação externa que chegou com o serviço parado
    private Io.Task elevTask, upsertTask, favTask, routeTask;
    private AnimatorSet heartAnim;

    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener = (sp, key) -> {
        if (Prefs.SPOOF_ACTIVE.equals(key)) onSpoofState(sp.getBoolean(key, false));
    };

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            updateDistance();
            ui.postDelayed(this, 1000);
        }
    };

    private final Runnable restart = () -> {
        Coord l = form == null ? null : form.last();
        if (l != null) startWith(new MainForm.Req(l.lat, l.lng, l.alt));
    };

    private final ActivityResultLauncher<Intent> favoritesLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), this::onFavoriteResult);
    private final ActivityResultLauncher<Intent> routesLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), this::onRouteResult);
    private final ActivityResultLauncher<Intent> searchLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), this::onSearchResult);

    @Override
    protected void onCreate(Bundle s) {
        setTheme(R.style.Theme_PokeLocation);   // troca o tema Launch antes do super
        super.onCreate(s);
        if (!Prefs.getBool(this, Prefs.SETUP_WIZARD_COMPLETED)) {
            startActivity(new Intent(this, SetupWizardActivity.class));
            finish();
            return;
        }
        setContentView(R.layout.main_activity);
        startChecks = new StartChecks(this, this::onChecksPassed);

        drawer = findViewById(R.id.main_drawer);
        formScroll = findViewById(R.id.main_form_scroll);
        mapContainer = findViewById(R.id.main_map_container);
        progress = findViewById(R.id.main_progress);
        progressText = findViewById(R.id.main_progress_text);
        distance = findViewById(R.id.main_distance);
        startBtn = findViewById(R.id.main_start);
        startBtn.setOnClickListener(v -> startFlow());
        applyInsets(findViewById(R.id.main_root));
        setupToolbar();
        setupDrawer();
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (drawer.isDrawerOpen(GravityCompat.START)) {
                    drawer.closeDrawer(GravityCompat.START);
                } else if (map == null || !map.onBackPressed()) {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                    setEnabled(true);
                }
            }
        });

        form = new MainForm(this, formScroll);
        form.init(s == null ? MainForm.SRC_LAST : s.getInt(K_SOURCE, MainForm.SRC_LAST),
                s == null ? null : s.getString(K_ROUTE));
        setMode(Prefs.getInt(this, Prefs.MAIN_SCREEN_MODE));
        if (s == null) handleIntent(getIntent());
        else if (s.getBoolean(K_STARTING)) ui.post(this::startFlow);   // estava iniciando
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (form != null) handleIntent(intent);
    }

    @Override
    protected void onStart() {
        super.onStart();
        Prefs.sp(this).registerOnSharedPreferenceChangeListener(prefListener);
        LiveStatus.request(this, this::showLast);
        form.setupHide();
        form.loadRoutes();
        boolean now = Intents.isOverlayRunning(this);
        if (running && !now) onSpoofState(false);   // parou com a Main em background: listener estava desligado
        else setRunning(now);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        form.onResume();
        // "iniciando" só sobrevive enquanto o usuário está fora (Settings/permissão) no meio das checagens;
        // post: depois do ON_RESUME do StartChecks, que retoma a cadeia
        ui.post(() -> starting = false);
        NavigationView nav = findViewById(R.id.main_nav);
        nav.setCheckedItem(R.id.main_nav_home);
        boolean pogo = Prefs.pogo(this);   // login ou logout na Conta: o PoGo aparece ou some na volta
        nav.getMenu().findItem(R.id.main_nav_pogo_list).setVisible(pogo);
        nav.getMenu().findItem(R.id.main_nav_pogo).setVisible(pogo);
        if (map != null) map.onResume();
        ui.removeCallbacks(ticker);
        if (Prefs.getBool(this, Prefs.DISPLAY_DISTANCE_INFO)) ticker.run();
        else distance.setVisibility(View.GONE);
    }

    @Override
    protected void onPause() {
        resumed = false;
        form.onPause();
        ui.removeCallbacks(ticker);
        if (map != null) map.onPause();
        super.onPause();
    }

    @Override
    protected void onStop() {
        Prefs.sp(this).unregisterOnSharedPreferenceChangeListener(prefListener);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(ticker);
        ui.removeCallbacks(restart);
        if (form != null) form.cancelAll();
        cancel(elevTask);
        cancel(upsertTask);
        cancel(favTask);
        cancel(routeTask);
        if (heartAnim != null) heartAnim.cancel();
        destroyMap();
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (form == null) return;
        out.putInt(K_SOURCE, form.source());
        out.putString(K_ROUTE, form.routeId());
        out.putBoolean(K_STARTING, starting);
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (map != null) map.onTrimMemory(level);
    }

    private void setupToolbar() {
        toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(R.string.app_name);
        toolbar.setNavigationIcon(R.drawable.ic_menu);
        toolbar.setNavigationContentDescription(R.string.main_open_drawer);
        toolbar.setNavigationOnClickListener(v -> drawer.openDrawer(GravityCompat.START));
        toolbar.inflateMenu(R.menu.main_toolbar);
        toolbar.setOnMenuItemClickListener(this::onToolbarItem);
    }

    private boolean onToolbarItem(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.main_menu_search) searchLauncher.launch(new Intent(this, SearchActivity.class));
        else if (id == R.id.main_menu_open_map) setMode(1);
        else if (id == R.id.main_menu_open_form) setMode(0);
        else return false;
        return true;
    }

    private void setupDrawer() {
        NavigationView nav = findViewById(R.id.main_nav);
        TextView version = nav.getHeaderView(0).findViewById(R.id.main_header_version);
        try {
            version.setText(getString(R.string.main_version,
                    getPackageManager().getPackageInfo(getPackageName(), 0).versionName));
        } catch (Exception e) {
            version.setVisibility(View.GONE);
        }
        nav.setNavigationItemSelectedListener(item -> {
            drawer.closeDrawer(GravityCompat.START);
            onDrawerItem(item.getItemId());
            return item.getItemId() == R.id.main_nav_home;
        });
    }

    private void onDrawerItem(int id) {
        if (id == R.id.main_nav_favorites) {
            favoritesLauncher.launch(new Intent(this, FavoritesActivity.class));
        } else if (id == R.id.main_nav_routes) {
            routesLauncher.launch(new Intent(this, RoutesActivity.class));
        } else if (id == R.id.main_nav_markers) {
            startActivity(new Intent(this, MarkerTypesActivity.class));
        } else if (id == R.id.main_nav_friends) {
            startActivity(Session.loggedIn(this) ? new Intent(this, FriendsActivity.class) : new Intent(this, LoginActivity.class)
                    .putExtra(LoginActivity.EXTRA_RETURN_TO, LoginActivity.RETURN_FRIENDS));
        } else if (id == R.id.main_nav_chats) {
            startActivity(Session.loggedIn(this) ? new Intent(this, ChatListActivity.class) : new Intent(this, LoginActivity.class)
                    .putExtra(LoginActivity.EXTRA_RETURN_TO, LoginActivity.RETURN_CHATS));
        } else if (id == R.id.main_nav_account) {
            startActivity(AccountActivity.open(this));
        } else if (id == R.id.main_nav_pogo_list) {
            Coord t = form == null ? null : form.typedCoord();
            // por nome (sem const-class): com a camada desligada nada do pacote pogo é carregado; extras = os da PogoListActivity
            startActivity(new Intent().setClassName(this, "com.pokelocation.app.pogo.PogoListActivity")
                    .putExtra("pogo_lat", t != null ? t.lat : Double.NaN).putExtra("pogo_lng", t != null ? t.lng : Double.NaN));
        } else if (id == R.id.main_nav_pogo) {
            startActivity(new Intent().setClassName(this, "com.pokelocation.app.pogo.PogoActivity"));
        } else if (id == R.id.main_nav_gpx) {
            SelectPanel.show(this, getString(R.string.main_nav_gpx),
                    new CharSequence[]{getString(R.string.main_gpx_import), getString(R.string.main_gpx_export)},
                    i -> startActivity(new Intent(this, i == 0 ? ImportActivity.class : ExportActivity.class)));
        } else if (id == R.id.main_nav_widget) {
            if (!WidgetPing.requestPin(this)) Ui.toast(this, R.string.main_widget_unsupported);
        } else if (id == R.id.main_nav_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
        } else if (id == R.id.main_nav_setup) {
            startActivity(new Intent(this, SetupWizardActivity.class));
        } else if (id == R.id.main_nav_about) {
            startActivity(new Intent(this, AboutActivity.class));
        }
    }

    void setMode(int m) {
        mode = m == 1 ? 1 : 0;
        if (Prefs.getInt(this, Prefs.MAIN_SCREEN_MODE) != mode) Prefs.edit(this).putInt(Prefs.MAIN_SCREEN_MODE, mode).apply();
        hideKeyboard();
        if (mode == 0) {
            destroyMap();
            mapContainer.setVisibility(View.GONE);
            formScroll.setVisibility(View.VISIBLE);
        } else {
            formScroll.setVisibility(View.GONE);
            mapContainer.setVisibility(View.VISIBLE);
            if (map == null) createMap();
        }
        Menu menu = toolbar.getMenu();
        menu.findItem(R.id.main_menu_open_map).setVisible(mode == 0);
        menu.findItem(R.id.main_menu_open_form).setVisible(mode == 1);
    }

    private void createMap() {
        try {
            map = new RouteMapView(this, RouteMapView.MODE_MAIN, mapHost, MapOptions.load(this));
            mapContainer.addView(map, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            Coord last = form.last();
            if (last != null) map.setCurrentPosition(last);
            Coord start = form.typedCoord();
            if (start != null) map.setStart(start);
            map.setRunning(running);
            if (resumed) map.onResume();
        } catch (Throwable t) {
            mapFailed();
        }
    }

    private void destroyMap() {
        if (map == null) return;
        RouteMapView m = map;
        map = null;
        try {
            m.destroy();
        } finally {
            mapContainer.removeView(m);
        }
    }

    /** "Map failed to load" + volta ao formulário (fora do callback do próprio mapa). */
    private void mapFailed() {
        ui.post(() -> {
            if (isDestroyed()) return;
            Ui.toast(this, R.string.main_map_failed);
            setMode(0);
        });
    }

    private final RouteMapView.Host mapHost = new RouteMapView.Host() {
        @Override public void onTeleportClick(Coord target) {
            form.fillLocation(target, false);
            startFlow();
        }

        @Override public void onStartRouteClick(double[] pts) {
            if (running) {
                stop();
                return;
            }
            cancel(upsertTask);
            upsertTask = Io.run(() -> RouteDao.upsertLastRoute(MainActivity.this, pts), r -> {
                Coord l = form.last();
                MainForm.Req req = l != null ? new MainForm.Req(l.lat, l.lng, l.alt)
                        : new MainForm.Req(pts[0], pts[1], pts[2]);
                req.routeId = r.id;
                startWith(req);
            });
        }

        @Override public void onStopClick() { stop(); }

        @Override public void onRecordClick(Coord start) {
            form.select(MainForm.SRC_RECORD);
            form.fillLocation(start, false);
            startFlow();
        }

        @Override public void onOptionsChanged(MapOptions o) {
            SharedPreferences.Editor e = Prefs.edit(MainActivity.this);
            o.save(e);
            e.apply();
            form.syncRouteRadios();
        }

        @Override public void onMapFailed() { mapFailed(); }
    };

    /** Botão Start (form ou mapa): Stop se rodando; senão valida o rádio e passa pelas checagens. */
    void startFlow() {
        if (running || Intents.isOverlayRunning(this)) {
            stop();
            return;
        }
        MainForm.Req r = form.collect();
        if (r != null) startWith(r);
    }

    private void startWith(MainForm.Req r) {
        pendingReq = r;
        starting = true;
        startChecks.run();
    }

    private void onChecksPassed() {
        MainForm.Req r = pendingReq;
        pendingReq = null;
        starting = false;
        if (r == null || isFinishing()) return;
        Intent i = Intents.overlay(this, null)
                .putExtra(Intents.EXTRA_START_LAT, r.lat)
                .putExtra(Intents.EXTRA_START_LNG, r.lng)
                .putExtra(Intents.EXTRA_START_ALT, r.alt)
                .putExtra(Intents.EXTRA_START_HIDDEN, Prefs.getBool(this, Prefs.HIDE_JOYSTICK));
        if (r.routeId != null) i.putExtra(Intents.EXTRA_START_ROUTE_ID, r.routeId);
        if (r.record) i.putExtra(Intents.EXTRA_RECORD_ROUTE, true);
        Intents.sendToOverlay(this, i);
        setRunning(true);
        if (pendingReplay != null) {
            Intents.sendToOverlay(this, pendingReplay);
            pendingReplay = null;
        }
        autoStartApp();
    }

    private void autoStartApp() {
        String pkg = Prefs.getString(this, Prefs.SETTINGS_AUTO_START_APP_PACKAGE);
        if (pkg != null && !pkg.isEmpty() && !"None".equals(pkg)) {
            try {
                Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
                if (launch == null) throw new IllegalStateException(pkg);
                startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e) {
                Ui.toast(this, getString(R.string.main_auto_start_failed,
                        Prefs.getString(this, Prefs.SETTINGS_AUTO_START_APP_NAME)));
            }
        }
        if (Prefs.getBool(this, Prefs.SETTINGS_AUTO_CLOSE_APP)) moveTaskToBack(true);
    }

    private void stop() {
        Intents.sendToOverlay(this, Intents.overlay(this, Intents.INT_STOP_JOYSTICK));
    }

    private void setRunning(boolean r) {
        running = r;
        startBtn.setText(r ? R.string.main_stop : R.string.main_start);
        onFormChanged();
        if (map != null) map.setRunning(r);
    }

    /** SPOOF_STATE_CHANGED gravado pelo SpoofStateReceiver (já com a sessão aplicada nas prefs). */
    private void onSpoofState(boolean active) {
        setRunning(active);
        if (active) return;
        showLast(Prefs.lastPosition(this));
        form.syncRouteRadios();
        if (map != null) map.clearRoute();
        if (restartAfterStop) {   // GpsFixDialog mudou algo com o serviço rodando
            restartAfterStop = false;
            ui.postDelayed(restart, RESTART_DELAY_MS);
        }
    }

    private void showLast(Coord l) {
        if (isDestroyed()) return;
        form.setLast(l);
        if (map != null && l != null) map.setCurrentPosition(l);
    }

    /** Habilitação do Start + linha de distância imediata. Chamado pelo MainForm. */
    void onFormChanged() {
        if (startBtn == null) return;
        startBtn.setEnabled(running || form.canStart());
        if (resumed && Prefs.getBool(this, Prefs.DISPLAY_DISTANCE_INFO)) updateDistance();
    }

    /** "Distance: … - Cooldown: … - Elapsed: …", verde/vermelho, 1 s entre onResume/onPause. */
    private void updateDistance() {
        Coord cur = form.last(), t = form.target();
        if (mode != 0 || cur == null || t == null || !Cooldown.visible(this)) {
            distance.setVisibility(View.GONE);
            return;
        }
        long now = System.currentTimeMillis(), lastMocked = Prefs.getLong(this, Prefs.LAST_MOCKED_TIME);
        double d = Geo.distance(cur.lat, cur.lng, t.lat, t.lng);
        distance.setText(Cooldown.line(this, d, lastMocked, now));
        distance.setTextColor(Cooldown.color(this, d, lastMocked, now));
        distance.setVisibility(View.VISIBLE);
    }

    void requestFine(Runnable onGranted) { startChecks.requestFine(onGranted); }

    void openCooldown() { CooldownSheet.show(this, form.last(), form.target()); }

    void openGpsFix() {
        GpsFixDialog.show(this, () -> {
            if (Intents.isOverlayRunning(this)) {
                restartAfterStop = true;
                stop();
            }
        });
    }

    /** "Add to Favorites" depois de uma busca. */
    void addFavorite(String name, String address, Coord c) {
        NameFolderPanel.show(this, getString(R.string.main_add_favorites), name, Folder.FAVORITES, null, (n, folderId) -> {
            cancel(favTask);
            Context app = getApplicationContext();
            favTask = Io.run(() -> {
                FavoriteDao.insert(app, n, address, c.lat, c.lng, c.altOr0(), folderId);   // o DAO já pinga o widget
                return Boolean.TRUE;
            }, new Io.Callback<Boolean>() {
                @Override public void onResult(Boolean ok) {
                    Ui.toast(MainActivity.this, R.string.item_saved);
                    form.hideAddFavorite();
                    playHeart();
                }
                @Override public void onError(Throwable t) { Ui.toast(MainActivity.this, R.string.unknown_error); }
            });
        });
    }

    private void playHeart() {
        View heart = findViewById(R.id.main_heart);
        if (heartAnim != null) heartAnim.cancel();
        heart.setScaleX(0f);
        heart.setScaleY(0f);
        heart.setAlpha(1f);
        heart.setVisibility(View.VISIBLE);
        ObjectAnimator fade = ObjectAnimator.ofFloat(heart, View.ALPHA, 1f, 0f).setDuration(400);
        fade.setStartDelay(800);
        heartAnim = new AnimatorSet();
        heartAnim.playTogether(ObjectAnimator.ofFloat(heart, View.SCALE_X, 0f, 1f).setDuration(600),
                ObjectAnimator.ofFloat(heart, View.SCALE_Y, 0f, 1f).setDuration(600), fade);
        heartAnim.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) { heart.setVisibility(View.GONE); }
        });
        heartAnim.start();
    }

    private void onFavoriteResult(ActivityResult res) {
        Intent d = res.getData();
        if (res.getResultCode() != RESULT_OK || d == null || !d.hasExtra(Intents.EXTRA_LAT)) return;
        Coord c = new Coord(Intents.getDouble(d, Intents.EXTRA_LAT, 0), Intents.getDouble(d, Intents.EXTRA_LNG, 0),
                Intents.getDouble(d, Intents.EXTRA_ALT, 0));
        form.fillLocation(c, true);
        moveMapStart(c);
        if (Prefs.getBool(this, Prefs.SETTINGS_AUTO_START_JOYSTICK)) ui.post(this::startFlow);
    }

    private void onRouteResult(ActivityResult res) {
        Intent d = res.getData();
        String id = d == null ? null : d.getStringExtra(Intents.EXTRA_ID);
        if (res.getResultCode() != RESULT_OK || id == null) return;
        form.selectRoute(id);
        if (map != null) {   // mapa aberto: a rota escolhida aparece nele e o Iniciar do mapa a percorre
            cancel(routeTask);
            routeTask = Io.run(() -> RouteDao.get(this, id), r -> {
                if (map != null && r != null && r.pts != null) map.loadRoute(r.pts, -1);
            });
        }
        if (Prefs.getBool(this, Prefs.SETTINGS_AUTO_START_JOYSTICK)) ui.post(this::startFlow);
    }

    private void onSearchResult(ActivityResult res) {
        Intent d = res.getData();
        if (res.getResultCode() != RESULT_OK || d == null || !d.hasExtra(Intents.EXTRA_LAT)) return;
        Coord c = new Coord(Intents.getDouble(d, Intents.EXTRA_LAT, 0), Intents.getDouble(d, Intents.EXTRA_LNG, 0),
                Intents.getDouble(d, Intents.EXTRA_ALT, 0));
        form.fillLocation(c, false);
        form.showAddFavorite(d.getStringExtra(Intents.EXTRA_NAME), d.getStringExtra(Intents.EXTRA_ADDRESS), c);
        moveMapStart(c);
    }

    private void moveMapStart(Coord c) {
        if (map == null) return;
        map.clearRoute();
        map.setStart(c);
    }

    private void handleIntent(Intent i) {
        if (i == null || !Intents.trusted(this, i)) return;
        String action = i.getAction();
        if (Intents.INT_START_AND_REPLAY.equals(action)) {
            // notificação de ação pendente (2ª via do replay em segundo plano, overlay/svc/Actions)
            ((android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                    .cancel(com.pokelocation.app.overlay.svc.Actions.REPLAY_NOTIFICATION_ID);
            onReplay(i);
        } else if (Intents.INT_REQUEST_PERMISSIONS.equals(action)) {
            MainForm.Req r = form.collect();   // o serviço parou por falta de permissão: só as checagens + start
            if (r != null) startWith(r);
        } else if (Intent.ACTION_VIEW.equals(action) && i.getData() != null) {
            onViewLink(i);
        } else if (Intents.INT_FILL_LOCATION.equals(action)) {
            fillCoordinate(Intents.getDouble(i, Intents.EXTRA_LAT, Double.NaN), Intents.getDouble(i, Intents.EXTRA_LNG, Double.NaN));
        }
    }

    /** Só preenche: diferente do openCoordinate, nunca teleporta, nem com o overlay rodando. */
    private void fillCoordinate(double lat, double lng) {
        if (!Intents.validLatLng(lat, lng)) return;
        if (mode == 1) setMode(0);
        form.fillLocation(new Coord(lat, lng, 0), true);
        cancel(elevTask);
        Context app = getApplicationContext();
        elevTask = Io.run(() -> Elevation.fetch(app, lat, lng), new Io.Callback<Double>() {
            @Override public void onResult(Double alt) {
                if (alt != null && !Double.isNaN(alt)) form.setAltitudeFor(lat, lng, alt);
            }
        });
    }

    private void onViewLink(Intent i) {
        String s = i.getDataString();
        if (LinkParser.isTrackFile(s)) {   // .gpx/.kml recebido por engano
            startActivity(new Intent(Intent.ACTION_VIEW, i.getData(), this, ImportActivity.class)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
            return;
        }
        double[] ll = new double[2];
        if (!LinkParser.parse(s, ll)) {
            MessagePanel.show(this, null, getString(R.string.main_error_location), getString(R.string.ok), null, null);
            return;
        }
        openCoordinate(ll[0], ll[1]);
    }

    /** Teleporta se rodando, preenche o form, busca a altitude. */
    private void openCoordinate(double lat, double lng) {
        if (Intents.isOverlayRunning(this)) {
            Intents.sendToOverlay(this, Intents.overlay(this, Intents.ACTION_TELEPORT)
                    .putExtra(Intents.EXTRA_LAT, lat).putExtra(Intents.EXTRA_LNG, lng));
        }
        form.fillLocation(new Coord(lat, lng, 0), true);
        if (map != null) {
            map.clearRoute();
            map.setStart(new Coord(lat, lng, Double.NaN));   // o mapa já busca a elevação do início
            return;
        }
        showProgress(R.string.main_retrieving_altitude);
        cancel(elevTask);
        Context app = getApplicationContext();
        elevTask = Io.run(() -> Elevation.fetch(app, lat, lng), new Io.Callback<Double>() {
            @Override public void onResult(Double alt) {
                hideProgress();
                if (alt != null && !Double.isNaN(alt)) form.setAltitudeFor(lat, lng, alt);
            }
            @Override public void onError(Throwable t) { hideProgress(); }
        });
    }

    /** Ação pública que chegou com o serviço parado. */
    private void onReplay(Intent i) {
        Intent svc = Intents.unwrapReplay(this, i);
        if (svc == null) return;
        if (Intents.isOverlayRunning(this)) {
            Intents.sendToOverlay(this, svc);
            return;
        }
        String act = i.getStringExtra(Intents.EXTRA_REPLAY_ACTION);
        double lat = Intents.getDouble(i, Intents.EXTRA_LAT, Double.NaN), lng = Intents.getDouble(i, Intents.EXTRA_LNG, Double.NaN);
        if (Intents.ACTION_TELEPORT.equals(act)) {
            if (!Intents.validLatLng(lat, lng)) return; // só um replay antigo da notificação chega aqui inválido
            // TELEPORT frio: inicia já no ponto, sem reenviar a ação
            double alt = Intents.getDouble(i, Intents.EXTRA_ALT, Double.NaN);
            form.select(MainForm.SRC_SET);
            form.fillLocation(new Coord(lat, lng, alt), true);
            ui.post(() -> startWith(new MainForm.Req(lat, lng, alt)));
            return;
        }
        pendingReplay = svc;
        if (form.last() != null) {
            form.select(MainForm.SRC_LAST);
            ui.post(this::startFlow);
        } else {
            form.startWhenCurrentReady();
            form.select(MainForm.SRC_CURRENT);
        }
    }

    private void showProgress(int text) {
        progressText.setText(text);
        progress.setVisibility(View.VISIBLE);
    }

    private void hideProgress() {
        progress.setVisibility(View.GONE);
    }

    private void hideKeyboard() {
        View f = getCurrentFocus();
        if (f == null) return;
        f.clearFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(f.getWindowToken(), 0);
    }

    private static void cancel(Io.Task t) {
        if (t != null) t.cancel();
    }
}
