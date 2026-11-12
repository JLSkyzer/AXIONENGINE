package dev.axion.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.axion.seat.VehicleInput;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    /** L'empreinte d'une definition d'essai, aux deux moitiés distinctes. */
    private static final long DEFINITION = 0x0123_4567_89AB_CDEFL;

    @Test
    void encodeUneCommandeCreateAssembly() {
        SimCommandStream stream = new SimCommandStream()
                .createAssembly(7, 1, 3L, new double[] {5.0, 10.0, -2.0}, new float[] {0.0f, 0.0f, 0.0f, 1.0f},
                        DEFINITION);

        assertEquals(1, stream.count());
        byte[] bytes = stream.toBytes();
        // ADR-156 D4 : en-tête de flux (8), en-tête de commande (8), l'en-tête CreateAssembly seul (72).
        assertEquals(8 + 8 + 72, bytes.length);
        ByteBuffer b = wrap(bytes);
        assertEquals(SimCommandStream.OP_CREATE_ASSEMBLY, b.getInt(8), "opcode");
        assertEquals(72, b.getInt(12), "payload_len");
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
        assertEquals(DEFINITION, b.getLong(72), "definition, à l'octet 56 de l'en-tête");
        assertEquals(0, b.get(80), "flags");
        for (int at = 81; at < 88; at++) {
            assertEquals(0, b.get(at), "réservé " + at);
        }
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
    void encodeLEtatRestaureApresLEnTete() {
        // ADR-153 D3, ADR-156 D4 : l'état restauré suit l'en-tête — 64 octets par corps, 8 par joint ;
        // les drapeaux RESTORED et ASLEEP sont dans l'en-tête.
        SimCommandStream stream = new SimCommandStream().createAssembly(7, 1, 0L, new double[] {0, 64, 0},
                new float[] {0, 0, 0, 1}, DEFINITION, etatRestaure(3f, 0f), true);

        byte[] bytes = stream.toBytes();
        ByteBuffer b = wrap(bytes);
        assertEquals(SimCommandStream.CREATE_ASSEMBLY_RESTORED | SimCommandStream.CREATE_ASSEMBLY_ASLEEP, b.get(80),
                "drapeaux");
        assertEquals(72 + 2 * 64 + 8, b.getInt(12), "payload_len");
        int restored = 16 + 72;
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
        assertEquals(16 + 72 + 2 * 64 + 8, bytes.length, "rien après l'état");
    }

    @Test
    void sansEtatRestaureLeDrapeauRestoredEstNul() {
        SimCommandStream stream = new SimCommandStream().createAssembly(7, 1, 0L, new double[] {0, 64, 0},
                new float[] {0, 0, 0, 1}, DEFINITION, null, true);
        assertEquals(SimCommandStream.CREATE_ASSEMBLY_ASLEEP, wrap(stream.toBytes()).get(80));
        assertEquals(72, wrap(stream.toBytes()).getInt(12), "l'en-tête seul");
    }

    @Test
    void unEtatRestaureFautifEstRefuseAvantLeNatif() {
        double[] position = {0, 64, 0};
        float[] rotation = {0, 0, 0, 1};
        assertThrows(IllegalArgumentException.class, () -> new SimCommandStream().createAssembly(7, 1, 0L, position,
                rotation, DEFINITION, etatRestaure(Float.NaN, 0f), false), "vitesse non finie");
        assertThrows(IllegalArgumentException.class, () -> new SimCommandStream().createAssembly(7, 1, 0L, position,
                rotation, DEFINITION, etatRestaure(3f, -0.5f), false), "désalignement négatif");
        assertThrows(IllegalArgumentException.class, () -> new SimCommandStream().createAssembly(7, 1, 0L, position,
                rotation, DEFINITION, new SimCommandStream.Restored(List.of(), List.of()), false),
                "sans corps racine");
    }

    @Test
    void leSchemaEtLEnTeteSontCeuxDuNatif() throws IOException {
        // Un écart ici ne se verrait qu'au premier CREATE_ASSEMBLY, abandonné en silence par le natif.
        Path source = Path.of(System.getProperty("axion.crates.dir"), "ax-model", "src", "dm", "commands.rs");
        String rust = Files.readString(source, StandardCharsets.UTF_8);
        Matcher schema = Pattern.compile("pub const CURRENT_SCHEMA: u32 = (\\d+);").matcher(rust);
        assertTrue(schema.find(), "CURRENT_SCHEMA dans commands.rs");
        assertEquals(Integer.parseInt(schema.group(1)), SimCommandStream.CURRENT_SCHEMA);
        Matcher header = Pattern.compile("impl CreateAssembly \\{[^}]*?pub const BYTES: usize = (\\d+);").matcher(rust);
        assertTrue(header.find(), "CreateAssembly::BYTES dans commands.rs");
        assertEquals(Integer.parseInt(header.group(1)), SimCommandStream.CREATE_ASSEMBLY_HEADER_BYTES);
    }

    @Test
    void createAssemblyAligneLaCommandeSuivante() {
        // Le lecteur natif avance d'align8(payload_len) : la commande suivante reste alignée.
        SimCommandStream stream = new SimCommandStream()
                .createAssembly(1, 1, 0L, new double[] {0, 0, 0}, new float[] {0, 0, 0, 1}, DEFINITION)
                .setDimensionEnv(0L, new float[] {0, -9.81f, 0}, new float[] {0, 0, 0}, 0, 0, false);

        byte[] bytes = stream.toBytes();
        int second = 8 + 8 + 72;
        assertEquals(0, second % 8, "la seconde commande est alignée sur 8");
        assertEquals(SimCommandStream.OP_SET_DIMENSION_ENV, wrap(bytes).getInt(second),
                "la seconde commande suit l'en-tête");
    }

    @Test
    void createAssemblyRefuseUnePoseInvalide() {
        assertThrows(IllegalArgumentException.class, () -> new SimCommandStream()
                .createAssembly(1, 1, 0L, new double[] {0, 0, 0}, new float[] {0, 0, 1}, DEFINITION),
                "quaternion incomplet");
        assertThrows(IllegalArgumentException.class, () -> new SimCommandStream()
                .createAssembly(1, 1, 0L, new double[] {0, 0}, new float[] {0, 0, 0, 1}, DEFINITION),
                "position incomplète");
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
    void encodeUneEntreeDeVehicule() throws IOException {
        // ADR-159, tranche 3 : SetVehicleInput, 36 octets — handle, VehicleInput de 12.8, drapeaux ; complétés
        // à 40 pour la commande suivante.
        VehicleInput entree = new VehicleInput(0.5f, 0.25f, -0.75f, 0.125f, 1f, (byte) -1, 0);
        byte[] bytes = new SimCommandStream().setVehicleInput(7, 3, entree, true).removeAssembly(9, 1).toBytes();
        assertEquals(8 + (8 + 40) + (8 + 8), bytes.length);
        ByteBuffer b = wrap(bytes);
        assertEquals(SimCommandStream.OP_SET_VEHICLE_INPUT, b.getInt(8), "opcode");
        assertEquals(36, b.getInt(12), "payload_len");
        assertEquals(7, b.getInt(16), "handle.index");
        assertEquals(3, b.getInt(20), "handle.generation");
        assertEquals(0.5f, b.getFloat(24), "throttle");
        assertEquals(1f, b.getFloat(40), "handbrake");
        assertEquals(-1, b.get(44), "gear, i8");
        assertEquals(0, b.get(45), "flags de l'entrée");
        assertEquals(SimCommandStream.VEHICLE_INPUT_DRIVER, b.getInt(48), "conducteur présent");
        assertEquals(SimCommandStream.OP_REMOVE_ASSEMBLY, b.getInt(8 + 8 + 40), "commande suivante, alignée");
        byte[] sans = new SimCommandStream().setVehicleInput(7, 3, VehicleInput.NONE, false).toBytes();
        assertEquals(0, wrap(sans).getInt(48), "siège libre");

        Path source = Path.of(System.getProperty("axion.crates.dir"), "ax-model", "src", "dm", "commands.rs");
        String rust = Files.readString(source, StandardCharsets.UTF_8);
        assertTrue(rust.contains("pub const SET_VEHICLE_INPUT: u32 = " + SimCommandStream.OP_SET_VEHICLE_INPUT + ";"));
        Matcher taille = Pattern.compile("impl SetVehicleInput \\{[^}]*?pub const BYTES: usize = (\\d+);").matcher(rust);
        assertTrue(taille.find(), "SetVehicleInput::BYTES dans commands.rs");
        assertEquals(Integer.parseInt(taille.group(1)), SimCommandStream.SET_VEHICLE_INPUT_BYTES);
        assertTrue(rust.contains("pub const DRIVER: u32 = 1 << 0;"));
    }

    @Test
    void encodeLeCoupleDesRoues() throws IOException {
        // R-1082, ADR-167 D9 : SetWheelTorques, 12 octets — handle, nombre de couples — puis les couples.
        byte[] bytes = new SimCommandStream().setWheelTorques(7, 3, new float[] {0f, 0f, 150f, -20f})
                .removeAssembly(9, 1).toBytes();
        assertEquals(8 + (8 + 32) + (8 + 8), bytes.length, "28 octets de payload, complétés à 32");
        ByteBuffer b = wrap(bytes);
        assertEquals(SimCommandStream.OP_SET_WHEEL_TORQUES, b.getInt(8), "opcode");
        assertEquals(12 + 4 * 4, b.getInt(12), "payload_len, couples compris");
        assertEquals(7, b.getInt(16), "handle.index");
        assertEquals(4, b.getShort(16 + 8), "count");
        assertEquals(150f, b.getFloat(16 + 12 + 8), "troisième roue");
        assertEquals(SimCommandStream.OP_REMOVE_ASSEMBLY, b.getInt(8 + 8 + 32), "commande suivante, alignée");
        assertThrows(IllegalArgumentException.class, () -> new SimCommandStream().setWheelTorques(7, 3, new float[33]));

        Path source = Path.of(System.getProperty("axion.crates.dir"), "ax-model", "src", "dm", "commands.rs");
        String rust = Files.readString(source, StandardCharsets.UTF_8);
        assertTrue(rust.contains("pub const SET_WHEEL_TORQUES: u32 = " + SimCommandStream.OP_SET_WHEEL_TORQUES + ";"));
        Matcher taille = Pattern.compile("impl SetWheelTorques \\{[^}]*?pub const BYTES: usize = (\\d+);").matcher(rust);
        assertTrue(taille.find(), "SetWheelTorques::BYTES dans commands.rs");
        assertEquals(Integer.parseInt(taille.group(1)), SimCommandStream.SET_WHEEL_TORQUES_BYTES);
    }

    @Test
    void encodeLesParametresDUneInstance() throws IOException {
        // ADR-167 D6 : SetParameters, 72 octets — handle, quinze f32, nombre de rapports — puis les rapports.
        float[] valeurs = new float[SimCommandStream.PARAMETER_FIELDS.size()];
        java.util.Arrays.fill(valeurs, Float.NaN);
        valeurs[0] = 1100f;
        valeurs[1] = 390f;
        byte[] bytes = new SimCommandStream().setParameters(7, 3, valeurs, new float[] {-3.2f, 0f, 3.5f})
                .removeAssembly(9, 1).toBytes();
        assertEquals(8 + (8 + 88) + (8 + 8), bytes.length, "84 octets de payload, complétés à 88");
        ByteBuffer b = wrap(bytes);
        assertEquals(SimCommandStream.OP_SET_PARAMETERS, b.getInt(8), "opcode");
        assertEquals(72 + 3 * 4, b.getInt(12), "payload_len, rapports compris");
        assertEquals(7, b.getInt(16), "handle.index");
        assertEquals(1100f, b.getFloat(24), "mass");
        assertEquals(390f, b.getFloat(28), "torque");
        assertTrue(Float.isNaN(b.getFloat(32)), "max_rpm : la base");
        assertEquals(3, b.getShort(16 + 68), "gear_count");
        assertEquals(-3.2f, b.getFloat(16 + 72), "premier rapport");
        assertEquals(3.5f, b.getFloat(16 + 80), "dernier rapport");
        assertEquals(SimCommandStream.OP_REMOVE_ASSEMBLY, b.getInt(8 + 8 + 88), "commande suivante, alignée");
        assertThrows(IllegalArgumentException.class,
                () -> new SimCommandStream().setParameters(7, 3, new float[3], new float[0]));

        Path source = Path.of(System.getProperty("axion.crates.dir"), "ax-model", "src", "dm", "commands.rs");
        String rust = Files.readString(source, StandardCharsets.UTF_8);
        assertTrue(rust.contains("pub const SET_PARAMETERS: u32 = " + SimCommandStream.OP_SET_PARAMETERS + ";"));
        Matcher taille = Pattern.compile("impl SetParameters \\{[^}]*?pub const BYTES: usize = (\\d+);").matcher(rust);
        assertTrue(taille.find(), "SetParameters::BYTES dans commands.rs");
        assertEquals(Integer.parseInt(taille.group(1)), SimCommandStream.SET_PARAMETERS_BYTES);
        // L'ordre des champs : celui de la struct Rust, nom pour nom.
        Matcher struct = Pattern.compile("pub struct SetParameters \\{(.*?)\\R\\}", Pattern.DOTALL).matcher(rust);
        assertTrue(struct.find());
        Matcher champ = Pattern.compile("pub (\\w+): f32,").matcher(struct.group(1));
        java.util.List<String> champs = new java.util.ArrayList<>();
        while (champ.find()) {
            champs.add(champ.group(1));
        }
        assertEquals(java.util.List.of("mass", "torque", "max_rpm", "final_drive", "shift_time", "brake_front",
                "brake_rear", "handbrake", "grip", "stiffness", "damping", "max_angle", "drag", "downforce_front",
                "downforce_rear"), champs);
        assertEquals(java.util.List.of("mass", "engine.torque", "engine.max_rpm", "transmission.final_drive",
                "transmission.shift_time", "brakes.front", "brakes.rear", "brakes.handbrake", "tires.grip",
                "suspension.stiffness", "suspension.damping", "steering.max_angle", "aero.drag", "aero.downforce_front",
                "aero.downforce_rear"), SimCommandStream.PARAMETER_FIELDS, "un paramètre par champ, dans le même ordre");
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
    void encodeUneCommandeAnimateAssembly() {
        byte[] bytes = new SimCommandStream()
                .animateAssembly(5, 1, 40.5, 4, 2, 0, 0f, 0f, 1f, 0.5f, new int[] {3, 7})
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
        assertEquals(0.5f, b.getFloat(60), "temps de clip");
        assertEquals(3, b.getInt(16 + SimCommandStream.ANIMATE_ASSEMBLY_BYTES));
        assertEquals(7, b.getInt(16 + SimCommandStream.ANIMATE_ASSEMBLY_BYTES + 4));
    }

    @Test
    void encodeLesCommandesDeLaPrediction() throws IOException {
        // ADR-168 D1 : SetSimMode (4 octets), SetStateExport (12), SetAssemblyState (12, puis le bloc).
        byte[] bloc = new byte[16];
        bloc[0] = 1;
        byte[] bytes = new SimCommandStream().setSimMode(SimCommandStream.SIM_MODE_CLIENT_PREDICTED)
                .setStateExport(7, 3, true).setAssemblyState(7, 3, bloc).toBytes();
        assertEquals(8 + (8 + 8) + (8 + 16) + (8 + 28 + 4), bytes.length, "chaque payload complété à 8");
        ByteBuffer b = wrap(bytes);
        assertEquals(SimCommandStream.OP_SET_SIM_MODE, b.getInt(8));
        assertEquals(4, b.getInt(12));
        assertEquals(SimCommandStream.SIM_MODE_CLIENT_PREDICTED, b.getInt(16));
        assertEquals(SimCommandStream.OP_SET_STATE_EXPORT, b.getInt(24));
        assertEquals(12, b.getInt(28));
        assertEquals(1, b.getInt(32 + 8), "exporter");
        assertEquals(SimCommandStream.OP_SET_ASSEMBLY_STATE, b.getInt(48));
        assertEquals(12 + 16, b.getInt(52), "payload_len, bloc compris");
        assertEquals(16, b.getInt(56 + 8), "longueur du bloc");
        assertEquals(1, b.get(56 + 12), "le bloc, tel quel");
        assertThrows(IllegalArgumentException.class, () -> new SimCommandStream().setSimMode(3));
        assertThrows(IllegalArgumentException.class, () -> new SimCommandStream().setAssemblyState(7, 3, new byte[12]),
                "un bloc d'état est un multiple de 8 octets");
        assertThrows(IllegalArgumentException.class, () -> new SimCommandStream().setAssemblyState(7, 3,
                new byte[SimCommandStream.MAX_ASSEMBLY_STATE_BYTES + 8]));

        Path crates = Path.of(System.getProperty("axion.crates.dir"));
        String commandes = Files.readString(crates.resolve("ax-model/src/dm/commands.rs"), StandardCharsets.UTF_8);
        assertTrue(commandes.contains("pub const SET_SIM_MODE: u32 = " + SimCommandStream.OP_SET_SIM_MODE + ";"));
        assertTrue(commandes.contains("pub const SET_STATE_EXPORT: u32 = " + SimCommandStream.OP_SET_STATE_EXPORT + ";"));
        assertTrue(commandes.contains("pub const SET_ASSEMBLY_STATE: u32 = " + SimCommandStream.OP_SET_ASSEMBLY_STATE
                + ";"));
        assertTrue(commandes.contains("pub const CLIENT_PREDICTED: u32 = " + SimCommandStream.SIM_MODE_CLIENT_PREDICTED
                + ";"));
        String etat = Files.readString(crates.resolve("ax-physics/src/world/state.rs"), StandardCharsets.UTF_8);
        java.util.function.ToIntFunction<String> constante = nom -> {
            Matcher m = Pattern.compile("pub const " + nom + ": usize = (\\d+);").matcher(etat);
            assertTrue(m.find(), nom);
            return Integer.parseInt(m.group(1));
        };
        assertEquals(constante.applyAsInt("HEADER_BYTES") + 64 * constante.applyAsInt("BODY_BYTES")
                + 128 * constante.applyAsInt("JOINT_BYTES") + constante.applyAsInt("VEHICLE_BYTES")
                + 32 * constante.applyAsInt("WHEEL_BYTES"), SimCommandStream.MAX_ASSEMBLY_STATE_BYTES,
                "le plus grand bloc, miroir de world::state::MAX_STATE_BYTES");
    }

    @Test
    void litLaSectionDesEtatsExportes() {
        // ADR-168 D1 : le nombre d'entrées, puis chacune — handle, longueur, statut, bloc aligné sur 8.
        ByteBuffer section = ByteBuffer.allocate(8 + 16 + 16 + 16 + 8).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        section.putInt(2).putInt(0);
        section.putInt(7).putInt(1).putInt(16).putInt(AssemblyStateReport.OK);
        for (int i = 0; i < 16; i++) {
            section.put((byte) i);
        }
        section.putInt(9).putInt(2).putInt(3).putInt(AssemblyStateReport.IRRECONCILABLE).put(new byte[] {1, 2, 3, 0, 0,
                0, 0, 0});
        java.util.List<AssemblyStateReport> lus = AssemblyStateReport.decodeSection(section, 0);
        assertEquals(2, lus.size());
        assertEquals(7, lus.get(0).handleIndex());
        assertEquals(16, lus.get(0).state().length);
        assertEquals(AssemblyStateReport.IRRECONCILABLE, lus.get(1).status());
        assertEquals(3, lus.get(1).state().length, "le bloc sans son bourrage");
        ByteBuffer tronquee = ByteBuffer.allocate(8 + 16 + 8).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        tronquee.putInt(1).putInt(0).putInt(7).putInt(1).putInt(16).putInt(0);
        assertEquals(java.util.List.of(), AssemblyStateReport.decodeSection(tronquee, 0), "tronquée : rien");
    }
}
