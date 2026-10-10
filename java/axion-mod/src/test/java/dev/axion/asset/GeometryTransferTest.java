package dev.axion.asset;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * Épingle la lecture du transfert de géométrie (ADR-119 §3).
 *
 * <p>Les octets sont écrits à la main d'après la disposition ratifiée, comme le test de
 * disposition côté Rust : c'est la disposition qui est vérifiée, pas un lecteur contre un
 * encodeur de sa propre main.
 */
class GeometryTransferTest {

    private static final float EPS = 1e-6f;

    /** Un mesh transparent à double face, trois sommets, un triangle, un dessin translaté. */
    private static byte[] transfert() {
        return transfert(0);
    }

    /** Le même, ses UV décodées sous la plage {@code uv0Range} (ADR-122 §4). */
    private static byte[] transfert(int uv0Range) {
        ByteBuffer out = ByteBuffer.allocate(16 + 48 + 3 * 48 + 3 * 4 + 64).order(ByteOrder.LITTLE_ENDIAN);
        // En-tête : meshes, sommets, indices, dessins.
        out.putInt(1).putInt(3).putInt(3).putInt(1);

        // MeshDesc (48 o) : plages, matériau 7, LOD 0, drapeaux, AABB, région, plage d'UV.
        out.putInt(0).putInt(3).putInt(0).putInt(3);
        out.putShort((short) 7).put((byte) 0)
                .put((byte) (GeometryTransfer.MESH_TRANSPARENT | GeometryTransfer.MESH_DOUBLE_SIDED));
        out.putFloat(0).putFloat(0).putFloat(0).putFloat(1).putFloat(1).putFloat(2);
        out.putShort((short) 0xFFFF).putShort((short) uv0Range);

        // Trois Vertex (48 o chacun).
        float[][] positions = {{0, 0, 0}, {1, 0.5f, 0}, {0, 0, 2}};
        byte[][] normales = {{0, 127, 0, 0}, {-127, 0, 0, 0}, {0, 0, 127, 0}};
        int[][] uv = {{0, 0}, {65535, 32768}, {0, 65535}};
        byte[][] couleurs = {{(byte) 255, (byte) 128, 0, (byte) 255}, {0, 0, 0, 0}, {10, 20, 30, 40}};
        for (int v = 0; v < 3; v++) {
            for (float value : positions[v]) {
                out.putFloat(value);
            }
            out.put(normales[v]);
            out.put(new byte[4]); // tangente
            out.putShort((short) uv[v][0]).putShort((short) uv[v][1]);
            out.putShort((short) 0).putShort((short) 0); // uv1
            out.put(couleurs[v]);
            out.put(new byte[4]); // os
            out.put(new byte[] {(byte) 255, 0, 0, 0}); // poids
            out.put((byte) 255).put((byte) 0); // région, def_w
            out.put(new byte[6]);
        }

        // Indices locaux.
        out.putInt(2).putInt(1).putInt(0);

        // RestDraw (64 o) : mesh 0, node 4, drapeaux 1, réservé, mat4x3 colonne-major.
        out.putInt(0).putInt(4).putInt(1).putInt(0);
        float[] modele = {1, 0, 0, 0, 1, 0, 0, 0, 1, 1, 2, 3};
        for (float value : modele) {
            out.putFloat(value);
        }
        return out.array();
    }

    @Test
    void laDispositionDAdr119SeLit() {
        GeometryTransfer geometrie = GeometryTransfer.parse(transfert());

        assertEquals(1, geometrie.meshes().size());
        GeometryTransfer.Mesh mesh = geometrie.meshes().get(0);
        assertEquals(0, mesh.vertexOffset());
        assertEquals(3, mesh.vertexCount());
        assertEquals(3, mesh.indexCount());
        assertEquals(7, mesh.material());
        assertTrue(mesh.has(GeometryTransfer.MESH_TRANSPARENT));
        assertTrue(mesh.has(GeometryTransfer.MESH_DOUBLE_SIDED));

        assertEquals(3, geometrie.vertexCount());
        assertArrayEquals(new float[] {1, 0.5f, 0}, Arrays.copyOfRange(geometrie.positions(), 3, 6), EPS);
        assertArrayEquals(new int[] {2, 1, 0}, geometrie.indices());

        assertEquals(1, geometrie.draws().size());
        GeometryTransfer.Draw dessin = geometrie.draws().get(0);
        assertEquals(0, dessin.mesh());
        assertEquals(4, dessin.node());
        assertEquals(1, dessin.nodeFlags());
        assertArrayEquals(new float[] {1, 2, 3}, Arrays.copyOfRange(dessin.model(), 9, 12), EPS);
    }

