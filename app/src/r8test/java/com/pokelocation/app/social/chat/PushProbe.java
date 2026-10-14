package com.pokelocation.app.social.chat;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;

/**
 * Só no build r8test: prova o init manual sem projeto real. "am start -n <pkg>/com.pokelocation.app.social.chat.PushProbe"
 * → init + getToken, que deve falhar na rede por projeto/chave inválidos (não por classe ausente). O ping se prova pelo
 * caminho real: broadcast c2dm RECEIVE no FirebaseInstanceIdReceiver (o FirebaseMessagingService ignora start direto).
 */
public final class PushProbe extends Activity {
    private static final String TAG = "PushProbe";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            Push.app(this);
            Log.i(TAG, "init ok: " + FirebaseApp.getInstance().getOptions().getProjectId());
            FirebaseMessaging.getInstance().getToken().addOnCompleteListener(t ->
                    Log.i(TAG, "token: " + (t.isSuccessful() ? "ok" : String.valueOf(t.getException()))));
        } catch (Throwable e) {
            Log.e(TAG, "falhou", e);
        }
        finish();
    }
}
