package dev.axion.definition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-156 D2 : le {@code DEFN} qu'écrit Java, tel que le natif le lit. */
class DefinitionEncoderTest {

    private static final String PICKUP = "mymod:axion/models/pickup.glb";

    private static final DefinitionRules RULES = new DefinitionRules(true, Set.of(PICKUP)::contains);

    /** Une porte sur un cadre : un corps déclaré, une charnière qui crée le second, une part déformable. */
    private static final String PORTE = """
            {
              "schema": 2,
              "asset": "mymod:models/pickup.glb",
              "kind": "articulated",
              "physics": { "mass": 30.0, "self_collision": true, "collides_with": ["world"] },
              "bodies": [ { "name": "cadre", "root_node": "cadre", "mass": 10.0 } ],
              "joints": [ { "name": "charniere", "type": "revolute", "a": "cadre", "b_node": "porte",
                            "axis": [0, 1, 0], "limits": [-1.0, 1.0],
                            "jam": { "curve": [[0.5, 0.0]], "friction": 5.0 } } ],
              "parts": [ { "name": "battant", "root_node": "porte", "material": "mymod:chene",
                           "deform_regions": ["face"] } ]
            }
            """;

    private static Definition read(String json) throws DefinitionException {
        return DefinitionRegistry.read(
                "mymod:axion/definitions/porte.json", json.getBytes(StandardCharsets.UTF_8), RULES);
    }

    private static DefinitionEncoder.Encoded encode(String json, boolean withJoints) throws DefinitionException {
        return DefinitionEncoder.encode(read(json), withJoints, CollisionGroupRegistry.empty(), name -> 7);
    }

