package com.pokelocation.app.gpx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.kxml2.io.KXmlSerializer;
import org.xmlpull.v1.XmlSerializer;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class WriterRoundTripTest {

    private static final List<ExportItem> FAVS = Arrays.asList(
            ExportItem.favorite("1", "Casa", "Rua X, 10", -23.5, -46.6, 760, "Pasta/Sub"),
            ExportItem.favorite("2", "Zero", "", 0.0, 0.0, 0, null));
    private static final List<ExportItem> ROUTES = Arrays.asList(
            ExportItem.route("r1", "Rota 1", 2, "Pasta"),
            ExportItem.markers(3, "Landmark", 1));

    private static final ExportItem.Loader LOADER = it -> it.kind == ExportItem.MARKERS
            ? new double[]{9, 9, Double.NaN}
            : new double[]{1, 2, Double.NaN, 3, 4, 5};

    private static String write(boolean kml, List<ExportItem> f, List<ExportItem> r) throws Exception {
        StringWriter w = new StringWriter();
        XmlSerializer s = new KXmlSerializer();
        s.setOutput(w);
        if (kml) KmlWriter.write(s, f, r, LOADER);
        else GpxWriter.write(s, f, r, LOADER);
        return w.toString();
    }

    @Test
    public void gpxFormat() throws Exception {
        String x = write(false, FAVS, ROUTES);
        assertTrue(x.contains("xmlns=\"http://www.topografix.com/GPX/1/1\""));
        assertTrue(x.contains("xmlns:pokelocation=\"https://pokelocation.app/gpx/1\""));
        assertTrue(x.contains("<pokelocation:folder>Pasta/Sub</pokelocation:folder>"));
        assertTrue(x.contains("lat=\"0.0\" lon=\"0.0\""));     // lat/lon sempre
        assertFalse(x.contains("NaN"));
        assertFalse(x.contains("<trk"));
        assertTrue(x.indexOf("<wpt") < x.indexOf("<rte"));
    }

    @Test
    public void gpxWithoutFoldersHasNoExtraNamespace() throws Exception {
        String x = write(false, Collections.singletonList(FAVS.get(1)), Collections.emptyList());
        assertFalse(x.contains("pokelocation.app"));
    }

    @Test
    public void gpxRoundTrip() throws Exception {
        ImportData d = GpxReader.read(GpxReaderTest.parser(new StringReader(write(false, FAVS, ROUTES))));
        assertEquals(2, d.points.size());
        ImportData.Item c = d.points.get(0);
        assertEquals("Casa", c.name);
        assertEquals("Rua X, 10", c.desc);
        assertEquals("Pasta/Sub", c.folder);
        assertEquals(760, c.pts[2], 0);
        assertTrue(Double.isNaN(d.points.get(1).pts[2]));   // ele 0 não é escrita
        assertNull(d.points.get(1).desc);
        assertEquals(2, d.lines.size());
        assertEquals("Pasta", d.lines.get(0).folder);
        assertEquals(2, d.lines.get(0).count());
        assertEquals(5, d.lines.get(0).pts[5], 0);
        assertEquals("Landmark", d.lines.get(1).name);
    }

    @Test
    public void kmlRoundTrip() throws Exception {
        String x = write(true, FAVS, ROUTES);
        assertTrue(x.contains("<name>PokeLocation</name>"));
        assertTrue(x.contains("-46.6,-23.5,760.0"));
        assertTrue(x.contains("2.0,1.0,0.0 4.0,3.0,5.0"));    // ALT 0.0 se NaN, 1 espaço entre tuplas
        // sem pasta antes das pastas
        assertTrue(x.indexOf("Zero") < x.indexOf("<Folder>"));
        assertTrue(x.indexOf("Landmark") < x.indexOf("<Folder>"));

        ImportData d = KmlReader.read(GpxReaderTest.parser(new StringReader(x)));
        assertEquals(0, d.skipped);
        assertEquals(2, d.points.size());
        assertEquals(2, d.lines.size());
        ImportData.Item casa = null;
        for (ImportData.Item it : d.points) if ("Casa".equals(it.name)) casa = it;
        assertEquals("Pasta/Sub", casa.folder);
        assertEquals("Rua X, 10", casa.desc);
        ImportData.Item rota = null;
        for (ImportData.Item it : d.lines) if ("Rota 1".equals(it.name)) rota = it;
        assertEquals("Pasta", rota.folder);
        assertEquals(2, rota.count());
    }
}
