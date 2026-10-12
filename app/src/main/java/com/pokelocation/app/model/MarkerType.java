package com.pokelocation.app.model;

public final class MarkerType {
    public static final int LANDMARK = 1, RECREATIONAL_AREA = 2;
    public final int id;
    public final String name;
    public final String icon;        // chave de MapHelper.ICON_KEYS
    public final int color;          // ARGB
    public final int count;          // nº de marcadores (preenchido por MarkerDao.types/getType)

    public MarkerType(int id, String name, String icon, int color, int count) {
        this.id = id;
        this.name = name;
        this.icon = icon;
        this.color = color;
        this.count = count;
    }

    public boolean deletable() { return id > 2; }
}
