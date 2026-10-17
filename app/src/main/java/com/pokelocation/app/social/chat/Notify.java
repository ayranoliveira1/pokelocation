package com.pokelocation.app.social.chat;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Person;
import android.app.TaskStackBuilder;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.service.notification.StatusBarNotification;
import android.util.TypedValue;

import com.pokelocation.app.R;
import com.pokelocation.app.data.Prefs;
import com.pokelocation.app.ipc.Intents;
import com.pokelocation.app.social.net.Api;
import com.pokelocation.app.social.ui.chat.ChatActivity;
import com.pokelocation.app.social.ui.chat.ConvRow;
import com.pokelocation.app.util.Shortcuts;
import com.pokelocation.app.util.Ui;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Notify {
    private static final String CHANNEL = "soc_messages";
    private static final String CHAT_PID = "chat_pid";
    private static final int ID = 0x50c;
    /** ponytail: por processo (principal e :overlay: no máximo 2 a cada 10 min por conversa). */
    private static final Rules.Limiter UNREADABLE = new Rules.Limiter();
    private static final String SHORTCUT = "chat:";
    /** Por inicial e cor (claro/escuro): o círculo da lista de conversas, desenhado uma vez. */
    private static final Map<String, Icon> AVATARS = new LinkedHashMap<String, Icon>(8, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Icon> e) { return size() > 3; }
    };

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
        String body = body(c, m), who = conv.group ? label(c, m.convId, m.from) : null, text = body;
        if (conv.group) text = sender(true, null, who) + ": " + body;
        String title = conv.title != null ? conv.title : c.getString(R.string.soc_notif_new);
        Notification.Builder b = builder(c, nm);
        Intent open = ChatActivity.conv(c, conv.id, title, conv.group);
        b.setSmallIcon(R.drawable.ic_notification_logo)
                .setContentTitle(title)
                .setContentText(text)
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
        boolean conversation = conversation(c, b, conv, title, sender(conv.group, title, who), body, m.ts, open);
        try {
            nm.notify(conv.id, ID, b.build());
        } catch (RuntimeException ignored) {
            // sem POST_NOTIFICATIONS: a mensagem já está gravada
        } finally {
            if (conversation) Shortcuts.release(c, shortcutId(conv.id));
        }
    }

    /** 30+: conversa, com o círculo da inicial no lugar do ícone pequeno; antes, o círculo vai de largeIcon. */
    private static boolean conversation(Context c, Notification.Builder b, Chat.Conv conv, String title, String who,
                                        String body, long ts, Intent open) {
        Icon face = avatar(c, title);
        b.setLargeIcon(face);
        if (Build.VERSION.SDK_INT < 30 || !Shortcuts.publish(c, shortcutId(conv.id), title, null, Intents.main(c), open)) {
            return false;
        }
        Person from = new Person.Builder().setKey(who).setName(who).setIcon(conv.group ? avatar(c, who) : face).build();
        b.setShortcutId(shortcutId(conv.id)).setStyle(new Notification.MessagingStyle(
                new Person.Builder().setName(c.getString(R.string.app_name)).build())
                .setConversationTitle(conv.group ? title : null).setGroupConversation(conv.group)
                .addMessage(new Notification.MessagingStyle.Message(body, ts, from)));
        return true;
    }

    static String sender(boolean group, String title, String member) {
        if (!group) return title;
        return member != null ? member : "?";
    }

    static String shortcutId(String convId) { return SHORTCUT + convId; }

    private static Icon avatar(Context c, String name) {
        String initial = ConvRow.initial(name);
        int bg = c.getColor(R.color.primary_container), fg = c.getColor(R.color.on_primary_container);
        String key = initial + bg;
        synchronized (AVATARS) {
            Icon icon = AVATARS.get(key);
            if (icon != null) return icon;
            int px = Ui.dp(c, 48);
            Bitmap bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(bmp);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(bg);
            cv.drawCircle(px / 2f, px / 2f, px / 2f, p);
            p.setColor(fg);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 22, c.getResources().getDisplayMetrics()));
            cv.drawText(initial, px / 2f, px / 2f - (p.ascent() + p.descent()) / 2, p);
            icon = Icon.createWithBitmap(bmp);
            AVATARS.put(key, icon);
            return icon;
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
        Shortcuts.prune(c, SHORTCUT, Collections.emptySet()); // logout: os nomes dos amigos não ficam nos atalhos
    }
}
