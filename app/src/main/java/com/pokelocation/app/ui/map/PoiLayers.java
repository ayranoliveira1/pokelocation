package com.pokelocation.app.ui.map;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

import com.pokelocation.app.pogo.PogoLayer;

import org.osmdroid.views.MapView;

/** Único ponto que cita o PogoLayer: com a camada desligada o RouteMapView nunca carrega esta classe nem o pacote pogo. */
final class PoiLayers {
    private PoiLayers() {}

    static MapPoiLayer pogo(Context c, MapView mv, FrameLayout cardHost, View status, int goLabel, MapPoiLayer.OnGo onGo) {
        return new PogoLayer(c, mv, cardHost, status, goLabel, onGo);
    }
}