    @Test
    void lesAttributsSontDeQuantifiesUneFois() {
        GeometryTransfer geometrie = GeometryTransfer.parse(transfert());
        // Normales i8 : ±127 code ±1.
        assertArrayEquals(new float[] {0, 1, 0}, Arrays.copyOfRange(geometrie.normals(), 0, 3), EPS);
        assertArrayEquals(new float[] {-1, 0, 0}, Arrays.copyOfRange(geometrie.normals(), 3, 6), EPS);
        // UV UNORM16 : 65535 code 1.
        assertEquals(1.0f, geometrie.uvs()[2], EPS);
        assertEquals(32768 / 65535.0f, geometrie.uvs()[3], EPS);
        // Couleurs RGBA, octets lus non signés.
        assertEquals(255, Byte.toUnsignedInt(geometrie.colors()[0]));
        assertEquals(128, Byte.toUnsignedInt(geometrie.colors()[1]));
        assertEquals(40, Byte.toUnsignedInt(geometrie.colors()[11]));
    }

    @Test
    void lesUvSeDecodentDansLaPlageDeLeurMesh() {
        // [-2, 1] : -2 en complément à deux (0xFE), puis l'étendue 3 — ADR-122 §4.
        GeometryTransfer geometrie = GeometryTransfer.parse(transfert(0x03FE));
        GeometryTransfer.Mesh mesh = geometrie.meshes().get(0);
        assertEquals(0x03FE, mesh.uv0Range());
        assertEquals(-2.0f, mesh.uvMin());
        assertEquals(3.0f, mesh.uvSpan());

        // Les bornes de la plage sont atteintes exactement : q = 0 vaut -2, q = 65535 vaut 1.
        assertEquals(-2.0f, geometrie.uvs()[0]);
        assertEquals(1.0f, geometrie.uvs()[2]);
        // q = 32768 : -2 + 32768 / 65535 × 3, dans cet ordre. Le motif binaire est celui que
        // rend le natif (`UvRange::dequantize`, épinglé au même motif côté Rust).
        assertEquals(0xbefffd00, Float.floatToIntBits(geometrie.uvs()[3]));
    }

    @Test
    void sansPlageLesUvRestentDansLUnite() {
        // Le code 0 vaut [0, 1] : la lecture de tout asset compilé avant ADR-122.
        GeometryTransfer.Mesh mesh = GeometryTransfer.parse(transfert()).meshes().get(0);
        assertEquals(0, mesh.uv0Range());
        assertEquals(0.0f, mesh.uvMin());
        assertEquals(1.0f, mesh.uvSpan());
    }

    @Test
    void uneLongueurQuiContreditLesDenombrementsEstRefusee() {
        byte[] juste = transfert();
        assertThrows(IllegalArgumentException.class,
                () -> GeometryTransfer.parse(Arrays.copyOf(juste, juste.length + 1)));
        assertThrows(IllegalArgumentException.class,
                () -> GeometryTransfer.parse(Arrays.copyOf(juste, juste.length - 1)));
        assertThrows(IllegalArgumentException.class, () -> GeometryTransfer.parse(new byte[15]));
    }

    @Test
    void unTransfertVideEstValide() {
        // Un asset sans géométrie ni node rend des comptes nuls (ADR-119 §3).
        GeometryTransfer vide = GeometryTransfer.parse(new byte[16]);
        assertEquals(0, vide.vertexCount());
        assertTrue(vide.meshes().isEmpty() && vide.draws().isEmpty());
        assertEquals(0, vide.vertexData().remaining());
        assertEquals(0, vide.indexData().remaining());
    }

    @Test
    void lesSommetsEtLesIndicesBrutsSontCeuxDuTransfert() {
        // ADR-127 §2 : le backend natif téléverse le bloc des sommets tel quel, au format GPU du
        // §19.4, et les indices de même — sans re-quantifier ce que les tableaux ont décodé.
        byte[] octets = transfert(0x03FE);
        GeometryTransfer geometrie = GeometryTransfer.parse(octets);

        ByteBuffer sommets = geometrie.vertexData();
        assertTrue(sommets.isReadOnly());
        assertEquals(ByteOrder.LITTLE_ENDIAN, sommets.order());
        byte[] lus = new byte[sommets.remaining()];
        sommets.duplicate().get(lus);
        assertArrayEquals(Arrays.copyOfRange(octets, 16 + 48, 16 + 48 + 3 * 48), lus);

        ByteBuffer indices = geometrie.indexData();
        assertEquals(3 * 4, indices.remaining());
        assertEquals(2, indices.getInt(0));
        assertEquals(1, indices.getInt(4));
        assertEquals(0, indices.getInt(8));
    }
}
