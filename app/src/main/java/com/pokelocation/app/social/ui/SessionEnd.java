package com.pokelocation.app.social.ui;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;

import com.pokelocation.app.ipc.Intents;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

public final class SessionEnd {
    private static final Set<Activity> open = new HashSet<>();
    private static BroadcastReceiver rx;

    private SessionEnd() {}

    public static void watch(AppCompatActivity a) {
        Context app = a.getApplicationContext();
        open.add(a);
        a.getLifecycle().addObserver((LifecycleEventObserver) (o, e) -> {
            if (e != Lifecycle.Event.ON_DESTROY) return;
            open.remove(a);
            if (open.isEmpty() && rx != null) {
                app.unregisterReceiver(rx);
                rx = null;
            }
        });
        if (rx != null) return;
        rx = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                if (!i.getBooleanExtra(Intents.EXTRA_LOGGED_OUT, false)) return;
                for (Activity x : new ArrayList<>(open)) x.finish();
            }
        };
        ContextCompat.registerReceiver(app, rx, new IntentFilter(Intents.INT_CHAT_UNREAD), app.getPackageName() + ".permission.INTERNAL",
                null, ContextCompat.RECEIVER_NOT_EXPORTED);
    }
}
