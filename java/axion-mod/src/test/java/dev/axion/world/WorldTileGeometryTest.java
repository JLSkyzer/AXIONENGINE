package dev.axion.world;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Épingle la géométrie pure des tuiles du monde (C-38, fiche 5.30 ; ADR-117). */
class WorldTileGeometryTest {

    @Test
    void sectionOfBlockEstUnFloorDiv() {
        assertEquals(0, WorldTileGeometry.sectionOfBlock(0));
        assertEquals(0, WorldTileGeometry.sectionOfBlock(15));
        assertEquals(1, WorldTileGeometry.sectionOfBlock(16));
        // Négatif : descend vers la section inférieure, sans trou autour de zéro.
        assertEquals(-1, WorldTileGeometry.sectionOfBlock(-1));
        assertEquals(-1, WorldTileGeometry.sectionOfBlock(-16));
        assertEquals(-2, WorldTileGeometry.sectionOfBlock(-17));
    }

    @Test
    void sectionOfWorldSuitLaPositionMonde() {
        assertEquals(0, WorldTileGeometry.sectionOfWorld(0.0));
        assertEquals(0, WorldTileGeometry.sectionOfWorld(15.9));
        assertEquals(1, WorldTileGeometry.sectionOfWorld(16.0));
        assertEquals(-1, WorldTileGeometry.sectionOfWorld(-0.5));
    }

    @Test
    void quantizeArrondiAuSeizieme() {
        assertEquals(0.5f, WorldTileGeometry.quantize(0.5));
        // 0.9375 = 15/16, déjà sur la grille (barrière).
        assertEquals(0.9375f, WorldTileGeometry.quantize(0.9375));
        // Une valeur légèrement hors grille est ramenée au 1/16 le plus proche.
        assertEquals(0.0625f, WorldTileGeometry.quantize(0.06));
        assertEquals(1.0f, WorldTileGeometry.quantize(0.97));
    }

    @Test
    void sectionRelativeBoxRetireLOrigineEtQuantifie() {
        // Une dalle inférieure (hauteur 0.5) au bloc monde (18, 5, 3) → section (1, 0, 0).
        double[] worldBox = {18.0, 5.0, 3.0, 19.0, 5.5, 4.0};
        float[] rel = WorldTileGeometry.sectionRelativeBox(worldBox, 1, 0, 0);
        // x : 18 - 16 = 2 ; 19 - 16 = 3. y et z inchangés (section 0 sur ces axes).
        assertArrayEquals(new float[] {2f, 5f, 3f, 3f, 5.5f, 4f}, rel, 0f);
    }

    @Test
    void sectionRelativeBoxGereLesSectionsNegatives() {
        // Bloc monde (-3, 0, 0) est dans la section (-1, 0, 0) ; origine -16.
        double[] worldBox = {-3.0, 0.0, 0.0, -2.0, 1.0, 1.0};
        float[] rel = WorldTileGeometry.sectionRelativeBox(worldBox, -1, 0, 0);
        // x : -3 - (-16) = 13 ; -2 - (-16) = 14.
        assertArrayEquals(new float[] {13f, 0f, 0f, 14f, 1f, 1f}, rel, 0f);
    }

    @Test
    void needsHeightfieldAuDelaDuPlafond() {
        assertFalse(WorldTileGeometry.needsHeightfield(WorldTileGeometry.MAX_TILE_BOXES));
        assertTrue(WorldTileGeometry.needsHeightfield(WorldTileGeometry.MAX_TILE_BOXES + 1));
    }
}
