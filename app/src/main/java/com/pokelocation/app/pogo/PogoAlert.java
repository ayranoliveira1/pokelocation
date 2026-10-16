package com.pokelocation.app.pogo;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.TaskStackBuilder;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;

import com.pokelocation.app.R;
import com.pokelocation.app.ipc.Intents;

import java.util.Map;

/**
 * Push de regra de alerta (FCM {"t":"p", n, iv, cp, d, r, rc, e, s}): dados públicos do PoGo, não E2E. Tocar abre a
 * PogoListActivity com o card desse Pokémon: o FCM inteiro vai num Bundle em EXTRA.
 */
public final class PogoAlert {
    public static final String EXTRA = "pogo_alert";
    static final String CHANNEL = "pogo_alerts";
    /** createNotificationChannel é IPC: 1× por processo. */
    private static boolean sChannel;
    private static final int ID = 0x9090;

    private PogoAlert() {}

    public static void show(Context c, Map<String, String> d) {
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
        b.setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title(d.get("n"), d.get("iv"), d.get("cp"), despawn, System.currentTimeMillis(),
                        "1".equals(d.get("s"))))
                .setContentText(d.get("r"))
                .setAutoCancel(true)
                .setContentIntent(TaskStackBuilder.create(c).addNextIntent(Intents.main(c))
                        .addNextIntent(PogoListActivity.intent(c, Double.NaN, Double.NaN).putExtra(EXTRA, data))
                        .getPendingIntent(String.valueOf(tag).hashCode(), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        long left = despawn - System.currentTimeMillis();
        if (Build.VERSION.SDK_INT >= 26 && left > 0) b.setTimeoutAfter(left); // some quando o spawn acaba
        try {
            nm.notify(tag, ID, b.build());
        } catch (RuntimeException ignored) {
            // sem POST_NOTIFICATIONS
        }
    }

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
