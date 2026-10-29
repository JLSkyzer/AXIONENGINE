package dev.axion.physics;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
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
                        BodyDescription.undeclared(SimCommandStream.BODY_DYNAMIC),
                        phys);

        assertEquals(1, stream.count());
        byte[] bytes = stream.toBytes();
        // en-tête de flux (8) + en-tête de commande (8) + payload aligné sur 8 :
        // align8(72 + 64 + 5) = 144 (ADR-151 D7 : un corps de 64 o suit l'en-tête de 72 o).
        assertEquals(8 + 8 + 144, bytes.length);

        ByteBuffer b = wrap(bytes);
        assertEquals(SimCommandStream.OP_CREATE_ASSEMBLY, b.getInt(8), "opcode");
        // payload_len annoncé = en-tête (72) + corps (64) + PHYS (5), sans le remplissage.
        assertEquals(72 + 64 + 5, b.getInt(12), "payload_len");
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
        assertEquals(0, b.get(73), "flags");
        assertEquals(1, b.getShort(74), "body_count");
        assertEquals(0, b.getShort(76), "joint_count");
        assertEquals(0.0f, b.getFloat(80), "assembly_mass");
        assertEquals(0, b.getInt(84), "node_bytes : un seul corps, sans section NODE");
        // Le corps suit l'en-tête (offset 16 + 72 = 88) : sa description DM-08 — type, puis rien
        // de déclaré, masse 0, centre NaN, gravité entière, plafonds de R-180 —, son node racine.
        assertEquals(SimCommandStream.BODY_DYNAMIC, b.get(88), "body_type");
        assertEquals(0.0f, b.getFloat(92), "mass");
        assertTrue(Float.isNaN(b.getFloat(96)), "center_of_mass.x");
        assertEquals(1.0f, b.getFloat(128), "gravity_scale");
        assertEquals(300.0f, b.getFloat(132), "max_linear_vel");
        assertEquals(100.0f, b.getFloat(136), "max_angular_vel");
        assertEquals(0, b.getInt(140), "réservé de la description");
        assertEquals(0, b.getInt(144), "root_node");
        assertEquals(0, b.getInt(148), "réservé du corps");
        // Les octets PHYS suivent le corps (offset 16 + 136 = 152).
        assertArrayEquals(phys, Arrays.copyOfRange(bytes, 152, 157), "octets PHYS");
    }

    @Test
    void encodeUneAssemblyDePlusieursCorps() {
        // ADR-151 D7 : deux corps, la section NODE complétée à 8, puis PHYS ; drapeau et masse
        // de l'assembly dans l'en-tête.
        byte[] nodes = new byte[13];
        Arrays.fill(nodes, (byte) 9);
        byte[] phys = {1, 2, 3, 4, 5};
        SimCommandStream stream = new SimCommandStream()
                .createAssembly(
                        7,
                        1,
                        0L,
                        new double[] {0, 64, 0},
                        new float[] {0, 0, 0, 1},
                        List.of(
                                new SimCommandStream.AssemblyBody(
                                        BodyDescription.undeclared(SimCommandStream.BODY_STATIC), 0),
                                new SimCommandStream.AssemblyBody(
                                        BodyDescription.undeclared(SimCommandStream.BODY_DYNAMIC), 4)),
                        120.0f,
                        true,
                        nodes,
                        phys);

        byte[] bytes = stream.toBytes();
        ByteBuffer b = wrap(bytes);
        // payload = 72 + 2 × 64 + align8(13) + 5 = 221, aligné à 224.
        assertEquals(221, b.getInt(12), "payload_len");
        assertEquals(8 + 8 + 224, bytes.length);
        assertEquals(SimCommandStream.BODY_STATIC, b.get(72), "body_kind : celui du corps racine");
        assertEquals(SimCommandStream.CREATE_ASSEMBLY_SELF_COLLISION, b.get(73), "flags");
        assertEquals(2, b.getShort(74), "body_count");
        assertEquals(120.0f, b.getFloat(80), "assembly_mass");
        assertEquals(13, b.getInt(84), "node_bytes");
        assertEquals(SimCommandStream.BODY_DYNAMIC, b.get(16 + 72 + 64), "type du second corps");
        assertEquals(4, b.getInt(16 + 72 + 64 + 56), "root_node du second corps");
        assertArrayEquals(nodes, Arrays.copyOfRange(bytes, 216, 229), "section NODE");
        assertArrayEquals(phys, Arrays.copyOfRange(bytes, 232, 237), "PHYS aligné sur 8 après NODE");
    }

    @Test
    void encodeLesJointsEtLeurAnnexeEntreLesCorpsEtNode() {
        // ADR-151 D7 : les joints suivent les corps, puis les points de grippage, puis NODE
        // complétée à 8 et PHYS ; l'en-tête compte les uns et les autres.
        JointDescription joint = new JointDescription(0, 0, 0, 1, new int[6], 4, new float[3], new float[3],
                new float[2], 0f, 0f, 0f, 0f, Float.POSITIVE_INFINITY, 0f, 8000f, Float.POSITIVE_INFINITY,
                Float.POSITIVE_INFINITY, 0f, 0, 0, new float[6][2], 0f, 0f, 0f, 0f);
        byte[] nodes = {9, 9, 9};
        byte[] phys = {1, 2};
        SimCommandStream stream = new SimCommandStream()
                .createAssembly(
                        7,
                        1,
                        0L,
                        new double[] {0, 64, 0},
                        new float[] {0, 0, 0, 1},
                        List.of(
                                new SimCommandStream.AssemblyBody(
                                        BodyDescription.undeclared(SimCommandStream.BODY_STATIC), 0),
                                new SimCommandStream.AssemblyBody(
                                        BodyDescription.undeclared(SimCommandStream.BODY_DYNAMIC), 4)),
                        List.of(joint),
                        List.of(new float[] {0.0f, 1.0f}, new float[] {0.1f, 0.0f}),
                        0.0f,
                        false,
                        nodes,
                        phys);

        byte[] bytes = stream.toBytes();
        ByteBuffer b = wrap(bytes);
        // payload = 72 + 2 × 64 + 160 + 2 × 8 + align8(3) + 2 = 386.
        assertEquals(386, b.getInt(12), "payload_len");
        assertEquals(1, b.getShort(76), "joint_count");
        assertEquals(2, b.getShort(78), "jam_points");
        int joints = 16 + 72 + 2 * 64;
        assertEquals(1, b.getShort(joints + 4), "body_b du joint");
        assertEquals(4, b.getInt(joints + 12), "b_node du joint");
        assertEquals(8000f, b.getFloat(joints + 72), "break_force du joint");
        int points = joints + JointDescription.BYTES;
        assertEquals(1.0f, b.getFloat(points + 4), "fraction du premier point");
        assertEquals(0.1f, b.getFloat(points + 8), "abscisse du second point");
        int nodesAt = points + 2 * JointDescription.JAM_POINT_BYTES;
        assertArrayEquals(nodes, Arrays.copyOfRange(bytes, nodesAt, nodesAt + 3), "section NODE");
        assertArrayEquals(phys, Arrays.copyOfRange(bytes, nodesAt + 8, nodesAt + 10), "PHYS aligné sur 8");
    }

    private static JointDescription jointDEssai() {
        return new JointDescription(0, 0, 0, 1, new int[6], 4, new float[3], new float[3],
                new float[2], 0f, 0f, 0f, 0f, Float.POSITIVE_INFINITY, 0f, 8000f, Float.POSITIVE_INFINITY,
                Float.POSITIVE_INFINITY, 0f, 0, 0, new float[6][2], 0f, 0f, 0f, 0f);
    }

    private static List<SimCommandStream.AssemblyBody> deuxCorps() {
        return List.of(
                new SimCommandStream.AssemblyBody(BodyDescription.undeclared(SimCommandStream.BODY_STATIC), 0),
                new SimCommandStream.AssemblyBody(BodyDescription.undeclared(SimCommandStream.BODY_DYNAMIC), 4));
    }

    private static SimCommandStream.Restored etatRestaure(float vitesse, float desalignement) {
        return new SimCommandStream.Restored(
                List.of(
                        new SimCommandStream.RestoredBody(new double[] {10.25, 70.5, -3.125},
                                new float[] {0f, 0.5f, 0f, 0.8660254f}, new float[] {vitesse, 0f, 0f},
                                new float[] {0f, 0.25f, 0f}),
                        new SimCommandStream.RestoredBody(new double[] {12.0, 70.5, -4.0},
                                new float[] {0f, 0f, 0f, 1f}, new float[] {3f, 0.5f, 0f}, new float[3])),
                List.of(new SimCommandStream.RestoredJoint(true, desalignement)));
    }

    @Test
    void encodeLEtatRestaureEntreNodeEtPhys() {
        // ADR-153 D3 : l'état restauré suit NODE — 64 octets par corps, 8 par joint —, puis PHYS
        // aligné sur 8 ; les drapeaux RESTORED et ASLEEP sont dans l'en-tête.
        byte[] nodes = {9, 9, 9};
        byte[] phys = {1, 2};
        SimCommandStream stream = new SimCommandStream().createAssembly(7, 1, 0L, new double[] {0, 64, 0},
                new float[] {0, 0, 0, 1}, deuxCorps(), List.of(jointDEssai()), List.of(), 0.0f, false, nodes,
                etatRestaure(3f, 0f), true, phys);

        byte[] bytes = stream.toBytes();
        ByteBuffer b = wrap(bytes);
        assertEquals(SimCommandStream.CREATE_ASSEMBLY_RESTORED | SimCommandStream.CREATE_ASSEMBLY_ASLEEP, b.get(73),
                "drapeaux");
        int nodesAt = 16 + 72 + 2 * 64 + JointDescription.BYTES;
        assertArrayEquals(nodes, Arrays.copyOfRange(bytes, nodesAt, nodesAt + 3), "section NODE");
        int restored = nodesAt + 3;
        assertEquals(10.25, b.getDouble(restored), "position x du corps racine");
        assertEquals(-3.125, b.getDouble(restored + 16), "position z du corps racine");
        assertEquals(0.5f, b.getFloat(restored + 24 + 4), "rotation y du corps racine");
        assertEquals(3f, b.getFloat(restored + 40), "vitesse x du corps racine");
        assertEquals(0.25f, b.getFloat(restored + 52 + 4), "vitesse angulaire y du corps racine");
        assertEquals(12.0, b.getDouble(restored + 64), "position x du second corps");
        assertEquals(0.5f, b.getFloat(restored + 64 + 44), "vitesse y du second corps");
        int joint = restored + 2 * SimCommandStream.RESTORED_BODY_BYTES;
        assertEquals(SimCommandStream.RESTORED_JOINT_BROKEN, b.get(joint), "joint rompu");
        assertEquals(0, b.get(joint + 1) | b.get(joint + 2) | b.get(joint + 3), "réservés");
        assertEquals(0f, b.getFloat(joint + 4), "désalignement");
        // payload = align8(72 + 128 + 160 + 3 + 136) + 2 = 506.
        assertEquals(506, b.getInt(12), "payload_len");
        assertArrayEquals(phys, Arrays.copyOfRange(bytes, 16 + 504, 16 + 506), "PHYS aligné sur 8 après l'état");
    }

    @Test
    void sansEtatRestaureLeDrapeauRestoredEstNul() {
        SimCommandStream stream = new SimCommandStream().createAssembly(7, 1, 0L, new double[] {0, 64, 0},
                new float[] {0, 0, 0, 1}, deuxCorps(), List.of(jointDEssai()), List.of(), 0.0f, true,
                new byte[] {9}, null, true, new byte[] {1});
        assertEquals(SimCommandStream.CREATE_ASSEMBLY_SELF_COLLISION | SimCommandStream.CREATE_ASSEMBLY_ASLEEP,
                wrap(stream.toBytes()).get(73));
    }

    @Test
    void unEtatRestaureFautifEstRefuseAvantLeNatif() {
        double[] position = {0, 64, 0};
        float[] rotation = {0, 0, 0, 1};
        assertThrows(IllegalArgumentException.class, () -> new SimCommandStream().createAssembly(7, 1, 0L, position,
                rotation, deuxCorps(), List.of(), List.of(), 0f, false, new byte[] {9}, etatRestaure(3f, 0f), false,
                new byte[] {1}), "un état de joint sans joint");
        assertThrows(IllegalArgumentException.class, () -> new SimCommandStream().createAssembly(7, 1, 0L, position,
                rotation, deuxCorps(), List.of(jointDEssai()), List.of(), 0f, false, new byte[] {9},
                etatRestaure(Float.NaN, 0f), false, new byte[] {1}), "vitesse non finie");
        assertThrows(IllegalArgumentException.class, () -> new SimCommandStream().createAssembly(7, 1, 0L, position,
                rotation, deuxCorps(), List.of(jointDEssai()), List.of(), 0f, false, new byte[] {9},
                etatRestaure(3f, -0.5f), false, new byte[] {1}), "désalignement négatif");
    }

    @Test
    void createAssemblyRefuseUneAssemblyMalFormee() {
        BodyDescription dynamique = BodyDescription.undeclared(SimCommandStream.BODY_DYNAMIC);
        BodyDescription cinematique = BodyDescription.undeclared(SimCommandStream.BODY_KINEMATIC);
        SimCommandStream.AssemblyBody racine = new SimCommandStream.AssemblyBody(dynamique, 0);
        double[] position = {0, 0, 0};
        float[] rotation = {0, 0, 0, 1};
        byte[] nodes = {1};
        // Aucun corps ; un second corps non dynamique ; plusieurs corps sans NODE ; masse négative.
        assertThrows(IllegalArgumentException.class, () -> new SimCommandStream()
                .createAssembly(1, 1, 0L, position, rotation, List.of(), 0f, false, nodes, nodes));
        assertThrows(IllegalArgumentException.class, () -> new SimCommandStream()
                .createAssembly(1, 1, 0L, position, rotation,
                        List.of(racine, new SimCommandStream.AssemblyBody(cinematique, 1)),
                        0f, false, nodes, nodes));
        assertThrows(IllegalArgumentException.class, () -> new SimCommandStream()
                .createAssembly(1, 1, 0L, position, rotation,
                        List.of(racine, new SimCommandStream.AssemblyBody(dynamique, 1)),
                        0f, false, new byte[0], nodes));
        assertThrows(IllegalArgumentException.class, () -> new SimCommandStream()
                .createAssembly(1, 1, 0L, position, rotation, List.of(racine), -1f, false, nodes, nodes));
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
                        BodyDescription.undeclared(SimCommandStream.BODY_STATIC),
                        new byte[] {1, 2, 3}) // 72 + 64 + 3 -> align8 = 144
                .setDimensionEnv(0L, new float[] {0, -9.81f, 0}, new float[] {0, 0, 0}, 0, 0, false);

        byte[] bytes = stream.toBytes();
        int second = 8 + 8 + 144; // en-tête flux + en-tête cmd + payload aligné
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
                                BodyDescription.undeclared(SimCommandStream.BODY_DYNAMIC),
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
                                // Type de corps hors de {0, 1, 2} : la description le refuse.
                                BodyDescription.undeclared(7),
                                new byte[] {1}));
    }

    @Test
    void encodeUneCommandeSetWorldCollision() {
        // Une dalle 16×1×16 sur la section (0, 0, 0), matériau d'index 5 (ADR-149).
        SimCommandStream stream = new SimCommandStream()
                .setWorldCollision(
                        4L,
                        new int[] {1, 0, -2},
                        new float[][] {{0f, 0f, 0f, 16f, 1f, 16f}},
                        5);
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
        assertEquals(5, b.getShort(40), "material");
        assertEquals(0, b.getShort(42), "_pad");
        assertEquals(0, b.getInt(44), "_reserved");
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
                        0L, new int[] {0, 0, 0}, 3, 3, heights, new float[] {16f, 1f, 16f}, 7);
        byte[] bytes = stream.toBytes();
        // en-tête flux (8) + en-tête cmd (8) + payload aligné (88)
        assertEquals(8 + 8 + 88, bytes.length);
        ByteBuffer b = wrap(bytes);
        assertEquals(SimCommandStream.OP_SET_WORLD_HEIGHTFIELD, b.getInt(8), "opcode");
        assertEquals(48 + 36, b.getInt(12), "payload_len (utile, sans remplissage)");
        assertEquals(3, b.getInt(36), "rows");
        assertEquals(3, b.getInt(40), "cols");
        assertEquals(7, b.getShort(44), "material");
        assertEquals(0, b.getShort(46), "_pad");
        assertEquals(0, b.getInt(48), "_reserved");
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
                .setWorldCollision(0L, new int[] {0, 0, 0}, new float[0][], SimCommandStream.NO_MATERIAL)
                .toBytes();
        assertEquals(8 + 8 + 32, bytes.length);
        assertEquals(0, wrap(bytes).getInt(36), "box_count nul");
    }

    @Test
    void setWorldCollisionRefuseAuDelaDuPlafond() {
        float[][] boxes = new float[SimCommandStream.MAX_WORLD_TILE_BOXES + 1][6];
        assertThrows(
                IllegalArgumentException.class,
                () -> new SimCommandStream().setWorldCollision(0L, new int[] {0, 0, 0}, boxes, SimCommandStream.NO_MATERIAL));
    }

    @Test
    void setWorldHeightfieldRefuseUneTailleIncoherente() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new SimCommandStream()
                        .setWorldHeightfield(
                                0L, new int[] {0, 0, 0}, 2, 2, new float[3], new float[] {1, 1, 1}, SimCommandStream.NO_MATERIAL));
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
    void encodeUnJamJoint() {
        // ADR-151 D7 : JamJoint, 16 octets — handle, rang u16, 2 octets réservés, désalignement.
        byte[] bytes = new SimCommandStream().jamJoint(7, 3, 0xFFFF, 0.25f).toBytes();
        assertEquals(8 + 8 + 16, bytes.length);
        ByteBuffer b = wrap(bytes);
        assertEquals(14, SimCommandStream.OP_JAM_JOINT, "opcode::JAM_JOINT du natif");
        assertEquals(SimCommandStream.OP_JAM_JOINT, b.getInt(8), "opcode");
        assertEquals(16, b.getInt(12), "payload_len");
        assertEquals(7, b.getInt(16), "handle.index");
        assertEquals(3, b.getInt(20), "handle.generation");
        assertEquals(0xFFFF, Short.toUnsignedInt(b.getShort(24)), "rang du joint, u16 entier");
        assertEquals(0, b.getShort(26), "réservé");
        assertEquals(0.25f, b.getFloat(28), "désalignement");
    }

    @Test
    void encodeUnBreakJointAligneSurHuit() {
        // ADR-151 D7 : BreakJoint, 12 octets annoncés, complétés à 16 pour la commande suivante.
        SimCommandStream stream = new SimCommandStream().breakJoint(7, 3, 2).removeAssembly(9, 1);
        byte[] bytes = stream.toBytes();
        assertEquals(8 + (8 + 16) + (8 + 8), bytes.length);
        ByteBuffer b = wrap(bytes);
        assertEquals(15, SimCommandStream.OP_BREAK_JOINT, "opcode::BREAK_JOINT du natif");
        assertEquals(SimCommandStream.OP_BREAK_JOINT, b.getInt(8), "opcode");
        assertEquals(12, b.getInt(12), "payload_len");
        assertEquals(7, b.getInt(16), "handle.index");
        assertEquals(3, b.getInt(20), "handle.generation");
        assertEquals(2, b.getShort(24), "rang du joint");
        assertEquals(0, b.getShort(26), "réservé");
        assertEquals(SimCommandStream.OP_REMOVE_ASSEMBLY, b.getInt(8 + 8 + 16), "commande suivante, alignée");
    }

    @Test
    void unRangDeJointHorsU16EstRefuse() {
        assertThrows(IllegalArgumentException.class, () -> new SimCommandStream().jamJoint(1, 1, -1, 0.0f));
        assertThrows(IllegalArgumentException.class, () -> new SimCommandStream().jamJoint(1, 1, 0x10000, 0.0f));
        assertThrows(IllegalArgumentException.class, () -> new SimCommandStream().breakJoint(1, 1, -1));
        assertThrows(IllegalArgumentException.class, () -> new SimCommandStream().breakJoint(1, 1, 0x10000));
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

    @Test
    void encodeUneCommandeSetObservers() {
        // ADR-123 §2 : en-tête (16 o) puis count × [f64;3].
        byte[] bytes = new SimCommandStream()
                .setObservers(3L, new double[][] {{1.5, 64.0, -3.25}, {1.0e6, 70.0, 2.0e5}})
                .toBytes();
        assertEquals(8 + 8 + 16 + 2 * 24, bytes.length);
        ByteBuffer b = wrap(bytes);
        assertEquals(11, b.getInt(8), "opcode");
        assertEquals(16 + 48, b.getInt(12), "payload_len");
        assertEquals(3L, b.getLong(16), "dimension");
        assertEquals(2, b.getInt(24), "count");
        assertEquals(0, b.getInt(28), "_pad");
        assertEquals(1.5, b.getDouble(32), "positions[0].x");
        assertEquals(64.0, b.getDouble(40), "positions[0].y");
        assertEquals(-3.25, b.getDouble(48), "positions[0].z");
        assertEquals(1.0e6, b.getDouble(56), "positions[1].x");
        assertEquals(2.0e5, b.getDouble(72), "positions[1].z");
    }

    @Test
    void encodeUneCommandeSetEntityProxies() {
        // ADR-123 §5 : en-tête (16 o) puis count × EntityProxyDesc (56 o, alignement 8).
        SimCommandStream.EntityProxy vache = new SimCommandStream.EntityProxy(
                42,
                new double[] {10.5, 64.9, -3.5},
                new float[] {0.45f, 0.7f, 0.45f},
                new float[] {1.0f, -2.0f, 0.5f},
                SimCommandStream.PROXY_CAPSULE);
        byte[] bytes = new SimCommandStream().setEntityProxies(7L, java.util.List.of(vache)).toBytes();
        assertEquals(8 + 8 + 16 + 56, bytes.length);
        ByteBuffer b = wrap(bytes);
        assertEquals(12, b.getInt(8), "opcode");
        assertEquals(16 + 56, b.getInt(12), "payload_len");
        assertEquals(7L, b.getLong(16), "dimension");
        assertEquals(1, b.getInt(24), "count");
        assertEquals(0, b.getInt(28), "_pad");
        int desc = 32;
        assertEquals(10.5, b.getDouble(desc), "center.x @0");
        assertEquals(64.9, b.getDouble(desc + 8), "center.y @8");
        assertEquals(-3.5, b.getDouble(desc + 16), "center.z @16");
        assertEquals(0.45f, b.getFloat(desc + 24), "half_extents.x @24");
        assertEquals(0.7f, b.getFloat(desc + 28), "half_extents.y @28");
        assertEquals(1.0f, b.getFloat(desc + 36), "velocity.x @36");
        assertEquals(0.5f, b.getFloat(desc + 44), "velocity.z @44");
        assertEquals(42, b.getInt(desc + 48), "entity @48");
        assertEquals(SimCommandStream.PROXY_CAPSULE, b.get(desc + 52), "shape @52");
        assertEquals(0, b.get(desc + 53), "_pad @53");
        assertEquals(0, b.get(desc + 55), "_pad @55");
    }

    @Test
    void lesPlafondsEtLesFormesSontRefusesAvantLeNatif() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new SimCommandStream().setObservers(0L, new double[SimCommandStream.MAX_OBSERVERS + 1][3]));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SimCommandStream().setObservers(0L, new double[][] {{0.0, 0.0}}));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SimCommandStream.EntityProxy(1, new double[3], new float[3], new float[3], 2));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SimCommandStream.EntityProxy(1, new double[2], new float[3], new float[3], 0));
        SimCommandStream.EntityProxy une =
                new SimCommandStream.EntityProxy(1, new double[3], new float[] {1, 1, 1}, new float[3], 0);
        java.util.List<SimCommandStream.EntityProxy> trop =
                java.util.Collections.nCopies(SimCommandStream.MAX_ENTITY_PROXIES + 1, une);
        assertThrows(IllegalArgumentException.class, () -> new SimCommandStream().setEntityProxies(0L, trop));
    }

    @Test
    void encodeUneCommandeSetPhysicsMaterials() {
        // ADR-149 : l'en-tête (nombre, réservé), puis les entrées DM-07 telles quelles ; le flux est
        // au schéma courant, 3 depuis ADR-151.
        byte[] entries = new byte[2 * SimCommandStream.PHYSICS_MATERIAL_BYTES];
        entries[8] = 0x11;
        entries[SimCommandStream.PHYSICS_MATERIAL_BYTES + 8] = 0x22;
        byte[] bytes = new SimCommandStream().setPhysicsMaterials(entries, 2).toBytes();
        assertEquals(8 + 8 + 8 + entries.length, bytes.length);
        ByteBuffer b = wrap(bytes);
        assertEquals(SimCommandStream.CURRENT_SCHEMA, b.getInt(0), "schéma du flux");
        assertEquals(SimCommandStream.OP_SET_PHYSICS_MATERIALS, b.getInt(8), "opcode");
        assertEquals(13, SimCommandStream.OP_SET_PHYSICS_MATERIALS);
        assertEquals(8 + entries.length, b.getInt(12), "payload_len");
        assertEquals(2, b.getInt(16), "count");
        assertEquals(0, b.getInt(20), "réservé");
        assertEquals(0x11, b.get(24 + 8));
        assertEquals(0x22, b.get(24 + SimCommandStream.PHYSICS_MATERIAL_BYTES + 8));

        // Une table incohérente, un index hors d'un u16 : refusés avant la frontière.
        assertThrows(IllegalArgumentException.class, () -> new SimCommandStream().setPhysicsMaterials(new byte[119], 1));
        assertThrows(IllegalArgumentException.class,
                () -> new SimCommandStream().setWorldCollision(0L, new int[] {0, 0, 0}, new float[0][], 0x10000));
    }

    @Test
    void laSectionAnimSuitLEtatRestaurePrefixeeDeSaTaille() {
        // ADR-155 : ANIM après NODE (et l'état restauré), préfixée de sa taille et d'un mot réservé ;
        // PHYS reste aligné sur 8.
        byte[] anim = {1, 2, 3};
        byte[] phys = {9};
        byte[] bytes = new SimCommandStream()
                .createAssembly(5, 1, 0L, new double[] {0, 0, 0}, new float[] {0, 0, 0, 1},
                        java.util.List.of(new SimCommandStream.AssemblyBody(
                                BodyDescription.undeclared(SimCommandStream.BODY_DYNAMIC), 0)),
                        java.util.List.of(), java.util.List.of(), 0f, false, new byte[0], null, false, anim, phys)
                .toBytes();
        ByteBuffer b = wrap(bytes);
        assertEquals(SimCommandStream.CREATE_ASSEMBLY_ANIMATED, b.get(73), "drapeau ANIMATED");
        int anims = 16 + 72 + 64;
        assertEquals(3, b.getInt(anims), "taille de la section ANIM");
        assertEquals(0, b.getInt(anims + 4), "réservé");
        assertArrayEquals(anim, Arrays.copyOfRange(bytes, anims + 8, anims + 11));
        int physAt = 16 + ((72 + 64 + 8 + 3 + 7) & ~7);
        assertEquals(9, bytes[physAt], "PHYS aligné sur 8 après ANIM");
        assertEquals(physAt - 16 + 1, b.getInt(12), "payload_len");
    }

    @Test
    void encodeUneCommandeAnimateAssembly() {
        byte[] bytes = new SimCommandStream()
                .animateAssembly(5, 1, 40.5, 4, 2, 0, 0f, 0f, 1f, new int[] {3, 7})
                .toBytes();
        ByteBuffer b = wrap(bytes);
        assertEquals(SimCommandStream.OP_ANIMATE_ASSEMBLY, b.getInt(8));
        assertEquals(SimCommandStream.ANIMATE_ASSEMBLY_BYTES + 8, b.getInt(12), "payload et masque");
        assertEquals(5, b.getInt(16));
        assertEquals(1, b.getInt(20));
        assertEquals(40.5, b.getDouble(24), "tick");
        assertEquals(4, b.getInt(32), "action");
        assertEquals(2, b.getInt(36), "couche");
        assertEquals(1f, b.getFloat(52), "vitesse");
        assertEquals(2, b.getInt(56), "nodes du masque");
        assertEquals(3, b.getInt(16 + SimCommandStream.ANIMATE_ASSEMBLY_BYTES));
        assertEquals(7, b.getInt(16 + SimCommandStream.ANIMATE_ASSEMBLY_BYTES + 4));
    }
}
