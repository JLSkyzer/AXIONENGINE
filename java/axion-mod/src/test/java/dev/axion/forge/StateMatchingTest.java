package dev.axion.forge;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import dev.axion.definition.BodyRests;
import dev.axion.physics.SimCommandStream;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-175 D1, D2 : un état se rapproche de la definition par les noms, et ce qui manque se dit. */
class StateMatchingTest {

    private static final float[] DROIT = {0f, 0f, 0f, 1f};
    private static final float[] ZERO = {0f, 0f, 0f};

    private static PersistedState.Body corps(int node, String nom, double x) {
        return new PersistedState.Body(node, nom, x, 64.0, 0.0, DROIT, new float[] {1f, 0f, 0f}, ZERO);
    }

    private static PersistedState etat(List<PersistedState.Body> corps, List<PersistedState.Joint> joints) {
        return new PersistedState(new double[] {10.0, 64.0, 5.0}, DROIT, new float[] {0f, 0f, 2f}, ZERO, false, corps,
                joints);
    }

    private static final BodyRests REPOS = new BodyRests(List.of(
            new BodyRests.Rest(new double[3], DROIT), new BodyRests.Rest(new double[] {2.0, 0.0, 0.0}, DROIT),
            new BodyRests.Rest(new double[] {0.0, 1.0, 0.0}, DROIT)));

    @Test
    @DisplayName("D1 : des corps réordonnés se retrouvent par leur nom, pas par leur rang ni par un index qui a changé")
    void parLeNom() {
        PersistedState saved = etat(List.of(corps(7, "porte", 1.0), corps(3, "capot", 2.0)), List.of());
        StateMatching.Outcome outcome = StateMatching.match(saved, List.of("capot", "porte"), null, 0,
                List.of("chassis", "x", "y", "z", "w", "v", "u", "t"), REPOS);
        assertNotNull(outcome.restored());
        assertEquals(2.0, outcome.restored().bodies().get(1).position()[0], "capot garde l'état de « capot »");
        assertEquals(1.0, outcome.restored().bodies().get(2).position()[0], "porte garde l'état de « porte »");
        assertTrue(outcome.lost().isEmpty() && outcome.fresh().isEmpty(), outcome.toString());
        assertFalse(outcome.legacy());
    }

    @Test
    @DisplayName("D2 : un corps nouveau prend sa pose de repos relative à la racine et sa vitesse ; un corps disparu se dit")
    void nouveauEtDisparu() {
        PersistedState saved = etat(List.of(corps(3, "capot", 2.0), corps(9, "coffre", 4.0)), List.of());
        StateMatching.Outcome outcome = StateMatching.match(saved, List.of("capot", "porte"), null, 0,
                List.of("chassis"), REPOS);
        assertEquals(List.of("corps « coffre »"), outcome.lost());
        assertEquals(List.of("corps « porte »"), outcome.fresh());
        SimCommandStream.RestoredBody porte = outcome.restored().bodies().get(2);
        assertArrayEquals(new double[] {10.0, 65.0, 5.0}, porte.position(), 1e-9, "repos (0, 1, 0) autour de la racine");
        assertArrayEquals(new float[] {0f, 0f, 2f}, porte.linear(), 1e-6f, "la vitesse de la racine, ω étant nul");
    }

    @Test
    @DisplayName("D2 : un corps nouveau tourne avec la racine restaurée, et prend sa vitesse de rotation")
    void nouveauSuitLaRacineQuiTourne() {
        float c = (float) Math.sqrt(0.5);
        PersistedState saved = new PersistedState(new double[] {0.0, 0.0, 0.0}, new float[] {0f, c, 0f, c},
                new float[] {0f, 0f, 0f}, new float[] {0f, 1f, 0f}, false, List.of(), List.of());
        StateMatching.Outcome outcome = StateMatching.match(saved, List.of("porte"), null, 0, List.of(),
                new BodyRests(List.of(new BodyRests.Rest(new double[3], DROIT),
                        new BodyRests.Rest(new double[] {2.0, 0.0, 0.0}, DROIT))));
        SimCommandStream.RestoredBody porte = outcome.restored().bodies().get(1);
        // Un quart de tour autour de y : +x du repère de la racine vise -z du monde.
        assertArrayEquals(new double[] {0.0, 0.0, -2.0}, porte.position(), 1e-6);
        // ω × r = (0, 1, 0) × (0, 0, -2) = (-2, 0, 0).
        assertArrayEquals(new float[] {-2f, 0f, 0f}, porte.linear(), 1e-5f);
    }

