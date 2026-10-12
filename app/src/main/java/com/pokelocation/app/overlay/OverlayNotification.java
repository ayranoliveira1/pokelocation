package com.pokelocation.app.overlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.widget.RemoteViews;

import com.pokelocation.app.Io;
import com.pokelocation.app.R;
import com.pokelocation.app.ipc.Intents;

/**
 * Notificação do serviço (F52, N08). Só framework: Notification.Builder + RemoteViews.
 * PendingIntents criados uma vez; RemoteViews novo a cada mudança de estado (reaproveitar acumula ações);
 * notify() só quando o estado muda, no máximo 1×/s.
 */
public final class OverlayNotification {
    public static final int ID = 1;
    public static final String CHANNEL = "pokelocation_joystick";
    private static final long THROTTLE_MS = 1000;
    private static final int RC_INJECT = 11;

    private final Context ctx;
    private final NotificationManager nm;
    private final PendingIntent piMain, piHide, piShow, piStop, piShare, piRouteStop, piPause, piResume, piNext, piMap, piSave;
    private PendingIntent piInject;

    // estado pedido
    private boolean hidden, hasRoute, playing, inject;
    private int index, total;
    // estado publicado
    private boolean sent, sHidden, sHasRoute, sPlaying, sInject;
    private int sIndex, sTotal;
    private long lastNotify;
    private boolean scheduled;
    private final Runnable flush = this::flush;

