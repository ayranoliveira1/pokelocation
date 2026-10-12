package com.pokelocation.app.ipc;

import android.annotation.SuppressLint;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.pokelocation.app.Io;
import com.pokelocation.app.widget.QuickActionsWidgetProvider;

/** Pede refresh do widget (só se houver widgets) ou o pin na tela inicial. */
public final class WidgetPing {
    @SuppressLint("StaticFieldLeak")   // sempre o application context
    private static Context sApp;
    private static final Runnable PING = () -> requestUpdateNow(sApp);

    private WidgetPing() {}

    /** Coalesce rajadas (ex.: import em lote) em 1 broadcast após 300 ms. Seguro de qualquer thread. */
    public static void requestUpdate(Context c) {
        sApp = c.getApplicationContext();
        Io.main().removeCallbacks(PING);
        Io.main().postDelayed(PING, 300);
    }

    /** Broadcast explícito INT_WIDGET_REFRESH ao provider, se houver widgets. */
    public static void requestUpdateNow(Context c) {
        if (c == null) return;
        try {
            ComponentName cn = new ComponentName(c, QuickActionsWidgetProvider.class);
            int[] ids = AppWidgetManager.getInstance(c).getAppWidgetIds(cn);
            if (ids != null && ids.length > 0) c.sendBroadcast(new Intent(Intents.INT_WIDGET_REFRESH).setComponent(cn));
        } catch (Exception ignored) {
        }
    }

    /** requestPinAppWidget (API 26+); false se o launcher não suporta. */
    public static boolean requestPin(Context c) {
        if (Build.VERSION.SDK_INT < 26) return false;
        try {
            AppWidgetManager awm = AppWidgetManager.getInstance(c);
            if (!awm.isRequestPinAppWidgetSupported()) return false;
            return awm.requestPinAppWidget(new ComponentName(c, QuickActionsWidgetProvider.class), null, null);
        } catch (Exception e) {
            return false;
        }
    }
}
