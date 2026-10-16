package dev.axion.definition;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.axion.asset.AssetSource;
import dev.axion.physics.CollisionFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** R-980, ADR-132 A1 : le registre des groupes de collision tiers, et ce qu'en font les definitions. */
class CollisionGroupRegistryTest {

    private static final int WORLD = 1;
    private static final int ASSEMBLY = 1 << 1;

    /** Sources simulées, dans l'ordre où le test les ajoute ; {@code null} pour une illisible. */
    private static final class FakeSource implements AssetSource {
        private final Map<String, byte[]> files = new LinkedHashMap<>();

        FakeSource group(String id, String content) {
            int colon = id.indexOf(':');
            String location = id.substring(0, colon) + ":" + CollisionGroupRegistry.PREFIX
                    + id.substring(colon + 1) + CollisionGroupRegistry.EXTENSION;
            return put(location, content);
        }

        FakeSource put(String location, String content) {
            files.put(location, content == null ? null : content.getBytes(StandardCharsets.UTF_8));
            return this;
        }

        FakeSource reversed() {
            FakeSource reversed = new FakeSource();
            List<String> locations = new ArrayList<>(files.keySet());
            Collections.reverse(locations);
            locations.forEach(location -> reversed.files.put(location, files.get(location)));
            return reversed;
        }

        @Override
        public List<String> list() {
            return List.copyOf(files.keySet());
        }

        @Override
        public byte[] read(String path) throws IOException {
            byte[] content = files.get(path);
            if (content == null) {
                throw new IOException("disparu");
            }
            return content;
        }
    }

    private static JsonObject definition(String physics) {
        return JsonParser.parseString("{\"physics\": " + physics + "}").getAsJsonObject();
    }

    /** Le seul refus d'un chargement. */
    private static String seulRefus(CollisionGroupRegistry registry) {
        assertEquals(1, registry.refusals().size(), registry.refusals()::toString);
        return registry.refusals().get(0);
    }

    @Test
    @DisplayName("R-980 : sans registre, les réservés seuls ; membre d'assembly et heurte tout sans déclaration")
    void reservesSeuls() {
        CollisionGroupRegistry vide = CollisionGroupRegistry.empty();
        JsonObject none = JsonParser.parseString("{}").getAsJsonObject();
        assertEquals(ASSEMBLY, vide.membership(none));
        assertEquals(CollisionFilter.ALL, vide.filter(none));
        assertEquals(ASSEMBLY, vide.membership(definition("{\"mass\": 2.0}")));

        JsonObject debris = definition(
                "{\"collision_group\": \"debris\", \"collides_with\": [\"world\", \"entity_proxy\"]}");
        assertEquals(1 << 3, vide.membership(debris));
        assertEquals(WORLD | (1 << 4), vide.filter(debris));
        assertEquals(CollisionFilter.ALL, vide.filter(definition("{\"collision_group\": \"debris\"}")),
                "un groupe réservé n'a pas de masque par défaut");
        assertEquals(0, vide.filter(definition("{\"collides_with\": []}")), "une liste vide ne heurte rien");
        assertTrue(vide.knows("debug"));
        assertFalse(vide.knows("mymod:lourd"));
        assertThrows(IllegalArgumentException.class, () -> vide.bit("mymod:lourd"));
    }

    @Test
    @DisplayName("ADR-132 A1 : bits 8 à 31 dans l'ordre des identifiants, quel que soit l'ordre des packs")
    void bitsDansLOrdreDesIdentifiants() {
        FakeSource source = new FakeSource()
                .group("zeta:b", "{}")
                .group("alpha:c/d", "{}")
                .group("alpha:a", "{}");
        CollisionGroupRegistry registry = CollisionGroupRegistry.load(source);
        assertEquals(List.of("alpha:a", "alpha:c/d", "zeta:b"), registry.ids());
        assertEquals(1 << 8, registry.bit("alpha:a"));
        assertEquals(1 << 9, registry.bit("alpha:c/d"));
        assertEquals(1 << 10, registry.bit("zeta:b"));
        assertTrue(registry.refusals().isEmpty(), registry.refusals()::toString);

        CollisionGroupRegistry inverse = CollisionGroupRegistry.load(source.reversed());
        assertEquals(registry.ids(), inverse.ids());
        assertEquals(registry.fingerprint(), inverse.fingerprint());
        assertEquals(1 << 8, inverse.bit("alpha:a"));
    }

