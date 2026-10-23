package dev.axion.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-151 D2 : les corps qu'une definition déclare, résolus contre la section NODE. */
class AssemblyBodiesTest {

    /** Une section NODE réduite à ses noms : les nodes eux-mêmes ne sont pas lus ici. */
    private static byte[] nodes(String... names) {
        byte[][] encoded = new byte[names.length][];
        int size = 0;
        for (int i = 0; i < names.length; i++) {
            encoded[i] = names[i].getBytes(StandardCharsets.UTF_8);
            size += encoded[i].length;
        }
        ByteBuffer out = ByteBuffer.allocate(8 + names.length * (80 + 8) + size).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(names.length).putInt(size);
        out.position(8 + names.length * 80);
        int offset = 0;
        for (byte[] name : encoded) {
            out.putInt(offset).putInt(name.length);
            offset += name.length;
        }
        for (byte[] name : encoded) {
            out.put(name);
        }
        return out.array();
    }

    private static JsonObject definition(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static final BodyDescription PHYSIQUE = BodyDescription.of(
            definition("{\"physics\": {\"mass\": 120, \"linear_damping\": 0.5, \"center_of_mass\": [0, 0.5, 0]}}"),
            SimCommandStream.BODY_DYNAMIC);

    @Test
    @DisplayName("Sans bodies : un seul corps, à la racine de l'asset, la description telle quelle")
    void sansBodies() {
        AssemblyBodies.Layout layout = AssemblyBodies.of(definition("{}"), nodes("racine"), PHYSIQUE, true);
        assertEquals(List.of(new SimCommandStream.AssemblyBody(PHYSIQUE, 0)), layout.bodies());
        assertEquals(0.0f, layout.assemblyMass());
        assertEquals(0, layout.rootNode());
    }

    @Test
    @DisplayName("Un corps déclaré : son root_node résolu par son nom, la masse dans sa description")
    void unCorps() {
        AssemblyBodies.Layout layout = AssemblyBodies.of(
                definition("{\"bodies\": [{\"name\": \"caisse\", \"root_node\": \"chassis\"}]}"),
                nodes("racine", "chassis"),
                PHYSIQUE,
                true);
        assertEquals(List.of(new SimCommandStream.AssemblyBody(PHYSIQUE, 1)), layout.bodies());
        assertEquals(1, layout.rootNode());
        assertEquals(0.0f, layout.assemblyMass());
    }

    @Test
    @DisplayName("Plusieurs corps : la masse se répartit, les autres corps sont dynamiques et amortis pareil")
    void plusieursCorps() {
        AssemblyBodies.Layout layout = AssemblyBodies.of(
                definition("{\"bodies\": [{\"name\": \"a\", \"root_node\": \"a\"}, {\"name\": \"b\", \"root_node\": \"b\"}]}"),
                nodes("a", "a_vue", "b"),
                BodyDescription.of(
                        definition("{\"physics\": {\"mass\": 120, \"linear_damping\": 0.5, \"center_of_mass\": [0, 0.5, 0]}}"),
                        SimCommandStream.BODY_STATIC),
                true);

        assertEquals(120.0f, layout.assemblyMass());
        assertEquals(0, layout.rootNode());
        BodyDescription racine = layout.bodies().get(0).description();
        assertEquals(SimCommandStream.BODY_STATIC, racine.bodyType(), "le type de l'assembly");
        assertEquals(0.0f, racine.mass(), "la masse se répartit");
        assertEquals(0.5f, racine.centerOfMassY(), "le centre déclaré reste au corps racine");
        SimCommandStream.AssemblyBody second = layout.bodies().get(1);
        assertEquals(2, second.rootNode());
        assertEquals(SimCommandStream.BODY_DYNAMIC, second.description().bodyType());
        assertEquals(0.0f, second.description().mass());
        assertTrue(Float.isNaN(second.description().centerOfMassX()), "centre des densités");
        assertEquals(0.5f, second.description().linearDamping());
    }

    @Test
    @DisplayName("Un root_node qui ne nomme aucun node, ou un second corps sans node : aucun corps")
    void racineIntrouvable() {
        assertNull(AssemblyBodies.of(
                definition("{\"bodies\": [{\"name\": \"a\", \"root_node\": \"absent\"}]}"), nodes("a"), PHYSIQUE, true));
        assertNull(AssemblyBodies.of(
                definition("{\"bodies\": [{\"name\": \"a\"}, {\"name\": \"b\"}]}"), nodes("a", "b"), PHYSIQUE, true));
        assertNull(AssemblyBodies.of(
                definition("{\"bodies\": [{\"name\": \"a\", \"root_node\": \"a\"}]}"), null, PHYSIQUE, true));
    }

    @Test
    @DisplayName("Une section NODE tronquée ne donne aucun nom")
    void sectionTronquee() {
        byte[] complete = nodes("a", "b");
        assertEquals(List.of("a", "b"), AssemblyBodies.names(complete));
        assertEquals(List.of(), AssemblyBodies.names(Arrays.copyOf(complete, complete.length - 1)));
        assertEquals(List.of(), AssemblyBodies.names(new byte[3]));
    }

    /** Une caisse déclarée, et une porte qu'un joint fixe relie à elle par son {@code b_node}. */
    private static final String SOUDURE =
            "{\"bodies\": [{\"name\": \"caisse\"}], \"joints\": [{\"name\": \"s\", \"type\": \"fixed\","
                    + " \"a\": \"caisse\", \"b_node\": \"porte\"}]}";

    @Test
    @DisplayName("Le b_node d'un joint crée un corps à ce node, et le joint relie les deux corps")
    void corpsImplicite() {
        AssemblyBodies.Layout layout =
                AssemblyBodies.of(definition(SOUDURE), nodes("racine", "porte"), PHYSIQUE, true);

        assertEquals(2, layout.bodies().size());
        assertEquals(1, layout.bodies().get(1).rootNode(), "le corps implicite, au b_node");
        assertEquals(SimCommandStream.BODY_DYNAMIC, layout.bodies().get(1).description().bodyType());
        assertEquals(120.0f, layout.assemblyMass(), "la masse se répartit entre les deux");
        JointDescription joint = layout.joints().get(0);
        assertEquals(0, joint.bodyA());
        assertEquals(1, joint.bodyB());
        assertEquals(1, joint.bNode());
        assertEquals(List.of(), layout.jamPoints());
    }

    @Test
    @DisplayName("Un b_node déjà racine d'un corps déclaré ne crée pas de second corps")
    void bNodeDeclare() {
        AssemblyBodies.Layout layout = AssemblyBodies.of(
                definition("{\"bodies\": [{\"name\": \"caisse\"}, {\"name\": \"porte\", \"root_node\": \"porte\"}],"
                        + " \"joints\": [{\"name\": \"s\", \"type\": \"fixed\", \"a\": \"caisse\","
                        + " \"b_node\": \"porte\"}]}"),
                nodes("racine", "porte"),
                PHYSIQUE,
                true);
        assertEquals(2, layout.bodies().size());
        assertEquals(1, layout.joints().get(0).bodyB());
    }

    @Test
    @DisplayName("modules.joints désactivé : ni joint ni corps implicite")
    void jointsDesactives() {
        AssemblyBodies.Layout layout =
                AssemblyBodies.of(definition(SOUDURE), nodes("racine", "porte"), PHYSIQUE, false);
        assertEquals(List.of(new SimCommandStream.AssemblyBody(PHYSIQUE, 0)), layout.bodies());
        assertEquals(List.of(), layout.joints());
    }

    /** Une caisse et {@code count} joints fixes, chacun vers son propre node {@code n1..n<count>}. */
    private static AssemblyBodies.Layout soudures(int count) {
        StringBuilder joints = new StringBuilder();
        String[] names = new String[count + 1];
        names[0] = "racine";
        for (int i = 1; i <= count; i++) {
            names[i] = "n" + i;
            joints.append(i > 1 ? ", " : "")
                    .append("{\"name\": \"j").append(i).append("\", \"type\": \"fixed\", \"a\": \"caisse\",")
                    .append(" \"b_node\": \"n").append(i).append("\"}");
        }
        String json = "{\"bodies\": [{\"name\": \"caisse\"}], \"joints\": [" + joints + "]}";
        return AssemblyBodies.of(definition(json), nodes(names), PHYSIQUE, true);
    }

    @Test
    @DisplayName("Un b_node introuvable, ou plus de 64 corps avec les implicites : aucun corps")
    void bNodeIntrouvable() {
        assertNull(AssemblyBodies.of(definition(SOUDURE), nodes("racine"), PHYSIQUE, true));
        assertEquals(64, soudures(63).bodies().size(), "64 corps, le plafond (R-190)");
        assertNull(soudures(64), "65 corps");
    }

    @Test
    @DisplayName("bodies[].mass fixe la masse d'un corps ; celle de l'assembly reste à répartir")
    void masseDeclaree() {
        AssemblyBodies.Layout layout = AssemblyBodies.of(
                definition("{\"bodies\": [{\"name\": \"a\", \"root_node\": \"a\", \"mass\": 30},"
                        + " {\"name\": \"b\", \"root_node\": \"b\", \"mass\": 20}, {\"name\": \"c\", \"root_node\": \"c\"}]}"),
                nodes("a", "b", "c"),
                PHYSIQUE,
                true);
        assertEquals(30.0f, layout.bodies().get(0).description().mass(), "racine");
        assertEquals(0.5f, layout.bodies().get(0).description().centerOfMassY(), "centre déclaré gardé");
        assertEquals(20.0f, layout.bodies().get(1).description().mass(), "second corps");
        assertEquals(0.0f, layout.bodies().get(2).description().mass(), "sans masse déclarée");
        assertEquals(120.0f, layout.assemblyMass());
    }

    @Test
    @DisplayName("physics.self_collision passe à l'assembly ; absent, ses corps ne se touchent pas")
    void collisionsInternes() {
        String corps = "\"bodies\": [{\"name\": \"a\", \"root_node\": \"a\"}, {\"name\": \"b\", \"root_node\": \"b\"}]";
        assertTrue(AssemblyBodies.of(definition("{" + corps + ", \"physics\": {\"self_collision\": true}}"),
                        nodes("a", "b"), PHYSIQUE, true)
                .selfCollision());
        assertFalse(AssemblyBodies.of(definition("{" + corps + "}"), nodes("a", "b"), PHYSIQUE, true)
                .selfCollision());
    }
}
