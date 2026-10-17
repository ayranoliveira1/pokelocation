package com.pokelocation.app.pogo;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Person;
import android.app.TaskStackBuilder;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.RemoteViews;

import com.pokelocation.app.R;
import com.pokelocation.app.geo.CoordParser;
import com.pokelocation.app.ipc.Intents;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Push de regra de alerta (FCM {"t":"p", n, iv, cp, d, r, rc, e, s; opcionais la, lo, l, v, lv}): dados públicos do
 * PoGo, não E2E. Tocar abre a aba Notificações da PogoAlertsActivity com o card desse Pokémon (o FCM vai num Bundle).
 */
public final class PogoAlert {
    public static final String EXTRA = "pogo_alert";
    static final String CHANNEL = "pogo_alerts";
    /** createNotificationChannel é IPC: 1× por processo. */
    private static boolean sChannel, sShortcut;
    private static final String SHORTCUT = "pogo_alerts";
    private static final int ID = 0x9090;
    /** Grupo próprio: com 5+ avisos o Android agrupa sozinho e as linhas do grupo recolhido não abrem. */
    private static final String GROUP = "pogo_alerts", SUMMARY = "pogo_summary";
    /** Só o processo principal (o do PushService e da tela) lê e grava. */
    private static final String FILE = "pogo_alerts", SEEN = "seen", LOG = "log";
    static final int MAX_LOG = 50, MAX_SEEN = 1000;
    private static final long KEEP = 60 * 60_000L, KEEP_UNKNOWN = 2 * KEEP;
    /** A aba Notificações à vista: o aviso entra no topo dela na hora (chamado na thread do FCM). */
    static volatile Consumer<Pogo.Mon> sLive;

    private PogoAlert() {}

