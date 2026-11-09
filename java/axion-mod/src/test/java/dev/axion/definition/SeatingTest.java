package dev.axion.definition;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.axion.asset.AnimClips;
import dev.axion.asset.AssetSource;
import dev.axion.asset.DefinitionCompiler;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-158 D1, D6 : les sièges et les interactions d'une definition, vérifiés puis compilés. */
class SeatingTest {

    private static final String MODELE = "mymod:axion/models/voiture.glb";
    private static final String VOITURE = "mymod:axion/definitions/voiture.json";

    /** Une voiture à deux sièges, une portière qui s'ouvre et une action qui attend les attaches. */
    private static final String JSON = """
            { "schema": 1, "asset": "mymod:models/voiture.glb", "kind": "rigid_object",
              "seats": [
                { "name": "conducteur", "node": "siege_g", "role": "driver",
                  "exit_offsets": [[-1.0, 0.0, 0.5], [0.0, 1.5, 0.0]],
                  "controls": ["throttle", "steer", "handbrake"] },
                { "name": "passager", "node": "siege_d" } ],
              "animations": { "ouvrir": { "clip": "porte_ouverte", "layer": 2 } },
              "interaction": [
                { "node": "porte_g", "action": "sit", "seat": "conducteur" },
                { "node": "porte_d", "action": "toggle_animation", "animation": "ouvrir" },
                { "node": "crochet", "action": "attach" } ] }
            """;

    private static JsonObject document() {
        return JsonParser.parseString(JSON).getAsJsonObject();
    }

    /** {@return les chemins des fautes de {@code root} qui commencent par {@code prefix}} */
    private static List<String> fautes(JsonObject root, String prefix) {
        return DefinitionChecker.check(root).stream()
                .map(DefinitionChecker.Issue::path)
                .filter(path -> path.startsWith(prefix))
                .toList();
    }

    /** Le natif simulé : le node {@code 10 + rang} pour chaque référence à un node, -1 sinon. */
    private static int[] resolus(List<DefinitionChecker.AssetReference> references) {
        int[] nodes = new int[references.size()];
        for (int rank = 0; rank < nodes.length; rank++) {
            nodes[rank] = references.get(rank).category() == NameCategory.NODE ? 10 + rank : -1;
        }
        return nodes;
    }

    private static int rang(List<DefinitionChecker.AssetReference> references, String path) {
        for (int rank = 0; rank < references.size(); rank++) {
            if (references.get(rank).path().equals(path)) {
                return rank;
            }
        }
        throw new AssertionError("aucune référence en " + path);
    }

    @Test
    @DisplayName("ADR-158 D1 : la voiture d'exemple est acceptée telle quelle")
    void accepte() {
        assertEquals(List.of(), DefinitionChecker.check(document()));
    }

    @Test
    @DisplayName("ADR-158 D1 : un siège sans node est refusé, il n'aurait pas de position")
    void siegeSansNode() {
        JsonObject root = document();
        root.getAsJsonArray("seats").get(1).getAsJsonObject().remove("node");
        assertEquals(List.of("$.seats[1]"), fautes(root, "$.seats"));
    }

    @Test
    @DisplayName("ADR-158 D1 : un second conducteur est refusé, le premier nommé")
    void secondConducteur() {
        JsonObject root = document();
        root.getAsJsonArray("seats").get(1).getAsJsonObject().addProperty("role", "driver");
        List<DefinitionChecker.Issue> issues = DefinitionChecker.check(root);
        assertEquals(List.of("$.seats[1].role"), issues.stream().map(DefinitionChecker.Issue::path).toList());
        assertTrue(issues.get(0).message().contains("$.seats[0]"), issues.get(0).message());
        root.getAsJsonArray("seats").get(1).getAsJsonObject().addProperty("role", "passenger");
        assertEquals(List.of(), fautes(root, "$.seats"), "un passager déclaré comme tel");
    }

    @Test
    @DisplayName("ADR-158 D1 : un contrôle hors de VehicleInput est refusé, à son rang")
    void controleInconnu() {
        JsonObject root = document();
        root.getAsJsonArray("seats").get(0).getAsJsonObject()
                .getAsJsonArray("controls").set(1, new com.google.gson.JsonPrimitive("klaxon"));
        List<DefinitionChecker.Issue> issues = DefinitionChecker.check(root);
        assertEquals(List.of("$.seats[0].controls[1]"), issues.stream().map(DefinitionChecker.Issue::path).toList());
        assertTrue(issues.get(0).message().contains("klaxon"), issues.get(0).message());
    }

