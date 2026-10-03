package dev.axion.render;

import static dev.axion.render.TestMaterials.EMBEDDED;
import static dev.axion.render.TestMaterials.material;
import static dev.axion.render.TestMaterials.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
    @DisplayName("Les apparences vont une par mesh")
    void lesApparencesVontUneParMesh() {
        GeometryTransfer geometry = TestGeometry.of(TestGeometry.white(0, 0), TestGeometry.white(0, 0));
        MaterialTransfer materials = table(List.of(material()));
        List<MeshLook> one = MeshLooks.of(TestGeometry.of(TestGeometry.white(0, 0)), materials).looks();

        assertThrows(IllegalArgumentException.class, () -> new RenderAsset(geometry, materials, one, Map.of()));
    }
}