    @Test
    @DisplayName("ADR-132 A1 : 24 groupes tiers au plus ; au-delà, chaque groupe en trop est refusé, nommé")
    void plafond() {
        FakeSource source = new FakeSource();
        for (int i = 0; i < CollisionGroupRegistry.MAX_GROUPS + 1; i++) {
            source.group(String.format("mymod:g%02d", i), "{}");
        }
        CollisionGroupRegistry registry = CollisionGroupRegistry.load(source);
        assertEquals(24, CollisionGroupRegistry.MAX_GROUPS);
        assertEquals(24, registry.ids().size());
        assertEquals(1 << 31, registry.bit("mymod:g23"), "le dernier bit est le 31");
        String refus = seulRefus(registry);
        assertTrue(refus.startsWith("E-7001 mymod:axion/collision_groups/g24.json"), refus);
        assertTrue(refus.contains("« mymod:g24 »") && refus.contains("plus de 24"), refus);
        assertFalse(registry.knows("mymod:g24"));
    }

    @Test
    @DisplayName("ADR-132 A1 : un groupe qui nomme un groupe en trop est refusé à son tour")
    void plafondPuisReferences() {
        FakeSource source = new FakeSource();
        for (int i = 0; i < CollisionGroupRegistry.MAX_GROUPS + 1; i++) {
            source.group(String.format("mymod:g%02d", i), i == 0 ? "{\"collides_with\": [\"mymod:g24\"]}" : "{}");
        }
        CollisionGroupRegistry registry = CollisionGroupRegistry.load(source);
        assertEquals(23, registry.ids().size(), registry.refusals()::toString);
        assertEquals(2, registry.refusals().size(), registry.refusals()::toString);
        assertTrue(registry.refusals().get(0).contains("g24.json — $ : plus de 24"), registry.refusals()::toString);
        assertTrue(registry.refusals().get(1).contains("g00.json — $.collides_with[0]"), registry.refusals()::toString);
        assertEquals(1 << 8, registry.bit("mymod:g01"));
    }

    @Test
    @DisplayName("ADR-132 A1 : un groupe refusé pour sa forme ne prend pas de place sous le plafond")
    void plafondApresLesRefus() {
        FakeSource source = new FakeSource().group("mymod:a_casse", "{\"collides_with\": [\"mymod:absent\"]}");
        for (int i = 0; i < CollisionGroupRegistry.MAX_GROUPS; i++) {
            source.group(String.format("mymod:g%02d", i), "{}");
        }
        CollisionGroupRegistry registry = CollisionGroupRegistry.load(source);
        assertEquals(24, registry.ids().size());
        assertTrue(seulRefus(registry).contains("a_casse.json"), registry.refusals()::toString);
        assertEquals(1 << 8, registry.bit("mymod:g00"));
    }

    @Test
    @DisplayName("ADR-132 A1 : un masque qui nomme un groupe inconnu est refusé, avec ceux qui comptaient sur lui")
    void referenceInconnue() {
        CollisionGroupRegistry registry = CollisionGroupRegistry.load(new FakeSource()
                .group("mymod:a", "{\"collides_with\": [\"world\", \"mymod:absent\"]}")
                .group("mymod:b", "{\"collides_with\": [\"mymod:a\"]}")
                .group("mymod:c", "{\"collides_with\": [\"mymod:c\", \"mymod:d\"]}")
                .group("mymod:d", "{\"collides_with\": [\"mymod:c\"]}")
                // Trié avant b, qu'il nomme : il ne tombe qu'au passage suivant, au point fixe.
                .group("mymod:0", "{\"collides_with\": [\"mymod:b\"]}"));
        assertEquals(List.of("mymod:c", "mymod:d"), registry.ids(), "c et d se nomment l'un l'autre : admis");
        assertEquals(3, registry.refusals().size(), registry.refusals()::toString);
        assertTrue(registry.refusals().get(0).contains("a.json — $.collides_with[1]")
                && registry.refusals().get(0).contains("« mymod:absent » inconnu"), registry.refusals()::toString);
        assertTrue(registry.refusals().get(1).contains("b.json — $.collides_with[0]")
                && registry.refusals().get(1).contains("« mymod:a » inconnu"), registry.refusals()::toString);
        assertTrue(registry.refusals().get(2).contains("0.json — $.collides_with[0]")
                && registry.refusals().get(2).contains("« mymod:b » inconnu"), registry.refusals()::toString);
    }

