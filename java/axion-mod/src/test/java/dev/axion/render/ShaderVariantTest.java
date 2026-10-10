package dev.axion.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.axion.render.ShaderVariant.Define;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T-512, pour sa part pure — variantes par {@code #define}, liste fermée de R-762 (ADR-127 §4). */
class ShaderVariantTest {

    private static final String SOURCE = "// surface\n#version 330 core\nin vec3 a_position;\nvoid main() {}\n";

    @Test
    @DisplayName("Les définitions entrent juste après la ligne #version, que GLSL exige en tête")
    void lesDefinitionsSuiventLaLigneVersion() {
        String sources = ShaderVariant.of(Define.CUTOUT).apply(SOURCE);
        assertEquals("// surface\n#version 330 core\n#define CUTOUT\nin vec3 a_position;\nvoid main() {}\n", sources);
    }

    @Test
    @DisplayName("Plusieurs définitions entrent dans l'ordre de R-762, quel que soit l'ordre donné")
    void dansLOrdreDeR762() {
        String sources = ShaderVariant.of(Define.CUTOUT, Define.SKINNED).apply("#version 330 core\n");
        assertEquals("#version 330 core\n#define SKINNED\n#define CUTOUT\n", sources);
        assertEquals("SKINNED+CUTOUT", ShaderVariant.of(Define.CUTOUT, Define.SKINNED).key());
    }

    @Test
    @DisplayName("La variante de base laisse les sources telles quelles")
    void laBaseLaisseLesSources() {
        assertEquals(SOURCE, ShaderVariant.BASE.apply(SOURCE));
        assertEquals("BASE", ShaderVariant.BASE.key());
        assertFalse(ShaderVariant.BASE.has(Define.CUTOUT));
    }

    @Test
    @DisplayName("Des sources sans #version en tête sont refusées")
    void sansVersionRefuse() {
        assertThrows(IllegalArgumentException.class, () -> ShaderVariant.BASE.apply("void main() {}\n"));
        assertThrows(IllegalArgumentException.class,
                () -> ShaderVariant.BASE.apply("in vec3 a;\n#version 330 core\n"));
    }

    @Test
    @DisplayName("Deux variantes aux mêmes définitions sont égales : une clé de cache stable")
    void deuxVariantesEgales() {
        assertEquals(ShaderVariant.of(Define.CUTOUT, Define.SKINNED), ShaderVariant.of(Define.SKINNED, Define.CUTOUT));
        assertTrue(ShaderVariant.of(Define.CUTOUT).has(Define.CUTOUT));
    }
}
