package com.pokelocation.app.ui.gpx;

import android.app.Dialog;
import android.content.Context;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.pokelocation.app.util.Ui;

/** Diálogos simples do import/export. */
final class GpxUi {
    private GpxUi() {}

    /** Progresso modal (spinner + mensagem), não cancelável. */
    static Dialog progress(Context c, int msgRes) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Ui.dp(c, 24);
        row.setPadding(pad, pad, pad, pad);
        ProgressBar bar = new ProgressBar(c);
        bar.setIndeterminate(true);
        row.addView(bar, new LinearLayout.LayoutParams(Ui.dp(c, 40), Ui.dp(c, 40)));
        TextView tv = new TextView(c);
        tv.setText(msgRes);
        tv.setPaddingRelative(pad, 0, 0, 0);
        row.addView(tv, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Dialog d = new MaterialAlertDialogBuilder(c).setView(row).setCancelable(false).create();
        d.show();
        return d;
    }

    static void dismiss(Dialog d) {
        if (d == null) return;
        try {
            d.dismiss();
        } catch (Exception ignored) {
            // janela já destacada
        }
    }

    static void message(Context c, int titleRes, int msgRes) {
        new MaterialAlertDialogBuilder(c).setTitle(titleRes).setMessage(msgRes)
                .setPositiveButton(com.pokelocation.app.R.string.ok, null).show();
    }
}
