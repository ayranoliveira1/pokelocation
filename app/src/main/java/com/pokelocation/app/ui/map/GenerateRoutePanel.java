package com.pokelocation.app.ui.map;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;

import com.pokelocation.app.R;
import com.pokelocation.app.engine.MockEngine;
import com.pokelocation.app.model.MapOptions;
import com.pokelocation.app.ui.panel.PanelHost;

/** Generate Route: Random/Circle, contagem 1..5000, offset > 0. Valores iniciais e salvos em MapOptions. */
final class GenerateRoutePanel {
    static final int MAX_COUNT = 5000;

    interface Callback { void onGenerate(int mode, int count, double offsetM); }

    private GenerateRoutePanel() {}

    static PanelHost show(Context c, MapOptions o, Callback cb) {
        View v = LayoutInflater.from(c).inflate(R.layout.map_generate_panel, null);
        RadioGroup mode = v.findViewById(R.id.map_gen_mode);
        EditText count = v.findViewById(R.id.map_gen_count);
        EditText offset = v.findViewById(R.id.map_gen_offset);
        TextView error = v.findViewById(R.id.map_gen_error);
        mode.check(o.generateMode == MockEngine.GEN_CIRCLE ? R.id.map_gen_circle : R.id.map_gen_random);
        count.setText(String.valueOf(o.generateCount));
        offset.setText(trim(o.generateOffset));
        PanelHost host = PanelHost.show(c, v, true);
        v.findViewById(R.id.map_gen_cancel).setOnClickListener(x -> host.dismiss());
        v.findViewById(R.id.map_gen_ok).setOnClickListener(x -> {
            long n = parseCount(count.getText().toString().trim());
            double off = parseDouble(offset.getText().toString().trim());
            String msg = null;
            if (n < 1) msg = c.getString(R.string.map_err_count);
            else if (n > MAX_COUNT) msg = c.getString(R.string.map_err_count_max, MAX_COUNT);
            else if (!(off > 0) || Double.isInfinite(off)) msg = c.getString(R.string.map_err_offset);
            if (msg != null) {
                error.setText(msg);
                error.setVisibility(View.VISIBLE);
                return;
            }
            host.dismiss();
            cb.onGenerate(mode.getCheckedRadioButtonId() == R.id.map_gen_circle ? MockEngine.GEN_CIRCLE : MockEngine.GEN_RANDOM, (int) n, off);
        });
        return host;
    }

    /** Inteiro; inválido → -1; só dígitos mas enorme → MAX_VALUE (cai na mensagem de máximo). */
    private static long parseCount(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return !s.isEmpty() && s.chars().allMatch(Character::isDigit) ? Long.MAX_VALUE : -1;
        }
    }

    private static double parseDouble(String s) {
        try {
            return Double.parseDouble(s.replace(',', '.'));
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static String trim(double d) {
        return d == Math.rint(d) && !Double.isInfinite(d) ? String.valueOf((long) d) : String.valueOf(d);
    }
}
