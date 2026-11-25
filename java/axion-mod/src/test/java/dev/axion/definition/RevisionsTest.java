package dev.axion.definition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.axion.asset.AssetSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-175 D3 : la révision d'une definition, ses migrations et les identifiants qu'elle remplace. */
class RevisionsTest {

    private static final String MODELE = "mymod:axion/models/pickup.glb";

    private static JsonObject definition(String extra) {
        return JsonParser.parseString("{\"schema\": 7, \"asset\": \"mymod:models/pickup.glb\", \"kind\":"
                + " \"rigid_object\"" + (extra == null ? "" : ", " + extra) + "}").getAsJsonObject();
    }

    private static Definition lire(JsonObject root) throws DefinitionException {
        return DefinitionRegistry.read("mymod:axion/definitions/pickup.json",
                root.toString().getBytes(StandardCharsets.UTF_8), new DefinitionRules(true, Set.of(MODELE)::contains,
                        false, CollisionGroupRegistry.empty(), PhysicsMaterials.empty()));
    }

    @Test
    @DisplayName("D3 : une definition sans révision est à la révision 1, sans migration ni remplacement")
    void parDefaut() {
        assertEquals(Revisions.NONE, Revisions.of(definition(null)));
        JsonObject six = definition("\"revision\": 4");
        six.addProperty("schema", 6);
        assertSame(Revisions.NONE, Revisions.of(six), "avant le schéma 7, rien ne se lit");
    }

    @Test
    @DisplayName("D3 : les migrations se lisent par révision croissante ; la chaîne d'un état va de sa révision à la courante")
    void chaine() {
        Revisions revisions = Revisions.of(definition("\"revision\": 4, \"migrations\": ["
                + "{\"from\": 3, \"rename\": {\"variables\": {\"phare\": \"feux\"}}},"
                + " {\"from\": 1, \"rename\": {\"nodes\": {\"porte_g\": \"portiere_gauche\"}, \"joints\": {\"a\": \"b\"}}},"
                + " {\"from\": 2}], \"replaces\": [\"mymod:ancienne\"]"));
        assertEquals(4, revisions.revision());
        assertEquals(List.of(1, 2, 3), revisions.migrations().stream().map(Revisions.Migration::from).toList());
        assertEquals(Map.of("porte_g", "portiere_gauche"), revisions.migrations().get(0).of("nodes"));
        assertEquals(Map.of(), revisions.migrations().get(1).of("nodes"));
        assertEquals(List.of("mymod:ancienne"), revisions.replaces());
        assertEquals(List.of(1, 2, 3), revisions.chain(1).stream().map(Revisions.Migration::from).toList());
        assertEquals(List.of(3), revisions.chain(3).stream().map(Revisions.Migration::from).toList());
        assertTrue(revisions.chain(4).isEmpty(), "un état à la révision courante n'en subit aucune");
    }

    @Test
    @DisplayName("D3 : le successeur d'un nom — les renommages d'une migration à la fois, un nom écrasé disparaît")
    void successeurs() {
        Revisions revisions = Revisions.of(definition("\"revision\": 3, \"migrations\": ["
                + "{\"from\": 1, \"rename\": {\"variables\": {\"gauche\": \"droite\", \"droite\": \"gauche\","
                + " \"neuf\": \"vieux\"}}}, {\"from\": 2, \"rename\": {\"variables\": {\"vieux\": \"dernier\"}}}]"));
        List<String> noms = List.of("gauche", "droite", "neuf", "vieux", "autre");
        assertEquals(Map.of("gauche", "droite", "droite", "gauche", "neuf", "dernier", "autre", "autre"),
                revisions.successors("variables", 1, noms), "« vieux », écrasé par « neuf », ne revient pas");
        assertEquals(Map.of("gauche", "gauche", "droite", "droite", "neuf", "neuf", "vieux", "dernier", "autre", "autre"),
                revisions.successors("variables", 2, noms));
        assertEquals(Map.of("gauche", "gauche"), revisions.successors("variables", 3, List.of("gauche")));
        assertEquals(Map.of("autre", "autre"), revisions.successors("seats", 1, List.of("autre")));
    }

