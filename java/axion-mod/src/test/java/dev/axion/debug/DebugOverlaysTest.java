package dev.axion.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.axion.bridge.NativeBridge;
import dev.axion.debug.DebugOverlays.Overlay;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** C-67 — R-800, R-2280, ADR-121 : état des overlays de debug côté client. */
class DebugOverlaysTest {

    /** La liste du §31.4, dans son ordre : le rang d'un overlay y est son bit (ADR-121). */
    private static final List<String> PARAGRAPHE_31_4 = List.of(
            "colliders", "aabb", "bodies", "contacts", "normals", "joints", "limits", "raycasts",
            "wheels", "suspension", "bones", "nodes", "pivots", "sockets", "damage_zones",
            "subzones", "impacts", "deform_lattice", "deform_heat", "strain", "structure", "parts",
            "wear", "decals", "lod", "culling", "occluders", "occlusion_buffer", "shadow_map",
            "probe", "world_tiles", "center_of_mass", "network", "budgets", "quality");

    @Test
    @DisplayName("R-800 : tous les overlays sont éteints par défaut, le masque est vide")
    void eteintsParDefaut() {
        DebugOverlays overlays = new DebugOverlays();
        assertEquals(0L, overlays.mask());
        for (Overlay overlay : Overlay.values()) {
            assertFalse(overlays.isOn(overlay), overlay.commandName());
        }
    }

    @Test
    @DisplayName("R-2280 : un overlay s'allume et s'éteint, deux fois de suite sans effet de plus")
    void allumerEteindre() {
        DebugOverlays overlays = new DebugOverlays();
        overlays.set(Overlay.COLLIDERS, true);
        overlays.set(Overlay.COLLIDERS, true);
        assertTrue(overlays.isOn(Overlay.COLLIDERS));
        assertEquals(Overlay.COLLIDERS.bit(), overlays.mask());

        overlays.set(Overlay.COLLIDERS, false);
        overlays.set(Overlay.COLLIDERS, false);
        assertFalse(overlays.isOn(Overlay.COLLIDERS));
        assertEquals(0L, overlays.mask());
    }

    @Test
    @DisplayName("ADR-121 : le bit d'un overlay est le rang de son nom au §31.4")
    void bitAuRangDuParagraphe() {
        assertEquals(35, PARAGRAPHE_31_4.size(), "35 overlays : le masque de 64 bits suffit");
        for (Overlay overlay : Overlay.values()) {
            int rang = PARAGRAPHE_31_4.indexOf(overlay.commandName());
            assertTrue(rang >= 0, overlay.commandName() + " n'est pas un overlay du §31.4");
            assertEquals(1L << rang, overlay.bit(), overlay.commandName());
        }
    }

    @Test
    @DisplayName("A1 d'ADR-121 : chaque overlay porte le bit que le natif attend, et son rang le retrouve")
    void collidersCommeLeNatif() {
        assertEquals(NativeBridge.OVERLAY_COLLIDERS, Overlay.COLLIDERS.bit());
        assertEquals(NativeBridge.OVERLAY_AABB, Overlay.AABB.bit());
        assertEquals(NativeBridge.OVERLAY_BODIES, Overlay.BODIES.bit());
        assertEquals(NativeBridge.OVERLAY_CONTACTS, Overlay.CONTACTS.bit());
        assertEquals(NativeBridge.OVERLAY_NORMALS, Overlay.NORMALS.bit());
        assertEquals(NativeBridge.OVERLAY_WORLD_TILES, Overlay.WORLD_TILES.bit());
        assertEquals(NativeBridge.OVERLAY_CENTER_OF_MASS, Overlay.CENTER_OF_MASS.bit());
        assertEquals(7, Overlay.values().length, "les six overlays de M3 et colliders (ADR-146 §1)");
        for (Overlay overlay : Overlay.values()) {
            assertEquals(overlay, Overlay.ofRank(overlay.rank()), overlay.commandName());
        }
        assertEquals(null, Overlay.ofRank(5), "joints n'est pas implémenté");
    }
}