    @Test
    @DisplayName("ADR-158 D6 : une interaction sans node, un sit sans siège, une animation sans clip sont refusés")
    void interactionIncomplete() {
        JsonObject root = document();
        root.getAsJsonArray("interaction").get(2).getAsJsonObject().remove("node");
        root.getAsJsonArray("interaction").get(0).getAsJsonObject().remove("seat");
        root.getAsJsonArray("interaction").get(1).getAsJsonObject().remove("animation");
        assertEquals(List.of("$.interaction[0]", "$.interaction[1]", "$.interaction[2]"),
                fautes(root, "$.interaction"));

        JsonObject jouer = document();
        jouer.getAsJsonArray("interaction").get(1).getAsJsonObject().addProperty("action", "play_animation");
        jouer.getAsJsonArray("interaction").get(1).getAsJsonObject().remove("animation");
        assertEquals(List.of("$.interaction[1]"), fautes(jouer, "$.interaction"));
    }

    @Test
    @DisplayName("ADR-158 D1, D6 : sièges et interactions compilés aux nodes que le natif a résolus")
    void compile() {
        JsonObject root = document();
        List<DefinitionChecker.AssetReference> references = DefinitionChecker.assetReferences(root);
        Seating seating = Seating.of(root, references, resolus(references));

        assertEquals(2, seating.seats().size());
        Seating.Seat conducteur = seating.seats().get(0);
        assertEquals("conducteur", conducteur.name());
        assertEquals(10 + rang(references, "$.seats[0].node"), conducteur.node());
        assertTrue(conducteur.driver());
        assertEquals(2, conducteur.exits().size());
        assertArrayEquals(new float[] {-1.0f, 0.0f, 0.5f}, conducteur.exits().get(0));
        assertArrayEquals(new float[] {0.0f, 1.5f, 0.0f}, conducteur.exits().get(1), "dans l'ordre déclaré");
        assertTrue(conducteur.allows(Seating.Control.THROTTLE));
        assertTrue(conducteur.allows(Seating.Control.STEER));
        assertTrue(conducteur.allows(Seating.Control.HANDBRAKE));
        assertFalse(conducteur.allows(Seating.Control.BRAKE));
        assertFalse(conducteur.allows(Seating.Control.GEAR));

        Seating.Seat passager = seating.seats().get(1);
        assertFalse(passager.driver(), "sans rôle, un passager");
        assertEquals(0, passager.controls());
        assertTrue(passager.exits().isEmpty());
        assertEquals(1, seating.seatAt(passager.node()));
        assertEquals(-1, seating.seatAt(9999));

        assertEquals(3, seating.interactions().size());
        Seating.Interaction portiere = seating.interactions().get(0);
        assertEquals(Seating.Action.SIT, portiere.action());
        assertEquals(10 + rang(references, "$.interaction[0].node"), portiere.node());
        assertEquals(0, portiere.seat(), "le siège nommé, par son rang");
        Seating.Interaction porte = seating.interactions().get(1);
        assertEquals(Seating.Action.TOGGLE_ANIMATION, porte.action());
        assertEquals("ouvrir", porte.animation());
        assertEquals("porte_ouverte", porte.clip(), "le clip que l'animation déclare");
        assertEquals(2, porte.layer(), "et sa couche");
        assertEquals(-1, porte.seat());

        // Q4 : l'action sans système compile, avec un avertissement qui dit ce qu'elle attend.
        assertFalse(seating.interactions().get(2).action().effective());
        assertEquals(1, seating.warnings().size());
        assertTrue(seating.warnings().get(0).startsWith("$.interaction[2]"), seating.warnings().get(0));
        assertTrue(seating.warnings().get(0).contains("C-48"), seating.warnings().get(0));
    }

    @Test
    @DisplayName("ADR-158 D2 : un clic se rapporte au premier ancêtre qui porte une interaction ou un siège")
    void cible() {
        JsonObject root = document();
        List<DefinitionChecker.AssetReference> references = DefinitionChecker.assetReferences(root);
        Seating seating = Seating.of(root, references, resolus(references));
        int siegeG = seating.seats().get(0).node();
        int siegeD = seating.seats().get(1).node();
        int porteG = seating.interactions().get(0).node();
        int porteD = seating.interactions().get(1).node();
        int crochet = seating.interactions().get(2).node();
        // Une table de nodes assez grande : la caisse (0) porte les sièges et les portières ; sous le
        // siège droit, son dossier ; sous la portière gauche, sa poignée.
        int size = 40;
        int[] parents = new int[size];
        java.util.Arrays.fill(parents, 0);
        parents[0] = -1;
        int dossier = 30;
        int poignee = 31;
        parents[dossier] = siegeD;
        parents[poignee] = porteG;
        assertTrue(siegeD < dossier && porteG < poignee, "un parent précède son enfant");
        dev.axion.asset.Anatomy anatomy =
                dev.axion.asset.Anatomy.of(parents, new int[size], new float[size * 7], new int[][] {});

        assertEquals(new Seating.Target(-1, 0), seating.target(siegeG, anatomy), "l'assise");
        assertEquals(new Seating.Target(-1, 1), seating.target(dossier, anatomy), "le dossier, par son parent");
        assertEquals(new Seating.Target(0, 0), seating.target(poignee, anatomy), "la portière qui assoit");
        assertEquals(new Seating.Target(0, 0), seating.target(porteG, anatomy));
        assertEquals(new Seating.Target(1, -1), seating.target(porteD, anatomy), "la portière qui s'ouvre");
        assertEquals(new Seating.Target(2, -1), seating.target(crochet, anatomy), "l'action sans système");
        assertEquals(null, seating.target(0, anatomy), "la caisse ne porte rien");
        assertEquals(null, seating.target(-1, anatomy), "le collider de l'asset entier");
        assertEquals(null, seating.target(5, anatomy), "un node sous la caisse seule");
    }

