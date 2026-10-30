package com.pokelocation.app.pogo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class PogoAlertsActivityTest {
    private static Pogo.Rule rule(boolean world, boolean enabled) {
        Pogo.Rule r = new Pogo.Rule();
        r.id = "r1";
        r.name = "Velho";
        r.world = world;
        r.enabled = enabled;
        r.filter = new PogoFilter();
        r.filter.ivMin = 90.0;
        if (!world) {
            r.lat = -23.5;
            r.lng = -46.6;
            r.radiusKm = 2;
        }
        return r;
    }

    @Test public void editingNearKeepsIdEnabledAndCenter() {
        Pogo.Rule old = rule(false, false);
        PogoFilter f = PogoAlertsActivity.editorFilter(old);
        assertEquals(2, f.km);
        assertEquals(90.0, f.ivMin, 0);
        f.km = 5;
        f.ivMin = 100.0;
        Pogo.Rule r = PogoAlertsActivity.rule(old, f, "Novo", 10, 20);
        assertEquals("r1", r.id);
        assertFalse(r.enabled);
        assertEquals(-23.5, r.lat, 0);
        assertEquals(-46.6, r.lng, 0);
        assertFalse(r.world);
        assertEquals(5, r.radiusKm);
        assertEquals(100.0, r.filter.ivMin, 0);
        assertEquals("Novo", r.name);
        assertEquals(90.0, old.filter.ivMin, 0);
    }

    @Test public void worldGainingARadiusUsesTheCurrentPosition() {
        Pogo.Rule old = rule(true, true);
        PogoFilter f = PogoAlertsActivity.editorFilter(old);
        assertEquals(0, f.km);
        f.km = 1;
        Pogo.Rule r = PogoAlertsActivity.rule(old, f, "Novo", 10, 20);
        assertEquals("r1", r.id);
        assertTrue(r.enabled);
        assertFalse(r.world);
        assertEquals(1, r.radiusKm);
        assertEquals(10, r.lat, 0);
        assertEquals(20, r.lng, 0);
    }

    @Test public void missingSpeciesLabelsComeFromTheCatalog() {
        PogoFilter f = new PogoFilter();
        f.pokemonIds.add(25);
        f.pokemonIds.add(19);
        f.pokemonIds.add(999);
        f.pokemonIds.add(1);
        f.speciesForms.add(new int[]{19, 46});
        f.labels.put(PogoFilter.labelKey(1, -1), "Meu rótulo");
        List<Pogo.Species> catalog = Arrays.asList(new Pogo.Species(1, 0, "Bulbasaur", null),
                new Pogo.Species(19, 45, "Rattata", "Normal"), new Pogo.Species(19, 46, "Rattata", "Alola"),
                new Pogo.Species(25, 0, "Pikachu", null));
        PogoFilterForm.label(f, catalog, n -> n + " (qualquer forma)");
        assertEquals("Pikachu", f.labels.get("25|-1"));
        assertEquals("Rattata (qualquer forma)", f.labels.get("19|-1"));
        assertEquals("Rattata · Alola", f.labels.get("19|46"));
        assertNull(f.labels.get("999|-1"));
        assertEquals("Meu rótulo", f.labels.get("1|-1"));
    }

    @Test public void newRuleStartsEnabledWithoutId() {
        PogoFilter f = new PogoFilter();
        Pogo.Rule r = PogoAlertsActivity.rule(null, f, "", 10, 20);
        assertNull(r.id);
        assertTrue(r.enabled);
        assertTrue(r.world);
        assertEquals(10, r.lat, 0);
    }
}
