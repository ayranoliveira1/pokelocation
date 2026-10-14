package com.pokelocation.app.social.ui.chat;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.text.format.DateUtils;
import android.view.View;
import android.widget.TextView;

import com.google.android.material.color.MaterialColors;
import com.pokelocation.app.R;
import com.pokelocation.app.social.chat.Chat;

import java.text.DateFormat;

/** Linha de conversa fora das Activities: o painel do :overlay usa sem carregar AppCompat. */
final class ConvRow {
    // Extras da ChatActivity aqui: o painel do :overlay abre a conversa pelo nome, sem carregar a Activity.
    static final String CHAT_ACTIVITY = "com.pokelocation.app.social.ui.chat.ChatActivity";
    static final String EXTRA_CONV = "conv", EXTRA_TITLE = "title", EXTRA_GROUP = "group", EXTRA_SAFETY = "safety";

    private ConvRow() {}

    static long liveUntil(Chat.Conv c, long now) {
        if (c == null) return 0;
        long until = Math.max(c.myLiveUntil, c.liveUntil);
        return until > now ? until : 0;
    }

    static String initial(String title) {
        if (title == null) return "?";
        String t = title.startsWith("@") ? title.substring(1) : title;
        return t.isEmpty() ? "?" : new String(Character.toChars(Character.toUpperCase(t.codePointAt(0))));
    }

    static String preview(Context ctx, Chat.Conv c) {
        switch (c.previewType) {
            case Chat.TEXT: return c.preview == null ? "" : c.preview;
            case Chat.LOC: return ctx.getString(R.string.soc_chat_location);
            case Chat.LIVE_START: return ctx.getString(R.string.soc_chat_live_location);
            case Chat.LIVE_STOP: return ctx.getString(R.string.soc_chat_msg_live_stop);
            case Chat.UNREADABLE: return ctx.getString(R.string.soc_chat_unreadable);
            case Chat.IDENTITY_CHANGED: return ctx.getString(R.string.soc_chat_identity_changed);
            default: return "";
        }
    }

    static void bind(View v, Chat.Conv c) {
        Context ctx = v.getContext();
        TextView avatar = v.findViewById(R.id.soc_chat_row_avatar), title = v.findViewById(R.id.soc_chat_row_title),
                time = v.findViewById(R.id.soc_chat_row_time), preview = v.findViewById(R.id.soc_chat_row_preview),
                unread = v.findViewById(R.id.soc_chat_row_unread);
        long now = System.currentTimeMillis();
        boolean has = c.unread > 0;
        avatar.setText(initial(c.title));
        avatar.setBackgroundTintList(ColorStateList.valueOf(
                MaterialColors.getColor(v, com.google.android.material.R.attr.colorPrimaryContainer)));
        avatar.setTextColor(MaterialColors.getColor(v, com.google.android.material.R.attr.colorOnPrimaryContainer));
        title.setText(c.title != null ? c.title : "…");
        title.setTypeface(null, has ? Typeface.BOLD : Typeface.NORMAL);
        time.setText(c.lastTs > 0 ? DateUtils.formatSameDayTime(c.lastTs, now, DateFormat.SHORT, DateFormat.SHORT) : "");
        time.setTextColor(MaterialColors.getColor(v, has ? androidx.appcompat.R.attr.colorPrimary
                : com.google.android.material.R.attr.colorOnSurfaceVariant));
        String p = preview(ctx, c);
        if (liveUntil(c, now) > 0) p = ctx.getString(R.string.soc_chat_live) + " · " + p;
        preview.setText(p);
        unread.setText(c.unread > 99 ? "99+" : String.valueOf(c.unread));
        unread.setVisibility(has ? View.VISIBLE : View.GONE);
    }
}
