package com.pokelocation.app.social.chat;

import android.util.Log;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

/** Ping sem conteúdo do servidor ({"t":"m"}): só avisa que há fila. */
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
        Log.i("PushService", "ping");
    }
}