    @Test
    @DisplayName("ADR-158 D1 : une definition sans siège ni interaction n'a rien à garder")
    void rien() {
        JsonObject root = JsonParser.parseString("""
                { "schema": 1, "asset": "mymod:models/voiture.glb", "kind": "rigid_object" }
                """).getAsJsonObject();
        assertSame(Seating.NONE, Seating.of(root, List.of(), new int[0]));
    }

    @Test
    @DisplayName("ADR-158 D1 : un node que le natif n'a pas résolu est un défaut de la frontière")
    void nonResolu() {
        JsonObject root = document();
        List<DefinitionChecker.AssetReference> references = DefinitionChecker.assetReferences(root);
        int[] nodes = resolus(references);
        nodes[rang(references, "$.seats[1].node")] = -1;
        IllegalArgumentException refus =
                assertThrows(IllegalArgumentException.class, () -> Seating.of(root, references, nodes));
        assertTrue(refus.getMessage().contains("$.seats[1].node"), refus.getMessage());
        assertThrows(IllegalArgumentException.class, () -> Seating.of(root, references, new int[0]), "tronqué");
    }

    // La compilation : la table suit le résumé, ses avertissements vont au journal.

    private static final class Natif implements DefinitionCompiler {
        Function<Integer, int[]> nodes = count -> new int[count];
        final List<Long> removed = new ArrayList<>();
        int references;

        @Override
        public Result compile(long definition, long assetId, byte[] a3d, byte[] defn) {
            return new Result(0, new Summary(new int[] {0}, 0, false, List.of(), AnimClips.NONE,
                    nodes.apply(references)), -1, "");
        }

        @Override
        public boolean remove(long definition) {
            removed.add(definition);
            return true;
        }
    }

    private static DefinitionRegistry registry() {
        byte[] json = JSON.getBytes(StandardCharsets.UTF_8);
        AssetSource source = new AssetSource() {
            @Override
            public List<String> list() {
                return List.of(VOITURE);
            }

            @Override
            public byte[] read(String path) {
                return VOITURE.equals(path) ? json : null;
            }
        };
        return DefinitionRegistry.load(source, new DefinitionRules(true, Set.of(MODELE)::contains));
    }

    @Test
    @DisplayName("ADR-158 D1 : la compilation garde la table et journalise l'action qui attend son chantier")
    void compilation() {
        Natif natif = new Natif();
        List<DefinitionChecker.AssetReference> references = DefinitionChecker.assetReferences(document());
        natif.references = references.size();
        natif.nodes = count -> resolus(references);
        DefinitionCompilation compilation = new DefinitionCompilation(natif, true, name -> 0);
        DefinitionRegistry registry = registry();
        String id = registry.ids().iterator().next();
        compilation.reset(registry);
        assertTrue(compilation.seating(id).isEmpty(), "rien avant la compilation");

        compilation.pump(path -> new DefinitionCompilation.AssetStatus(42L, new byte[] {1}, false), 0, System::nanoTime);
        Seating seating = compilation.seating(id).orElseThrow();
        assertEquals(List.of("conducteur", "passager"), seating.seats().stream().map(Seating.Seat::name).toList());
        List<String> journal = compilation.drainJournal();
        assertEquals(1, journal.size(), journal.toString());
        assertTrue(journal.get(0).contains(id) && journal.get(0).contains("attach"), journal.get(0));
    }

    @Test
    @DisplayName("ADR-158 D1 : un résumé sans les nodes des sièges refuse la definition et la retire du natif")
    void compilationSansNodes() {
        Natif natif = new Natif();
        DefinitionCompilation compilation = new DefinitionCompilation(natif, true, name -> 0);
        DefinitionRegistry registry = registry();
        String id = registry.ids().iterator().next();
        compilation.reset(registry);

        compilation.pump(path -> new DefinitionCompilation.AssetStatus(42L, new byte[] {1}, false), 0, System::nanoTime);
        assertTrue(compilation.seating(id).isEmpty());
        assertTrue(compilation.summary(id).isEmpty());
        assertTrue(compilation.effective().get(id).isEmpty(), "retirée de la registry effective");
        assertEquals(List.of(DefinitionIds.hash(id)), natif.removed, "et du registre natif");
        assertEquals(1, compilation.refusedCount());
    }
}
