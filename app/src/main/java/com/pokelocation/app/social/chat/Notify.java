package com.pokelocation.app.social.chat;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.TaskStackBuilder;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.service.notification.StatusBarNotification;

import com.pokelocation.app.R;
import com.pokelocation.app.data.Prefs;
import com.pokelocation.app.ipc.Intents;
import com.pokelocation.app.social.net.Api;
import com.pokelocation.app.social.ui.chat.ChatActivity;

import java.io.File;
import java.util.List;

public final class Notify {
    private static final String CHANNEL = "soc_messages";
    private static final String CHAT_PID = "chat_pid";
    private static final int ID = 0x50c;
    /** ponytail: por processo (principal e :overlay: no máximo 2 a cada 10 min por conversa). */
    private static final Rules.Limiter UNREADABLE = new Rules.Limiter();

    private Notify() {}

    static void fresh(Context c, List<Chat.Msg> msgs) {
        if (msgs.isEmpty() || ChatSocket.listening() || openInMain(c)) return;
        for (Chat.Msg m : msgs) {
            if (m.from == null || !Rules.notifies(m.type)) continue;
            if (m.type == Chat.UNREADABLE && !UNREADABLE.allow(m.convId, System.currentTimeMillis())) continue;
            Chat.Conv conv = Chat.conversation(c, m.convId);
            if (conv == null) continue;
            post(c, conv, m);
        }
    }

    private static void post(Context c, Chat.Conv conv, Chat.Msg m) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        String body = body(c, m);
        if (conv.group) {
            String who = label(c, m.convId, m.from);
            body = (who != null ? who : "?") + ": " + body;
        }
        String title = conv.title != null ? conv.title : c.getString(R.string.soc_notif_new);
        Notification.Builder b = builder(c, nm);
        Intent open = ChatActivity.conv(c, conv.id, title, conv.group);
        b.setSmallIcon(R.drawable.ic_notification_logo)
                .setContentTitle(title)
                .setContentText(body)
                .setWhen(m.ts)
                .setShowWhen(true)
                .setNumber(conv.unread)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setContentIntent(TaskStackBuilder.create(c).addNextIntent(Intents.main(c)).addNextIntent(open)
                        .getPendingIntent(conv.id.hashCode(), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                // o texto decifrado não aparece na tela bloqueada
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setPublicVersion(builder(c, nm).setSmallIcon(R.drawable.ic_notification_logo)
                        .setContentTitle(c.getString(R.string.soc_notif_new)).build());
        try {
            nm.notify(conv.id, ID, b.build());
        } catch (RuntimeException ignored) {
            // sem POST_NOTIFICATIONS: a mensagem já está gravada
        }
    }

    /** Guarda o pid: o principal morto com a tela aberta não cala as notificações do :overlay. */
    @SuppressWarnings("deprecation")
    static void chatVisible(Context c, boolean visible) {
        c.getSharedPreferences(Prefs.SHARE_FILE, Context.MODE_MULTI_PROCESS).edit()
                .putInt(CHAT_PID, visible ? android.os.Process.myPid() : 0).apply();
    }

    @SuppressWarnings("deprecation")
    private static boolean openInMain(Context c) {
        int pid = c.getSharedPreferences(Prefs.SHARE_FILE, Context.MODE_MULTI_PROCESS).getInt(CHAT_PID, 0);
        return pid != 0 && new File("/proc/" + pid).exists();
    }

    private static Notification.Builder builder(Context c, NotificationManager nm) {
        return (Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, channel(c, nm)) : legacy(c))
                .setColor(c.getColor(R.color.primary));
    }

    @SuppressWarnings("deprecation")
    private static Notification.Builder legacy(Context c) {
        return new Notification.Builder(c).setPriority(Notification.PRIORITY_HIGH).setDefaults(Notification.DEFAULT_ALL);
    }

    private static String channel(Context c, NotificationManager nm) {
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, c.getString(R.string.soc_notif_channel),
                    NotificationManager.IMPORTANCE_HIGH));
        }
        return CHANNEL;
    }

    private static String body(Context c, Chat.Msg m) {
        switch (m.type) {
            case Chat.TEXT: return m.text;
            case Chat.LOC: return c.getString(R.string.soc_chat_location);
            case Chat.LIVE_START: return c.getString(R.string.soc_chat_live_location);
            default: return c.getString(R.string.soc_chat_unreadable);
        }
    }

    private static String label(Context c, String convId, String userId) {
        for (Api.Friend f : Chat.members(c, convId)) if (f.userId.equals(userId)) return f.label;
        return null;
    }

    static void cancel(Context c, String convId) {
        ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(convId, ID);
    }

    /** As notificações do overlay e do mapa têm outros ids. */
    public static void cancelAll(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        for (StatusBarNotification n : nm.getActiveNotifications()) if (n.getId() == ID) nm.cancel(n.getTag(), ID);
    }
}