    @Test
    @DisplayName("D2 : sans pose de repos calculable, un corps nouveau refuse la restauration et le dit")
    void sansPoseDeRepos() {
        StateMatching.Outcome outcome = StateMatching.match(etat(List.of(), List.of()), List.of("porte"), null, 0,
                List.of(), new BodyRests(java.util.Arrays.asList(new BodyRests.Rest(new double[3], DROIT), null)));
        assertNull(outcome.restored());
        assertTrue(outcome.refusal().contains("porte"), outcome.refusal());
    }

    @Test
    @DisplayName("D1 : des joints réordonnés gardent chacun son état rompu ; un joint disparu et un joint nouveau se disent")
    void lesJoints() {
        PersistedState saved = etat(List.of(), List.of(new PersistedState.Joint(true, 0.5f, "a"),
                new PersistedState.Joint(false, 0.0f, "b"), new PersistedState.Joint(true, 0.25f, "vieux")));
        StateMatching.Outcome outcome = StateMatching.match(saved, List.of(), List.of("b", "a", "neuf"), 3, List.of(),
                REPOS);
        List<SimCommandStream.RestoredJoint> joints = outcome.restored().joints();
        assertFalse(joints.get(0).broken(), "« b » n'est pas rompu");
        assertTrue(joints.get(1).broken(), "« a » est rompu, même devenu le second");
        assertEquals(0.5f, joints.get(1).misalignment());
        assertFalse(joints.get(2).broken(), "« neuf » repart intact");
        assertEquals(List.of("joint « vieux »"), outcome.lost());
        assertEquals(List.of("joint « neuf »"), outcome.fresh());
    }

    @Test
    @DisplayName("Schéma 5 : un état sans noms se rapproche par les index d'alors, nommés d'après l'asset ; il le dit")
    void etatSansNoms() {
        PersistedState saved = etat(List.of(corps(2, null, 2.0), corps(5, null, 4.0)),
                List.of(new PersistedState.Joint(true, 0.5f), new PersistedState.Joint(false, 0.0f)));
        List<String> nodes = List.of("chassis", "x", "capot", "y", "z", "porte");
        StateMatching.Outcome outcome = StateMatching.match(saved, List.of("porte", "capot"), List.of("a", "b"), 2,
                nodes, REPOS);
        assertTrue(outcome.legacy());
        assertEquals(4.0, outcome.restored().bodies().get(1).position()[0], "« porte » était le node 5");
        assertEquals(2.0, outcome.restored().bodies().get(2).position()[0], "« capot » était le node 2");
        assertTrue(outcome.restored().joints().get(0).broken(), "même nombre de joints : par rang");
        assertTrue(outcome.lost().isEmpty());
    }

    @Test
    @DisplayName("Schéma 5 : des joints sans noms, d'un autre nombre, ne se rapprochent de rien et se disent")
    void jointsSansNomsDUnAutreNombre() {
        PersistedState saved = etat(List.of(), List.of(new PersistedState.Joint(true, 0.5f)));
        StateMatching.Outcome outcome = StateMatching.match(saved, List.of(), List.of("a", "b"), 2, List.of(), REPOS);
        assertTrue(outcome.restored().joints().stream().noneMatch(SimCommandStream.RestoredJoint::broken));
        assertEquals(1, outcome.lost().size(), outcome.lost().toString());
        assertTrue(outcome.legacy());
    }

    @Test
    @DisplayName("Les noms des joints d'une definition, dans son ordre ; hors de compte, aucun")
    void nomsDesJoints() {
        var root = JsonParser.parseString("{\"joints\": [{\"name\": \"a\"}, {\"name\": \"b\"}]}").getAsJsonObject();
        assertEquals(List.of("a", "b"), StateMatching.jointNames(root, 2));
        assertNull(StateMatching.jointNames(root, 0), "modules.joints éteint : le natif n'en a créé aucun");
        assertNull(StateMatching.jointNames(null, 2));
        assertEquals("#9", StateMatching.nameOf(List.of("a"), 9));
    }
}
