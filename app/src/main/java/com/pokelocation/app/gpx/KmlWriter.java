package com.pokelocation.app.gpx;

import android.util.Xml;

import org.xmlpull.v1.XmlSerializer;

import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * KML 2.2 em streaming (06 §3.10.2): favoritos sem pasta, rotas sem pasta, depois a árvore de &lt;Folder&gt;
 * (em cada pasta: favoritos, rotas, subpastas). Ordem das pastas: as dos favoritos, depois as novas das rotas.
 */
public final class KmlWriter {
    public static final String NS = "http://www.opengis.net/kml/2.2";
    private static final String INDENT = "http://xmlpull.org/v1/doc/features.html#indent-output";

    private KmlWriter() {}

    private static final class Node {
        final String name;
        final ArrayList<ExportItem> favs = new ArrayList<>(), routes = new ArrayList<>();
        final LinkedHashMap<String, Node> children = new LinkedHashMap<>();
        Node(String name) { this.name = name; }
    }

    public static void write(OutputStream os, List<ExportItem> favorites, List<ExportItem> routes, ExportItem.Loader loader)
            throws Exception {
        BufferedOutputStream out = new BufferedOutputStream(os, 8192);
        XmlSerializer s = Xml.newSerializer();
        s.setOutput(out, "UTF-8");
        write(s, favorites, routes, loader);
        out.flush();
    }

    /** Serializer já com setOutput (injetável para testes). */
    public static void write(XmlSerializer s, List<ExportItem> favorites, List<ExportItem> routes, ExportItem.Loader loader)
            throws Exception {
        Node root = new Node(null);
        for (ExportItem f : favorites) node(root, f.folder).favs.add(f);
        for (ExportItem r : routes) node(root, r.folder).routes.add(r);

        try {
            s.setFeature(INDENT, true);
        } catch (Exception ignored) {
            // serializer sem indentação: o arquivo continua válido
        }
        s.startDocument("UTF-8", null);
        s.setPrefix("", NS);
        s.startTag(NS, "kml");
        s.startTag(NS, "Document");
        leaf(s, "name", "PokeLocation");
        StringBuilder sb = new StringBuilder(256);
        body(s, root, loader, sb);
        s.endTag(NS, "Document");
        s.endTag(NS, "kml");
        s.endDocument();
        s.flush();
    }

    private static Node node(Node root, String path) {
        if (path == null) return root;
        Node n = root;
        for (String seg : path.split("/")) {
            Node c = n.children.get(seg);
            if (c == null) n.children.put(seg, c = new Node(seg));
            n = c;
        }
        return n;
    }

    private static void body(XmlSerializer s, Node n, ExportItem.Loader loader, StringBuilder sb) throws Exception {
        for (ExportItem f : n.favs) {
            s.startTag(NS, "Placemark");
            leaf(s, "name", f.name);
            if (f.desc != null && !f.desc.isEmpty()) leaf(s, "description", f.desc);
            s.startTag(NS, "Point");
            sb.setLength(0);
            tuple(sb, f.lat, f.lng, f.alt);
            leaf(s, "coordinates", sb.toString());
            s.endTag(NS, "Point");
            s.endTag(NS, "Placemark");
        }
        for (ExportItem r : n.routes) {
            double[] pts = loader.load(r);
            if (pts == null) continue;
            s.startTag(NS, "Placemark");
            leaf(s, "name", r.name);
            s.startTag(NS, "LineString");
            s.startTag(NS, "coordinates");
            sb.setLength(0);
            for (int i = 0; i + 2 < pts.length; i += 3) {
                if (i > 0) sb.append(' ');
                tuple(sb, pts[i], pts[i + 1], pts[i + 2]);
                if (sb.length() > 8192) {   // escreve em pedaços: sem String do tamanho da rota
                    s.text(sb.toString());
                    sb.setLength(0);
                }
            }
            if (sb.length() > 0) s.text(sb.toString());
            s.endTag(NS, "coordinates");
            s.endTag(NS, "LineString");
            s.endTag(NS, "Placemark");
        }
        for (Node c : n.children.values()) {
            s.startTag(NS, "Folder");
            leaf(s, "name", c.name);
            body(s, c, loader, sb);
            s.endTag(NS, "Folder");
        }
    }

    /** LON,LAT,ALT (alt desconhecida = 0.0), mesmo formato de String.valueOf(double). */
    private static void tuple(StringBuilder sb, double lat, double lng, double alt) {
        sb.append(lng).append(',').append(lat).append(',').append(Double.isNaN(alt) ? 0.0 : alt);
    }

    private static void leaf(XmlSerializer s, String tag, String text) throws Exception {
        s.startTag(NS, tag);
        if (text != null) s.text(text);
        s.endTag(NS, tag);
    }
}
