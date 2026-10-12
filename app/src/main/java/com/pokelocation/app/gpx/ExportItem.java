package com.pokelocation.app.gpx;

/**
 * Item leve da lista de exportação. Rotas não carregam pontos aqui: o writer pede cada uma ao {@link Loader}
 * na hora de escrever (uma rota com pontos em memória por vez).
 */
public final class ExportItem {
    public static final int FAVORITE = 0, ROUTE = 1, MARKERS = 2;

    public interface Loader {
        /** Pontos intercalados lat,lng,alt da rota (ROUTE/MARKERS); null = omitir. Chamado no Io. */
        double[] load(ExportItem route) throws Exception;
    }

    public final int kind;
    public final String id;        // favorito/rota: id do banco; marcadores: "mk:<typeId>"
    public final int typeId;       // MARKERS
    public final String name, desc, folder;   // desc = endereço do favorito; folder = caminho normalizado ou null
    public final double lat, lng, alt;
    public final int count;        // nº de pontos (rotas/marcadores)

    private ExportItem(int kind, String id, int typeId, String name, String desc, String folder,
                       double lat, double lng, double alt, int count) {
        this.kind = kind;
        this.id = id;
        this.typeId = typeId;
        this.name = name;
        this.desc = desc;
        this.folder = folder;
        this.lat = lat;
        this.lng = lng;
        this.alt = alt;
        this.count = count;
    }

    public static ExportItem favorite(String id, String name, String address, double lat, double lng, double alt, String folder) {
        return new ExportItem(FAVORITE, id, 0, name, address, FolderTree.normalize(folder), lat, lng, alt, 1);
    }

    public static ExportItem route(String id, String name, int count, String folder) {
        return new ExportItem(ROUTE, id, 0, name, null, FolderTree.normalize(folder), 0, 0, Double.NaN, count);
    }

    public static ExportItem markers(int typeId, String name, int count) {
        return new ExportItem(MARKERS, "mk:" + typeId, typeId, name, null, null, 0, 0, Double.NaN, count);
    }

    public boolean isRoute() { return kind != FAVORITE; }
}
