package com.pokelocation.app.gpx;

import android.util.Xml;

import org.xmlpull.v1.XmlSerializer;

import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * GPX 1.1 em streaming (versão PokeLocation): todos os wpt (favoritos) e depois os rte (rotas), sem trk.
 * lat/lon sempre; &lt;ele&gt; só se ≠ 0 e conhecida; pasta em &lt;extensions&gt;&lt;pokelocation:folder&gt;.
 */
public final class GpxWriter {
    public static final String NS = "http://www.topografix.com/GPX/1/1";
    public static final String NS_PL = "https://pokelocation.app/gpx/1";

    private GpxWriter() {}

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
        boolean anyFolder = false;
        for (ExportItem f : favorites) anyFolder |= f.folder != null;
        for (ExportItem r : routes) anyFolder |= r.folder != null;

        s.startDocument("UTF-8", null);
        s.setPrefix("", NS);
        if (anyFolder) s.setPrefix("pokelocation", NS_PL);
        s.startTag(NS, "gpx");
        s.attribute(null, "version", "1.1");
        s.attribute(null, "creator", "PokeLocation");

        for (ExportItem f : favorites) {
            s.startTag(NS, "wpt");
            s.attribute(null, "lat", String.valueOf(f.lat));
            s.attribute(null, "lon", String.valueOf(f.lng));
            ele(s, f.alt);
            leaf(s, "name", f.name);
            if (f.desc != null && !f.desc.isEmpty()) leaf(s, "desc", f.desc);
            folder(s, f.folder);
            s.endTag(NS, "wpt");
        }

        for (ExportItem r : routes) {
            double[] pts = loader.load(r);
            if (pts == null) continue;
            s.startTag(NS, "rte");
            leaf(s, "name", r.name);
            folder(s, r.folder);
            for (int i = 0; i + 2 < pts.length; i += 3) {
                s.startTag(NS, "rtept");
                s.attribute(null, "lat", String.valueOf(pts[i]));
                s.attribute(null, "lon", String.valueOf(pts[i + 1]));
                ele(s, pts[i + 2]);
                s.endTag(NS, "rtept");
            }
            s.endTag(NS, "rte");
        }

        s.endTag(NS, "gpx");
        s.endDocument();
        s.flush();
    }

    private static void ele(XmlSerializer s, double alt) throws Exception {
        if (alt != 0 && !Double.isNaN(alt) && !Double.isInfinite(alt)) leaf(s, "ele", String.valueOf(alt));
    }

    private static void leaf(XmlSerializer s, String tag, String text) throws Exception {
        s.startTag(NS, tag);
        if (text != null) s.text(text);
        s.endTag(NS, tag);
    }

    private static void folder(XmlSerializer s, String folder) throws Exception {
        if (folder == null) return;
        s.startTag(NS, "extensions");
        s.startTag(NS_PL, "folder");
        s.text(folder);
        s.endTag(NS_PL, "folder");
        s.endTag(NS, "extensions");
    }
}
