package dev.axion.physics;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** C-34, ADR-151 D3, D7 : la cohérence d'un joint avec son type, et sa transcription. */
class AssemblyJointsTest {

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static String faute(String joint) {
        AssemblyJoints.Fault fault = AssemblyJoints.check(json(joint));
        return fault == null ? null : fault.field();
    }

    private static final String CHARNIERE = """
            { "name": "charniere", "type": "revolute", "a": "caisse", "b_node": "porte",
              "anchor": [0, 0, 0.5], "axis": [0, 2, 0], "limits": [0.0, 1.6],
              "motor": { "target": 1.2, "stiffness": 50, "damping": 5, "max_force": 200 },
              "friction": 4.0, "break": { "force": 8000, "energy": 400 },
              "jam": { "curve": [[0, 1.0], [0.05, 0.5], [0.12, 0.0]], "friction": 60 }, "collide": true }
            """;

    @Test
    @DisplayName("Une charnière complète est cohérente ; chaque règle refuse ce qu'elle doit")
    void regles() {
        assertNull(faute(CHARNIERE));
        assertEquals("a", faute("{\"name\": \"j\", \"type\": \"fixed\", \"b_node\": \"n\"}"));
        assertEquals("limits", faute("{\"name\": \"j\", \"type\": \"rope\", \"a\": \"c\", \"b_node\": \"n\","
                + " \"max_length\": 2, \"limits\": [0, 1]}"));
        assertEquals("max_length", faute("{\"name\": \"j\", \"type\": \"rope\", \"a\": \"c\", \"b_node\": \"n\"}"));
        assertEquals("axis", faute("{\"name\": \"j\", \"type\": \"revolute\", \"a\": \"c\", \"b_node\": \"n\","
                + " \"axis\": [0, 0, 0]}"));
        assertEquals("limits", faute("{\"name\": \"j\", \"type\": \"prismatic\", \"a\": \"c\", \"b_node\": \"n\","
                + " \"axis\": [1, 0, 0], \"limits\": [1, 0]}"));
        assertEquals("motor.stiffness", faute("{\"name\": \"j\", \"type\": \"revolute\", \"a\": \"c\","
                + " \"b_node\": \"n\", \"axis\": [1, 0, 0], \"motor\": {\"target\": 1}}"));
        assertEquals("motor", faute("{\"name\": \"j\", \"type\": \"revolute\", \"a\": \"c\","
                + " \"b_node\": \"n\", \"axis\": [1, 0, 0], \"motor\": {\"max_force\": 1}}"));
        assertEquals("jam", faute("{\"name\": \"j\", \"type\": \"revolute\", \"a\": \"c\", \"b_node\": \"n\","
                + " \"axis\": [1, 0, 0], \"jam\": {\"curve\": [[0, 1]]}}"));
        assertEquals("jam.curve[1]", faute("{\"name\": \"j\", \"type\": \"revolute\", \"a\": \"c\","
                + " \"b_node\": \"n\", \"axis\": [1, 0, 0], \"limits\": [0, 1],"
                + " \"jam\": {\"curve\": [[0.1, 1], [0.1, 0.5]]}}"));
        assertEquals("jam_curve[0]", faute("{\"name\": \"j\", \"type\": \"revolute\", \"a\": \"c\","
                + " \"b_node\": \"n\", \"axis\": [1, 0, 0], \"limits\": [0, 1], \"jam_curve\": [[0, 1.5]]}"));
        assertEquals("axes[3]", faute("{\"name\": \"j\", \"type\": \"generic\", \"a\": \"c\", \"b_node\": \"n\","
                + " \"axes\": [{\"mode\": \"locked\"}, {\"mode\": \"locked\"}, {\"mode\": \"free\"},"
                + " {\"mode\": \"limited\"}, {\"mode\": \"locked\"}, {\"mode\": \"locked\"}]}"));
        assertEquals("stiffness", faute("{\"name\": \"j\", \"type\": \"spring\", \"a\": \"c\", \"b_node\": \"n\","
                + " \"rest_length\": 1}"));
    }

