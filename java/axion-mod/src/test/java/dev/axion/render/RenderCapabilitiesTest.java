package dev.axion.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.axion.render.RenderCapabilities.Availability;
import dev.axion.render.RenderCapabilities.Capability;
import dev.axion.render.RenderCapabilities.Entry;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** R-1493 — matrice de capacités du §19.2bis, ramenée à ce qui est livré. */
class RenderCapabilitiesTest {

    private static final BackendSelection.Selection VANILLA = new BackendSelection.Selection(
            BackendSelection.Kind.VANILLA, "render.backend = auto, backend natif indisponible : repli vanilla");

    private static final BackendSelection.Selection NATIVE =
            new BackendSelection.Selection(BackendSelection.Kind.NATIVE, "render.backend = native");

    @Test
    @DisplayName("R-1493 : chaque backend déclare toute la matrice du §19.2bis, dans son ordre")
    void matriceComplete() {
        for (BackendSelection.Selection selection : List.of(VANILLA, NATIVE)) {
            List<Capability> declarees = RenderCapabilities.of(selection).entries().stream()
                    .map(Entry::capability)
                    .toList();
            assertEquals(List.of(Capability.values()), declarees, selection.kind().name());
        }
    }

    @Test
    @DisplayName("R-1493 : en vanilla, ce que le backend ne fait pas par conception est indisponible, avec son repli")
    void vanillaIndisponiblesParConception() {
        RenderCapabilities vanilla = RenderCapabilities.of(VANILLA);
        for (Capability capacite : List.of(
                Capability.ADVANCED_MATERIALS, Capability.MATERIAL_MAPS, Capability.SSR, Capability.INSTANCING)) {
            assertEquals(Availability.UNAVAILABLE, vanilla.availability(capacite), capacite.name());
        }
        Entry instancing = vanilla.entries().get(Capability.INSTANCING.ordinal());
        assertTrue(instancing.detail().contains("draw calls individuels"), instancing.detail());
        // ADR-122 §7 : les cartes sans effet en vanilla y sont déclarées, avec ce qui reste.
        Entry cartes = vanilla.entries().get(Capability.MATERIAL_MAPS.ordinal());
        assertTrue(cartes.detail().contains("R-1513") && cartes.detail().contains("albedo et émissive"),
                cartes.detail());
    }

    @Test
    @DisplayName("R-1493 : rien n'est déclaré disponible avant d'être livré, et ce qui manque nomme son composant")
    void rienDeDisponibleAvantLivraison() {
        RenderCapabilities vanilla = RenderCapabilities.of(VANILLA);
        Set<Capability> disponibles = Set.copyOf(vanilla.entries().stream()
                .filter(entry -> entry.availability() == Availability.AVAILABLE)
                .map(Entry::capability)
                .toList());
        assertEquals(Set.of(Capability.LIGHTING), disponibles);
        for (Entry entry : vanilla.entries()) {
            if (entry.availability() == Availability.NOT_DELIVERED) {
                assertTrue(entry.detail().matches(".*\\bC-\\d+.*"), entry.detail());
            }
        }
    }

    @Test
    @DisplayName("R-1493 : le backend natif, pas encore livré, ne déclare aucune capacité")
    void natifNonLivre() {
        for (Entry entry : RenderCapabilities.of(NATIVE).entries()) {
            assertEquals(Availability.NOT_DELIVERED, entry.availability(), entry.capability().name());
            assertTrue(entry.detail().contains("C-60"), entry.detail());
        }
    }

    @Test
    @DisplayName("R-1493 : la description dit le backend et sa raison, puis groupe les capacités par état")
    void description() {
        List<String> lignes = RenderCapabilities.of(VANILLA).describe();

        assertEquals("backend VANILLA (" + VANILLA.reason() + ")", lignes.get(0));
        assertEquals(4, lignes.size(), String.join("\n", lignes));
        assertTrue(lignes.get(1).startsWith("disponibles : éclairage"), lignes.get(1));
        assertTrue(lignes.get(2).startsWith("indisponibles dans ce backend : "), lignes.get(2));
        assertTrue(lignes.get(2).contains("SSR"), lignes.get(2));
        assertTrue(lignes.get(3).startsWith("pas encore livrées : ombres AXION"), lignes.get(3));

        // Un état sans capacité n'a pas de ligne : rien d'indisponible ni de disponible en natif.
        List<String> natif = RenderCapabilities.of(NATIVE).describe();
        assertEquals(2, natif.size(), String.join("\n", natif));
        assertTrue(natif.get(1).startsWith("pas encore livrées : "), natif.get(1));
    }
}
