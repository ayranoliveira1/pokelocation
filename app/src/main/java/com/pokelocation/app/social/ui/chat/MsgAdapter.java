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

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.color.MaterialColors;
import com.pokelocation.app.R;
import com.pokelocation.app.social.chat.Chat;
import com.pokelocation.app.util.Ui;

import java.text.DateFormat;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

final class MsgAdapter extends ListAdapter<Chat.Msg, MsgAdapter.VH> {
    private static final DiffUtil.ItemCallback<Chat.Msg> DIFF = new DiffUtil.ItemCallback<Chat.Msg>() {
        @Override public boolean areItemsTheSame(@NonNull Chat.Msg a, @NonNull Chat.Msg b) {
            return a.clientMsgId.equals(b.clientMsgId);
        }
        @Override public boolean areContentsTheSame(@NonNull Chat.Msg a, @NonNull Chat.Msg b) {
            return a.state == b.state && a.type == b.type && a.ts == b.ts && a.liveUntil == b.liveUntil && a.lat == b.lat
                    && a.lng == b.lng && Objects.equals(a.text, b.text) && Objects.equals(a.from, b.from);
        }
    };

    private final boolean group;
    private final Consumer<Chat.Msg> onTap;
    private final DateFormat time;
    private final int mineBg, mineFg, theirsBg, theirsFg, muted, danger;
    private Map<String, String> labels = Collections.emptyMap();

    MsgAdapter(Context c, boolean group, Consumer<Chat.Msg> onTap) {
        super(DIFF);
        setHasStableIds(true);
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
        if (group) notifyItemRangeChanged(0, getItemCount());
    }

    @Override public long getItemId(int position) { return Pager.stableId(getItem(position).clientMsgId); }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.soc_chat_msg, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        bind(h, getItem(position));
    }

    void bind(VH h, Chat.Msg m) {
        Context c = h.itemView.getContext();
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

        long now = System.currentTimeMillis();
        String when = DateUtils.formatSameDayTime(m.ts, now, DateFormat.SHORT, DateFormat.SHORT).toString();
        h.meta.setTextColor(mine && m.state == Chat.FAILED ? danger : muted);
        // Na fila (sem WS) pode ficar assim até a próxima conexão: relógio discreto, não um "enviando…" parado.
        boolean queued = mine && !notice && m.state == Chat.SENDING;
        h.meta.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, queued ? R.drawable.soc_ic_clock : 0, 0);
        h.meta.setCompoundDrawableTintList(queued ? ColorStateList.valueOf(muted) : null);
        h.meta.setContentDescription(queued ? when + ", " + c.getString(R.string.soc_chat_sending) : null);
        if (!mine || notice || queued) h.meta.setText(when);
        else if (m.state == Chat.FAILED) h.meta.setText(c.getString(R.string.soc_chat_failed));
        else h.meta.setText(when + "  ✓");

        h.itemView.setOnClickListener(v -> onTap.accept(m));
        h.itemView.setOnLongClickListener(m.type == Chat.TEXT ? v -> {
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
            case Chat.IDENTITY_CHANGED: return c.getString(R.string.soc_chat_identity_changed);
            default: return c.getString(R.string.soc_chat_unreadable);
        }
    }

    static String coords(double lat, double lng) {
        return String.format(Locale.ROOT, "%.5f, %.5f", lat, lng);
    }

    static final class VH extends RecyclerView.ViewHolder {
        final View bubble;
        final TextView from, body, meta;

        VH(View v) {
            super(v);
            bubble = v.findViewById(R.id.soc_chat_msg_bubble);
            from = v.findViewById(R.id.soc_chat_msg_from);
            body = v.findViewById(R.id.soc_chat_msg_body);
            meta = v.findViewById(R.id.soc_chat_msg_meta);
        }
    }
}
