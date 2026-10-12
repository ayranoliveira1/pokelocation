package com.pokelocation.app.ui.panel;

import android.content.Context;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.pokelocation.app.R;

/** Lista curta de opções (≤ 4) + Cancel. */
public final class SelectPanel {
    public interface Callback { void onSelect(int index); }

    private SelectPanel() {}

    public static PanelHost show(Context c, CharSequence title, CharSequence[] options, Callback cb) {
        LayoutInflater inf = LayoutInflater.from(c);
        View v = inf.inflate(R.layout.panel_select, null);
        TextView t = v.findViewById(R.id.panel_title);
        t.setText(title);
        t.setVisibility(TextUtils.isEmpty(title) ? View.GONE : View.VISIBLE);
        LinearLayout box = v.findViewById(R.id.panel_options);
        PanelHost[] host = new PanelHost[1];
        for (int i = 0; i < options.length; i++) {
            TextView row = (TextView) inf.inflate(R.layout.panel_option_row, box, false);
            row.setText(options[i]);
            final int idx = i;
            row.setOnClickListener(x -> {
                host[0].dismiss();
                if (cb != null) cb.onSelect(idx);
            });
            box.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        v.findViewById(R.id.panel_cancel).setOnClickListener(x -> host[0].dismiss());
        host[0] = PanelHost.show(c, v, false);
        return host[0];
    }
}
