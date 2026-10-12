package com.pokelocation.app.ui.base;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import android.widget.ViewAnimator;

import com.pokelocation.app.R;

/** Alterna Loading/Content/Empty/Error. O 1º (e único) filho do XML é o conteúdo. Começa em LOADING. */
public final class StateView extends ViewAnimator {
    public static final int LOADING = 0, CONTENT = 1, EMPTY = 2, ERROR = 3;

    private TextView emptyText, errorText;
    private View retry;
    private Runnable onRetry;

    public StateView(Context c) {
        super(c);
    }

    public StateView(Context c, AttributeSet a) {
        super(c, a);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        LayoutInflater inf = LayoutInflater.from(getContext());
        addView(inf.inflate(R.layout.state_loading, this, false), 0);
        View empty = inf.inflate(R.layout.state_empty, this, false);
        addView(empty, EMPTY);
        View error = inf.inflate(R.layout.state_error, this, false);
        addView(error, ERROR);
        emptyText = empty.findViewById(R.id.state_empty_text);
        errorText = error.findViewById(R.id.state_error_text);
        retry = error.findViewById(R.id.state_retry);
        retry.setOnClickListener(v -> { if (onRetry != null) onRetry.run(); });
        retry.setVisibility(GONE);
        setDisplayedChild(LOADING);
    }

    public void setState(int state) {
        if (getDisplayedChild() != state) setDisplayedChild(state);
    }

    public void setEmptyText(CharSequence text) { emptyText.setText(text); }

    public void setErrorText(CharSequence text) { errorText.setText(text); }

    /** Sem retry (null) o botão fica oculto. */
    public void setOnRetry(Runnable r) {
        onRetry = r;
        retry.setVisibility(r != null ? VISIBLE : GONE);
    }
}