    @Test
    @DisplayName("La transcription : drapeaux déclarés, axe normalisé, seuils absents à +inf, courbe en annexe")
    void transcription() {
        JsonObject definition = json("{\"joints\": [" + CHARNIERE + ", "
                + "{\"name\": \"corde\", \"type\": \"rope\", \"a\": \"caisse\", \"b_node\": \"poids\", \"max_length\": 3}]}");
        AssemblyJoints.Transcription t = AssemblyJoints.of(
                definition,
                List.of("racine", "porte", "poids"),
                Map.of("caisse", 0, "porte", 1, "poids", 2),
                Map.of(0, 0, 1, 1, 2, 2));

        JointDescription charniere = t.joints().get(0);
        assertEquals(JointDescription.KIND_REVOLUTE, charniere.kind());
        assertEquals(JointDescription.FLAG_LIMITS | JointDescription.FLAG_MOTOR_POSITION
                | JointDescription.FLAG_FRICTION | JointDescription.FLAG_JAM | JointDescription.FLAG_COLLIDE,
                charniere.flags());
        assertEquals(0, charniere.bodyA());
        assertEquals(1, charniere.bodyB());
        assertEquals(1, charniere.bNode());
        assertArrayEquals(new float[] {0, 1, 0}, charniere.axis(), 1e-6f);
        assertEquals(8000f, charniere.breakForce());
        assertEquals(Float.POSITIVE_INFINITY, charniere.breakTorque());
        assertEquals(60f, charniere.jamFriction());
        assertEquals(0, charniere.jamFirst());
        assertEquals(3, charniere.jamCount());

        JointDescription corde = t.joints().get(1);
        assertEquals(JointDescription.KIND_ROPE, corde.kind());
        assertEquals(0, corde.flags());
        assertEquals(3f, corde.maxLength());
        assertEquals(Float.POSITIVE_INFINITY, corde.motorMaxForce());
        assertEquals(3, t.jamPoints().size());
        assertArrayEquals(new float[] {0.05f, 0.5f}, t.jamPoints().get(1));
    }

    @Test
    @DisplayName("Le schéma 1 : break_force et jam_curve, lus comme break.force et jam.curve")
    void schemaUn() {
        JsonObject definition = json("{\"joints\": [{\"name\": \"capot\", \"type\": \"revolute\", \"a\": \"caisse\","
                + " \"b_node\": \"capot\", \"axis\": [1, 0, 0], \"limits\": [0, 1.2], \"break_force\": 6000,"
                + " \"jam_curve\": [[0, 1], [0.1, 0]]}]}");
        JointDescription capot = AssemblyJoints.of(definition, List.of("racine", "capot"),
                Map.of("caisse", 0), Map.of(0, 0, 1, 1)).joints().get(0);
        assertEquals(6000f, capot.breakForce());
        assertEquals(JointDescription.FLAG_LIMITS | JointDescription.FLAG_JAM, capot.flags());
        assertEquals(2, capot.jamCount());
    }

    @Test
    @DisplayName("Un joint fautif, un corps ou un node introuvable, les deux bouts sur un même corps : aucun joint")
    void irresolus() {
        List<String> nodes = List.of("racine", "porte");
        Map<String, Integer> corps = Map.of("caisse", 0);
        Map<Integer, Integer> racines = Map.of(0, 0, 1, 1);
        String joint = "{\"joints\": [{\"name\": \"j\", \"type\": \"fixed\", \"a\": \"%s\", \"b_node\": \"%s\"}]}";
        assertNull(AssemblyJoints.of(json(String.format(joint, "absent", "porte")), nodes, corps, racines));
        assertNull(AssemblyJoints.of(json(String.format(joint, "caisse", "absent")), nodes, corps, racines));
        assertNull(AssemblyJoints.of(json(String.format(joint, "caisse", "racine")), nodes, corps, racines));
        assertNull(AssemblyJoints.of(json("{\"joints\": [{\"name\": \"j\", \"type\": \"rope\", \"a\": \"caisse\","
                + " \"b_node\": \"porte\"}]}"), nodes, corps, racines));
        assertEquals(0, AssemblyJoints.of(json("{}"), nodes, corps, racines).joints().size());
    }

    @Test
    @DisplayName("JointDescription : 160 octets, dans la disposition du DM natif JointDesc")
    void disposition() {
        float[][] limites = new float[6][2];
        limites[3] = new float[] {-0.1f, 0.1f};
        JointDescription joint = new JointDescription(4, 0x21, 2, 3, new int[] {0, 0, 1, 2, 0, 0}, 7,
                new float[] {1, 2, 3}, new float[] {0, 1, 0}, new float[] {-1, 1}, 0.5f, 1.2f, 50f, 5f, 200f, 4f,
                8000f, Float.POSITIVE_INFINITY, 400f, 60f, 9, 3, limites, 11f, 12f, 13f, 14f);
        ByteBuffer b = ByteBuffer.allocate(JointDescription.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        joint.writeTo(b);
        assertEquals(JointDescription.BYTES, b.position());
        assertEquals(4, b.get(0));
        assertEquals(0x21, b.get(1));
        assertEquals(2, b.getShort(2));
        assertEquals(3, b.getShort(4));
        assertEquals(2, b.get(9), "mode du quatrième axe");
        assertEquals(7, b.getInt(12));
        assertEquals(1f, b.getFloat(16));
        assertEquals(1f, b.getFloat(32), "axis.y");
        assertEquals(-1f, b.getFloat(40));
        assertEquals(0.5f, b.getFloat(48));
        assertEquals(4f, b.getFloat(68));
        assertEquals(8000f, b.getFloat(72));
        assertEquals(Float.POSITIVE_INFINITY, b.getFloat(76));
        assertEquals(60f, b.getFloat(84));
        assertEquals(9, b.getInt(88));
        assertEquals(3, b.getInt(92));
        assertEquals(-0.1f, b.getFloat(96 + 3 * 8));
        assertEquals(11f, b.getFloat(144));
        assertEquals(14f, b.getFloat(156));
    }
}
