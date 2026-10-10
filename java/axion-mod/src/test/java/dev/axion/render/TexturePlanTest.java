package dev.axion.render;

import static dev.axion.asset.MaterialTransfer.BLEND_CUTOUT;
import static dev.axion.asset.MaterialTransfer.BLEND_OPAQUE;
import static dev.axion.asset.MaterialTransfer.BLEND_TRANSLUCENT;
import static dev.axion.asset.MaterialTransfer.NO_TEXTURE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.axion.asset.MaterialTransfer;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Les textures qu'un asset charge : pour le backend vanilla (ADR-122 §7, R-1513), puis pour le natif (ADR-127 §5). */
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
    @DisplayName("L'émissive d'un matériau découpé texturé est masquée par son albedo, même sans texture d'émissive")
    void lEmissiveDUnMateriauDecoupeEstMasquee() {
        assertEquals(TextureKey.masked(1, 0, 0.5f), TexturePlan.emissive(material(0, 1, BLEND_CUTOUT, 0.5f, 1, 2)));
        assertEquals(TextureKey.masked(NO_TEXTURE, 0, 0.5f),
                TexturePlan.emissive(material(0, NO_TEXTURE, BLEND_CUTOUT, 0.5f, 1, 2)), "du blanc, masqué");
        assertEquals(TextureKey.plain(1), TexturePlan.emissive(material(NO_TEXTURE, 1, BLEND_CUTOUT, 0.5f, 1, 2)),
                "sans albedo texturé, rien à masquer");
        assertEquals(TextureKey.plain(1), TexturePlan.emissive(material(0, 1, BLEND_OPAQUE, 0.5f, 1, 2)));
        assertNull(TexturePlan.emissive(material(0, 1, BLEND_CUTOUT, 0.5f, 1, 0)), "aucune émission");
        assertNull(TexturePlan.emissive(material(0, NO_TEXTURE, BLEND_OPAQUE, 0.5f, 1, 2)), "facteur seul, sur du blanc");
    }

    @Test
    @DisplayName("Les textures à charger comprennent la variante découpée et l'émissive masquée, chacune une fois")
    void lesClesComprennentLEmissiveMasquee() {
        MaterialTransfer table = TestMaterials.table(
                List.of(
                        TestMaterials.material().albedo(0).cutout(0.5f).emissive(1, 1, 0.5f, 0),
                        TestMaterials.material().albedo(0).cutout(0.5f).emissive(1, 1, 0.5f, 0)),
                TestMaterials.EMBEDDED, TestMaterials.EMBEDDED);
        assertEquals(List.of(TextureKey.cutout(0, 0.5f), TextureKey.masked(1, 0, 0.5f)), TexturePlan.keys(table));
    }

    @Test
    @DisplayName("ADR-127 §5 : le backend natif charge en plus la normale et l'ORM ; le vanilla, jamais (R-1513)")
    void lesCartesDuNatif() {
        MaterialTransfer table = TestMaterials.table(
                List.of(TestMaterials.material().albedo(0).normal(1).orm(2)),
                TestMaterials.EMBEDDED, TestMaterials.EMBEDDED, TestMaterials.EMBEDDED);
        assertEquals(List.of(TextureKey.plain(0)), TexturePlan.keys(table));
        assertEquals(List.of(TextureKey.plain(0)), TexturePlan.keys(table, false));
        assertEquals(List.of(TextureKey.plain(0), TextureKey.plain(1), TextureKey.plain(2)), TexturePlan.keys(table, true));
        MaterialTransfer.Material sansCartes = material(0, NO_TEXTURE, BLEND_OPAQUE, 0.5f, 1, 0);
        assertEquals(TextureKey.plain(7), TexturePlan.normal(sansCartes));
        assertEquals(TextureKey.plain(7), TexturePlan.orm(sansCartes));
        MaterialTransfer.Material vide = TestMaterials.table(List.of(TestMaterials.material())).materials().get(0);
        assertNull(TexturePlan.normal(vide));
        assertNull(TexturePlan.orm(vide));
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
