package com.pokelocation.app.pogo;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.pokelocation.app.ui.base.BaseActivity;

/**
 * Login do Discord numa WebView (o Discord aceita; o Google não). O redirect para pogocoordinates.com/callback é
 * cortado antes de a página do site carregar, senão ela consumiria o code. Devolve EXTRA_CODE/EXTRA_STATE com RESULT_OK.
 * Processo :pogoweb: o Chromium fica residente no processo que o carrega, então ele morre junto com a tela. Nada de
 * prefs nem Keystore aqui: a troca do code é no main.
 */
public final class PogoDiscordActivity extends BaseActivity {
    static final String EXTRA_CODE = "code", EXTRA_STATE = "state";
    private WebView web;
    private boolean done;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                WebView.setDataDirectorySuffix("pogoweb");
            } catch (IllegalStateException ignored) {
                // recreate no mesmo processo: o sufixo já vale
            }
        }
        web = new WebView(this);
        setContentView(web);
        applyInsets(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return intercept(r.getUrl());
            }

            @SuppressWarnings("deprecation")
            @Override public boolean shouldOverrideUrlLoading(WebView v, String url) {
                return intercept(Uri.parse(url));
            }

            /** Rede de segurança: navegações que não passam pelo shouldOverride (ex.: POST). */
            @Override public void onPageStarted(WebView v, String url, Bitmap icon) {
                if (intercept(Uri.parse(url))) v.stopLoading();
            }
        });
        web.loadUrl(Pogo.DISCORD_URL);
    }

    /** true = nada do pogocoordinates.com carrega aqui. */
    private boolean intercept(Uri u) {
        if (!"pogocoordinates.com".equals(u.getHost())) return false;
        if (!done) {
            done = true;
            String code = Pogo.isCallback(u, "discord") && u.getQueryParameter("error") == null
                    ? u.getQueryParameter("code") : null;
            if (code != null)
                setResult(RESULT_OK, new Intent().putExtra(EXTRA_CODE, code).putExtra(EXTRA_STATE, u.getQueryParameter("state")));
            finish();
        }
        return true;
    }

    @Override
    protected void onDestroy() {
        // sessão do Discord não fica no app: o próximo login começa do zero
        boolean end = isFinishing();
        CookieManager.getInstance().removeAllCookies(ok -> {
            CookieManager.getInstance().flush();
            if (end) Process.killProcess(Process.myPid());
        });
        WebStorage.getInstance().deleteAllData();
        web.stopLoading();
        web.clearCache(true);
        ((ViewGroup) web.getParent()).removeView(web);
        web.destroy();
        super.onDestroy();
    }
}
