package com.pokelocation.app.gpx;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;

/**
 * KML em streaming (match por localName, então aceita "kml:Placemark"). Folder aninhado vira caminho "A/B";
 * Placemark → Point / LineString / MultiGeometry (vários itens com o mesmo nome/desc/pasta).
 * Placemark sem geometria válida conta em {@link ImportData#skipped}. KMZ fora (DECISIONS).
 */
public final class KmlReader {
    private final XmlPullParser p;
    private final ImportData d = new ImportData(true);
    private final ImportData.Buf buf = new ImportData.Buf();
    private final StringBuilder sb = new StringBuilder();
    private final double[] tuple = new double[3];

    // geometrias do Placemark corrente (nome/desc podem vir depois delas)
    private final ArrayList<double[]> pmPoints = new ArrayList<>(), pmLines = new ArrayList<>();

    private KmlReader(XmlPullParser p) { this.p = p; }

    public static ImportData read(InputStream in) throws IOException, XmlPullParserException {
        XmlPullParser p = Xml.newPullParser();
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
        p.setInput(new BufferedInputStream(in, 8192), null);
        return read(p);
    }

    /** Parser já configurado com FEATURE_PROCESS_NAMESPACES (injetável para testes). */
    public static ImportData read(XmlPullParser p) throws IOException, XmlPullParserException {
        return new KmlReader(p).run();
    }

    private ImportData run() throws IOException, XmlPullParserException {
        int ev = p.next();
        while (ev != XmlPullParser.START_TAG && ev != XmlPullParser.END_DOCUMENT) ev = p.next();
        if (ev != XmlPullParser.START_TAG) throw new IOException("Not a valid KML file");
        container(null, false);
        return d;
    }

    /**
     * Percorre um container (kml, Document, Folder, …) até o END_TAG dele.
     * Em Folder, o filho direto &lt;name&gt; define o caminho dos itens que vêm depois dele.
     */
    private void container(String parentPath, boolean isFolder) throws IOException, XmlPullParserException {
        String path = parentPath;
        int ev;
        while ((ev = p.next()) != XmlPullParser.END_TAG) {
            if (ev == XmlPullParser.END_DOCUMENT) return;
            if (ev != XmlPullParser.START_TAG) continue;
            switch (p.getName()) {
                case "name":
                    if (isFolder) {
                        String n = GpxReader.text(p, sb);
                        if (n != null) path = d.folder(parentPath == null ? n : parentPath + "/" + n);
                    } else {
                        GpxReader.skip(p);
                    }
                    break;
                case "Folder": container(path, true); break;
                case "Document": case "kml": container(path, false); break;
                case "Placemark": placemark(path); break;
                default: GpxReader.skip(p);
            }
        }
    }

    private void placemark(String folder) throws IOException, XmlPullParserException {
        String name = null, desc = null;
        pmPoints.clear();
        pmLines.clear();
        int ev;
        while ((ev = p.next()) != XmlPullParser.END_TAG) {
            if (ev == XmlPullParser.END_DOCUMENT) throw new IOException("Unexpected end");
            if (ev != XmlPullParser.START_TAG) continue;
            switch (p.getName()) {
                case "name": name = GpxReader.text(p, sb); break;
                case "description": desc = GpxReader.text(p, sb); break;
                default: geometry();
            }
        }
        if (pmPoints.isEmpty() && pmLines.isEmpty()) {
            d.skipped++;
            return;
        }
        for (double[] t : pmPoints) d.addPoint(name, desc, folder, t[0], t[1], t[2]);
        for (double[] l : pmLines) d.addLine(name, desc, folder, l);
    }

    /** Elemento corrente: Point / LineString / MultiGeometry (recursivo); qualquer outro é pulado. */
    private void geometry() throws IOException, XmlPullParserException {
        String tag = p.getName();
        if (!"Point".equals(tag) && !"LineString".equals(tag) && !"MultiGeometry".equals(tag)) {
            GpxReader.skip(p);
            return;
        }
        int ev;
        while ((ev = p.next()) != XmlPullParser.END_TAG) {
            if (ev == XmlPullParser.END_DOCUMENT) throw new IOException("Unexpected end");
            if (ev != XmlPullParser.START_TAG) continue;
            if ("MultiGeometry".equals(tag)) {
                geometry();
            } else if ("coordinates".equals(p.getName())) {
                coordinates();
                if ("Point".equals(tag)) {
                    // Point: só a 1ª tupla
                    if (buf.size() > 0) {
                        double[] all = buf.take();
                        pmPoints.add(new double[]{all[0], all[1], all[2]});
                    }
                } else if (buf.size() > 0) {
                    pmLines.add(buf.take());
                }
            } else {
                GpxReader.skip(p);
            }
        }
    }

    /** Lê o texto de &lt;coordinates&gt; num StringBuilder reaproveitado e joga as tuplas válidas no buffer. */
    private void coordinates() throws IOException, XmlPullParserException {
        sb.setLength(0);
        int depth = 1;
        while (depth > 0) {
            int ev = p.next();
            if (ev == XmlPullParser.TEXT && depth == 1) sb.append(p.getText());
            else if (ev == XmlPullParser.START_TAG) depth++;
            else if (ev == XmlPullParser.END_TAG) depth--;
            else if (ev == XmlPullParser.END_DOCUMENT) throw new IOException("Unexpected end");
        }
        parseCoords(sb, buf, tuple);
        if (sb.capacity() > 64 * 1024) {   // não segura o maior LineString do arquivo
            sb.setLength(0);
            sb.trimToSize();
        }
    }

    /** Tuplas separadas por espaço em branco (\s+); inválidas descartadas. Devolve quantas entraram. */
    static int parseCoords(CharSequence s, ImportData.Buf out, double[] tmp3) {
        int n = s.length(), i = 0, added = 0;
        while (i < n) {
            while (i < n && Character.isWhitespace(s.charAt(i))) i++;
            int a = i;
            while (i < n && !Character.isWhitespace(s.charAt(i))) i++;
            if (a < i && parseTuple(s, a, i, tmp3)) {
                out.add(tmp3[0], tmp3[1], tmp3[2]);
                added++;
            }
        }
        return added;
    }

    /** "lon,lat[,alt]" em s[a,b) → out3 = {lat, lng, alt}; alt ausente = NaN. false se &lt; 2 partes ou número inválido. */
    static boolean parseTuple(CharSequence s, int a, int b, double[] out3) {
        int c1 = indexOf(s, ',', a, b);
        if (c1 < 0) return false;
        int c2 = indexOf(s, ',', c1 + 1, b);
        double lon = GpxReader.num(s.subSequence(a, c1).toString(), Double.NaN);
        double lat = GpxReader.num(s.subSequence(c1 + 1, c2 < 0 ? b : c2).toString(), Double.NaN);
        if (Double.isNaN(lon) || Double.isNaN(lat)) return false;
        double alt = Double.NaN;
        if (c2 >= 0) {
            int c3 = indexOf(s, ',', c2 + 1, b);
            String as = s.subSequence(c2 + 1, c3 < 0 ? b : c3).toString();
            alt = GpxReader.num(as, Double.NaN);
            if (Double.isNaN(alt) && !as.trim().isEmpty()) return false;   // alt presente mas lixo = tupla inválida
        }
        out3[0] = lat;
        out3[1] = lon;
        out3[2] = alt;
        return true;
    }

    private static int indexOf(CharSequence s, char ch, int a, int b) {
        for (int i = a; i < b; i++) if (s.charAt(i) == ch) return i;
        return -1;
    }
}
