package com.pokelocation.app.social;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.pokelocation.app.Io;
import com.pokelocation.app.ipc.Intents;

import java.io.IOException;

/** Principal: o :overlay viu a sessão morrer e só o principal escreve a sessão. Explícito: acorda o processo. */
public final class SessionEndedReceiver extends BroadcastReceiver {
    private static final String ACTION = "com.pokelocation.app.internal.SESSION_ENDED", GEN = "gen";

    static void send(Context c, long gen) {
        c.sendBroadcast(Intents.auth(c, new Intent(ACTION).setClass(c, SessionEndedReceiver.class).putExtra(GEN, gen)),
                c.getPackageName() + ".permission.INTERNAL");
    }

    @Override
    public void onReceive(Context c, Intent i) {
        if (!ACTION.equals(i.getAction()) || !Intents.trusted(c, i)) return;
        Context app = c.getApplicationContext();
        long gen = i.getLongExtra(GEN, -1);
        PendingResult r = goAsync();
        Io.executor().execute(() -> {
            try {
                Session.ended(app, gen);
            } catch (IOException e) {
                Log.w("SessionEnded", e.getClass().getSimpleName());
            } finally {
                r.finish();
            }
        });
    }
}
