package com.pokelocation.app.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.util.Log;
import android.widget.RemoteViews;

import com.pokelocation.app.Io;
import com.pokelocation.app.R;
import com.pokelocation.app.data.Prefs;
import com.pokelocation.app.ipc.Intents;
import com.pokelocation.app.util.Themes;

import java.util.List;

/**
 * Widget Quick Actions: RemoteViews puros, atualização só por evento.
 * onUpdate / INT_WIDGET_REFRESH → debounce 300 ms latest-wins → resolve no Io → widget_cache_&lt;id&gt; → render.
 * O render só lê o cache (nunca o banco).
 */
public class QuickActionsWidgetProvider extends AppWidgetProvider {
    private static final String TAG = "Widget";
    private static final long DEBOUNCE_MS = 300;
    private static final String EXTRA_SHOW_ROUTES = "show_routes";

    // Paletas azuis fixas: fundo, linha, texto, texto secundário, destaque, ACTIVE
    private static final int BG = 0, LINE = 1, TEXT = 2, TEXT2 = 3, ACCENT = 4, ACTIVE = 5;
    private static final int[] LIGHT = {0xFFFFFFFF, 0xFFBBDEFB, 0xFF1A1C1E, 0xFF5F6670, 0xFF1E88E5, 0xFF2E7D32};
    private static final int[] DARK = {0xFF0D1B2A, 0xFF1E3A5F, 0xFFE2E2E6, 0xFFA7B1BE, 0xFF42A5F5, 0xFF81C784};
    static final int MODE_LIGHT = 0, MODE_DARK = 1, MODE_DUAL = 2;   // DUAL = "system" no 31+ (segue o launcher ao vivo)

    // Debounce: só tocados na main thread. Guardam o application context, nunca uma Activity.
    private static PendingResult sPending;
    private static Runnable sRun;

    @Override
    public void onReceive(Context c, Intent intent) {
        String a = intent.getAction();
        if (Intents.INT_WIDGET_REFRESH.equals(a)) {
            schedule(c);
        } else if (Intents.INT_WIDGET_TOGGLE_TAB.equals(a)) {
            int id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
            boolean routes = intent.getBooleanExtra(EXTRA_SHOW_ROUTES, false);
            if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return;
            Context app = c.getApplicationContext();
            PendingResult pr = goAsync();
            Io.run(() -> {
                try {
                    Prefs.edit(app).putBoolean(Prefs.widgetKey(Prefs.WIDGET_SHOW_ROUTES, id), routes).apply();
                    render(app, id, WidgetData.cached(app, id));
                } finally {
                    pr.finish();
                }
            });
        } else {
            super.onReceive(c, intent);
        }
    }

    @Override
    public void onUpdate(Context c, AppWidgetManager mgr, int[] ids) {
        schedule(c);
    }

    @Override
    public void onDeleted(Context c, int[] ids) {
        for (int id : ids) WidgetData.delete(c, id);
    }

    /** Latest-wins: o PendingResult substituído é finalizado na hora; o último é finalizado depois do render. */
    private void schedule(Context c) {
        Context app = c.getApplicationContext();
        PendingResult pr = goAsync();
        Handler h = Io.main();
        if (sRun != null) h.removeCallbacks(sRun);
        if (sPending != null) sPending.finish();
        sPending = pr;
        sRun = () -> {
            PendingResult p = sPending;
            sPending = null;
            sRun = null;
            Io.run(() -> {
                try {
                    refreshAll(app);
                } finally {
                    if (p != null) p.finish();
                }
            });
        };
        h.postDelayed(sRun, DEBOUNCE_MS);
    }

    /** BLOQUEANTE (Io): resolve e renderiza todos os widgets. */
    private static void refreshAll(Context c) {
        int[] ids = AppWidgetManager.getInstance(c).getAppWidgetIds(new ComponentName(c, QuickActionsWidgetProvider.class));
        for (int id : ids) {
            try {
                render(c, id, WidgetData.refresh(c, id));
            } catch (RuntimeException e) {
                Log.w(TAG, "refresh " + id, e);
            }
        }
    }

