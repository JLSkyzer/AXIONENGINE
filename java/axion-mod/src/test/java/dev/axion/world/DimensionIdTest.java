package dev.axion.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

/** Épingle la convention d'id de dimension de la frontière (C-31 R-610, IF-03). */
class DimensionIdTest {

    @Test
    void memeDimensionMemeId() {
        assertEquals(
                DimensionId.of("minecraft:overworld"), DimensionId.of("minecraft:overworld"));
    }

    @Test
    void dimensionsDistinctesIdsDistincts() {
        long overworld = DimensionId.of("minecraft:overworld");
        long nether = DimensionId.of("minecraft:the_nether");
        long end = DimensionId.of("minecraft:the_end");
        assertNotEquals(overworld, nether);
        assertNotEquals(overworld, end);
        assertNotEquals(nether, end);
    }

    @Test
    void laChaineVideEstLeBiaisInitialFnv() {
        // FNV-1a sans octet consommé = le biais initial. Constante connue : une divergence
        // signalerait un changement d'algorithme d'encodage (donc d'incompatibilité).
        assertEquals(0xcbf29ce484222325L, DimensionId.of(""));
    }
}
