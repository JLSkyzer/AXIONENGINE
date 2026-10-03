package dev.axion.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.axion.asset.MaterialTransfer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-273 : passe et shader vanilla par mode de mélange et de faces (ADR-122 §7, §19.10).
 */
class EntityShaderTest {

    @Test
    @DisplayName("Le mode de mélange donne la passe ; un mode inconnu est refusé")
    void leModeDeMelangeDonneLaPasse() {
        assertEquals(SurfacePass.OPAQUE, SurfacePass.of(MaterialTransfer.BLEND_OPAQUE));
        assertEquals(SurfacePass.CUTOUT, SurfacePass.of(MaterialTransfer.BLEND_CUTOUT));
        assertEquals(SurfacePass.TRANSLUCENT, SurfacePass.of(MaterialTransfer.BLEND_TRANSLUCENT));
        assertThrows(IllegalArgumentException.class, () -> SurfacePass.of(3));
    }

    @Test
    @DisplayName("Opaque : entity_solid, quelles que soient les faces")
    void opaqueEntitySolid() {
        assertEquals(EntityShader.SOLID, EntityShader.of(SurfacePass.OPAQUE, false));
        assertEquals(EntityShader.SOLID, EntityShader.of(SurfacePass.OPAQUE, true));
        assertEquals("entity_solid", EntityShader.SOLID.vanillaName());
    }

    @Test
    @DisplayName("Découpe : entity_cutout, ou entity_cutout_no_cull pour deux faces")
    void decoupeEntityCutout() {
        assertEquals(EntityShader.CUTOUT, EntityShader.of(SurfacePass.CUTOUT, false));
        assertEquals(EntityShader.CUTOUT_NO_CULL, EntityShader.of(SurfacePass.CUTOUT, true));
        assertEquals("entity_cutout", EntityShader.CUTOUT.vanillaName());
        assertEquals("entity_cutout_no_cull", EntityShader.CUTOUT_NO_CULL.vanillaName());
    }

    @Test
    @DisplayName("Une surface translucide n'a pas de shader des passes 1 et 2")
    void uneSurfaceTranslucideEstRefusee() {
        assertThrows(IllegalArgumentException.class, () -> EntityShader.of(SurfacePass.TRANSLUCENT, false));
        assertThrows(IllegalArgumentException.class, () -> EntityShader.of(SurfacePass.TRANSLUCENT, true));
    }
}
