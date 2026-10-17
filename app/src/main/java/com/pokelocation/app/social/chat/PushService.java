package com.pokelocation.app.social.chat;

import android.content.Context;
import android.util.Log;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import com.pokelocation.app.pogo.PogoAlert;
import com.pokelocation.app.social.Session;
import com.pokelocation.app.social.crypto.SignalLoader;
import com.pokelocation.app.social.loc.Locations;
import com.pokelocation.app.social.net.Api;

import java.io.IOException;

/** Ping sem conteúdo ({"t":"m"}): só avisa que há fila. {"t":"p"} = alerta de regra PoGo (PogoAlert). */
public final class PushService extends FirebaseMessagingService {
    @Override
    public void onCreate() {
        Push.app(this);
        super.onCreate();
    }

    @Override
    public void onNewToken(String token) { Push.upload(getApplicationContext(), token); }

    @Override
    public void onMessageReceived(RemoteMessage message) {
        Context c = getApplicationContext();
        boolean in = Session.loggedIn(c); // ping de uma conta que saiu (token ainda não apagado no servidor): ignora
        if ("p".equals(message.getData().get("t"))) { // alerta de regra PoGo: dados públicos, sem fila
            if (in) PogoAlert.show(c, message.getData());
            return;
        }
        try {
            if (in) SignalLoader.loadDownloaded(c);
            Rules.drain(in, () -> Locations.drain(c));
        } catch (Api.Error e) {
            Log.w("PushService", "fila: " + e.code);
        } catch (IOException e) {
            Log.w("PushService", "fila: " + e.getClass().getSimpleName());
        }
    }
}