    private static ByteBuffer le(byte[] bytes) {
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    @Test
    @DisplayName("ADR-156 D2 : en-tête, références, corps, joint, point et part, à leur place")
    void disposition() throws DefinitionException {
        DefinitionEncoder.Encoded encoded = encode(PORTE, true);
        ByteBuffer in = le(encoded.bytes());
        assertEquals("DEFN", new String(encoded.bytes(), 0, 4, StandardCharsets.US_ASCII));
        assertEquals(DefinitionEncoder.VERSION, in.getShort(4));
        assertEquals(DefinitionEncoder.FLAG_SELF_COLLISION, in.getShort(6), "self_collision");
        assertEquals(CollisionFilterBits.ASSEMBLY, in.getInt(8), "membre d'assembly par défaut");
        assertEquals(CollisionFilterBits.WORLD, in.getInt(12), "ne heurte que world");
        assertEquals(30.0f, in.getFloat(16), "masse de l'assembly");
        assertEquals(3, in.getInt(20), "références");
        assertEquals(1, in.getShort(24), "corps déclarés");
        assertEquals(1, in.getShort(26), "joints");
        assertEquals(1, in.getShort(28), "points de grippage");
        assertEquals(1, in.getShort(30), "parts");
        // Le corps implicite, après la description de l'assembly : dynamique, sans masse ni centre propres.
        int implicit = 32 + dev.axion.physics.BodyDescription.WIRE_BYTES;
        assertEquals(2, in.get(implicit), "dynamique");
        assertEquals(0.0f, in.getFloat(implicit + 4), "sans masse propre");
        assertTrue(Float.isNaN(in.getFloat(implicit + 8)), "centre de masse de ses densités");

        // Les références, dans l'ordre du document.
        List<String> paths = encoded.references().stream().map(DefinitionChecker.AssetReference::path).toList();
        assertEquals(List.of("$.bodies[0].root_node", "$.joints[0].b_node", "$.parts[0].root_node"), paths);
        int refs = DefinitionEncoder.HEADER_BYTES;
        assertEquals(DefinitionIds.hash("cadre"), in.getLong(refs));
        assertEquals(DefinitionEncoder.CATEGORY_NODE, in.get(refs + 8));
        assertEquals(DefinitionIds.hash("porte"), in.getLong(refs + 16));

        // Le corps racine à la référence 0, avec sa masse propre.
        int bodies = refs + 3 * DefinitionEncoder.REFERENCE_BYTES;
        assertEquals(10.0f, in.getFloat(bodies + 4), "masse du corps racine");
        assertEquals(0, in.getInt(bodies + 56), "rang de la référence de son root_node");

        // Le joint : son corps a, son b_node au rang de sa référence, le corps b laissé au natif.
        int joints = bodies + DefinitionEncoder.BODY_BYTES;
        assertEquals(0, in.getShort(joints + 2), "corps a");
        assertEquals((short) 0xFFFF, in.getShort(joints + 4), "corps b tranché par le natif");
        assertEquals(1, in.getInt(joints + 12), "b_node : référence 1");

        int points = joints + dev.axion.physics.JointDescription.BYTES;
        assertEquals(0.5f, in.getFloat(points));
        int parts = points + dev.axion.physics.JointDescription.JAM_POINT_BYTES;
        assertEquals(2, in.getInt(parts), "racine de la part : référence 2");
        assertEquals(7, in.getShort(parts + 4), "index de son matériau");
        assertEquals(DefinitionEncoder.PART_DEFORMABLE, in.getShort(parts + 6), "elle porte une région");
        assertEquals(parts + DefinitionEncoder.PART_BYTES, encoded.bytes().length, "rien après la dernière part");
    }

    @Test
    @DisplayName("ADR-156 D2 : modules.joints désactivé, ni joint ni point de grippage")
    void sansJoints() throws DefinitionException {
        ByteBuffer in = le(encode(PORTE, false).bytes());
        assertEquals(0, in.getShort(26));
        assertEquals(0, in.getShort(28));
    }

    @Test
    @DisplayName("ADR-156 D2 : sans bodies, aucun corps déclaré ; la racine de l'asset porte l'assembly")
    void sansCorps() throws DefinitionException {
        ByteBuffer in = le(encode("""
                { "schema": 1, "asset": "mymod:models/pickup.glb", "kind": "rigid_object" }
                """, true).bytes());
        assertEquals(0, in.getInt(20));
        assertEquals(0, in.getShort(24));
        assertEquals(2, in.get(32), "dynamique");
    }

    @Test
    @DisplayName("R-580 : un corps autre que le premier sans root_node refuse la definition, à son chemin")
    void secondCorpsSansRacine() {
        DefinitionException refus = assertThrows(DefinitionException.class, () -> encode("""
                { "schema": 2, "asset": "mymod:models/pickup.glb", "kind": "articulated",
                  "bodies": [ { "name": "cadre" }, { "name": "porte" } ] }
                """, true));
        assertEquals("E-7001", refus.codeName());
        assertEquals("$.bodies[1]", refus.path());
    }

    @Test
    @DisplayName("ADR-156 D4 : le rang que le natif rend se ramène au chemin et au nom")
    void locate() throws DefinitionException {
        DefinitionEncoder.Encoded encoded = encode(PORTE, true);
        assertEquals("$.joints[0].b_node « porte »", encoded.locate(1));
        assertEquals("$", encoded.locate(-1), "un refus sans référence désigne le document");
        assertEquals("$", encoded.locate(3));
    }

    @Test
    @DisplayName("ADR-156 D2 : la disposition est celle d'ax_model::dm::definition")
    void pariteAvecLeNatif() throws IOException {
        Path source = Path.of(System.getProperty("axion.crates.dir"), "ax-model", "src", "dm", "definition.rs");
        String rust = Files.readString(source);
        for (String fragment : List.of(
                "pub const DEFN_VERSION: u16 = " + DefinitionEncoder.VERSION + ";",
                "pub const BYTES: usize = 32 + 2 * BodyDesc::WIRE_BYTES;",
                "pub const BYTES: usize = " + DefinitionEncoder.REFERENCE_BYTES + ";",
                "pub const BYTES: usize = " + DefinitionEncoder.PART_BYTES + ";",
                "pub const SELF_COLLISION: u16 = 1 << 0;",
                "pub const DEFORMABLE: u16 = 1 << 0;",
                "pub const NODE: u8 = " + DefinitionEncoder.CATEGORY_NODE + ";",
                "pub const CLIP: u8 = " + DefinitionEncoder.CATEGORY_CLIP + ";",
                "pub const BONE: u8 = " + DefinitionEncoder.CATEGORY_BONE + ";")) {
            assertTrue(rust.contains(fragment), () -> "absent de definition.rs : " + fragment);
        }
        assertEquals(1, DefinitionEncoder.FLAG_SELF_COLLISION);
        assertEquals(1, DefinitionEncoder.PART_DEFORMABLE);
        assertEquals(144, DefinitionEncoder.HEADER_BYTES);
    }

    /** Les bits des groupes réservés que le test attend, ceux de R-980. */
    private static final class CollisionFilterBits {
        static final int WORLD = dev.axion.physics.CollisionFilter.bit("world");
        static final int ASSEMBLY = dev.axion.physics.CollisionFilter.bit("assembly");
    }
}
