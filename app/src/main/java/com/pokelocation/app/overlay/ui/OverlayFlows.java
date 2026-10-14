package com.pokelocation.app.overlay.ui;

import android.content.Context;

import com.pokelocation.app.Io;
import com.pokelocation.app.R;
import com.pokelocation.app.data.FavoriteDao;
import com.pokelocation.app.data.MarkerDao;
import com.pokelocation.app.data.RouteDao;
import com.pokelocation.app.geo.Cooldown;
import com.pokelocation.app.geo.CoordParser;
import com.pokelocation.app.geo.Geo;
import com.pokelocation.app.model.Coord;
import com.pokelocation.app.model.Favorite;
import com.pokelocation.app.model.Folder;
import com.pokelocation.app.model.MarkerType;
import com.pokelocation.app.model.Route;
import com.pokelocation.app.ui.panel.ItemListPanel;
import com.pokelocation.app.ui.panel.NameFolderPanel;
import com.pokelocation.app.social.ui.chat.ChatPanel;
import com.pokelocation.app.ui.panel.PanelHost;
import com.pokelocation.app.ui.panel.SelectPanel;
import com.pokelocation.app.ui.panel.TeleportPanel;
import com.pokelocation.app.util.Ui;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Fluxos de painel do overlay (02 §2.3–2.4, 05 §3.10, 04 §3.7) sobre ui/panel.
 * Painéis só existem enquanto abertos (PanelHost remove e anula ao fechar); abrir um fecha os outros.
 */
final class OverlayFlows {
    private final OverlayView ov;
    private final Context ctx;
    private final Context app;            // DAOs no Io: nunca segurar o contexto temático
    private final boolean showDistance;   // display_distance_info

    OverlayFlows(OverlayView ov, boolean showDistance) {
        this.ov = ov;
        this.ctx = ov.ctx;
        this.app = ov.ctx.getApplicationContext();
        this.showDistance = showDistance;
    }

    void close() {
        PanelHost.closeAll();
    }

    // ------------------------------------------------------------------ Teleport (F45)

    /** Antes de abrir guarda lastMockedTime = agora (10 §3.2): o cooldown conta a partir daqui. */
    void openTeleport(String text) {
        openTeleport(text, null);
    }

    void refreshChat() {
        ChatPanel.refreshOpen();
    }

    void closeChat() {
        ChatPanel.dismissOpen();
    }

    void openChat() {
        close();
        String title = ctx.getString(R.string.soc_teleport_title);
        ChatPanel.show(ctx, text -> openTeleport(text, title));
    }

    void openTeleport(String text, String title) {
        close();
        ov.lastMockedTime = System.currentTimeMillis();
        TeleportPanel.Args a = new TeleportPanel.Args();
        a.current = new Coord(ov.engine.lat(), ov.engine.lng(), ov.engine.alt());
        a.text = text;
        a.title = title;
        a.walkMode = ov.walkMode;
        a.showBack = true;
        a.canGoBack = ov.host.hasPreviousPosition();
        a.showFavorites = true;
        a.showDistance = showDistance;
        a.lastMockedTime = ov.lastMockedTime;
        TeleportPanel.show(ctx, a, new TeleportPanel.Callback() {
            @Override
            public void onGo(double lat, double lng, boolean walk) {
                close();
                if (!ov.isDestroyed()) ov.host.onGo(lat, lng, Double.NaN, walk);
            }

            @Override
            public void onBack(boolean walk) {
                close();
                if (!ov.isDestroyed()) ov.host.onGoBack(walk);
            }

            @Override
            public void onFavorites() {
                openFavorites();
            }

            @Override
            public void onWalkModeChanged(boolean walk) {
                ov.walkMode = walk;
            }
        });
    }

    // ------------------------------------------------------------------ Favoritos (F46)

