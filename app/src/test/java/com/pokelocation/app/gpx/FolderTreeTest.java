package com.pokelocation.app.gpx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class FolderTreeTest {

    @Test
    public void normalize() {
        assertEquals("A/B", FolderTree.normalize("  A / /B "));
        assertEquals("Casa/Pokestops", FolderTree.normalize("Casa/Poke\tstops\n"));   // \n \r \t removidos
        assertNull(FolderTree.normalize(" / "));
        assertNull(FolderTree.normalize(null));
    }

    @Test
    public void depth() {
        assertEquals(0, FolderTree.depth(null));
        assertEquals(1, FolderTree.depth("A"));
        assertEquals(3, FolderTree.depth("A/B/C"));
    }

    @Test
    public void flattenWithoutFoldersKeepsItems() {
        List<String> items = Arrays.asList("x", "y");
        List<Object> out = FolderTree.flatten(items, s -> null, "");
        assertEquals(items, out);
    }

    @Test
    public void flattenGroupsDfsThenNoFolder() {
        // item = "nome|pasta"
        List<String> items = Arrays.asList("a|A/B", "n|", "b|A", "c|C", "d|A/B");
        List<Object> out = FolderTree.flatten(items, s -> {
            String f = s.substring(s.indexOf('|') + 1);
            return f.isEmpty() ? null : f;
        }, "p:");
        // A(h) b, A/B(h) a d, C(h) c, n
        assertEquals(8, out.size());
        FolderTree.Header a = (FolderTree.Header) out.get(0);
        assertEquals("A", a.title);
        assertEquals(0, a.level);
        assertEquals("p:A", a.key);
        assertSame("b|A", out.get(1));
        FolderTree.Header ab = (FolderTree.Header) out.get(2);
        assertEquals("B", ab.title);
        assertEquals("A/B", ab.path);
        assertEquals(1, ab.level);
        assertSame("a|A/B", out.get(3));
        assertSame("d|A/B", out.get(4));
        assertTrue(out.get(5) instanceof FolderTree.Header);
        assertSame("c|C", out.get(6));
        assertSame("n|", out.get(7));
    }
}
