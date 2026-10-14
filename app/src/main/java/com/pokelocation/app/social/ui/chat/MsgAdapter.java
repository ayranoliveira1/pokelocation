package com.pokelocation.app.social.ui.chat;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.pokelocation.app.social.chat.Chat;

import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

final class MsgAdapter extends ListAdapter<Chat.Msg, MsgAdapter.VH> {
    private static final DiffUtil.ItemCallback<Chat.Msg> DIFF = new DiffUtil.ItemCallback<Chat.Msg>() {
        @Override public boolean areItemsTheSame(@NonNull Chat.Msg a, @NonNull Chat.Msg b) {
            return a.clientMsgId.equals(b.clientMsgId);
        }
        @Override public boolean areContentsTheSame(@NonNull Chat.Msg a, @NonNull Chat.Msg b) {
            return a.state == b.state && a.resend == b.resend && a.type == b.type && a.ts == b.ts && a.liveUntil == b.liveUntil && a.lat == b.lat
                    && a.lng == b.lng && Objects.equals(a.text, b.text) && Objects.equals(a.from, b.from);
        }
    };

    private final Bubble bubble;
    private final boolean group;

    MsgAdapter(Context c, boolean group, Consumer<Chat.Msg> onTap) {
        super(DIFF);
        setHasStableIds(true);
        this.group = group;
        bubble = new Bubble(c, group, onTap);
    }

    void setLabels(Map<String, String> labels) {
        bubble.setLabels(labels);
        if (group) notifyItemRangeChanged(0, getItemCount());
    }

    void setIdentity(String peer, Consumer<Chat.Msg> onCode, Consumer<Chat.Msg> onResend) {
        bubble.setIdentity(peer, onCode, onResend);
        notifyItemRangeChanged(0, getItemCount());
    }

    @Override public long getItemId(int position) { return Pager.stableId(getItem(position).clientMsgId); }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(bubble.create(parent));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        bubble.bind(h.itemView, getItem(position));
    }

    static final class VH extends RecyclerView.ViewHolder {
        VH(View v) { super(v); }
    }
}
