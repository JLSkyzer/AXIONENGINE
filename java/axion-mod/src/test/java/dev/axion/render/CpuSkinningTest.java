package dev.axion.render;

import static dev.axion.render.TestMaterials.EMBEDDED;
import static dev.axion.render.TestMaterials.material;
import static dev.axion.render.TestMaterials.table;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.axion.asset.GeometryTransfer;
import dev.axion.asset.MaterialTransfer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** R-742 : le skinning du backend vanilla, sur le CPU, et son plafond de sommets. */
class CpuSkinningTest {

    private static final float EPSILON = 1e-6f;

    /** Os 0 : une translation de (1, 2, 3). Os 1 : un quart de tour autour de z, x vers y. */
    private static final float[] PALETTE = {
        1, 0, 0, 0, 1, 0, 0, 0, 1, 1, 2, 3,
        0, 1, 0, -1, 0, 0, 0, 0, 1, 0, 0, 0,
    };

    /**
     * Deux meshes skinnés de trois sommets ; ceux du second, en (1, v, 0) de normale +y, suivent l'os 0, les
     * os 0 et 1 à 0,2 et 0,8, puis l'os 1.
     */
    private static GeometryTransfer geometry() {
        TestGeometry.Mesh skinned = TestGeometry.white(0, GeometryTransfer.MESH_SKINNED);
        byte[] bytes = TestGeometry.bytes(skinned, skinned);
        int vertices = GeometryTransfer.HEADER_BYTES + 2 * GeometryTransfer.MESH_BYTES;
        int middle = vertices + 4 * GeometryTransfer.VERTEX_BYTES;
        bytes[middle + 33] = 1;
        bytes[middle + 36] = 51;
        bytes[middle + 37] = (byte) 204;
        bytes[vertices + 5 * GeometryTransfer.VERTEX_BYTES + 32] = 1;
        return GeometryTransfer.parse(bytes);
    }

    @Test
    @DisplayName("Os et poids se lisent dans le sommet, aux octets 32 et 36 (DM-04)")
    void osEtPoids() {
        GeometryTransfer geometry = geometry();
        assertEquals(0, geometry.bone(4, 0));
        assertEquals(1, geometry.bone(4, 1));
        assertEquals(0.2f, geometry.weight(4, 0), EPSILON);
        assertEquals(0.8f, geometry.weight(4, 1), EPSILON);
        assertEquals(0f, geometry.weight(4, 2));
        assertEquals(1, geometry.bone(5, 0));
        assertEquals(1f, geometry.weight(5, 0));
    }

    @Test
    @DisplayName("Chaque sommet suit ses os pondérés : sa position par leur matrice, sa normale par sa partie 3×3")
    void sommetsPortesParLeursOs() {
        CpuSkinning.Skinned skinned = new CpuSkinning(PALETTE).mesh(geometry(), 1);

        assertArrayEquals(new float[] {
                    2, 2, 3,
                    0.2f * 2 - 0.8f * 1, 0.2f * 3 + 0.8f * 1, 0.2f * 3,
                    -2, 1, 0,
                }, skinned.positions(), EPSILON, "le second mesh, du premier de ses sommets au dernier");
        assertArrayEquals(new float[] {0, 1, 0, -0.8f, 0.2f, 0, -1, 0, 0}, skinned.normals(), EPSILON,
                "la translation ne touche pas les normales");
    }

    @Test
    @DisplayName("Un mesh se skinne une fois par instance : sa surface et son émission partagent ses sommets")
    void unMeshSkinneUneFois() {
        GeometryTransfer geometry = geometry();
        CpuSkinning skin = new CpuSkinning(PALETTE);
        CpuSkinning.Skinned first = skin.mesh(geometry, 0);

        assertSame(first, skin.mesh(geometry, 0));
        assertArrayEquals(new float[] {1, 2, 3, 1, 3, 3, 1, 4, 3}, first.positions(), EPSILON);
    }

    @Test
    @DisplayName("Le compte : les meshes skinnés et visibles dessinés, chacun une fois")
    void sommetsComptes() {
        GeometryTransfer geometry = TestGeometry.of(
                TestGeometry.white(0, GeometryTransfer.MESH_SKINNED),
                TestGeometry.white(1, 0),
                TestGeometry.white(2, GeometryTransfer.MESH_SKINNED));
        // Le troisième est découpé au-delà de 1 : il ne laisse rien à l'écran, et ne se skinne pas.
        MaterialTransfer materials = table(List.of(material(), material(), material().cutout(2f)), EMBEDDED);
        RenderAsset asset =
                new RenderAsset(geometry, materials, MeshLooks.of(geometry, materials).looks(), Map.of());
        List<GeometryTransfer.Draw> draws = geometry.draws();

        assertEquals(3, CpuSkinning.vertices(asset, List.of(draws.get(0), draws.get(0), draws.get(1), draws.get(2))));
        assertEquals(0, CpuSkinning.vertices(asset, List.of(draws.get(1))));
    }

    @Test
    @DisplayName("Le plafond prend les plus proches d'abord ; la première qui le dépasse arrête le compte")
    void plafondLesPlusProchesDAbord() {
        assertArrayEquals(new boolean[] {false, true, false, false, true},
                CpuSkinning.admit(new int[] {10, 10, 10, 0, 10}, new float[] {5, 1, 3, 0.5f, 2}, 25),
                "à 1 puis 2 blocs : 20 sommets ; à 3, 30 dépasseraient 25 ; sans skin, rien n'est compté");
        assertArrayEquals(new boolean[] {false, false},
                CpuSkinning.admit(new int[] {30, 5}, new float[] {1, 2}, 20),
                "jamais une instance lointaine animée quand une plus proche ne l'est pas");
        assertArrayEquals(new boolean[] {true, false},
                CpuSkinning.admit(new int[] {10, 10}, new float[] {1, 1}, 15), "à égalité, l'ordre donné");
        assertArrayEquals(new boolean[] {true, true},
                CpuSkinning.admit(new int[] {10, 10}, new float[] {2, 1}, 20), "le plafond atteint, pas dépassé");
    }
}