    public static void show(Context c, Map<String, String> d) {
        if (!remember(c, d)) return;
        long despawn;
        try {
            despawn = Long.parseLong(d.get("d"));
        } catch (NumberFormatException e) {
            despawn = 0;
        }
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26 && !sChannel) {
            sChannel = true;
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, c.getString(R.string.pogo_alerts_channel),
                    NotificationManager.IMPORTANCE_HIGH));
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CHANNEL) : legacy(c);
        String tag = d.get("e") != null ? d.get("e") : d.get("rc"); // um aviso por spawn, sem sobrescrever outro
        Bundle data = new Bundle();
        for (Map.Entry<String, String> x : d.entrySet()) data.putString(x.getKey(), x.getValue());
        Pogo.Mon mon = mon(d);
        Consumer<Pogo.Mon> live = sLive;
        if (live != null) live.accept(mon);
        Bitmap photo = mon.id > 0 ? PogoIcons.now(c, mon, 3_000) : null; // sem foto em 3 s: sai sem, nunca atrasada
        String title = title(d.get("n"), d.get("iv"), d.get("cp"), despawn, System.currentTimeMillis(),
                "1".equals(d.get("s")));
        String where = where(d);
        b.setGroup(GROUP).setSmallIcon(R.drawable.ic_notification_logo).setColor(c.getColor(R.color.primary))
                .setContentTitle(title)
                .setContentText(where)
                .setAutoCancel(true)
                .setContentIntent(TaskStackBuilder.create(c).addNextIntent(Intents.main(c))
                        .addNextIntent(PogoAlertsActivity.intent(c).putExtra(EXTRA, data))
                        .getPendingIntent(String.valueOf(tag).hashCode(), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        if (!conversation(c, b, photo, title, where)) b.setCustomContentView(content(c, photo, title, where, false))
                .setCustomBigContentView(content(c, photo, title, where, true))
                .setStyle(new Notification.DecoratedCustomViewStyle());
        long left = despawn - System.currentTimeMillis();
        if (Build.VERSION.SDK_INT >= 26 && left > 0) b.setTimeoutAfter(left); // some quando o spawn acaba
        try {
            nm.notify(tag, ID, b.build());
            nm.notify(SUMMARY, ID, summary(c));
        } catch (RuntimeException ignored) {
            // sem POST_NOTIFICATIONS
        }
    }

    /**
     * 30+: conversa, para a foto ocupar o círculo grande no lugar do ícone pequeno. O SystemUI troca o título da conversa
     * pelo rótulo do atalho, então o aviso vai como conversa em grupo com o Pokémon de remetente (nome = título); o atalho
     * vai sem ícone para o círculo ser o largeIcon de cada aviso. false = fica o layout próprio.
     */
    private static boolean conversation(Context c, Notification.Builder b, Bitmap photo, String title, String where) {
        if (Build.VERSION.SDK_INT < 30) return false;
        String label = c.getString(R.string.pogo_alerts_channel);
        if (!sShortcut) {
            try {
                ShortcutInfo.Builder sb = new ShortcutInfo.Builder(c, SHORTCUT).setShortLabel(label).setLongLived(true)
                        .setCategories(Collections.singleton(ShortcutInfo.SHORTCUT_CATEGORY_CONVERSATION))
                        .setIntents(new Intent[]{Intents.main(c).setAction(Intent.ACTION_VIEW),
                                PogoAlertsActivity.intent(c).setAction(Intent.ACTION_VIEW)});
                if (Build.VERSION.SDK_INT >= 33) sb.setExcludedFromSurfaces(ShortcutInfo.SURFACE_LAUNCHER);
                c.getSystemService(ShortcutManager.class).pushDynamicShortcut(sb.build());
                sShortcut = true;
            } catch (RuntimeException e) {
                return false;
            }
        }
        Icon face = photo != null ? Icon.createWithBitmap(photo)
                : Icon.createWithResource(c, R.drawable.pogo_ic_pokemon).setTint(c.getColor(R.color.primary));
        Person mon = new Person.Builder().setName(title).setIcon(face).build();
        b.setShortcutId(SHORTCUT).setLargeIcon(face).setStyle(new Notification.MessagingStyle(
                new Person.Builder().setName(label).build()).setConversationTitle(label).setGroupConversation(true)
                .addMessage(new Notification.MessagingStyle.Message(where != null ? where : "", System.currentTimeMillis(), mon)));
        return true;
    }

    private static RemoteViews content(Context c, Bitmap photo, String title, String text, boolean big) {
        RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.pogo_alert_notif);
        if (photo != null) {
            rv.setImageViewBitmap(R.id.pogo_notif_photo, photo);
            rv.setViewVisibility(R.id.pogo_notif_photo, View.VISIBLE);
            rv.setViewVisibility(R.id.pogo_notif_placeholder, View.GONE);
        }
        rv.setTextViewText(R.id.pogo_notif_title, title);
        rv.setTextViewText(R.id.pogo_notif_text, text);
        if (text == null) rv.setViewVisibility(R.id.pogo_notif_text, View.GONE);
        if (big) rv.setInt(R.id.pogo_notif_text, "setMaxLines", 4);
        return rv;
    }

    /** Resumo do grupo: o toque abre as Notificações; não toca sozinho (quem avisa é cada alerta). */
    @SuppressWarnings("deprecation")
    private static Notification summary(Context c) {
        // < 26 sem GROUP_ALERT_CHILDREN: sem os defaults do legacy, senão cada alerta soaria 2×
        Notification.Builder s = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CHANNEL) : new Notification.Builder(c);
        if (Build.VERSION.SDK_INT >= 26) s.setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN);
        return s.setOnlyAlertOnce(true).setGroup(GROUP).setGroupSummary(true).setSmallIcon(R.drawable.ic_notification_logo)
                .setColor(c.getColor(R.color.primary)).setContentTitle(c.getString(R.string.pogo_alerts_channel)).setAutoCancel(true)
                .setContentIntent(TaskStackBuilder.create(c).addNextIntent(Intents.main(c))
                        .addNextIntent(PogoAlertsActivity.intent(c))
                        .getPendingIntent(SUMMARY.hashCode(), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                .build();
    }

    /** Texto da notificação do sistema: o lugar do Pokémon ou a coordenada; r é o nome da regra e fica de fora. */
    static String where(Map<String, String> d) {
        String l = blank(d.get("l"));
        if (l != null) return l;
        double la = num(d.get("la"), Double.NaN), lo = num(d.get("lo"), Double.NaN);
        return Double.isNaN(la) || Double.isNaN(lo) ? null : CoordParser.label(la, lo);
    }

    /** O push (ou o item salvo) como Pokémon do card e da lista; sem id não há foto. */
    static Pogo.Mon mon(Map<String, String> d) {
        Pogo.Mon m = new Pogo.Mon();
        m.id = (int) num(d.get("id"), 0);
        m.name = d.get("n");
        m.form = blank(d.get("f"));
        m.costume = blank(d.get("c"));
        m.shiny = "1".equals(d.get("s"));
        m.reveal = d.get("rc");
        m.iv = num(d.get("iv"), -1);
        m.cp = (int) num(d.get("cp"), 0);
        m.level = num(d.get("lv"), 0);
        m.despawnMs = (long) num(d.get("d"), 0);
        m.verified = !"0".equals(d.get("v"));   // servidor antigo não manda: conta como verificado
        m.lat = num(d.get("la"), Double.NaN);
        m.lng = num(d.get("lo"), Double.NaN);
        if (Double.isNaN(m.lng)) m.lat = Double.NaN;
        m.location = blank(d.get("l"));
        m.historyId = blank(d.get("h"));
        if (m.location == null && Double.isNaN(m.lat)) m.location = blank(d.get("r"));
        return m;
    }

    private static double num(String s, double def) {
        try {
            return s == null || s.isEmpty() ? def : Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** As notificações já mostradas, da mais nova para a mais velha (as expiradas ficam até o teto). */
    static List<Pogo.Mon> load(Context c) {
        return mons(array(prefs(c).getString(LOG, null)));
    }

    /** Logout e Limpar histórico: a lista é da conta; o seen fica (ids públicos que vencem sozinhos) para nunca repetir um aviso. */
    public static synchronized void forget(Context c) {
        prefs(c).edit().remove(LOG).apply();
    }

    /** A primeira página do servidor vira a lista local: é o que a aba mostra sem rede. */
    static synchronized void cache(Context c, JSONArray log) {
        prefs(c).edit().putString(LOG, log.toString()).apply();
    }

    static synchronized void drop(Context c, String h) {
        SharedPreferences sp = prefs(c);
        JSONArray log = array(sp.getString(LOG, null));
        drop(log, h);
        sp.edit().putString(LOG, log.toString()).apply();
    }

    static void drop(JSONArray log, String h) {
        for (int i = log.length() - 1; i >= 0; i--) {
            JSONObject x = log.optJSONObject(i);
            if (x != null && h.equals(x.optString("h"))) log.remove(i);
        }
    }

    /** false = este spawn já foi avisado: nem notifica nem entra de novo na lista. Grava antes de notificar. */
    private static synchronized boolean remember(Context c, Map<String, String> d) {
        SharedPreferences sp = prefs(c);
        JSONObject seen;
        try {
            seen = new JSONObject(sp.getString(SEEN, "{}"));
        } catch (JSONException e) {
            seen = new JSONObject();
        }
        JSONArray log = array(sp.getString(LOG, null));
        boolean fresh = add(seen, log, d, System.currentTimeMillis());
        sp.edit().putString(SEEN, seen.toString()).putString(LOG, log.toString()).commit();
        return fresh;
    }

    /**
     * Dedup pelo encounter (rc no servidor antigo): um id visto vale até o despawn + 1 h (2 h sem despawn) e um
     * despawn corrigido para depois estende. log (mais velho primeiro) só mostra: sair dele pelo teto não reabre o aviso.
     */
    static boolean add(JSONObject seen, JSONArray log, Map<String, String> d, long now) {
        String field = d.get("e") != null ? "e" : "rc", key = d.get(field);
        if (key == null) return true;
        long despawn = (long) num(d.get("d"), 0), until = despawn > 0 ? despawn + KEEP : now + KEEP_UNKNOWN;
        List<String> dead = new ArrayList<>();
        for (Iterator<String> it = seen.keys(); it.hasNext(); ) {
            String k = it.next();
            if (seen.optLong(k) <= now) dead.add(k);
        }
        for (String k : dead) seen.remove(k);
        try {
            long had = seen.optLong(key);
            if (had > now) {
                if (until > had) {
                    seen.put(key, until);
                    if (despawn > 0) for (int i = log.length() - 1; i >= 0; i--) {
                        JSONObject x = log.optJSONObject(i);
                        if (x != null && key.equals(x.optString(field))) x.put("d", String.valueOf(despawn));
                    }
                }
                return false;
            }
            if (seen.length() >= MAX_SEEN) seen.remove(first(seen));
            seen.put(key, until);
            JSONObject x = new JSONObject();
            for (Map.Entry<String, String> f : d.entrySet()) x.put(f.getKey(), f.getValue());
            log.put(x);
        } catch (JSONException ignored) {
            // put com chave/valor não nulos não lança
        }
        while (log.length() > MAX_LOG) log.remove(0);
        return true;
    }

    /** O que morre primeiro. */
    private static String first(JSONObject seen) {
        String min = null;
        for (Iterator<String> it = seen.keys(); it.hasNext(); ) {
            String k = it.next();
            if (min == null || seen.optLong(k) < seen.optLong(min)) min = k;
        }
        return min;
    }

    static List<Pogo.Mon> mons(JSONArray log) {
        List<Pogo.Mon> out = new ArrayList<>(log.length());
        Map<String, String> d = new HashMap<>();
        for (int i = log.length() - 1; i >= 0; i--) {
            JSONObject x = log.optJSONObject(i);
            if (x == null) continue;
            d.clear();
            for (Iterator<String> it = x.keys(); it.hasNext(); ) {
                String k = it.next();
                d.put(k, x.optString(k));
            }
            out.add(mon(d));
        }
        return out;
    }

    private static JSONArray array(String s) {
        try {
            return s != null ? new JSONArray(s) : new JSONArray();
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(FILE, Context.MODE_PRIVATE); }

    private static String blank(String s) { return s == null || s.isEmpty() ? null : s; }

    @SuppressWarnings("deprecation")
    private static Notification.Builder legacy(Context c) {
        return new Notification.Builder(c).setPriority(Notification.PRIORITY_HIGH).setDefaults(Notification.DEFAULT_ALL);
    }

    /** "✨ Dratini 100% CP512 · 12 min" (✨ só se shiny); partes ausentes somem. */
    static String title(String name, String iv, String cp, long despawnMs, long now, boolean shiny) {
        StringBuilder t = new StringBuilder(shiny ? "✨ " : "").append(name != null ? name : "?");
        if (iv != null && !iv.isEmpty()) t.append(' ').append(iv).append('%');
        if (cp != null && !cp.isEmpty()) t.append(" CP").append(cp);
        long min = (despawnMs - now + 59_999) / 60_000;
        if (despawnMs > now) t.append(" · ").append(min).append(" min");
        return t.toString();
    }
}
