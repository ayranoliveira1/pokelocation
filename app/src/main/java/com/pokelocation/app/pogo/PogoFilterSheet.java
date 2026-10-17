package com.pokelocation.app.pogo;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.pokelocation.app.R;
import com.pokelocation.app.util.Ui;

import java.util.function.Consumer;

/**
 * Bottom sheet dos filtros: véu 40% que fecha ao toque, folha de até 85% da altura com
 * cabeçalho (Filtros, Limpar, ✕) e rodapé "Mostrar resultados (N)" fixos. Edita uma cópia: ✕/véu descartam, o rodapé
 * entrega a cópia ao onApply. Quem hospeda põe view() em tela cheia e a tira no onClosed.
 */
public final class PogoFilterSheet {
    private final Context c;
    private final PogoFilter edit;
    private final PogoFilterForm form;
    private final FrameLayout root;
    private final LinearLayout sheet;
    private final TextView apply;
    private final Runnable onClosed;
    private final Consumer<Boolean> focusable;
    private boolean closing;

    @SuppressWarnings("deprecation") // getSystemWindowInsetBottom: o Insets.Type é da API 30
    public PogoFilterSheet(Context c, PogoFilter current, Consumer<PogoFilter> onApply, Runnable onClosed,
                           Consumer<Boolean> focusable, Runnable linkTrainer) {
        this.c = c;
        this.onClosed = onClosed;
        this.focusable = focusable;
        edit = current.copy();
        int dp16 = Ui.dp(c, 16), dp48 = Ui.dp(c, 48);
        root = new FrameLayout(c);
        View veil = new View(c);
        veil.setBackgroundColor(0x66000000);
        veil.setOnClickListener(v -> close());
        root.addView(veil);

        sheet = new LinearLayout(c);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setClickable(true); // toque na folha não chega ao véu
        sheet.setBackground(shape(R.color.surface, new float[]{dp16, dp16, dp16, dp16, 0, 0, 0, 0}));
        View handle = new View(c);
        handle.setBackground(shape(R.color.pogo_outline, null));
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(Ui.dp(c, 32), Ui.dp(c, 4));
        hl.gravity = Gravity.CENTER_HORIZONTAL;
        hl.topMargin = Ui.dp(c, 8);
        sheet.addView(handle, hl);

        LinearLayout head = new LinearLayout(c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp16, 0, 0, 0);
        TextView title = text(R.string.pogo_panel_filters, R.color.on_surface);
        title.setTextSize(20);
        title.setTypeface(PogoChips.MEDIUM);
        title.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(title, new LinearLayout.LayoutParams(0, dp48, 1));
        TextView clear = text(R.string.pogo_f_clear, R.color.primary);
        clear.setPadding(dp16, 0, dp16, 0);
        head.addView(clear, new LinearLayout.LayoutParams(-2, dp48));
        TextView x = text(0, R.color.on_surface);
        x.setText("✕");
        x.setOnClickListener(v -> close());
        head.addView(x, new LinearLayout.LayoutParams(dp48, dp48));
        sheet.addView(head);

        form = new PogoFilterForm(c, edit, this::count, focusable, linkTrainer);
        clear.setOnClickListener(v -> form.reset());
        sheet.addView(form.view(), new LinearLayout.LayoutParams(-1, 0, 1));

        apply = text(0, R.color.on_primary);
        apply.setTypeface(PogoChips.MEDIUM);
        apply.setBackground(shape(R.color.primary, null));
        apply.setOnClickListener(v -> { if (!closing) onApply.accept(edit); }); // quem hospeda fecha no onApply
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-1, Ui.dp(c, 52));
        al.setMargins(dp16, Ui.dp(c, 8), dp16, dp16);
        sheet.addView(apply, al);
        // rodapé acima da barra de navegação (no app; no :overlay o inset vem 0)
        root.setOnApplyWindowInsetsListener((v, in) -> {
            int bottom = dp16 + in.getSystemWindowInsetBottom();
            if (al.bottomMargin != bottom) {
                al.bottomMargin = bottom;
                apply.setLayoutParams(al);
            }
            return in;
        });
        count();

        root.addView(sheet, new FrameLayout.LayoutParams(-1, (int) (c.getResources().getDisplayMetrics().heightPixels * 0.85f),
                Gravity.BOTTOM));
        sheet.setVisibility(View.INVISIBLE);
        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) {
                sheet.post(() -> {
                    sheet.setTranslationY(sheet.getHeight());
                    sheet.setVisibility(View.VISIBLE);
                    sheet.animate().translationY(0).setDuration(200);
                });
            }
            @Override public void onViewDetachedFromWindow(View v) {}
        });
    }

    public View view() { return root; }

    /** Subtela de espécies volta; senão fecha. */
    public boolean back() {
        if (!form.back()) close();
        return true;
    }

    /** Anima a saída e chama onClosed (quem hospeda tira a view). */
    public void close() {
        if (closing) return;
        destroy();
        sheet.animate().translationY(sheet.getHeight()).setDuration(150).withEndAction(onClosed);
    }

    /** Sem animação e sem onClosed: o painel inteiro sumiu com a folha aberta. */
    public void destroy() {
        closing = true;
        form.destroy();
        if (focusable != null) focusable.accept(false);
    }

    /** Remonta o form (ex.: na volta do vincular treinador). */
    public void render() { form.render(); }

    private void count() {
        int n = edit.activeCount();
        apply.setText(n == 0 ? c.getString(R.string.pogo_f_show) : c.getResources().getQuantityString(R.plurals.pogo_f_show_n, n, n));
    }

    private TextView text(int res, int color) {
        TextView t = new TextView(c);
        if (res != 0) t.setText(res);
        t.setTextSize(16);
        t.setTextColor(c.getColor(color));
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private GradientDrawable shape(int color, float[] radii) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(c.getColor(color));
        if (radii != null) g.setCornerRadii(radii);
        else g.setCornerRadius(Ui.dp(c, 14));
        return g;
    }
}
