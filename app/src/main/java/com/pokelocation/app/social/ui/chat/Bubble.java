package com.pokelocation.app.social.ui.chat;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.google.android.material.color.MaterialColors;
import com.pokelocation.app.R;
import com.pokelocation.app.social.chat.Chat;
import com.pokelocation.app.util.Ui;

import java.text.DateFormat;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/** Balão de mensagem sem RecyclerView: o painel do :overlay usa sem carregar o RecyclerView nem as Activities. */
final class Bubble {
    private final boolean group;
    private final Consumer<Chat.Msg> onTap;
    private final DateFormat time;
    private final int mineBg, mineFg, theirsBg, theirsFg, muted, danger;
    private Map<String, String> labels = Collections.emptyMap();
    private String peer;
    private Consumer<Chat.Msg> onCode, onResend;

    Bubble(Context c, boolean group, Consumer<Chat.Msg> onTap) {
        this.group = group;
        this.onTap = onTap;
        time = android.text.format.DateFormat.getTimeFormat(c);
        mineBg = MaterialColors.getColor(c, com.google.android.material.R.attr.colorPrimaryContainer, 0);
        mineFg = MaterialColors.getColor(c, com.google.android.material.R.attr.colorOnPrimaryContainer, 0);
        theirsBg = MaterialColors.getColor(c, com.google.android.material.R.attr.colorSurfaceContainer, 0);
        theirsFg = MaterialColors.getColor(c, com.google.android.material.R.attr.colorOnSurface, 0);
        muted = MaterialColors.getColor(c, com.google.android.material.R.attr.colorOnSurfaceVariant, 0);
        danger = MaterialColors.getColor(c, androidx.appcompat.R.attr.colorError, 0);
    }

    void setLabels(Map<String, String> labels) {
        this.labels = labels;
    }

    void setIdentity(String peer, Consumer<Chat.Msg> onCode, Consumer<Chat.Msg> onResend) {
        this.peer = peer;
        this.onCode = onCode;
        this.onResend = onResend;
    }

    View create(ViewGroup parent) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.soc_chat_msg, parent, false);
        v.setTag(new Holder(v));
        return v;
    }

    void bind(View row, Chat.Msg m) {
        Holder h = (Holder) row.getTag();
        Context c = row.getContext();
        boolean mine = m.from == null, notice = m.type == Chat.LIVE_STOP || m.type == Chat.IDENTITY_CHANGED;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) h.bubble.getLayoutParams();
        lp.gravity = notice ? Gravity.CENTER_HORIZONTAL : mine ? Gravity.END : Gravity.START;
        h.bubble.setLayoutParams(lp);
        h.bubble.setBackgroundTintList(ColorStateList.valueOf(mine && !notice ? mineBg : theirsBg));
        int fg = mine ? mineFg : theirsFg;

        boolean showFrom = group && !mine && !notice;
        h.from.setVisibility(showFrom ? View.VISIBLE : View.GONE);
        if (showFrom) {
            String label = labels.get(m.from);
            h.from.setText(label != null ? label : "…");
        }

        h.body.setTextColor(notice || m.type == Chat.UNREADABLE ? muted : fg);
        h.body.setTypeface(null, notice || m.type == Chat.UNREADABLE ? Typeface.ITALIC : Typeface.NORMAL);
        boolean pin = m.type == Chat.LOC || m.type == Chat.LIVE_START;
        h.body.setCompoundDrawablesRelativeWithIntrinsicBounds(pin ? R.drawable.ic_pin : 0, 0, 0, 0);
        h.body.setText(body(c, m));
        boolean identity = m.type == Chat.IDENTITY_CHANGED && onCode != null;
        h.actions.setVisibility(identity ? View.VISIBLE : View.GONE);
        if (identity) {
            h.code.setOnClickListener(v -> onCode.accept(m));
            h.resend.setVisibility(m.resend > 0 ? View.VISIBLE : View.GONE);
            h.resend.setText(c.getString(R.string.soc_chat_resend, m.resend));
            h.resend.setOnClickListener(v -> onResend.accept(m));
        }

        long now = System.currentTimeMillis();
        String when = DateUtils.formatSameDayTime(m.ts, now, DateFormat.SHORT, DateFormat.SHORT).toString();
        h.meta.setTextColor(mine && m.state == Chat.FAILED ? danger : muted);
        // Na fila (sem WS) pode ficar assim até a próxima conexão: relógio discreto, não um "enviando…" parado.
        boolean queued = mine && !notice && m.state == Chat.SENDING;
        h.meta.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, queued ? R.drawable.soc_ic_clock : 0, 0);
        h.meta.setCompoundDrawableTintList(queued ? ColorStateList.valueOf(muted) : null);
        h.meta.setContentDescription(queued ? when + ", " + c.getString(R.string.soc_chat_sending) : null);
        if (!mine || notice || queued) h.meta.setText(when);
        else if (m.state == Chat.FAILED)
            h.meta.setText(m.fail == Chat.FAIL_GONE ? R.string.soc_chat_failed_gone : R.string.soc_chat_failed);
        else h.meta.setText(when + "  ✓");

        row.setOnClickListener(v -> onTap.accept(m));
        row.setOnLongClickListener(m.type == Chat.TEXT ? v -> {
            Ui.copy(c, m.text, true);
            return true;
        } : null);
    }

    private String body(Context c, Chat.Msg m) {
        switch (m.type) {
            case Chat.TEXT: return m.text;
            case Chat.LOC: return c.getString(R.string.soc_chat_location) + "\n" + coords(m.lat, m.lng);
            case Chat.LIVE_START:
                return m.liveUntil > System.currentTimeMillis()
                        ? c.getString(R.string.soc_chat_msg_live, time.format(m.liveUntil)) + "\n" + coords(m.lat, m.lng)
                        : c.getString(R.string.soc_chat_msg_live_ended);
            case Chat.LIVE_STOP: return c.getString(R.string.soc_chat_msg_live_stop);
            case Chat.IDENTITY_CHANGED:
                String s = peer != null ? c.getString(R.string.soc_chat_identity_changed_of, peer)
                        : c.getString(R.string.soc_chat_identity_changed);
                return m.resend > 0 ? s + "\n" + c.getResources().getQuantityString(R.plurals.soc_chat_resend_pending, m.resend, m.resend) : s;
            default: return c.getString(R.string.soc_chat_unreadable);
        }
    }

    static String coords(double lat, double lng) {
        return String.format(Locale.ROOT, "%.5f, %.5f", lat, lng);
    }

    private static final class Holder {
        final View bubble;
        final TextView from, body, meta;
        final View actions, code;
        final TextView resend;

        Holder(View v) {
            actions = v.findViewById(R.id.soc_chat_msg_actions);
            code = v.findViewById(R.id.soc_chat_msg_code);
            resend = v.findViewById(R.id.soc_chat_msg_resend);
            bubble = v.findViewById(R.id.soc_chat_msg_bubble);
            from = v.findViewById(R.id.soc_chat_msg_from);
            body = v.findViewById(R.id.soc_chat_msg_body);
            meta = v.findViewById(R.id.soc_chat_msg_meta);
        }
    }
}
