package com.pokelocation.app.ui.deeplink;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import com.pokelocation.app.ipc.Intents;

/**
 * pokelocation://&lt;host&gt;?… → ação pública do OverlayService (F101). Roda em :overlay, sem UI:
 * não sobe o processo main, não toca em Prefs/Db, finish() sempre.
 */
public final class DeepLinkActivity extends Activity {
    static final String SCHEME = "pokelocation";

    /** Parâmetros de query repassados como extras com o mesmo nome. */
    private static final String[] PARAMS = {
            Intents.EXTRA_LAT, Intents.EXTRA_LNG, Intents.EXTRA_ALT, Intents.EXTRA_OFFSET, Intents.EXTRA_SPEED,
            Intents.EXTRA_MODE, Intents.EXTRA_MARKER_COUNT, Intents.EXTRA_TELEPORT,
            Intents.EXTRA_NAME, Intents.EXTRA_ID, Intents.EXTRA_WAYPOINTS};

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            Intent in = toOverlay(getIntent());
            if (in != null) Intents.sendToOverlay(this, in);
        } catch (RuntimeException ignored) {
            // link malformado (ex.: URI opaca): nada a fazer
        } finally {
            finish();
        }
    }

    private Intent toOverlay(Intent src) {
        if (src == null || !Intent.ACTION_VIEW.equals(src.getAction())) return null;
        Uri u = src.getData();
        if (u == null || !SCHEME.equalsIgnoreCase(u.getScheme())) return null;
        String action = action(u.getHost());
        if (action == null) return null;
        Intent i = Intents.overlay(this, action);
        for (String k : PARAMS) {
            Object v = parse(k, u.getQueryParameter(k));
            if (v instanceof Double) i.putExtra(k, (double) (Double) v);
            else if (v instanceof Float) i.putExtra(k, (float) (Float) v);
            else if (v instanceof Integer) i.putExtra(k, (int) (Integer) v);
            else if (v instanceof Boolean) i.putExtra(k, (boolean) (Boolean) v);
            else if (v instanceof String) i.putExtra(k, (String) v);
        }
        return i;
    }

    /** host → ação pública; null para host desconhecido (inclui "update", descartado). */
    static String action(String host) {
        if (host == null) return null;
        switch (host) {
            case "teleport": return Intents.ACTION_TELEPORT;
            case "route": return Intents.ACTION_ROUTE;
            case "walk": return Intents.ACTION_WALK;
            case "stop": return Intents.ACTION_STOP;
            case "route-pause": return Intents.ACTION_ROUTE_PAUSE;
            case "route-resume": return Intents.ACTION_ROUTE_RESUME;
            case "route-next": return Intents.ACTION_ROUTE_NEXT;
            case "route-prev": return Intents.ACTION_ROUTE_PREV;
            case "hide": return Intents.ACTION_HIDE;
            case "show": return Intents.ACTION_SHOW;
            case "speed": return Intents.ACTION_SPEED;
            case "favorites": return Intents.ACTION_FAVORITES;
            case "record-start": return Intents.ACTION_RECORD_START;
            case "record-stop": return Intents.ACTION_RECORD_STOP;
            case "record-add": return Intents.ACTION_RECORD_ADD;
            case "generate": return Intents.ACTION_GENERATE;
            default: return null;
        }
    }

    /**
     * Valor tipado do parâmetro: lat/lng/alt/offset double (finito), speed float (finito), mode/marker_count int,
     * teleport boolean ("true"/"false"), name/id/waypoints String cru. Ausente ou inválido → null (extra omitido).
     */
    static Object parse(String key, String v) {
        if (v == null) return null;
        String s = v.trim();
        try {
            switch (key) {
                case Intents.EXTRA_LAT:
                case Intents.EXTRA_LNG:
                case Intents.EXTRA_ALT:
                case Intents.EXTRA_OFFSET: {
                    double d = Double.parseDouble(s);
                    return Double.isNaN(d) || Double.isInfinite(d) ? null : d;
                }
                case Intents.EXTRA_SPEED: {
                    float f = Float.parseFloat(s);
                    return Float.isNaN(f) || Float.isInfinite(f) ? null : f;
                }
                case Intents.EXTRA_MODE:
                case Intents.EXTRA_MARKER_COUNT:
                    return Integer.parseInt(s);
                case Intents.EXTRA_TELEPORT:
                    if ("true".equalsIgnoreCase(s)) return Boolean.TRUE;
                    if ("false".equalsIgnoreCase(s)) return Boolean.FALSE;
                    return null;
                default:
                    return v;
            }
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
