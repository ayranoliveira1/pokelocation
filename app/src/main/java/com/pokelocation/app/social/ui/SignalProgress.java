package com.pokelocation.app.social.ui;

import android.content.Context;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.pokelocation.app.R;
import com.pokelocation.app.social.crypto.SignalLoader;

import java.io.IOException;
import java.util.Locale;

/**
 * Bloco "Preparando chat seguro… X / Y MB" (layout soc_signal) ligado ao SignalLoader. Fica oculto até o 1º callback
 * (com a lib instalada só chega onReady, sem piscar). Login e amigos funcionam sem a lib: isto só informa.
 */
final class SignalProgress implements SignalLoader.Listener {
    private final View root, retry;
    private final TextView text;
    private final ProgressBar bar;

    SignalProgress(View root) {
        this.root = root;
        text = root.findViewById(R.id.soc_signal_text);
        bar = root.findViewById(R.id.soc_signal_bar);
        retry = root.findViewById(R.id.soc_signal_retry);
        retry.setOnClickListener(v -> {
            show(root.getContext().getString(R.string.soc_signal_preparing), true, false);
            bar.setIndeterminate(true);
            start();
        });
    }

    /** Idempotente (o SignalLoader só troca o listener se já estiver baixando). */
    void start() {
        SignalLoader.start(root.getContext(), this);
    }

    /** onDestroy: solta a tela; o download segue. */
    void detach() {
        SignalLoader.detach(this);
    }

    static String mb(long bytes, Locale l) {
        return String.format(l, "%.1f", bytes / 1048576.0);
    }

    static int errorText(IOException e) {
        return SignalLoader.NO_SPACE.equals(e.getMessage()) ? R.string.soc_signal_no_space : R.string.soc_signal_failed;
    }

    @Override public void onProgress(long done, long total) {
        Context c = root.getContext();
        Locale l = Locale.getDefault();
        boolean known = total > 0;
        show(known ? c.getString(R.string.soc_signal_progress, mb(done, l), mb(total, l))
                : c.getString(R.string.soc_signal_progress_unknown, mb(done, l)), true, false);
        bar.setIndeterminate(!known);   // o ProgressBar ignora quando não muda
        if (known) bar.setProgress((int) (done * 1000 / total));
    }

    @Override public void onReady() {
        root.setVisibility(View.GONE);
    }

    @Override public void onError(IOException e) {
        show(root.getContext().getString(errorText(e)), false, true);
    }

    @Override public void onUnavailable() {
        show(root.getContext().getString(R.string.soc_signal_unavailable), false, false);
    }

    /** Live region só fora do progresso: o TalkBack lê erro/indisponível, não cada passo de 128 KB. */
    private void show(CharSequence msg, boolean busy, boolean canRetry) {
        text.setAccessibilityLiveRegion(busy ? View.ACCESSIBILITY_LIVE_REGION_NONE : View.ACCESSIBILITY_LIVE_REGION_POLITE);
        text.setText(msg);
        bar.setVisibility(busy ? View.VISIBLE : View.GONE);
        retry.setVisibility(canRetry ? View.VISIBLE : View.GONE);
        root.setVisibility(View.VISIBLE);
    }
}