    @Test
    @DisplayName("ADR-132 A1 : une chaîne de groupes qui finit sur un inconnu tombe entière, quel que soit l'ordre")
    void referencesEnChaine() {
        // Chaque maillon est trié avant celui qu'il nomme : un passage n'en retire qu'un.
        CollisionGroupRegistry registry = CollisionGroupRegistry.load(new FakeSource()
                .group("mymod:m1", "{\"collides_with\": [\"mymod:m2\"]}")
                .group("mymod:m2", "{\"collides_with\": [\"mymod:m3\"]}")
                .group("mymod:m3", "{\"collides_with\": [\"mymod:m4\"]}")
                .group("mymod:m4", "{\"collides_with\": [\"mymod:absent\"]}")
                .group("mymod:sain", "{}"));
        assertEquals(List.of("mymod:sain"), registry.ids(), registry.refusals()::toString);
        assertEquals(4, registry.refusals().size(), registry.refusals()::toString);
    }

    @Test
    @DisplayName("ADR-132 A1 : collides_with d'un fichier est le masque par défaut du groupe")
    void masqueParDefaut() {
        CollisionGroupRegistry registry = CollisionGroupRegistry.load(new FakeSource()
                .group("mymod:fantome", "{\"collides_with\": [\"world\", \"mymod:fantome\"]}")
                .group("mymod:libre", "{}")
                .group("mymod:isole", "{\"collides_with\": []}"));
        int fantome = registry.bit("mymod:fantome");
        assertEquals(OptionalInt.of(WORLD | fantome), registry.defaultMask("mymod:fantome"));
        assertEquals(OptionalInt.empty(), registry.defaultMask("mymod:libre"));

        assertEquals(fantome, registry.membership(definition("{\"collision_group\": \"mymod:fantome\"}")));
        assertEquals(WORLD | fantome, registry.filter(definition("{\"collision_group\": \"mymod:fantome\"}")),
                "sans collides_with, la definition prend le masque du groupe");
        assertEquals(ASSEMBLY | registry.bit("mymod:libre"), registry.filter(definition(
                "{\"collision_group\": \"mymod:fantome\", \"collides_with\": [\"assembly\", \"mymod:libre\"]}")),
                "le collides_with de la definition l'emporte");
        assertEquals(CollisionFilter.ALL, registry.filter(definition("{\"collision_group\": \"mymod:libre\"}")),
                "sans masque par défaut, heurte tout");
        assertEquals(0, registry.filter(definition("{\"collision_group\": \"mymod:isole\"}")));
    }

    @Test
    @DisplayName("ADR-132 A1 : JSON strict — un objet, au plus collides_with, liste de noms de groupes")
    void jsonStrict() {
        assertTrue(seulRefus(CollisionGroupRegistry.load(new FakeSource().group("mymod:a", "{\"masque\": []}")))
                .contains("— $.masque : champ inconnu"));
        assertTrue(seulRefus(CollisionGroupRegistry.load(new FakeSource().group("mymod:a", "[]")))
                .contains("— $ : un groupe de collision est un objet JSON"));
        assertTrue(seulRefus(CollisionGroupRegistry.load(new FakeSource().group("mymod:a", "{\"collides_with\": \"world\"}")))
                .contains("— $.collides_with : attendu une liste"));
        assertTrue(seulRefus(CollisionGroupRegistry.load(new FakeSource().group("mymod:a", "{\"collides_with\": [1]}")))
                .contains("— $.collides_with[0] : attendu un nom de groupe"));
        assertTrue(seulRefus(CollisionGroupRegistry.load(new FakeSource().group("mymod:a", "{\"collides_with\": [\"Lourd\"]}")))
                .contains("« Lourd » n'est ni un groupe réservé"));
        assertTrue(seulRefus(CollisionGroupRegistry.load(new FakeSource()
                        .group("mymod:a", "{\"collides_with\": [], \"collides_with\": []}")))
                .contains("clé en double"));
        assertEquals("E-7001 mymod:axion/collision_groups/a.json — illisible : disparu",
                seulRefus(CollisionGroupRegistry.load(new FakeSource().group("mymod:a", null))));
        assertTrue(seulRefus(CollisionGroupRegistry.load(new FakeSource().put("mymod:axion/autre/a.json", "{}")))
                .contains("ressource hors de <ns>:axion/collision_groups/"));
    }

