package dev.axion.physics;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * Épingle l'encodage du flux {@code SIM_IN} sur la disposition figée d'ADR-114 :
 * {@code CommandStreamHeader} (8 o) puis, par commande, un {@code SimCommandHeader}
 * (opcode + payload_len, 8 o) et son payload, en little-endian.
 */
class SimCommandStreamTest {

    private static ByteBuffer wrap(byte[] bytes) {
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    @Test
    void encodeUneCommandeSetDimensionEnv() {
        SimCommandStream stream = new SimCommandStream()
                .setDimensionEnv(
                        7L,
                        new float[] {0.0f, -9.81f, 0.0f},
                        new float[] {1.0f, 0.0f, 2.0f},
                        63.0f,
                        1000.0f,
                        true);

        assertEquals(1, stream.count());
        byte[] bytes = stream.toBytes();
        // en-tête de flux (8) + en-tête de commande (8) + payload (48)
        assertEquals(8 + 8 + 48, bytes.length);

        ByteBuffer b = wrap(bytes);
        assertEquals(SimCommandStream.CURRENT_SCHEMA, b.getInt(0), "schema_version");
        assertEquals(0, b.getInt(4), "_pad");
        assertEquals(SimCommandStream.OP_SET_DIMENSION_ENV, b.getInt(8), "opcode");
        assertEquals(SimCommandStream.SET_DIMENSION_ENV_BYTES, b.getInt(12), "payload_len");
        assertEquals(7L, b.getLong(16), "dimension");
        assertEquals(0.0f, b.getFloat(24), "gravity.x");
        assertEquals(-9.81f, b.getFloat(28), "gravity.y");
        assertEquals(0.0f, b.getFloat(32), "gravity.z");
        assertEquals(1.0f, b.getFloat(36), "wind.x");
        assertEquals(0.0f, b.getFloat(40), "wind.y");
        assertEquals(2.0f, b.getFloat(44), "wind.z");
        assertEquals(63.0f, b.getFloat(48), "fluid_surface");
        assertEquals(1000.0f, b.getFloat(52), "fluid_density");
        assertEquals(SimCommandStream.FLUID_PRESENT, b.getInt(56), "flags");
    }

    @Test
    void sansFluideLeDrapeauEstNul() {
        byte[] bytes = new SimCommandStream()
                .setDimensionEnv(0L, new float[] {0, 0, 0}, new float[] {0, 0, 0}, 0, 0, false)
                .toBytes();
        assertEquals(0, wrap(bytes).getInt(56), "flags nul sans fluide");
    }

    @Test
    void deuxCommandesSeSuivent() {
        SimCommandStream stream = new SimCommandStream()
                .setDimensionEnv(0L, new float[] {0, -9.81f, 0}, new float[] {0, 0, 0}, 0, 0, false)
                .setDimensionEnv(1L, new float[] {0, -3.7f, 0}, new float[] {0, 0, 0}, 0, 0, false);

        assertEquals(2, stream.count());
        byte[] bytes = stream.toBytes();
        assertEquals(8 + 2 * (8 + 48), bytes.length);

        ByteBuffer b = wrap(bytes);
        // Seconde commande : après l'en-tête de flux et la première commande.
        int second = 8 + (8 + 48);
        assertEquals(SimCommandStream.OP_SET_DIMENSION_ENV, b.getInt(second));
        assertEquals(1L, b.getLong(second + 8), "dimension de la seconde commande");
    }

    @Test
    void fluxVideNaQuUnEntete() {
        SimCommandStream stream = new SimCommandStream();
        assertEquals(0, stream.count());
        assertEquals(8, stream.toBytes().length, "en-tête de flux seul");
    }

    @Test
    void encodeUneCommandeCreateAssembly() {
        byte[] phys = {10, 20, 30, 40, 50}; // 5 octets : PHYS opaque pour ce test
        SimCommandStream stream = new SimCommandStream()
                .createAssembly(
                        7,
                        1,
                        3L,
                        new double[] {5.0, 10.0, -2.0},
                        new float[] {0.0f, 0.0f, 0.0f, 1.0f},
                        SimCommandStream.BODY_DYNAMIC,
                        phys);

        assertEquals(1, stream.count());
        byte[] bytes = stream.toBytes();
        // en-tête de flux (8) + en-tête de commande (8) + payload aligné sur 8 :
        // align8(64 + 5) = 72.
        assertEquals(8 + 8 + 72, bytes.length);

        ByteBuffer b = wrap(bytes);
        assertEquals(SimCommandStream.OP_CREATE_ASSEMBLY, b.getInt(8), "opcode");
        // payload_len annoncé = en-tête (64) + PHYS (5), sans le remplissage.
        assertEquals(64 + 5, b.getInt(12), "payload_len");
        // En-tête CreateAssembly, payload à l'offset 16.
        assertEquals(7, b.getInt(16), "handle.index");
        assertEquals(1, b.getInt(20), "handle.generation");
        assertEquals(3L, b.getLong(24), "dimension");
        assertEquals(5.0, b.getDouble(32), "spawn.position.x");
        assertEquals(10.0, b.getDouble(40), "spawn.position.y");
        assertEquals(-2.0, b.getDouble(48), "spawn.position.z");
        assertEquals(0.0f, b.getFloat(56), "spawn.rotation.x");
        assertEquals(0.0f, b.getFloat(60), "spawn.rotation.y");
        assertEquals(0.0f, b.getFloat(64), "spawn.rotation.z");
        assertEquals(1.0f, b.getFloat(68), "spawn.rotation.w");
        assertEquals(SimCommandStream.BODY_DYNAMIC, b.get(72), "body_kind");
        // Les octets PHYS suivent l'en-tête (offset 16 + 64 = 80).
        assertArrayEquals(phys, Arrays.copyOfRange(bytes, 80, 85), "octets PHYS");
    }

    @Test
    void createAssemblyAligneLaCommandeSuivante() {
        // Après un CREATE_ASSEMBLY à payload non multiple de 8, une commande qui
        // suit doit rester alignée : le lecteur natif avance d'align8(payload_len).
        SimCommandStream stream = new SimCommandStream()
                .createAssembly(
                        1,
                        1,
                        0L,
                        new double[] {0, 0, 0},
                        new float[] {0, 0, 0, 1},
                        SimCommandStream.BODY_STATIC,
                        new byte[] {1, 2, 3}) // 64 + 3 -> align8 = 72
                .setDimensionEnv(0L, new float[] {0, -9.81f, 0}, new float[] {0, 0, 0}, 0, 0, false);

        byte[] bytes = stream.toBytes();
        int second = 8 + 8 + 72; // en-tête flux + en-tête cmd + payload aligné
        assertEquals(0, second % 8, "la seconde commande est alignée sur 8");
        assertEquals(
                SimCommandStream.OP_SET_DIMENSION_ENV,
                wrap(bytes).getInt(second),
                "la seconde commande suit bien le remplissage");
    }

    @Test
    void createAssemblyRefuseUneRotationInvalide() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new SimCommandStream()
                        .createAssembly(
                                1,
                                1,
                                0L,
                                new double[] {0, 0, 0},
                                new float[] {0, 0, 1}, // quaternion incomplet
                                SimCommandStream.BODY_DYNAMIC,
                                new byte[] {1}));
    }

    @Test
    void createAssemblyRefuseUnBodyKindInconnu() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new SimCommandStream()
                        .createAssembly(
                                1,
                                1,
                                0L,
                                new double[] {0, 0, 0},
                                new float[] {0, 0, 0, 1},
                                7, // hors de {0, 1, 2}
                                new byte[] {1}));
    }

    @Test
    void encodeUneCommandeSetWorldCollision() {
        // Une dalle 16×1×16 sur la section (0, 0, 0), matériau (0.6, 0.1).
        SimCommandStream stream = new SimCommandStream()
                .setWorldCollision(
                        4L,
                        new int[] {1, 0, -2},
                        new float[][] {{0f, 0f, 0f, 16f, 1f, 16f}},
                        0.6f,
                        0.1f);
        byte[] bytes = stream.toBytes();
        // en-tête flux (8) + en-tête cmd (8) + en-tête payload (32) + 1 boîte (24)
        assertEquals(8 + 8 + 32 + 24, bytes.length);

        ByteBuffer b = wrap(bytes);
        assertEquals(SimCommandStream.OP_SET_WORLD_COLLISION, b.getInt(8), "opcode");
        assertEquals(32 + 24, b.getInt(12), "payload_len");
        assertEquals(4L, b.getLong(16), "dimension");
        assertEquals(1, b.getInt(24), "section.x");
        assertEquals(0, b.getInt(28), "section.y");
        assertEquals(-2, b.getInt(32), "section.z");
        assertEquals(1, b.getInt(36), "box_count");
        assertEquals(0.6f, b.getFloat(40), "friction");
        assertEquals(0.1f, b.getFloat(44), "restitution");
        // Boîte à l'offset 48 (16 + en-tête 32).
        assertEquals(0f, b.getFloat(48), "box.minx");
        assertEquals(16f, b.getFloat(60), "box.maxx");
        assertEquals(1f, b.getFloat(64), "box.maxy");
        assertEquals(16f, b.getFloat(68), "box.maxz");
    }

    @Test
    void encodeUneCommandeSetWorldFluid() {
        SimCommandStream stream = new SimCommandStream()
                .setWorldFluid(0L, new int[] {0, 0, 0}, new float[][] {{0f, 1f, 0f, 16f, 9f, 16f}}, 1000f);
        byte[] bytes = stream.toBytes();
        assertEquals(8 + 8 + 32 + 24, bytes.length);
        ByteBuffer b = wrap(bytes);
        assertEquals(SimCommandStream.OP_SET_WORLD_FLUID, b.getInt(8), "opcode");
        assertEquals(32 + 24, b.getInt(12), "payload_len");
        assertEquals(1, b.getInt(36), "box_count");
        assertEquals(1000f, b.getFloat(40), "density");
        assertEquals(0, b.getInt(44), "_pad");
        assertEquals(1f, b.getFloat(52), "box.miny"); // offset 48 + 4
    }

    @Test
    void encodeUneCommandeSetWorldHeightfield() {
        // 3×3 hauteurs : payload 48 + 36 = 84, non multiple de 8 → aligné à 88.
        float[] heights = {0f, 0f, 0f, 1f, 1f, 1f, 2f, 2f, 2f};
        SimCommandStream stream = new SimCommandStream()
                .setWorldHeightfield(
                        0L, new int[] {0, 0, 0}, 3, 3, heights, new float[] {16f, 1f, 16f}, 0.5f, 0f);
        byte[] bytes = stream.toBytes();
        // en-tête flux (8) + en-tête cmd (8) + payload aligné (88)
        assertEquals(8 + 8 + 88, bytes.length);
        ByteBuffer b = wrap(bytes);
        assertEquals(SimCommandStream.OP_SET_WORLD_HEIGHTFIELD, b.getInt(8), "opcode");
        assertEquals(48 + 36, b.getInt(12), "payload_len (utile, sans remplissage)");
        assertEquals(3, b.getInt(36), "rows");
        assertEquals(3, b.getInt(40), "cols");
        assertEquals(0.5f, b.getFloat(44), "friction");
        assertEquals(0f, b.getFloat(48), "restitution");
        assertEquals(16f, b.getFloat(52), "scale.x");
        assertEquals(1f, b.getFloat(56), "scale.y");
        assertEquals(16f, b.getFloat(60), "scale.z");
        assertEquals(0f, b.getFloat(64), "height[0]"); // hauteurs à l'offset 64
        assertEquals(2f, b.getFloat(64 + 8 * 4), "height[8]");
    }

    @Test
    void encodeUnRetraitDeTuile() {
        byte[] bytes = new SimCommandStream().removeWorldCollision(2L, new int[] {3, 4, 5}).toBytes();
        assertEquals(8 + 8 + 24, bytes.length);
        ByteBuffer b = wrap(bytes);
        assertEquals(SimCommandStream.OP_REMOVE_WORLD_COLLISION, b.getInt(8), "opcode");
        assertEquals(24, b.getInt(12), "payload_len");
        assertEquals(2L, b.getLong(16), "dimension");
        assertEquals(3, b.getInt(24), "section.x");
        assertEquals(5, b.getInt(32), "section.z");

        byte[] fluid = new SimCommandStream().removeWorldFluid(0L, new int[] {0, 0, 0}).toBytes();
        assertEquals(SimCommandStream.OP_REMOVE_WORLD_FLUID, wrap(fluid).getInt(8), "opcode fluide");
    }

    @Test
    void uneTuileVideEncodeUnRetraitImplicite() {
        // box_count 0 : le natif y retire la tuile (alias défini, ADR-117).
        byte[] bytes = new SimCommandStream()
                .setWorldCollision(0L, new int[] {0, 0, 0}, new float[0][], 0.5f, 0f)
                .toBytes();
        assertEquals(8 + 8 + 32, bytes.length);
        assertEquals(0, wrap(bytes).getInt(36), "box_count nul");
    }

    @Test
    void setWorldCollisionRefuseAuDelaDuPlafond() {
        float[][] boxes = new float[SimCommandStream.MAX_WORLD_TILE_BOXES + 1][6];
        assertThrows(
                IllegalArgumentException.class,
                () -> new SimCommandStream().setWorldCollision(0L, new int[] {0, 0, 0}, boxes, 0.5f, 0f));
    }

    @Test
    void setWorldHeightfieldRefuseUneTailleIncoherente() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new SimCommandStream()
                        .setWorldHeightfield(
                                0L, new int[] {0, 0, 0}, 2, 2, new float[3], new float[] {1, 1, 1}, 0.5f, 0f));
    }

    @Test
    void encodeUnRemoveAssembly() {
        byte[] bytes = new SimCommandStream().removeAssembly(7, 1).toBytes();
        // en-tête flux (8) + en-tête cmd (8) + payload Handle (8)
        assertEquals(8 + 8 + 8, bytes.length);
        ByteBuffer b = wrap(bytes);
        assertEquals(SimCommandStream.OP_REMOVE_ASSEMBLY, b.getInt(8), "opcode");
        assertEquals(8, b.getInt(12), "payload_len");
        assertEquals(7, b.getInt(16), "handle.index");
        assertEquals(1, b.getInt(20), "handle.generation");
    }

    @Test
    void mergeConcateneLesCommandes() {
        SimCommandStream a = new SimCommandStream().removeAssembly(1, 1);
        SimCommandStream b = new SimCommandStream()
                .setDimensionEnv(0L, new float[] {0, -9.81f, 0}, new float[] {0, 0, 0}, 0, 0, false);
        a.merge(b);
        assertEquals(2, a.count(), "les deux commandes sont présentes");
        byte[] bytes = a.toBytes();
        assertEquals(SimCommandStream.OP_REMOVE_ASSEMBLY, wrap(bytes).getInt(8), "première commande");
        // Seconde commande : après l'en-tête de flux + REMOVE_ASSEMBLY (8 + 8).
        int second = 8 + (8 + 8);
        assertEquals(SimCommandStream.OP_SET_DIMENSION_ENV, wrap(bytes).getInt(second), "seconde commande");
    }
}