    @Test
    @DisplayName("D3 : une migration part d'une révision antérieure, une seule par révision ; un nouveau nom est valide et unique")
    void coherence() throws DefinitionException {
        lire(definition("\"revision\": 3, \"migrations\": [{\"from\": 1, \"rename\": {\"nodes\": {\"a\": \"b\"}}},"
                + " {\"from\": 2}], \"replaces\": [\"mymod:ancienne\"]"));
        assertEquals("$.migrations[0].from", assertThrows(DefinitionException.class,
                () -> lire(definition("\"revision\": 2, \"migrations\": [{\"from\": 2}]"))).path(),
                "une migration ne part pas de la révision courante");
        assertEquals("$.migrations[1].from", assertThrows(DefinitionException.class,
                () -> lire(definition("\"revision\": 3, \"migrations\": [{\"from\": 1}, {\"from\": 1}]"))).path());
        assertEquals("$.migrations[0].rename.nodes.b", assertThrows(DefinitionException.class,
                () -> lire(definition("\"revision\": 2, \"migrations\": [{\"from\": 1, \"rename\": {\"nodes\":"
                        + " {\"a\": \"x\", \"b\": \"x\"}}}]"))).path(), "deux anciens noms, un même nouveau");
        assertThrows(DefinitionException.class,
                () -> lire(definition("\"revision\": 2, \"migrations\": [{\"from\": 1, \"rename\": {\"nodes\":"
                        + " {\"a\": \"\"}}}]")), "un nom vide");
        assertThrows(DefinitionException.class, () -> lire(definition("\"revision\": 0")), "révision de 1 au moins");
        assertThrows(DefinitionException.class, () -> lire(definition("\"replaces\": [\"Mauvais Id\"]")));
    }

    /** Une source de definitions en mémoire. */
    private static final class FakeSource implements AssetSource {
        private final Map<String, byte[]> files = new LinkedHashMap<>();

        FakeSource put(String location, String json) {
            files.put(location, json.getBytes(StandardCharsets.UTF_8));
            return this;
        }

        @Override
        public List<String> list() {
            return List.copyOf(files.keySet());
        }

        @Override
        public byte[] read(String location) throws IOException {
            return files.get(location);
        }
    }

    @Test
    @DisplayName("D3 : une definition qui en remplace une reprend son identifiant 64 bits ; une definition qui le porte encore le garde")
    void remplacement() {
        String ancienne = "{\"schema\": 7, \"asset\": \"mymod:models/pickup.glb\", \"kind\": \"rigid_object\"}";
        String nouvelle = "{\"schema\": 7, \"asset\": \"mymod:models/pickup.glb\", \"kind\": \"rigid_object\","
                + " \"replaces\": [\"mymod:ancienne\", \"mymod:autre\"]}";
        DefinitionRules regles = new DefinitionRules(true, Set.of(MODELE)::contains, false,
                CollisionGroupRegistry.empty(), PhysicsMaterials.empty());
        DefinitionRegistry seule = DefinitionRegistry.load(new FakeSource()
                .put("mymod:axion/definitions/nouvelle.json", nouvelle), regles);
        long hashAncienne = DefinitionIds.hash("mymod:ancienne");
        assertEquals("mymod:nouvelle", seule.byHash(hashAncienne).orElseThrow().id(), "reliée à celle qui la remplace");
        assertEquals("mymod:nouvelle", seule.byHash(DefinitionIds.hash("mymod:autre")).orElseThrow().id());
        assertEquals("mymod:nouvelle", seule.byHash(DefinitionIds.hash("mymod:nouvelle")).orElseThrow().id());

        DefinitionRegistry avecAncienne = DefinitionRegistry.load(new FakeSource()
                .put("mymod:axion/definitions/nouvelle.json", nouvelle)
                .put("mymod:axion/definitions/ancienne.json", ancienne), regles);
        assertEquals("mymod:ancienne", avecAncienne.byHash(hashAncienne).orElseThrow().id(),
                "une definition qui porte encore l'identifiant le garde : R-704 tient");
        assertTrue(avecAncienne.warnings().stream().anyMatch(ligne -> ligne.contains("« mymod:ancienne »")
                && ligne.contains("ignoré")), avecAncienne.warnings().toString());
    }
}
