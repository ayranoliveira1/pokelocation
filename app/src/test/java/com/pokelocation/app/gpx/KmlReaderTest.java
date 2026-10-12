package com.pokelocation.app.gpx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.StringReader;

public class KmlReaderTest {

    static ImportData kml(String xml) throws Exception {
        return KmlReader.read(GpxReaderTest.parser(new StringReader(xml)));
    }

    @Test
    public void tuple() {
        double[] t = new double[3];
        assertTrue(KmlReader.parseTuple("-46.6,-23.5,760", 0, 15, t));
        assertEquals(-23.5, t[0], 0);
        assertEquals(-46.6, t[1], 0);
        assertEquals(760, t[2], 0);
        assertTrue(KmlReader.parseTuple("1,2", 0, 3, t));
        assertTrue(Double.isNaN(t[2]));
        assertFalse(KmlReader.parseTuple("1", 0, 1, t));
        assertFalse(KmlReader.parseTuple("a,2", 0, 3, t));
        assertFalse(KmlReader.parseTuple("1,2,x", 0, 5, t));
    }

    @Test
    public void coordsSplitOnAnyWhitespace() {
        ImportData.Buf b = new ImportData.Buf();
        int n = KmlReader.parseCoords("\n\t1,2,3 \n 4,5\tbad 6,7,8\n", b, new double[3]);
        assertEquals(3, n);
        double[] pts = b.take();
        assertEquals(9, pts.length);
        assertEquals(2, pts[0], 0);
        assertEquals(1, pts[1], 0);
        assertEquals(8, pts[8], 0);
    }

    @Test
    public void foldersGeometriesAndSkipped() throws Exception {
        ImportData d = kml("<?xml version='1.0'?><kml xmlns='http://www.opengis.net/kml/2.2'><Document><name>Doc</name>"
                + "<Placemark><name>Raiz</name><description>d</description><Point><coordinates> -46.6,-23.5,10 </coordinates></Point></Placemark>"
                + "<Folder><name>A</name>"
                + "  <Folder><name>B</name>"
                + "    <Placemark><name>Linha</name><LineString><coordinates>\n1,1,0\n2,2,0\n\t3,3</coordinates></LineString></Placemark>"
                + "  </Folder>"
                + "  <Folder><name> </name><Placemark><name>Herda</name><Point><coordinates>5,5</coordinates></Point></Placemark></Folder>"
                + "  <Placemark><name>Multi</name><MultiGeometry><Point><coordinates>7,7</coordinates></Point>"
                + "     <LineString><coordinates>8,8 9,9</coordinates></LineString></MultiGeometry></Placemark>"
                + "  <Placemark><name>Poly</name><Polygon><outerBoundaryIs/></Polygon></Placemark>"
                + "  <Placemark><name>Ruim</name><Point><coordinates>x,y</coordinates></Point></Placemark>"
                + "</Folder></Document></kml>");
        assertEquals(2, d.skipped);
        assertEquals(3, d.points.size());
        assertEquals(2, d.lines.size());

        ImportData.Item p0 = d.points.get(0);
        assertEquals("Raiz", p0.name);
        assertEquals("d", p0.desc);
        assertNull(p0.folder);
        assertEquals(-23.5, p0.pts[0], 0);
        assertEquals(-46.6, p0.pts[1], 0);
        assertEquals(10, p0.pts[2], 0);

        assertEquals("A", d.points.get(1).folder);      // Folder sem nome herda o caminho
        assertEquals("Multi", d.points.get(2).name);

        ImportData.Item l0 = d.lines.get(0);
        assertEquals("A/B", l0.folder);
        assertEquals(3, l0.count());
        assertEquals("Multi", d.lines.get(1).name);
        assertEquals("A", d.lines.get(1).folder);
    }

    @Test
    public void prefixedElements() throws Exception {
        ImportData d = kml("<kml:kml xmlns:kml='http://www.opengis.net/kml/2.2'><kml:Document>"
                + "<kml:Placemark><kml:name>P</kml:name><kml:Point><kml:coordinates>1,2</kml:coordinates></kml:Point></kml:Placemark>"
                + "</kml:Document></kml:kml>");
        assertEquals(1, d.points.size());
        assertEquals("P", d.points.get(0).name);
    }

    @Test
    public void emptyKml() throws Exception {
        assertTrue(kml("<kml><Document><name>x</name></Document></kml>").isEmpty());
    }
}