    /** Resolve um widget no Io e renderiza (configuração inicial / WidgetSettingsActivity). */
    static void refreshAsync(Context c, int id) {
        Context app = c.getApplicationContext();
        Io.run(() -> render(app, id, WidgetData.refresh(app, id)));
    }

    /** Re-render só a partir do cache (tema/agrupamento mudaram). */
    static void renderCachedAsync(Context c, int id) {
        Context app = c.getApplicationContext();
        Io.run(() -> render(app, id, WidgetData.cached(app, id)));
    }

    static int themeMode(Context c, int id) {
        String t = Prefs.getString(c, Prefs.widgetKey(Prefs.WIDGET_THEME, id), Themes.SYSTEM);
        if (Themes.LIGHT.equals(t)) return MODE_LIGHT;
        if (Themes.DARK.equals(t)) return MODE_DARK;
        if (Build.VERSION.SDK_INT >= 31) return MODE_DUAL;
        int night = Resources.getSystem().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return night == Configuration.UI_MODE_NIGHT_YES ? MODE_DARK : MODE_LIGHT;
    }

    private static void color(RemoteViews rv, int view, String method, int slot, int mode) {
        if (mode == MODE_DUAL && Build.VERSION.SDK_INT >= 31) rv.setColorInt(view, method, LIGHT[slot], DARK[slot]);
        else rv.setInt(view, method, (mode == MODE_DARK ? DARK : LIGHT)[slot]);
    }

    static void render(Context c, int id, List<WidgetData.Item> all) {
        int mode = themeMode(c, id);
        boolean grouped = WidgetData.grouped(c, id);
        boolean routes = WidgetData.showRoutes(c, id);
        boolean spoof = Prefs.getBool(c, Prefs.SPOOF_ACTIVE);
        List<WidgetData.Item> items = WidgetData.visible(c, id, all);

        RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.widget_quick_actions);
        color(rv, R.id.widget_bg, "setColorFilter", BG, mode);
        color(rv, R.id.widget_title, "setTextColor", TEXT, mode);
        rv.setTextViewText(R.id.widget_status, c.getString(spoof ? R.string.widget_active : R.string.widget_idle));
        color(rv, R.id.widget_status, "setTextColor", spoof ? ACTIVE : TEXT2, mode);
        // o launcher infla o layout no idioma do aparelho: só o texto posto aqui segue o idioma escolhido no app
        rv.setContentDescription(R.id.widget_add, c.getString(R.string.widget_add));
        color(rv, R.id.widget_add, "setColorFilter", ACCENT, mode);
        color(rv, R.id.widget_divider, "setBackgroundColor", LINE, mode);

