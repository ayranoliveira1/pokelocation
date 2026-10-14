package com.pokelocation.app.social.chat;

import android.content.Context;
import android.util.Log;

import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import com.pokelocation.app.App;
import com.pokelocation.app.BuildConfig;
import com.pokelocation.app.Io;
import com.pokelocation.app.social.Session;
import com.pokelocation.app.social.net.Api;

import java.io.IOException;

/** Sem FirebaseInitProvider e com auto-init desligado no manifest: nada do Firebase sobe sem login. */
public final class Push {
    private Push() {}

    /** Um push pode acordar o processo frio antes de qualquer tela. */
    static synchronized void app(Context c) {
        Context app = c.getApplicationContext();
        if (!FirebaseApp.getApps(app).isEmpty()) return;
        FirebaseApp.initializeApp(app, new FirebaseOptions.Builder()
                .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                .setApiKey(BuildConfig.FIREBASE_API_KEY)
                .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
                .build());
    }

    /** Com o auto-init desligado, só isto recupera um token que falhou ou que o servidor apagou. */
    public static void start(Context c) {
        if (App.process() != App.PROC_MAIN) return;
        Context app = c.getApplicationContext();
        app(app);
        FirebaseMessaging.getInstance().getToken()
                .addOnSuccessListener(t -> upload(app, t))
                .addOnFailureListener(e -> Log.w("Push", "getToken: " + e.getClass().getSimpleName()));
    }

    public static void stop(Context c) {
        if (App.process() != App.PROC_MAIN) return;
        app(c);
        FirebaseMessaging.getInstance().deleteToken();
    }

    static void upload(Context app, String token) {
        Io.run(() -> {
            try {
                if (Session.username(app) == null || token.equals(Session.fcmSent(app))) return; // sem @nome: 403
                Api.putFcmToken(app, token);
                Session.fcmSent(app, token);
            } catch (Api.Error e) {
                // 404 = ainda sem aparelho no servidor (antes do PUT /v1/keys): não marca, o Keys chama de novo
                Log.w("Push", "fcm token: " + e.code);
            } catch (IOException e) {
                Log.w("Push", "fcm token: " + e.getClass().getSimpleName());
            }
        });
    }
}
