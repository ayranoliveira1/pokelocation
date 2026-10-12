package com.pokelocation.app.util;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.text.Html;
import android.view.WindowManager;
import android.widget.Toast;

import com.pokelocation.app.R;

public final class Ui {
    private Ui() {}

    public static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    public static void toast(Context c, int resId) {
        Toast.makeText(c, resId, Toast.LENGTH_SHORT).show();
    }

    public static void toast(Context c, CharSequence text) {
        Toast.makeText(c, text, Toast.LENGTH_SHORT).show();
    }

    public static void copy(Context c, CharSequence text, boolean toast) {
        try {
            ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText(c.getString(R.string.app_name), text));
            if (toast) toast(c, R.string.copied);
        } catch (Exception ignored) {
        }
    }

    /** Texto do clipboard (getText, fallback htmlText); "" se vazio. */
    public static String paste(Context c) {
        try {
            ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return "";
            ClipData.Item it = clip.getItemAt(0);
            CharSequence t = it.getText();
            if (t == null && it.getHtmlText() != null) t = Html.fromHtml(it.getHtmlText(), Html.FROM_HTML_MODE_LEGACY);
            return t == null ? "" : t.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public static void share(Context c, String text) {
        try {
            Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text);
            c.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            copy(c, text, true);
        }
    }

    @SuppressWarnings("deprecation")
    public static int overlayWindowType() {
        return Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }
}
