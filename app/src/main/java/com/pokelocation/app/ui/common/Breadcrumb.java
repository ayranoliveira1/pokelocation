package com.pokelocation.app.ui.common;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.pokelocation.app.R;
import com.pokelocation.app.model.Folder;
import com.pokelocation.app.util.Ui;

import java.util.List;

/** Trilha "Raiz › A › B" (só widgets do framework). GONE na raiz. Toque num nível → onFolder (null = raiz). */
public final class Breadcrumb {
    public interface Callback { void onFolder(String folderIdOrNull); }

    private Breadcrumb() {}

    public static void bind(LinearLayout row, List<Folder> ancestors, CharSequence rootLabel, Callback cb) {
        row.removeAllViews();
        if (ancestors == null || ancestors.isEmpty()) {
            row.setVisibility(View.GONE);
            return;
        }
        row.setVisibility(View.VISIBLE);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        Context c = row.getContext();
        row.addView(crumb(c, rootLabel, false, v -> cb.onFolder(null)));
        for (int i = 0; i < ancestors.size(); i++) {
            Folder f = ancestors.get(i);
            ImageView sep = new ImageView(c);
            sep.setImageResource(R.drawable.ic_chevron_right);
            sep.setColorFilter(c.getColor(R.color.on_surface_variant));
            sep.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            int s = Ui.dp(c, 20);
            row.addView(sep, new LinearLayout.LayoutParams(s, s));
            boolean last = i == ancestors.size() - 1;
            row.addView(crumb(c, f.name, last, last ? null : v -> cb.onFolder(f.id)));
        }
    }

    private static TextView crumb(Context c, CharSequence text, boolean current, View.OnClickListener click) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setSingleLine(true);
        int p = Ui.dp(c, 8);
        t.setPadding(p, p, p, p);
        t.setTextColor(c.getColor(current ? R.color.on_surface : R.color.accent));
        if (current) t.setTypeface(Typeface.DEFAULT_BOLD);
        if (click != null) {
            t.setOnClickListener(click);
            t.setBackgroundResource(android.R.drawable.list_selector_background);
        }
        return t;
    }
}
