package com.pokelocation.app.pogo;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.pokelocation.app.R;
import com.pokelocation.app.util.Ui;

/**
 * Chips do filtros-design, só framework e GradientDrawable: borda 1dp outline, raio 18dp; selecionado = fundo primary,
 * texto on_primary e "✓ " (a seleção não fica só na cor). Alvo de 48dp com a pílula de 36dp no meio.
 */
final class PogoChips {
    private PogoChips() {}

    static TextView chip(Context c, CharSequence text, boolean selected) {
        TextView t = new TextView(c);
        t.setTag(R.id.pogo_chip_text, text);
        t.setMinHeight(Ui.dp(c, 48));
        t.setGravity(Gravity.CENTER);
        t.setTextSize(14);
        int h = Ui.dp(c, 14);
        t.setPadding(h, 0, h, 0);
        select(t, selected);
        return t;
    }

    /** O texto base fica na tag: select só põe ou tira o "✓ ". isSelected() diz o estado. */
    static void select(TextView t, boolean on) {
        Context c = t.getContext();
        CharSequence base = (CharSequence) t.getTag(R.id.pogo_chip_text);
        t.setText(on ? "✓ " + base : base);
        t.setTextColor(c.getColor(on ? R.color.on_primary : R.color.on_surface));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(Ui.dp(c, 18));
        g.setStroke(Ui.dp(c, 1), c.getColor(on ? R.color.primary : R.color.outline));
        if (on) g.setColor(c.getColor(R.color.primary));
        int v = Ui.dp(c, 6);
        t.setBackground(new InsetDrawable(g, 0, v, 0, v));
        t.setSelected(on);
    }

    /** Quebra linha: 8dp entre itens na linha; entre linhas a folga já vem do alvo de 48dp. */
    static final class Flow extends ViewGroup {
        private final int gap;

        Flow(Context c) {
            super(c);
            gap = Ui.dp(c, 8);
        }

        @Override
        protected void onMeasure(int ws, int hs) {
            int max = MeasureSpec.getMode(ws) == MeasureSpec.UNSPECIFIED ? Integer.MAX_VALUE
                    : MeasureSpec.getSize(ws) - getPaddingLeft() - getPaddingRight();
            for (int i = 0; i < getChildCount(); i++) measureChild(getChildAt(i), ws, hs);
            int[] wh = flow(max, false);
            setMeasuredDimension(resolveSize(wh[0] + getPaddingLeft() + getPaddingRight(), ws),
                    resolveSize(wh[1] + getPaddingTop() + getPaddingBottom(), hs));
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            flow(r - l - getPaddingLeft() - getPaddingRight(), true);
        }

        /** {largura, altura} do conteúdo; com layout, posiciona os filhos. */
        private int[] flow(int max, boolean layout) {
            int x = 0, y = 0, line = 0, width = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View v = getChildAt(i);
                if (v.getVisibility() == GONE) continue;
                int w = v.getMeasuredWidth(), h = v.getMeasuredHeight();
                if (x > 0 && x + w > max) {
                    y += line;
                    x = 0;
                    line = 0;
                }
                if (layout) v.layout(getPaddingLeft() + x, getPaddingTop() + y, getPaddingLeft() + x + w, getPaddingTop() + y + h);
                width = Math.max(width, x + w);
                x += w + gap;
                line = Math.max(line, h);
            }
            return new int[]{width, y + line};
        }
    }
}
