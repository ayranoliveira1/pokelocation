package com.pokelocation.app.gpx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.kxml2.io.KXmlParser;
import org.xmlpull.v1.XmlPullParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;

public class GpxReaderTest {

    static XmlPullParser parser(Reader r) throws Exception {
        KXmlParser p = new KXmlParser();
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
        p.setInput(r);
        return p;
    }

    static ImportData gpx(String xml) throws Exception {
        return GpxReader.read(parser(new StringReader(xml)));
    }

    private static final String SAMPLE = "<?xml version=\"1.0\"?>\n<!-- comentário antes da raiz -->\n"
            + "<gpx version=\"1.1\" xmlns=\"http://www.topografix.com/GPX/1/1\" xmlns:gpsjoystick=\"https://theappninjas.com/gpsjoystick/gpx/1\">"
            + "<metadata><name>meta</name><author><name>x</name></author></metadata>"
            + "<wpt lat=\"-23.5\" lon=\"-46.6\"><ele>760.5</ele><name> Casa </name><desc>Rua X, 10</desc><sat>abc</sat>"
            + "<extensions><gpsjoystick:folder>Casa / Pokestops</gpsjoystick:folder></extensions></wpt>"
            + "<wpt lat=\"1\" lon=\"2\"><ele></ele><name></name></wpt>"
            + "<wpt lat=\"lixo\"><ele>NaN</ele></wpt>"
            + "<rte><name>R1</name><link href=\"x\"><text>t</text></link>"
            + "<rtept lat=\"1\" lon=\"1\"><name>p</name></rtept><rtept lat=\"2\" lon=\"2\"><ele>5</ele></rtept></rte>"
            + "<rte><name>vazia</name></rte>"
            + "<trk><name>T</name><extensions><pokelocation:folder xmlns:pokelocation=\"https://pokelocation.app/gpx/1\">F</pokelocation:folder></extensions>"
            + "<trkseg><trkpt lat=\"3\" lon=\"3\"/><trkpt lat=\"4\" lon=\"4\"><time>2020</time></trkpt></trkseg>"
            + "<trkseg/><trkseg><trkpt lat=\"5\" lon=\"5\"/></trkseg></trk>"
            + "</gpx>";

    @Test
    public void readsOnlyUsedFieldsTolerantly() throws Exception {
        ImportData d = gpx(SAMPLE);
        assertEquals(3, d.points.size());
        ImportData.Item w = d.points.get(0);
        assertEquals("Casa", w.name);
        assertEquals("Rua X, 10", w.desc);
        assertEquals("Casa/Pokestops", w.folder);
        assertEquals(-23.5, w.pts[0], 0);
        assertEquals(-46.6, w.pts[1], 0);
        assertEquals(760.5, w.pts[2], 0);

        ImportData.Item w2 = d.points.get(1);
        assertNull(w2.name);
        assertTrue(Double.isNaN(w2.pts[2]));          // ele vazio ignorado
        ImportData.Item w3 = d.points.get(2);
        assertEquals(0, w3.pts[0], 0);                 // lat inválida → 0
        assertEquals(0, w3.pts[1], 0);                 // lon ausente → 0
        assertTrue(Double.isNaN(w3.pts[2]));

        assertEquals(1, d.lines.size());               // rota vazia descartada
        ImportData.Item r = d.lines.get(0);
        assertEquals("R1", r.name);
        assertEquals(2, r.count());
        assertEquals(2.0, r.pts[3], 0);
        assertEquals(5.0, r.pts[5], 0);

        assertEquals(1, d.tracks.size());
        ImportData.Item t = d.tracks.get(0);
        assertEquals("T", t.name);
        assertEquals("F", t.folder);
        assertEquals(2, t.segs.size());                // segmento vazio descartado
        assertEquals(2, t.segs.get(0).length / 3);
        assertEquals(5.0, t.segs.get(1)[0], 0);
    }

    @Test
    public void folderStringsArePooled() throws Exception {
        ImportData d = gpx("<gpx><wpt lat='1' lon='1'><extensions><folder>A/B</folder></extensions></wpt>"
                + "<wpt lat='2' lon='2'><extensions><folder> A/ B</folder></extensions></wpt></gpx>");
        assertSame(d.points.get(0).folder, d.points.get(1).folder);
    }

    @Test
    public void emptyGpxIsEmpty() throws Exception {
        assertTrue(gpx("<gpx version='1.1'><metadata/></gpx>").isEmpty());
    }

    @Test
    public void wrongRootFails() throws Exception {
        try {
            gpx("<kml><Document/></kml>");
            fail();
        } catch (IOException expected) {
            // msg genérica na UI
        }
    }

    /** ~10 MB gerados sob demanda (sem String gigante) passam pelo parser em streaming. */
    @Test
    public void largeTrackStreams() throws Exception {
        final int n = 130_000;
        InputStream in = new InputStream() {
            final byte[] head = "<gpx><trk><name>big</name><trkseg>".getBytes(StandardCharsets.UTF_8);
            final byte[] tail = "</trkseg></trk></gpx>".getBytes(StandardCharsets.UTF_8);
            byte[] cur = head;
            int pos, i;

            @Override
            public int read() {
                while (pos >= cur.length) {
                    if (cur == tail) return -1;
                    pos = 0;
                    if (i < n) {
                        cur = ("<trkpt lat=\"" + (i * 1e-5) + "\" lon=\"" + (-i * 1e-5) + "\"><ele>" + i
                                + "</ele><time>2024-01-01T00:00:00Z</time></trkpt>\n").getBytes(StandardCharsets.UTF_8);
                        i++;
                    } else {
                        cur = tail;
                    }
                }
                return cur[pos++] & 0xFF;
            }
        };
        ImportData d = GpxReader.read(parser(new InputStreamReader(in, StandardCharsets.UTF_8)));
        double[] pts = d.tracks.get(0).segs.get(0);
        assertEquals(n * 3, pts.length);
        assertEquals(n - 1, pts[pts.length - 1], 0);
    }
}