        PendingIntent config = PendingIntent.getActivity(c, id,
                new Intent(c, WidgetConfigActivity.class).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        rv.setOnClickPendingIntent(R.id.widget_add, config);
        rv.setOnClickPendingIntent(R.id.widget_empty, config);

        rv.setTextViewText(R.id.widget_empty_text,
                c.getString(routes ? R.string.widget_empty_routes : R.string.widget_empty_locations));
        color(rv, R.id.widget_empty_text, "setTextColor", TEXT, mode);
        rv.setTextViewText(R.id.widget_empty_hint, c.getString(R.string.widget_empty_hint));
        color(rv, R.id.widget_empty_hint, "setTextColor", TEXT2, mode);

        rv.setViewVisibility(R.id.widget_tabs, grouped ? android.view.View.VISIBLE : android.view.View.GONE);
        if (grouped) {
            tab(c, rv, id, R.id.widget_tab_favorites, false, !routes, mode);
            tab(c, rv, id, R.id.widget_tab_routes, true, routes, mode);
        }

        // Clique nas linhas: template mutável (fill-in define a ação) para o OverlayService, action null
        Intent t = Intents.overlay(c, null);
        int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
        PendingIntent tpl = Build.VERSION.SDK_INT >= 26 ? PendingIntent.getForegroundService(c, id, t, fl)
                : PendingIntent.getService(c, id, t, fl);
        rv.setPendingIntentTemplate(R.id.widget_list, tpl);
        rv.setEmptyView(R.id.widget_list, R.id.widget_empty);

        AppWidgetManager mgr = AppWidgetManager.getInstance(c);
        if (Build.VERSION.SDK_INT >= 31) {
            RemoteViews.RemoteCollectionItems.Builder b = new RemoteViews.RemoteCollectionItems.Builder()
                    .setHasStableIds(true).setViewTypeCount(1);
            for (WidgetData.Item it : items) b.addItem(stableId(it), row(c, it, mode));
            rv.setRemoteAdapter(R.id.widget_list, b.build());
            mgr.updateAppWidget(id, rv);
        } else {
            Intent svc = new Intent(c, WidgetListService.class)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .setData(Uri.parse("widget://" + id));
            setAdapterLegacy(rv, svc);
            mgr.updateAppWidget(id, rv);
            mgr.notifyAppWidgetViewDataChanged(id, R.id.widget_list);
        }
    }

    @SuppressWarnings("deprecation")
    private static void setAdapterLegacy(RemoteViews rv, Intent svc) {
        rv.setRemoteAdapter(R.id.widget_list, svc);
    }

    private static void tab(Context c, RemoteViews rv, int id, int view, boolean routes, boolean selected, int mode) {
        rv.setTextViewText(view, c.getString(routes ? R.string.widget_tab_routes : R.string.widget_tab_favorites));
        rv.setInt(view, "setBackgroundResource", selected ? R.drawable.widget_tab_selected : 0);
        if (selected) rv.setTextColor(view, 0xFFFFFFFF);
        else color(rv, view, "setTextColor", TEXT2, mode);
        Intent i = new Intent(c, QuickActionsWidgetProvider.class).setAction(Intents.INT_WIDGET_TOGGLE_TAB)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).putExtra(EXTRA_SHOW_ROUTES, routes);
        rv.setOnClickPendingIntent(view, PendingIntent.getBroadcast(c, id * 2 + (routes ? 1 : 0), i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
    }

    static long stableId(WidgetData.Item it) {
        return (it.route ? 1L << 32 : 0L) | (it.id.hashCode() & 0xFFFFFFFFL);
    }

    /** Linha 48dp; o clique preenche a ação: TELEPORT (lat/lng/alt double) ou ROUTE com id. */
    static RemoteViews row(Context c, WidgetData.Item it, int mode) {
        RemoteViews r = new RemoteViews(c.getPackageName(), R.layout.widget_row);
        r.setImageViewResource(R.id.widget_row_icon, it.route ? R.drawable.widget_ic_route : R.drawable.widget_ic_favorite);
        color(r, R.id.widget_row_icon, "setColorFilter", ACCENT, mode);
        r.setTextViewText(R.id.widget_row_name, it.name);
        color(r, R.id.widget_row_name, "setTextColor", TEXT, mode);
        Intent fill = new Intent();
        if (it.route) {
            fill.setAction(Intents.ACTION_ROUTE).putExtra(Intents.EXTRA_ID, it.id);
        } else {
            fill.setAction(Intents.ACTION_TELEPORT).putExtra(Intents.EXTRA_LAT, it.lat).putExtra(Intents.EXTRA_LNG, it.lng);
            // favorito guarda alt 0 quando desconhecida: sem o extra o serviço busca a elevação
            if (it.alt != 0) fill.putExtra(Intents.EXTRA_ALT, it.alt);
        }
        r.setOnClickFillInIntent(R.id.widget_row, fill);
        return r;
    }
}
