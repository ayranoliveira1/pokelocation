package com.pokelocation.app.gpx;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * GPX em streaming (XmlPullParser, namespaces, match por localName). Lê só name, desc, lat, lon, ele e a pasta
 * (1º filho de &lt;extensions&gt; com localName "folder"). O resto é pulado sem alocar. ele inválido é ignorado.
 */
public final class GpxReader {
    private final XmlPullParser p;
    private final ImportData d = new ImportData(false);
    private final ImportData.Buf buf = new ImportData.Buf();
    private final StringBuilder sb = new StringBuilder();

    private String name, desc, folder;

    private GpxReader(XmlPullParser p) { this.p = p; }

    public static ImportData read(InputStream in) throws IOException, XmlPullParserException {
        XmlPullParser p = Xml.newPullParser();
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
        p.setInput(new BufferedInputStream(in, 8192), null);
        return read(p);
    }

    /** Parser já configurado com FEATURE_PROCESS_NAMESPACES (injetável para testes). */
    public static ImportData read(XmlPullParser p) throws IOException, XmlPullParserException {
        return new GpxReader(p).run();
    }

    private ImportData run() throws IOException, XmlPullParserException {
        int ev = p.next();   // pula prólogo, comentários e espaço
        while (ev != XmlPullParser.START_TAG && ev != XmlPullParser.END_DOCUMENT) ev = p.next();
        if (ev != XmlPullParser.START_TAG || !"gpx".equals(p.getName())) throw new IOException("Not a valid GPX file");
        while ((ev = p.next()) != XmlPullParser.END_TAG && ev != XmlPullParser.END_DOCUMENT) {
            if (ev != XmlPullParser.START_TAG) continue;
            switch (p.getName()) {
                case "wpt": wpt(); break;
                case "rte": rte(); break;
                case "trk": trk(); break;
                default: skip(p);
            }
        }
        return d;
    }

    private void wpt() throws IOException, XmlPullParserException {
        double lat = attr("lat"), lon = attr("lon"), ele = Double.NaN;
        name = desc = folder = null;
        int ev;
        while ((ev = p.next()) != XmlPullParser.END_TAG) {
            if (ev == XmlPullParser.END_DOCUMENT) throw new IOException("Unexpected end");
            if (ev != XmlPullParser.START_TAG) continue;
            switch (p.getName()) {
                case "ele": ele = num(text(), Double.NaN); break;
                case "name": case "desc": case "extensions": common(); break;
                default: skip(p);
            }
        }
        d.addPoint(name, desc, folder, lat, lon, ele);
    }

    private void rte() throws IOException, XmlPullParserException {
        name = desc = folder = null;
        int ev;
        while ((ev = p.next()) != XmlPullParser.END_TAG) {
            if (ev == XmlPullParser.END_DOCUMENT) throw new IOException("Unexpected end");
            if (ev != XmlPullParser.START_TAG) continue;
            if ("rtept".equals(p.getName())) pt();
            else common();
        }
        if (buf.size() > 0) d.addLine(name, desc, folder, buf.take());
    }

    private void trk() throws IOException, XmlPullParserException {
        name = desc = folder = null;
        List<double[]> segs = new ArrayList<>(1);
        int ev;
        while ((ev = p.next()) != XmlPullParser.END_TAG) {
            if (ev == XmlPullParser.END_DOCUMENT) throw new IOException("Unexpected end");
            if (ev != XmlPullParser.START_TAG) continue;
            if ("trkseg".equals(p.getName())) {
                while ((ev = p.next()) != XmlPullParser.END_TAG) {
                    if (ev == XmlPullParser.END_DOCUMENT) throw new IOException("Unexpected end");
                    if (ev != XmlPullParser.START_TAG) continue;
                    if ("trkpt".equals(p.getName())) pt();
                    else skip(p);   // extensions do segmento: sem uso
                }
                if (buf.size() > 0) segs.add(buf.take());
            } else {
                common();
            }
        }
        if (!segs.isEmpty()) d.addTrack(name, desc, folder, segs);
    }

    /** name/desc/extensions do container corrente; qualquer outro filho é pulado. */
    private void common() throws IOException, XmlPullParserException {
        switch (p.getName()) {
            case "name": name = text(); break;
            case "desc": desc = text(); break;
            case "extensions": extensions(); break;
            default: skip(p);
        }
    }

    /** rtept/trkpt: só lat, lon e ele vão para o buffer. */
    private void pt() throws IOException, XmlPullParserException {
        double lat = attr("lat"), lon = attr("lon"), ele = Double.NaN;
        int ev;
        while ((ev = p.next()) != XmlPullParser.END_TAG) {
            if (ev == XmlPullParser.END_DOCUMENT) throw new IOException("Unexpected end");
            if (ev != XmlPullParser.START_TAG) continue;
            if ("ele".equals(p.getName())) ele = num(text(), Double.NaN);
            else skip(p);
        }
        buf.add(lat, lon, ele);
    }

    private void extensions() throws IOException, XmlPullParserException {
        int ev;
        while ((ev = p.next()) != XmlPullParser.END_TAG) {
            if (ev == XmlPullParser.END_DOCUMENT) throw new IOException("Unexpected end");
            if (ev != XmlPullParser.START_TAG) continue;
            if (folder == null && "folder".equals(p.getName())) folder = d.folder(text());
            else skip(p);
        }
    }

    private double attr(String n) { return num(p.getAttributeValue(null, n), 0); }

    /** Texto do elemento corrente (tolerante a filhos aninhados, que são ignorados); trim; null se vazio. */
    private String text() throws IOException, XmlPullParserException {
        return text(p, sb);
    }

    static String text(XmlPullParser p, StringBuilder sb) throws IOException, XmlPullParserException {
        sb.setLength(0);
        int depth = 1;
        while (depth > 0) {
            int ev = p.next();
            if (ev == XmlPullParser.TEXT && depth == 1) sb.append(p.getText());
            else if (ev == XmlPullParser.START_TAG) depth++;
            else if (ev == XmlPullParser.END_TAG) depth--;
            else if (ev == XmlPullParser.END_DOCUMENT) throw new IOException("Unexpected end");
        }
        int a = 0, b = sb.length();
        while (a < b && sb.charAt(a) <= ' ') a++;
        while (b > a && sb.charAt(b - 1) <= ' ') b--;
        return a == b ? null : sb.substring(a, b);
    }

    static void skip(XmlPullParser p) throws IOException, XmlPullParserException {
        int depth = 1;
        while (depth > 0) {
            switch (p.next()) {
                case XmlPullParser.START_TAG: depth++; break;
                case XmlPullParser.END_TAG: depth--; break;
                case XmlPullParser.END_DOCUMENT: throw new IOException("Unexpected end");
                default: break;
            }
        }
    }

    /** Número finito ou def (vazio, lixo, NaN, Infinity). */
    static double num(String s, double def) {
        if (s == null) return def;
        try {
            double v = Double.parseDouble(s.trim());
            return Double.isNaN(v) || Double.isInfinite(v) ? def : v;
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
