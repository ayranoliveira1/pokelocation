package com.pokelocation.app.pogo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import java.util.HashMap;
import java.util.Map;

/** QA: push de alerta simulado, "adb shell am broadcast -n <pkg>/com.pokelocation.app.pogo.DebugAlertReceiver --es n Pikachu ...". */
public final class DebugAlertReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        Bundle b = i.getExtras();
        Map<String, String> d = new HashMap<>();
        if (b != null) for (String k : b.keySet()) d.put(k, b.getString(k));
        Context app = c.getApplicationContext();
        PendingResult r = goAsync();
        new Thread(() -> {
            try {
                PogoAlert.show(app, d);
            } finally {
                r.finish();
            }
        }).start();
    }
}
