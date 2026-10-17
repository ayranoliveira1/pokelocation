package com.pokelocation.app.ui.map;

/**
 * Camada opcional sobre o mapa (hoje, os Pokémon). O RouteMapView só conhece esta interface: a classe concreta, de
 * outro pacote, só é carregada pelo PoiLayers quando a camada é ligada.
 */
public interface MapPoiLayer {
    interface OnGo { void at(double lat, double lng); }

    void resume();
    void pause();
    void destroy();
    void boxChanged();
    void setTapsEnabled(boolean on);
}
