package dev.axion.render;

import static dev.axion.render.TestMaterials.EMBEDDED;
import static dev.axion.render.TestMaterials.material;
import static dev.axion.render.TestMaterials.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.axion.asset.GeometryTransfer;
import dev.axion.asset.MaterialTransfer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Les textures liées à chaque mesh d'un asset prêt (ADR-122 §7). */
class RenderAssetTest {

    private static final TextureBinding PLAIN = new TextureBinding("axion:texture/a/1/0", true, true);
    private static final TextureBinding CUT = new TextureBinding("axion:texture/a/1/0/cutout/3f000000", false, true);

    /** Trois meshes : opaque texturé, découpé sur la même image, sans texture. */
    private static RenderAsset asset(Map<TextureKey, TextureBinding> textures) {
        GeometryTransfer geometry = TestGeometry.of(
                TestGeometry.white(0, 0), TestGeometry.white(1, 0), TestGeometry.white(2, 0));
        MaterialTransfer materials = table(
                List.of(material().albedo(0), material().albedo(0).cutout(0.5f), material()), EMBEDDED);
        return new RenderAsset(geometry, materials, MeshLooks.of(geometry, materials).looks(), textures);
    }

    @Test
    @DisplayName("Chaque mesh reçoit la texture d'albedo, et la variante, qu'il désigne")
    void chaqueMeshRecoitSaTexture() {
        RenderAsset asset = asset(Map.of(TextureKey.plain(0), PLAIN, TextureKey.cutout(0, 0.5f), CUT));

        assertEquals(PLAIN, asset.albedo(0));
        assertEquals(CUT, asset.albedo(1));
        assertNull(asset.albedo(2), "un slot vide prend la texture neutre");
        assertEquals(PLAIN, asset.texture(TextureKey.plain(0)));
        assertNull(asset.texture(null));
    }

    @Test
    @DisplayName("Une texture absente — refusée, ou perdue au téléversement — laisse la texture neutre")
    void uneTextureAbsenteLaisseLaNeutre() {
        RenderAsset asset = asset(Map.of(TextureKey.plain(0), PLAIN));

        assertEquals(PLAIN, asset.albedo(0));
        assertNull(asset.albedo(1));
    }

    @Test
    @DisplayName("Un mesh émet son facteur sur du blanc sans texture d'émissive ; texture perdue, il n'émet pas")
    void lEmissionSuitSaTexture() {
        GeometryTransfer geometry = TestGeometry.of(
                TestGeometry.white(0, 0), TestGeometry.white(1, 0), TestGeometry.white(2, 0), TestGeometry.white(3, 0));
        MaterialTransfer materials = table(
                List.of(
                        material().emissive(MaterialTransfer.NO_TEXTURE, 1, 1, 1),
                        material().emissive(0, 1, 1, 1),
                        material().emissive(1, 1, 1, 1),
                        material()),
                EMBEDDED, EMBEDDED);
        TextureBinding glow = new TextureBinding("axion:texture/a/1/0", false, true);
        RenderAsset asset = new RenderAsset(
                geometry, materials, MeshLooks.of(geometry, materials).looks(), Map.of(TextureKey.plain(0), glow));

        assertTrue(asset.emits(0), "facteur seul");
        assertNull(asset.emission(0), "sur du blanc");
        assertTrue(asset.emits(1));
        assertEquals(glow, asset.emission(1));
        assertFalse(asset.emits(2), "texture d'émissive perdue : la neutre est noire (C-26)");
        assertFalse(asset.emits(3), "n'émet pas");
    }

    @Test
    @DisplayName("L'émission d'un matériau découpé passe par sa variante masquée, ou n'a pas lieu")
    void lEmissionDUnMateriauDecoupeEstMasquee() {
        GeometryTransfer geometry = TestGeometry.of(TestGeometry.white(0, 0));
        MaterialTransfer materials = table(
                List.of(material().albedo(0).cutout(0.5f).emissive(MaterialTransfer.NO_TEXTURE, 1, 1, 1)), EMBEDDED);
        List<MeshLook> looks = MeshLooks.of(geometry, materials).looks();
        TextureBinding mask = new TextureBinding("axion:texture/a/1/blanc/masque/0/3f000000", false, true);

        RenderAsset masked = new RenderAsset(
                geometry, materials, looks, Map.of(TextureKey.masked(MaterialTransfer.NO_TEXTURE, 0, 0.5f), mask));
        assertTrue(masked.emits(0));
        assertEquals(mask, masked.emission(0));

        RenderAsset lost = new RenderAsset(geometry, materials, looks, Map.of());
        assertFalse(lost.emits(0), "jamais de blanc sans masque : il brillerait dans les trous");
    }

    @Test
    @DisplayName("Les apparences vont une par mesh")
    void lesApparencesVontUneParMesh() {
        GeometryTransfer geometry = TestGeometry.of(TestGeometry.white(0, 0), TestGeometry.white(0, 0));
        MaterialTransfer materials = table(List.of(material()));
        List<MeshLook> one = MeshLooks.of(TestGeometry.of(TestGeometry.white(0, 0)), materials).looks();

        assertThrows(IllegalArgumentException.class, () -> new RenderAsset(geometry, materials, one, Map.of()));
    }
}
