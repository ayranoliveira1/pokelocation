package com.pokelocation.app.pogo;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.pokelocation.app.R;
import com.pokelocation.app.util.Ui;

/**
 * Controles do filtros-design-v2, só framework e GradientDrawable: Segmented (escolha única), ChoiceGrid (múltipla, 4
 * colunas), summaryChip (filtro ativo com "×") e Flow (quebra linha, só para as espécies). Bordas interativas no
 * outline do app (contraste ≥ 3:1); o pogo_outline claro fica para divisórias.
 */
final class PogoChips {
    private PogoChips() {}

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

    /** Peso medium do sistema (sem fonte nova). */
    static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    static GradientDrawable round(Context c, float radiusDp, int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(Ui.dp(c, radiusDp));
        if (fill != 0) g.setColor(fill);
        if (stroke != 0) g.setStroke(Ui.dp(c, 1), stroke);
        return g;
    }

    /** 4 opções não cabem numa linha em tela estreita ou com fonte grande: vira 2×2 (nunca reflui irregular). */
    static boolean narrow(Context c) {
        android.content.res.Configuration cf = c.getResources().getConfiguration();
        return cf.screenWidthDp < 360 || cf.fontScale > 1.15f;
    }

    /**
     * Escolha única (Onde, raio, Ordenar, Gênero, Liga, Nº de resultados): segmentos de largura igual numa linha, 48dp
     * visíveis, num fundo surface_variant; o selecionado é uma pílula primary com texto medium. Sem "✓": a largura não
     * muda. 4 opções em tela estreita/fonte grande = 2×2.
     */
    static final class Segmented extends LinearLayout {
        interface OnPick { void pick(int index); }

        private final TextView[] seg;
        private final OnPick onPick;
        private int selected = -1;

        Segmented(Context c, CharSequence[] labels, int selected, OnPick onPick) {
            super(c);
            this.onPick = onPick;
            setOrientation(VERTICAL);
            int p = Ui.dp(c, 4);
            setPadding(p, p, p, p);
            setBackground(round(c, 12, c.getColor(R.color.pogo_surface_variant), 0));
            int perRow = labels.length == 4 && narrow(c) ? 2 : labels.length;
            seg = new TextView[labels.length];
            LinearLayout row = null;
            for (int i = 0; i < labels.length; i++) {
                if (i % perRow == 0) {
                    row = new LinearLayout(c);
                    addView(row, new LayoutParams(LayoutParams.MATCH_PARENT, Ui.dp(c, 48)));
                }
                TextView t = new TextView(c);
                t.setText(labels[i]);
                t.setTextSize(15);
                t.setGravity(Gravity.CENTER);
                t.setSingleLine(true);
                t.setEllipsize(android.text.TextUtils.TruncateAt.END);
                int h = Ui.dp(c, 8);
                t.setPadding(h, 0, h, 0);
                int idx = i;
                t.setOnClickListener(v -> {
                    if (idx == this.selected) return;
                    select(idx);
                    onPick.pick(idx);
                });
                row.addView(t, new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f));
                seg[i] = t;
            }
            select(selected);
        }

        /** Sem callback (estado vindo do filtro). */
        void select(int i) {
            selected = i;
            Context c = getContext();
            for (int k = 0; k < seg.length; k++) {
                boolean on = k == i;
                TextView t = seg[k];
                t.setSelected(on);
                t.setBackground(on ? round(c, 10, c.getColor(R.color.primary), 0) : null);
                t.setTextColor(c.getColor(on ? R.color.on_primary : R.color.on_surface));
                t.setTypeface(on ? MEDIUM : Typeface.DEFAULT);
                t.setContentDescription(on ? t.getText() + ", " + c.getString(R.string.pogo_selected) : t.getText());
            }
        }