    void openFavorites() {
        close();
        ItemListPanel.show(ctx, ctx.getText(R.string.favorites), Folder.FAVORITES, folder -> {
            List<Favorite> list = ItemListPanel.ALL.equals(folder) ? FavoriteDao.listAll(app) : FavoriteDao.list(app, folder);
            List<ItemListPanel.Item> out = new ArrayList<>(list.size());
            for (Favorite f : list) {
                out.add(new ItemListPanel.Item(f, f.name,
                        f.address.isEmpty() ? CoordParser.text(f.lat, f.lng) : f.address));
            }
            return out;
        }, new ItemListPanel.Callback() {
            @Override
            public void onPick(Object tag) {
                Favorite f = (Favorite) tag;
                close();
                if (!ov.isDestroyed()) ov.host.onGo(f.lat, f.lng, f.alt, ov.walkMode);
            }

            @Override
            public void onAction() {
                openTeleport("");
            }
        }, ctx.getText(R.string.ov_teleport_to_location), showDistance ? new ItemListPanel.LineProvider() {
            @Override
            public CharSequence line(Object tag, long now) {
                Favorite f = (Favorite) tag;
                return Cooldown.line(ctx, Geo.distance(ov.engine.lat(), ov.engine.lng(), f.lat, f.lng), ov.lastMockedTime, now);
            }

            @Override
            public int color(Object tag, long now) {
                Favorite f = (Favorite) tag;
                return Cooldown.color(ctx, Geo.distance(ov.engine.lat(), ov.engine.lng(), f.lat, f.lng), ov.lastMockedTime, now);
            }
        } : null);
    }

    // ------------------------------------------------------------------ Save (F47, F72)

    /** Seletor "Current Location". A posição é capturada ao abrir (o boneco pode continuar andando). */
    void openSave() {
        close();
        final double lat = ov.engine.lat(), lng = ov.engine.lng(), alt = ov.engine.alt();
        CharSequence[] options = {
                ctx.getText(R.string.ov_add_to_favorites),
                ctx.getText(R.string.ov_add_to_route),
                ctx.getText(R.string.ov_add_to_marker)};
        SelectPanel.show(ctx, ctx.getText(R.string.ov_current_location), options, i -> {
            if (i == 0) saveFavorite(lat, lng, alt);
            else if (i == 1) addToRoute(lat, lng, alt);
            else if (i == 2) addToMarker(lat, lng, alt);
        });
    }

    private void saveFavorite(double lat, double lng, double alt) {
        NameFolderPanel.show(ctx, ctx.getText(R.string.ov_add_to_favorites), CoordParser.text(lat, lng),
                Folder.FAVORITES, null, (name, folderId) -> write(() -> {
                    FavoriteDao.insert(app, name, "", lat, lng, Double.isNaN(alt) ? 0 : alt, folderId);
                    return Boolean.TRUE;
                }));
    }

    private void addToRoute(double lat, double lng, double alt) {
        ItemListPanel.show(ctx, ctx.getText(R.string.ov_add_to_route), Folder.ROUTES, folder -> {
            List<Route> list = ItemListPanel.ALL.equals(folder) ? RouteDao.listAll(app) : RouteDao.list(app, folder);
            List<ItemListPanel.Item> out = new ArrayList<>(list.size());
            for (Route r : list) out.add(new ItemListPanel.Item(r.id, r.name, null));
            return out;
        }, tag -> {
            close();
            write(() -> {
                RouteDao.appendPoint(app, (String) tag, lat, lng, alt);
                return Boolean.TRUE;
            });
        }, null, null);
    }

    private void addToMarker(double lat, double lng, double alt) {
        ItemListPanel.show(ctx, ctx.getText(R.string.ov_add_to_marker), null, folder -> {
            List<MarkerType> types = MarkerDao.types(app);
            List<ItemListPanel.Item> out = new ArrayList<>(types.size());
            for (MarkerType t : types) out.add(new ItemListPanel.Item(t.id, t.name, null));
            return out;
        }, tag -> {
            close();
            write(() -> {
                MarkerDao.insert(app, (Integer) tag, null, lat, lng, alt);
                return Boolean.TRUE;
            });
        }, null, null);
    }

    /** Escrita no Io; não é cancelada ao fechar painéis (é dado do usuário). Toast do resultado. */
    private void write(Callable<Boolean> work) {
        Io.run(work, new Io.Callback<Boolean>() {
            @Override
            public void onResult(Boolean v) {
                Ui.toast(app, R.string.item_saved);
            }

            @Override
            public void onError(Throwable t) {
                Ui.toast(app, R.string.unknown_error);
            }
        });
    }
}
