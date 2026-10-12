package com.pokelocation.app.ui.panel;

import android.content.Context;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.pokelocation.app.R;

public final class MessagePanel {
    private MessagePanel() {}

    public static PanelHost show(Context c, CharSequence title, CharSequence message,
                                 CharSequence positive, CharSequence negativeOrNull, Runnable onPositive) {
        View v = LayoutInflater.from(c).inflate(R.layout.panel_message, null);
        TextView t = v.findViewById(R.id.panel_title);
        t.setText(title);
        t.setVisibility(TextUtils.isEmpty(title) ? View.GONE : View.VISIBLE);
        ((TextView) v.findViewById(R.id.panel_message)).setText(message);
        Button pos = v.findViewById(R.id.panel_positive), neg = v.findViewById(R.id.panel_negative);
        pos.setText(positive);
        PanelHost[] host = new PanelHost[1];
        pos.setOnClickListener(x -> {
            host[0].dismiss();
            if (onPositive != null) onPositive.run();
        });
        if (negativeOrNull == null) {
            neg.setVisibility(View.GONE);
        } else {
            neg.setText(negativeOrNull);
            neg.setOnClickListener(x -> host[0].dismiss());
        }
        host[0] = PanelHost.show(c, v, false);
        return host[0];
    }
}
