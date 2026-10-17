package com.pokelocation.app.gpx;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;

/**
 * Resultado de GpxReader/KmlReader. Pontos sempre em double[] intercalado lat,lng,alt (alt NaN = ausente).
 * GPX: points = wpt, lines = rte, tracks = trk. KML: points = Point, lines = LineString, tracks vazio.
 * O estado "já salvo" da sessão de importação fica no próprio item.
 */
public final class ImportData {

    public static final class Item {
        public final int idx;
        public String name;                 // trocado pelo nome escolhido ao salvar no mapa
        public final String desc, folder;   // folder normalizado ("A/B") ou null
        public final double[] pts;          // ponto: 3 doubles; linha: n × 3; track: null
        public final List<double[]> segs;   // só track
        public boolean saved;               // ponto/linha
        public BitSet segSaved;             // track: segmentos salvos (lazy)

        Item(int idx, String name, String desc, String folder, double[] pts, List<double[]> segs) {
            this.idx = idx;
            this.name = name;
            this.desc = desc;
            this.folder = folder;
            this.pts = pts;
            this.segs = segs;
        }

        public int count() { return pts != null ? pts.length / 3 : segs.size(); }

        public boolean segIsSaved(int s) { return segSaved != null && segSaved.get(s); }

        public void markSeg(int s) {
            if (segSaved == null) segSaved = new BitSet(segs.size());
            segSaved.set(s);
        }

        public boolean allSegsSaved() { return segSaved != null && segSaved.cardinality() >= segs.size(); }
    }

    public final boolean kml;
    public final List<Item> points = new ArrayList<>(), lines = new ArrayList<>(), tracks = new ArrayList<>();
    public int skipped;   // KML: placemarks sem geometria válida

    private final HashMap<String, String> folderPool = new HashMap<>();

    ImportData(boolean kml) { this.kml = kml; }

    public boolean isEmpty() { return points.isEmpty() && lines.isEmpty() && tracks.isEmpty(); }

    Item addPoint(String name, String desc, String folder, double lat, double lng, double alt) {
        Item it = new Item(points.size(), name, desc, folder, new double[]{lat, lng, alt}, null);
        points.add(it);
        return it;
    }

    Item addLine(String name, String desc, String folder, double[] pts) {
        Item it = new Item(lines.size(), name, desc, folder, pts, null);
        lines.add(it);
        return it;
    }

    Item addTrack(String name, String desc, String folder, List<double[]> segs) {
        Item it = new Item(tracks.size(), name, desc, folder, null, segs);
        tracks.add(it);
        return it;
    }

    /** Normaliza o caminho e devolve uma instância única por caminho (milhares de itens na mesma pasta = 1 String). */
    String folder(String raw) {
        String p = FolderTree.normalize(raw);
        if (p == null) return null;
        String pooled = folderPool.get(p);
        if (pooled == null) folderPool.put(p, pooled = p);
        return pooled;
    }

    /** Concatena os pontos dos itens (ou segmentos) numa única rota. */
    public static double[] concat(List<double[]> parts) {
        int n = 0;
        for (double[] p : parts) n += p.length;
        double[] out = new double[n];
        int o = 0;
        for (double[] p : parts) {
            System.arraycopy(p, 0, out, o, p.length);
            o += p.length;
        }
        return out;
    }

    /** Buffer de pontos com crescimento por dobra; reaproveitado entre containers do mesmo arquivo. */
    static final class Buf {
        private double[] a = new double[3 * 64];
        private int n;

        void add(double lat, double lng, double alt) {
            if (n + 3 > a.length) a = Arrays.copyOf(a, a.length * 2);
            a[n++] = lat;
            a[n++] = lng;
            a[n++] = alt;
        }

        int size() { return n / 3; }

        /** Copia exata e zera o buffer (a capacidade fica para o próximo container). */
        double[] take() {
            double[] out = Arrays.copyOf(a, n);
            n = 0;
            return out;
        }
    }
}
