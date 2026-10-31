package com.pokelocation.app.ipc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import androidx.core.content.ContextCompat;

import com.pokelocation.app.data.Prefs;

/** Main: grava spoof_active e, no Stop, a sessão vinda do :overlay; depois pinga o widget. */
public final class SpoofStateReceiver extends BroadcastReceiver {
    /** Só no processo principal (App): fora do manifest, o aviso do :overlay nunca sobe a Main. */
    public static void register(Context app) {
        ContextCompat.registerReceiver(app, new SpoofStateReceiver(), new IntentFilter(Intents.BC_SPOOF_STATE_CHANGED),
                app.getPackageName() + ".permission.INTERNAL", null, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    public void onReceive(Context c, Intent i) {
        if (!Intents.BC_SPOOF_STATE_CHANGED.equals(i.getAction())) return;
        boolean active = Intents.getBool(i, Intents.EXTRA_SPOOF_ACTIVE, false);
        Prefs.edit(c).putBoolean(Prefs.SPOOF_ACTIVE, active).apply();
        if (!active) Prefs.applySession(c, i.getExtras());
        WidgetPing.requestUpdateNow(c);
    }
}