    public OverlayNotification(Context ctx) {
        this.ctx = ctx;
        nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, ctx.getString(R.string.app_name),
                    NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            ch.enableVibration(false);
            ch.enableLights(false);
            ch.setSound(null, null);
            nm.createNotificationChannel(ch);
        }
        int imm = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        piMain = PendingIntent.getActivity(ctx, 1,
                Intents.main(ctx).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), imm);
        piHide = service(0, Intents.overlay(ctx, Intents.INT_HIDE).putExtra(Intents.EXTRA_HIDE, true));
        piShow = service(9, Intents.overlay(ctx, Intents.INT_HIDE).putExtra(Intents.EXTRA_HIDE, false));
        piStop = service(2, Intents.overlay(ctx, Intents.INT_STOP_JOYSTICK));
        piShare = service(3, Intents.overlay(ctx, Intents.INT_SHARE));
        piRouteStop = route(4, Intents.ROUTE_ACTION_STOP);
        piPause = route(5, Intents.ROUTE_ACTION_PAUSE);
        piResume = route(10, Intents.ROUTE_ACTION_RESUME);
        piNext = route(6, Intents.ROUTE_ACTION_NEXT);
        piMap = service(7, Intents.overlay(ctx, Intents.INT_OPEN_MAP));
        piSave = service(8, Intents.overlay(ctx, Intents.INT_SAVE_LOCATION));
    }

    private PendingIntent route(int rc, int action) {
        return service(rc, Intents.overlay(ctx, Intents.INT_ROUTE_ACTION).putExtra(Intents.EXTRA_ROUTE_ACTION, action));
    }

    private PendingIntent service(int rc, Intent i) {
        int f = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        return Build.VERSION.SDK_INT >= 26 ? PendingIntent.getForegroundService(ctx, rc, i, f)
                : PendingIntent.getService(ctx, rc, i, f);
    }

    /** onCreate do serviço: obrigatório em até 5 s. Lança se o sistema recusar (N14: o serviço trata). */
    public void startForeground(Service s) {
        Notification n = build();
        if (Build.VERSION.SDK_INT >= 29) s.startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        else s.startForeground(ID, n);
        markSent();
    }

    public void setHidden(boolean h) { hidden = h; request(); }

    public void setRoute(boolean has, boolean play) { hasRoute = has; playing = play; request(); }

    public void setProgress(int i, int n) { index = i; total = n; request(); }

    /** Android 15+ sem janela visível (DECISIONS dúvida 4): ação "Inject" abre a MockingActivity com a posição. */
    public void setInject(Intent mocking) {
        piInject = PendingIntent.getActivity(ctx, RC_INJECT, mocking,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT); // atualiza os extras do token já publicado
        if (!inject) { inject = true; request(); }
    }

    public void clearInject() {
        if (inject) { inject = false; request(); }
    }

    public void release() {
        Io.main().removeCallbacks(flush);
        scheduled = false;
    }

    private void request() {
        if (sent && hidden == sHidden && hasRoute == sHasRoute && playing == sPlaying && inject == sInject
                && index == sIndex && total == sTotal) return;
        if (scheduled) return;
        long due = lastNotify + THROTTLE_MS;
        if (SystemClock.uptimeMillis() >= due) flush();
        else { scheduled = true; Io.main().postAtTime(flush, due); }
    }

    private void flush() {
        scheduled = false;
        try {
            nm.notify(ID, build());
        } catch (RuntimeException ignored) {
            // sem POST_NOTIFICATIONS ou falha do sistema: o FGS segue sem atualizar a notificação
        }
        markSent();
    }

    private void markSent() {
        sent = true;
        sHidden = hidden; sHasRoute = hasRoute; sPlaying = playing; sInject = inject; sIndex = index; sTotal = total;
        lastNotify = SystemClock.uptimeMillis();
    }

    private Notification build() {
        String title = hasRoute && total > 0 ? ctx.getString(R.string.ov_svc_route_progress, index, total)
                : ctx.getString(R.string.ov_svc_running);
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.ov_notif_main);
        rv.setTextViewText(R.id.ov_notif_title, title);
        rv.setViewVisibility(R.id.ov_notif_text, inject ? View.VISIBLE : View.GONE);
        rv.setOnClickPendingIntent(R.id.ov_notif_stop, piStop);
        rv.setOnClickPendingIntent(R.id.ov_notif_hide, hidden ? piShow : piHide);
        rv.setImageViewResource(R.id.ov_notif_hide, hidden ? R.drawable.ic_visibility : R.drawable.ic_visibility_off);
        rv.setContentDescription(R.id.ov_notif_hide, ctx.getString(hidden ? R.string.ov_svc_show : R.string.ov_svc_hide));
        rv.setOnClickPendingIntent(R.id.ov_notif_share, piShare);
        rv.setOnClickPendingIntent(R.id.ov_notif_map, piMap);
        rv.setOnClickPendingIntent(R.id.ov_notif_save, piSave);
        int rvis = hasRoute ? View.VISIBLE : View.GONE;
        rv.setViewVisibility(R.id.ov_notif_route_stop, rvis);
        rv.setViewVisibility(R.id.ov_notif_play, rvis);
        rv.setViewVisibility(R.id.ov_notif_next, rvis);
        if (hasRoute) {
            rv.setOnClickPendingIntent(R.id.ov_notif_route_stop, piRouteStop);
            rv.setOnClickPendingIntent(R.id.ov_notif_play, playing ? piPause : piResume);
            rv.setImageViewResource(R.id.ov_notif_play, playing ? R.drawable.ic_pause : R.drawable.ic_play);
            rv.setContentDescription(R.id.ov_notif_play, ctx.getString(playing ? R.string.ov_svc_pause : R.string.ov_svc_resume));
            rv.setOnClickPendingIntent(R.id.ov_notif_next, piNext);
        }

        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(ctx, CHANNEL)
                : new Notification.Builder(ctx);
        b.setSmallIcon(hidden ? R.drawable.ic_notification_hidden : R.drawable.ic_notification)
                .setColor(ctx.getColor(R.color.primary))
                .setContentTitle(title)
                .setContentIntent(piMain)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setCustomContentView(rv)
                .setStyle(new Notification.DecoratedCustomViewStyle());
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_LOW);
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        if (inject && piInject != null) {
            b.setContentText(ctx.getString(R.string.ov_svc_tap_inject));
            b.addAction(new Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_target),
                    ctx.getString(R.string.ov_svc_inject), piInject).build());
        }
        return b.build();
    }
}
