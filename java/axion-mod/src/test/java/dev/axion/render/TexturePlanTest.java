package dev.axion.render;

import static dev.axion.asset.MaterialTransfer.BLEND_CUTOUT;
import static dev.axion.asset.MaterialTransfer.BLEND_OPAQUE;
import static dev.axion.asset.MaterialTransfer.BLEND_TRANSLUCENT;
import static dev.axion.asset.MaterialTransfer.NO_TEXTURE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.axion.asset.MaterialTransfer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Les textures qu'un asset charge pour le backend vanilla (ADR-122 §7, R-1513). */
class TexturePlanTest {

    /** Un matériau : slots d'albedo et d'émissive, mode de mélange, seuil, alphas et émission. */
    static MaterialTransfer.Material material(
            int albedo, int emissive, int blend, float cutoff, float albedoAlpha, float glow) {
        return new MaterialTransfer.Material(
                0L, albedo, 7, 7, emissive, 7, 7,
                new float[] {1, 1, 1, albedoAlpha},
                new float[] {glow, 0, 0},
                0, 1, 1, 1, cutoff, 0, 0, 0, 0, 0,
                blend, 0, 0, 0, 0xFFFF);
    }

    @Test
    @DisplayName("L'albedo suit le mode de mélange : tel quel, ou binarisé au seuil du matériau")
    void lAlbedoSuitLeModeDeMelange() {
        assertEquals(TextureKey.plain(0), TexturePlan.albedo(material(0, NO_TEXTURE, BLEND_OPAQUE, 0.5f, 1, 0)));
        assertEquals(TextureKey.plain(0), TexturePlan.albedo(material(0, NO_TEXTURE, BLEND_TRANSLUCENT, 0.5f, 1, 0)));
        assertEquals(TextureKey.cutout(0, 1.0f), TexturePlan.albedo(material(0, NO_TEXTURE, BLEND_CUTOUT, 0.5f, 0.5f, 0)),
                "alpha × 0,5 ≥ 0,5 exige alpha ≥ 1");
        assertNull(TexturePlan.albedo(material(NO_TEXTURE, NO_TEXTURE, BLEND_OPAQUE, 0.5f, 1, 0)));
    }

    @Test
    @DisplayName("Un albedo d'alpha nul ne garde rien, sauf à seuil nul")
    void unAlbedoTransparentNeGardeRien() {
        assertEquals(Float.POSITIVE_INFINITY,
                TexturePlan.cutoutThreshold(material(0, NO_TEXTURE, BLEND_CUTOUT, 0.3f, 0, 0)));
        assertEquals(0.0f, TexturePlan.cutoutThreshold(material(0, NO_TEXTURE, BLEND_CUTOUT, 0, 0, 0)));
    }

    @Test
    @DisplayName("L'émissive ne se charge que si le matériau émet")
    void lEmissiveNeSeChargeQueSiLeMateriauEmet() {
        assertNull(TexturePlan.emissive(material(NO_TEXTURE, 1, BLEND_OPAQUE, 0.5f, 1, 0)), "facteur nul");
        assertEquals(TextureKey.plain(1), TexturePlan.emissive(material(NO_TEXTURE, 1, BLEND_OPAQUE, 0.5f, 1, 2)));
    }

    @Test
    @DisplayName("Normal, ORM, height et damage ne se chargent pas (R-1513)")
    void lesAutresSlotsNeSeChargentPas() {
        // Les slots 7 du matériau d'essai désignent normal, ORM, height et damage : aucun n'apparaît.
        MaterialTransfer.Material sansAlbedo = material(NO_TEXTURE, NO_TEXTURE, BLEND_OPAQUE, 0.5f, 1, 0);
        assertNull(TexturePlan.albedo(sansAlbedo));
        assertNull(TexturePlan.emissive(sansAlbedo));
    }
}
