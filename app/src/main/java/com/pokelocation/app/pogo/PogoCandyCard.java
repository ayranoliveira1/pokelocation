package com.pokelocation.app.pogo;

import android.content.Context;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.pokelocation.app.Io;
import com.pokelocation.app.R;
import com.pokelocation.app.geo.CoordParser;
import com.pokelocation.app.util.Ui;

final class PogoCandyCard {
    interface Actions {
        void walk(Pogo.Candy c);
        void teleport(Pogo.Candy c);
        void close();
    }

    final View view;
    private final Context ctx;
    private final TextView sub, expires, coords;
    private final java.text.DateFormat time;
    private Pogo.Candy candy;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            showExpires();
            if (candy != null && candy.expiresMs > System.currentTimeMillis() && view.isAttachedToWindow())
                Io.main().postDelayed(this, 60_000);
        }
    };

    PogoCandyCard(Context c, Actions a) {
        ctx = c;
        time = DateFormat.getTimeFormat(c);
        view = LayoutInflater.from(c).inflate(R.layout.pogo_candy_card, null);
        ((ImageView) view.findViewById(R.id.pcc_icon)).setImageDrawable(PogoChips.candy(c));
        sub = view.findViewById(R.id.pcc_sub);
        expires = view.findViewById(R.id.pcc_expires);
        coords = view.findViewById(R.id.pcc_coords);
        view.findViewById(R.id.pcc_close).setOnClickListener(v -> a.close());
        view.findViewById(R.id.pcc_walk).setOnClickListener(v -> { if (candy != null) a.walk(candy); });
        view.findViewById(R.id.pcc_teleport).setOnClickListener(v -> { if (candy != null) a.teleport(candy); });
        view.findViewById(R.id.pcc_copy).setOnClickListener(v -> {
            if (candy != null) Ui.copy(ctx, CoordParser.text(candy.lat, candy.lng), true);
        });
        view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View x) { Io.main().post(tick); }
            @Override public void onViewDetachedFromWindow(View x) { Io.main().removeCallbacks(tick); }
        });
    }

    void bind(Pogo.Candy c, String distance) {
        candy = c;
        String s = TextUtils.isEmpty(c.location) ? distance : distance == null ? c.location : c.location + " · " + distance;
        sub.setText(s);
        sub.setVisibility(s == null ? View.GONE : View.VISIBLE);
        coords.setText(ctx.getString(R.string.pogo_card_coords, CoordParser.text(c.lat, c.lng)));
        Io.main().removeCallbacks(tick);
        tick.run();
    }

    void destroy() {
        Io.main().removeCallbacks(tick);
        candy = null;
    }

    private void showExpires() {
        if (candy == null) return;
        long at = candy.expiresMs;
        if (at == 0) {
            expires.setVisibility(View.GONE);
            return;
        }
        expires.setText(ctx.getString(R.string.pogo_candy_expires, time.format(at),
                PogoText.minutesLeft(at, System.currentTimeMillis())));
        expires.setVisibility(View.VISIBLE);
    }
}