    @Test
    @DisplayName("ADR-132 A1 : l'empreinte change avec un groupe, un bit ou un masque")
    void empreinte() {
        String base = CollisionGroupRegistry.load(new FakeSource().group("mymod:a", "{}")).fingerprint();
        assertEquals(base, CollisionGroupRegistry.load(new FakeSource().group("mymod:a", "{}")).fingerprint());
        assertNotEquals(base, CollisionGroupRegistry.empty().fingerprint());
        assertNotEquals(base, CollisionGroupRegistry.load(new FakeSource()
                .group("mymod:a", "{\"collides_with\": [\"world\"]}")).fingerprint());
        assertNotEquals(base, CollisionGroupRegistry.load(new FakeSource()
                .group("mymod:0", "{}").group("mymod:a", "{}")).fingerprint(), "a passe au bit 9");
    }

    @Test
    @DisplayName("ADR-132 A1 : une definition qui nomme un groupe que le registre ignore est refusée, avec son chemin")
    void definitionsContreLeRegistre() throws DefinitionException {
        String pickup = "mymod:axion/models/pickup.glb";
        CollisionGroupRegistry registry = CollisionGroupRegistry.load(new FakeSource().group("mymod:lourd", "{}"));
        DefinitionRules avec = new DefinitionRules(true, Set.of(pickup)::contains, false, registry);
        DefinitionRules sans = new DefinitionRules(true, Set.of(pickup)::contains);
        String tete = "{\"schema\": 1, \"asset\": \"mymod:models/pickup.glb\", \"kind\": \"rigid_object\", ";

        String membre = tete + "\"physics\": {\"collision_group\": \"mymod:lourd\"}}";
        assertDoesNotThrow(() -> read(membre, avec));
        DefinitionException inconnu = assertThrows(DefinitionException.class, () -> read(membre, sans));
        assertEquals("$.physics.collision_group", inconnu.path());
        assertTrue(inconnu.getMessage().contains("« mymod:lourd » inconnu"), inconnu.getMessage());

        String heurte = tete + "\"physics\": {\"collides_with\": [\"world\", \"mymod:leger\"]}}";
        assertEquals("$.physics.collides_with[1]",
                assertThrows(DefinitionException.class, () -> read(heurte, avec)).path());

        // Le schéma juge la forme avant le registre : un nom qui n'est ni réservé ni un identifiant.
        DefinitionException forme = assertThrows(DefinitionException.class,
                () -> read(tete + "\"physics\": {\"collision_group\": \"Lourd\"}}", avec));
        assertEquals("$.physics.collision_group", forme.path());
        assertTrue(forme.getMessage().contains("ni un groupe réservé"), forme.getMessage());

        // La registry garde le registre contre lequel elle a validé : les bits s'y résolvent.
        DefinitionRegistry definitions = DefinitionRegistry.load(new FakeSource()
                .put("mymod:axion/definitions/pickup.json", membre), avec);
        assertEquals(1, definitions.size(), definitions.refusals()::toString);
        assertEquals(registry, definitions.collisionGroups());
        assertEquals(1 << 8, definitions.collisionGroups()
                .membership(definitions.get("mymod:pickup").orElseThrow().root()));
    }

    private static Definition read(String json, DefinitionRules rules) throws DefinitionException {
        return DefinitionRegistry.read(
                "mymod:axion/definitions/pickup.json", json.getBytes(StandardCharsets.UTF_8), rules);
    }
}