        int selected() { return selected; }
    }

    /**
     * Múltipla escolha (tamanhos, atalhos de IV): 4 colunas de largura igual que nunca refluem; célula de 44dp visíveis
     * (alvo 48dp), raio 12dp, borda outline; selecionada = primary + medium + ✓ à esquerda, com o espaço do ✓ reservado
     * também na não selecionada (a largura não muda).
     */
    static final class ChoiceGrid extends LinearLayout {
        interface OnToggle { void toggle(int index, boolean on); }

        private final TextView[] cell;
        private final android.graphics.drawable.Drawable check, blank;

        ChoiceGrid(Context c, CharSequence[] labels, boolean[] on, OnToggle cb) {
            super(c);
            setOrientation(VERTICAL);
            int s = Ui.dp(c, 18);
            check = c.getDrawable(R.drawable.ic_check).mutate();
            check.setTint(c.getColor(R.color.on_primary));
            check.setBounds(0, 0, s, s);
            blank = new android.graphics.drawable.ColorDrawable(0);
            blank.setBounds(0, 0, s, s);
            cell = new TextView[labels.length];
            int gap = Ui.dp(c, 8);
            LinearLayout row = null;
            for (int i = 0; i < labels.length; i++) {
                if (i % 4 == 0) {
                    row = new LinearLayout(c);
                    addView(row, new LayoutParams(LayoutParams.MATCH_PARENT, Ui.dp(c, 48)));
                }
                TextView t = new TextView(c);
                t.setText(labels[i]);
                t.setTextSize(15);
                t.setGravity(Gravity.CENTER);
                t.setSingleLine(true);
                t.setCompoundDrawablePadding(Ui.dp(c, 4));
                int idx = i;
                t.setOnClickListener(v -> {
                    boolean now = !v.isSelected();
                    set(idx, now);
                    cb.toggle(idx, now);
                });
                LayoutParams lp = new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f);
                if (i % 4 != 0) lp.setMarginStart(gap);
                row.addView(t, lp);
                cell[i] = t;
                set(i, on != null && on[i]);
            }
            // última linha incompleta: células vazias mantêm as 4 colunas iguais
            for (int k = labels.length % 4; k != 0 && k < 4; k++) {
                LayoutParams lp = new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f);
                lp.setMarginStart(gap);
                row.addView(new View(c), lp);
            }
        }

        /** Sem callback (estado vindo do filtro). */
        void set(int i, boolean on) {
            Context c = getContext();
            TextView t = cell[i];
            t.setSelected(on);
            int v = Ui.dp(c, 2);
            t.setBackground(new InsetDrawable(round(c, 12, on ? c.getColor(R.color.primary) : 0,
                    on ? 0 : c.getColor(R.color.outline)), 0, v, 0, v));
            t.setTextColor(c.getColor(on ? R.color.on_primary : R.color.on_surface));
            t.setTypeface(on ? MEDIUM : Typeface.DEFAULT);
            t.setCompoundDrawablesRelative(on ? check : blank, null, null, null);
            t.setContentDescription(on ? t.getText() + ", " + c.getString(R.string.pogo_selected) : t.getText());
        }
    }

    /**
     * Chip de resumo de um filtro ativo (fora do modal): 36dp visíveis, borda outline; tocar no texto = open, o "×" tem
     * alvo próprio de 40dp = remove. remove null = sem "×" (o escopo).
     */
    static View summaryChip(Context c, CharSequence text, View.OnClickListener open, View.OnClickListener remove) {
        LinearLayout chip = new LinearLayout(c);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setMinimumHeight(Ui.dp(c, 48));
        int v = Ui.dp(c, 6);
        chip.setBackground(new InsetDrawable(round(c, 18, 0, c.getColor(R.color.outline)), 0, v, 0, v));
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(14);
        t.setTextColor(c.getColor(R.color.on_surface));
        t.setSingleLine(true);
        t.setGravity(Gravity.CENTER_VERTICAL);
        int h = Ui.dp(c, 12);
        t.setPadding(h, 0, remove == null ? h : 0, 0);
        t.setOnClickListener(open);
        chip.addView(t, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT));
        if (remove != null) {
            TextView x = new TextView(c);
            x.setText("×");
            x.setTextSize(18);
            x.setGravity(Gravity.CENTER);
            x.setTextColor(c.getColor(R.color.on_surface_variant));
            x.setContentDescription(c.getString(R.string.pogo_remove, text));
            x.setOnClickListener(remove);
            chip.addView(x, new LinearLayout.LayoutParams(Ui.dp(c, 40), LinearLayout.LayoutParams.MATCH_PARENT));
        }
        return chip;
    }
}
