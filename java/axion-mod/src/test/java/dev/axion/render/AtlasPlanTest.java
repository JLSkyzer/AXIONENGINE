package dev.axion.render;

import static dev.axion.render.TestMaterials.EMBEDDED;
import static dev.axion.render.TestMaterials.material;
import static dev.axion.render.TestMaterials.table;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.axion.asset.GeometryTransfer;
import dev.axion.asset.MaterialTransfer;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Quelles textures d'un asset peuvent entrer dans son atlas (ADR-122 T-c). */
class AtlasPlanTest {

    private static Set<TextureKey> unrepeated(MaterialTransfer materials, TestGeometry.Mesh... meshes) {
        GeometryTransfer geometry = TestGeometry.of(meshes);
        return AtlasPlan.unrepeated(geometry, MeshLooks.of(geometry, materials).looks());
    }

    @Test
    @DisplayName("Une texture que tous ses meshes lisent dans [0,1] peut entrer ; une texture répétée, non")
    void seuleUneTextureNonRepeteePeutEntrer() {
        MaterialTransfer materials = table(List.of(material().albedo(0), material().albedo(1)), EMBEDDED, EMBEDDED);

        assertEquals(Set.of(TextureKey.plain(0)),
                unrepeated(materials, TestGeometry.white(0, 0), TestGeometry.repeated(1)));
    }

    @Test
    @DisplayName("Un seul mesh qui répète une texture la garde hors de l'atlas, pour tous")
    void unSeulMeshQuiRepeteSuffit() {
        MaterialTransfer materials = table(List.of(material().albedo(0)), EMBEDDED);

        assertEquals(Set.of(), unrepeated(materials, TestGeometry.white(0, 0), TestGeometry.repeated(0)));
    }

    @Test
    @DisplayName("L'émission compte comme l'albedo, variante masquée comprise")
    void lEmissionCompteCommeLAlbedo() {
        MaterialTransfer materials = table(
                List.of(material().albedo(0).cutout(0.5f).emissive(1, 1, 1, 1), material().emissive(1, 1, 1, 1)),
                EMBEDDED, EMBEDDED);

        assertEquals(Set.of(TextureKey.cutout(0, 0.5f), TextureKey.masked(1, 0, 0.5f), TextureKey.plain(1)),
                unrepeated(materials, TestGeometry.white(0, 0), TestGeometry.white(1, 0)));
        assertEquals(Set.of(TextureKey.cutout(0, 0.5f), TextureKey.masked(1, 0, 0.5f)),
                unrepeated(materials, TestGeometry.white(0, 0), TestGeometry.repeated(1)));
    }

    @Test
    @DisplayName("Un mesh sans texture n'apporte rien")
    void unMeshSansTextureNApporteRien() {
        assertEquals(Set.of(), unrepeated(table(List.of(material())), TestGeometry.white(0, 0)));
    }
}
