package com.pokelocation.app.pogo;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.pokelocation.app.R;
import com.pokelocation.app.ui.panel.PanelHost;
import com.pokelocation.app.util.Ui;

import java.util.function.IntConsumer;

final class PogoCandySheet {
    static final int[] KMS = {1, 2, 5, 10, 20};
    static final int DEFAULT_KM = 5;

    private PogoCandySheet() {}

    static PanelHost show(Context c, int km, IntConsumer onPick) {
        int dp16 = Ui.dp(c, 16), dp48 = Ui.dp(c, 48);
        PanelHost[] host = new PanelHost[1];
        FrameLayout root = new FrameLayout(c);
        View veil = new View(c);
        veil.setBackgroundColor(0x66000000);
        veil.setOnClickListener(v -> host[0].dismiss());
        root.addView(veil);

        LinearLayout sheet = new LinearLayout(c);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setClickable(true);
        sheet.setPadding(0, 0, 0, dp16);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(c.getColor(R.color.surface));
        bg.setCornerRadii(new float[]{dp16, dp16, dp16, dp16, 0, 0, 0, 0});
        sheet.setBackground(bg);
        View handle = new View(c);
        handle.setBackground(PogoChips.round(c, 2, c.getColor(R.color.pogo_outline), 0));
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(Ui.dp(c, 32), Ui.dp(c, 4));
        hl.gravity = Gravity.CENTER_HORIZONTAL;
        hl.topMargin = Ui.dp(c, 8);
        sheet.addView(handle, hl);

        LinearLayout head = new LinearLayout(c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp16, 0, 0, 0);
        TextView title = new TextView(c);
        title.setText(R.string.pogo_candy_distance);
        title.setTextSize(20);
        title.setTypeface(PogoChips.MEDIUM);
        title.setTextColor(c.getColor(R.color.on_surface));
        title.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(title, new LinearLayout.LayoutParams(0, dp48, 1));
        TextView x = new TextView(c);
        x.setText("✕");
        x.setTextSize(16);
        x.setTextColor(c.getColor(R.color.on_surface));
        x.setGravity(Gravity.CENTER);
        x.setContentDescription(c.getString(R.string.close));
        x.setOnClickListener(v -> host[0].dismiss());
        head.addView(x, new LinearLayout.LayoutParams(dp48, dp48));
        sheet.addView(head);

        String[] labels = new String[KMS.length];
        int selected = -1;
        for (int i = 0; i < KMS.length; i++) {
            labels[i] = c.getString(R.string.pogo_panel_km, KMS[i]);
            if (KMS[i] == km) selected = i;
        }
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
        sl.setMargins(dp16, Ui.dp(c, 8), dp16, 0);
        sheet.addView(new PogoChips.Segmented(c, labels, selected, i -> {
            onPick.accept(KMS[i]);
            host[0].dismiss();
        }), sl);

        root.addView(sheet, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        host[0] = PanelHost.sheet(c, root, () -> host[0].dismiss());
        return host[0];
    }
}
