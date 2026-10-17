package com.pokelocation.app.pogo;

import android.app.Activity;
import android.content.Context;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.pokelocation.app.Io;
import com.pokelocation.app.R;
import com.pokelocation.app.util.Ui;

import java.util.concurrent.Callable;

/** Vínculo de treinador (spec §14): código → lookup → "é você?" → aviso de permanente → confirm → /users/me. */
final class TrainerLink {
    private TrainerLink() {}

    static void start(Activity a, Runnable linked) {
        View v = a.getLayoutInflater().inflate(R.layout.set_dialog_value, null);
        EditText input = v.findViewById(R.id.set_value_input);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint(R.string.pogo_t_code_hint);
        AlertDialog d = new MaterialAlertDialogBuilder(a)
                .setTitle(R.string.pogo_t_link)
                .setMessage(R.string.pogo_t_code_msg)
                .setView(v)
                .setPositiveButton(R.string.pogo_t_lookup, null)
                .setNegativeButton(R.string.cancel, null)
                .create();
        Io.Task[] task = new Io.Task[1];
        d.setOnDismissListener(x -> { if (task[0] != null) task[0].cancel(); });
        d.setOnShowListener(x -> {
            Button ok = d.getButton(AlertDialog.BUTTON_POSITIVE);
            ok.setOnClickListener(b -> {
                String code = Pogo.trainerCode(input.getText().toString());
                if (code == null) {
                    input.setError(a.getString(R.string.pogo_t_bad_code));
                    return;
                }
                ok.setEnabled(false);
                d.setMessage(a.getString(R.string.pogo_t_looking_up));
                Context app = a.getApplicationContext();
                task[0] = bg(() -> Pogo.lookupTrainer(app, code), new Io.Callback<Pogo.Trainer>() {
                    @Override public void onResult(Pogo.Trainer t) {
                        task[0] = null;
                        if (gone(a)) return;
                        d.dismiss();
                        preview(a, code, t, linked);
                    }
                    @Override public void onError(Throwable e) {
                        task[0] = null;
                        if (gone(a)) return;
                        if (signedOut(a, e, linked)) {
                            d.dismiss();
                            return;
                        }
                        ok.setEnabled(true);
                        d.setMessage(a.getString(R.string.pogo_t_code_msg));
                        Ui.toast(a, message(a, e, false));
                    }
                });
            });
        });
        d.show();
    }

    private static void preview(Activity a, String code, Pogo.Trainer t, Runnable linked) {
        String who = a.getString(R.string.pogo_t_who, t.name != null ? t.name : code, t.level,
                t.teamName != null ? t.teamName : "—");
        MaterialAlertDialogBuilder b = new MaterialAlertDialogBuilder(a).setTitle(R.string.pogo_t_is_you);
        if (!t.eligible) {
            b.setMessage(who + "\n\n" + a.getString(R.string.pogo_t_low_level, t.level, t.minLevel))
                    .setPositiveButton(R.string.ok, null).show();
            return;
        }
        b.setMessage(who).setPositiveButton(R.string.pogo_t_link, (d, w) -> permanent(a, code, who, linked))
                .setNegativeButton(R.string.cancel, null).show();
    }

    private static void permanent(Activity a, String code, String who, Runnable linked) {
        AlertDialog d = new MaterialAlertDialogBuilder(a)
                .setTitle(R.string.pogo_t_permanent_title)
                .setMessage(a.getString(R.string.pogo_t_permanent, who))
                .setPositiveButton(R.string.pogo_t_link, null)
                .setNegativeButton(R.string.cancel, null)
                .create();
        d.setOnShowListener(x -> {
            Button ok = d.getButton(AlertDialog.BUTTON_POSITIVE);
            ok.setOnClickListener(b -> {
                ok.setEnabled(false);
                d.setCancelable(false);
                d.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
                d.setMessage(a.getString(R.string.pogo_t_linking));
                Context app = a.getApplicationContext();
                // sem cancelar: o confirm é permanente, a tela tem que refletir o resultado
                bg(() -> {
                    Pogo.confirmTrainer(app, code);
                    return Boolean.TRUE;
                }, new Io.Callback<Boolean>() {
                    @Override public void onResult(Boolean r) {
                        if (gone(a)) return;
                        d.dismiss();
                        Ui.toast(a, R.string.pogo_t_linked);
                        linked.run();
                    }
                    @Override public void onError(Throwable e) {
                        if (gone(a)) return;
                        d.dismiss();
                        if (!signedOut(a, e, linked)) Ui.toast(a, message(a, e, true));
                    }
                });
            });
        });
        d.show();
    }

    /** Lookup e confirm levam até 1 min cada: fora do Io, que é a thread única do app. */
    private static <T> Io.Task bg(Callable<T> work, Io.Callback<T> cb) {
        Io.Task t = new Io.Task();
        new Thread(() -> {
            try {
                T v = work.call();
                Io.main().post(() -> { if (!t.isCancelled()) cb.onResult(v); });
            } catch (Throwable e) {
                Io.main().post(() -> { if (!t.isCancelled()) cb.onError(e); });
            }
        }, "pl-pogo-trainer").start();
        return t;
    }

    /** pogo_relogin: o servidor já apagou o vínculo; a tela volta a mostrar "Entrar com Google". */
    private static boolean signedOut(Activity a, Throwable e, Runnable render) {
        if (!Pogo.relogin(e)) return false;
        Ui.toast(a, R.string.pogo_t_signed_out);
        render.run();
        return true;
    }

    private static boolean gone(Activity a) { return a.isFinishing() || a.isDestroyed(); }

    /** Textos do site para os erros do §14.3; o resto mostra a mensagem do servidor ou uma genérica. */
    private static String message(Context c, Throwable t, boolean confirm) {
        if (!(t instanceof Pogo.Error)) return c.getString(R.string.pogo_t_failed);
        Pogo.Error e = (Pogo.Error) t;
        if (e.http == 429) return confirm ? c.getString(R.string.pogo_t_confirm_limit)
                : c.getString(R.string.pogo_t_lookup_limit, Math.max(1, (e.retryAfterS + 59) / 60));
        if (e.http == 503) return c.getString(R.string.pogo_t_busy);
        if ("bad_code".equals(e.error)) return c.getString(R.string.pogo_t_bad_code);
        return e.message != null && e.http < 500 ? e.message : c.getString(R.string.pogo_t_failed);
    }
}
