package dev.axion.definition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.axion.api.content.ContentKind;
import dev.axion.asset.AssetSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** ADR-165 D4, CAP-51 : les surcharges de l'administrateur, bornées, nommées, sans nouveau contenu. */
class ServerOverridesTest {

    private static byte[] json(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static JsonObject lu(Map<String, byte[]> files, String key) {
        return JsonParser.parseString(new String(files.get(key), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static final String GLACE = "axion:axion/physics_materials/ice.json";
    private static final String PATROUILLE = "policemod:axion/definitions/patrouille.json";
    private static final String ROUTE = "axion:axion/tires/route.json";

    @Test
    @DisplayName("ADR-165 D4 : une section, une clé, un identifiant ou un multiplicateur hors des règles est refusé et nommé")
    void laLectureRefuseEtNomme() {
        ServerOverrides surcharges = ServerOverrides.parse("""
                [physics_materials."axion:ice"]
                friction = 1.5
                couleur = 2
                [definitions."policemod:patrouille"]
                mass = 1.2
                engine_torque = 11
                brake_torque = "fort"
                [definitions."pas un identifiant"]
                mass = 1.0
                [tires."axion:route"]
                grip = 0.05
                [vitesse]
                max = 2
                [block_materials]
                "create:andesite_casing" = "axion:steel"
                "#create:casing" = "axion:steel"
                "create:x" = 3
                """);
        String refus = String.join("\n", surcharges.refusals());
        assertTrue(refus.contains("physics_materials.\"axion:ice\".couleur : clé inconnue"), refus);
        assertTrue(refus.contains("definitions.\"policemod:patrouille\".engine_torque : multiplicateur 11 hors de [0.1, 10.0]"), refus);
        assertTrue(refus.contains("brake_torque : un nombre attendu"), refus);
        assertTrue(refus.contains("definitions.\"pas un identifiant\" : identifiant attendu"), refus);
        assertTrue(refus.contains("tires.\"axion:route\".grip : multiplicateur 0.05 hors de"), refus);
        assertTrue(refus.contains("section inconnue [vitesse]"), refus);
        assertTrue(refus.contains("block_materials.\"create:x\" : un matériau"), refus);
        assertEquals(7, surcharges.refusals().size(), refus);
    }

    @Test
    @DisplayName("ADR-165 D4 : un matériau voit sa grandeur multipliée ; hors de la plage du champ, la surcharge est refusée")
    void lesMateriaux() {
        ServerOverrides surcharges = ServerOverrides.parse("""
                [physics_materials."axion:ice"]
                friction = 1.5
                restitution = 4
                density = 2
                [physics_materials."autre:absent"]
                friction = 2
                """);
        Map<String, byte[]> fichiers = new LinkedHashMap<>();
        fichiers.put(GLACE, json("{\"friction\": 0.04, \"restitution\": 0.3}"));
        List<String> appliquees = new ArrayList<>();
        List<String> refusees = new ArrayList<>();
        Map<String, byte[]> sortie = surcharges.apply(ContentKind.PHYSICS_MATERIAL, fichiers, Set.of(), appliquees, refusees);

        assertEquals(0.06, lu(sortie, GLACE).get("friction").getAsDouble(), 1e-12);
        assertEquals(0.3, lu(sortie, GLACE).get("restitution").getAsDouble(), "la restitution refusée garde sa valeur");
        assertEquals(1, appliquees.size(), appliquees::toString);
        assertTrue(appliquees.get(0).startsWith("physics_materials.\"axion:ice\".friction ×1.5 : friction 0.04 → 0.06"),
                appliquees::toString);
        String refus = String.join("\n", refusees);
        assertTrue(refus.contains("restitution ×4 : 0.3 deviendrait 1.2, hors de [0, 1]"), refus);
        assertTrue(refus.contains("density ×2 : le matériau n'écrit pas « density »"), refus);
        assertTrue(refus.contains("physics_materials.\"autre:absent\" : aucune entrée autre:absent"), refus);
        assertEquals(0.04, lu(fichiers, GLACE).get("friction").getAsDouble(), "l'entrée d'origine n'est pas modifiée");
    }

    @Test
    @DisplayName("ADR-165 Q3 : mass, engine_torque et brake_torque multiplient chacun leurs grandeurs de la definition")
    void lesDefinitions() {
        ServerOverrides surcharges = ServerOverrides.parse("""
                [definitions."policemod:patrouille"]
                mass = 1.5
                engine_torque = 0.5
                brake_torque = 2
                """);
        Map<String, byte[]> fichiers = new LinkedHashMap<>();
        fichiers.put(PATROUILLE, json("""
                {"schema": 3, "asset": "policemod:models/p.glb", "kind": "vehicle",
                 "physics": {"mass": 1000},
                 "parts": [{"name": "caisse", "mass": 200}],
                 "wheels": [{"node": "r1", "radius": 0.4, "mass": 20}, {"node": "r2", "radius": 0.4}],
                 "powertrain": {"engine": {"torque_curve": [[0, 0], [3000, 300]]}},
                 "brakes": {"max_torque_front": 1000, "handbrake_torque": 500}}"""));
        List<String> appliquees = new ArrayList<>();
        List<String> refusees = new ArrayList<>();
        JsonObject resultat = lu(surcharges.apply(ContentKind.DEFINITION, fichiers, Set.of(), appliquees, refusees), PATROUILLE);

        assertTrue(refusees.isEmpty(), refusees::toString);
        assertEquals(1500, resultat.getAsJsonObject("physics").get("mass").getAsDouble());
        assertEquals(300, resultat.getAsJsonArray("parts").get(0).getAsJsonObject().get("mass").getAsDouble());
        assertEquals(30, resultat.getAsJsonArray("wheels").get(0).getAsJsonObject().get("mass").getAsDouble());
        assertEquals(150, resultat.getAsJsonObject("powertrain").getAsJsonObject("engine").getAsJsonArray("torque_curve")
                .get(1).getAsJsonArray().get(1).getAsDouble());
        assertEquals(2000, resultat.getAsJsonObject("brakes").get("max_torque_front").getAsDouble());
        assertEquals(1000, resultat.getAsJsonObject("brakes").get("handbrake_torque").getAsDouble());
        assertEquals(3, appliquees.size(), appliquees::toString);
        assertTrue(appliquees.get(0).contains("physics.mass 1000 → 1500, parts[0].mass 200 → 300, wheels[0].mass 20 → 30"),
                appliquees::toString);
    }

    @Test
    @DisplayName("ADR-165 D4 : une definition qui n'écrit pas la grandeur visée refuse la surcharge, et garde son document")
    void uneDefinitionSansLaGrandeur() {
        ServerOverrides surcharges = ServerOverrides.parse("""
                [definitions."policemod:patrouille"]
                engine_torque = 2
                """);
        Map<String, byte[]> fichiers = new LinkedHashMap<>();
        fichiers.put(PATROUILLE, json("{\"schema\": 3, \"asset\": \"policemod:models/p.glb\", \"kind\": \"visual\"}"));
        List<String> refusees = new ArrayList<>();
        Map<String, byte[]> sortie = surcharges.apply(ContentKind.DEFINITION, fichiers, Set.of(), new ArrayList<>(), refusees);
        assertSame(fichiers.get(PATROUILLE), sortie.get(PATROUILLE), "rien de touché");
        assertTrue(refusees.get(0).contains("n'écrit aucune grandeur que « engine_torque » multiplie"), refusees::toString);
    }

    @Test
    @DisplayName("ADR-165 D4 : le produit s'arrondit à douze chiffres significatifs, sans bruit binaire")
    void leProduitSansBruit() {
        assertEquals(14.4, ServerOverrides.product(12, 1.2));
        assertEquals(0.075, ServerOverrides.product(0.05, 1.5));
        assertEquals(408, ServerOverrides.product(340, 1.2));
    }

    @Test
    @DisplayName("ADR-165 D4 : grip multiplie l'adhérence d'un pneu")
    void lesPneus() {
        ServerOverrides surcharges = ServerOverrides.parse("""
                [tires."axion:route"]
                grip = 1.1
                """);
        Map<String, byte[]> fichiers = new LinkedHashMap<>();
        fichiers.put(ROUTE, json("{\"schema\": 1, \"mu_long\": 1.0, \"mu_lat\": 0.9}"));
        JsonObject resultat = lu(surcharges.apply(ContentKind.TIRE, fichiers, Set.of(), new ArrayList<>(), new ArrayList<>()),
                ROUTE);
        assertEquals(1.1, resultat.get("mu_long").getAsDouble(), 1e-12);
        assertEquals(0.99, resultat.get("mu_lat").getAsDouble(), 1e-12);
    }

    /** Une source de quelques fichiers, dans l'ordre donné. */
    private record Source(Map<String, byte[]> files) implements AssetSource {
        @Override
        public List<String> list() {
            return List.copyOf(files.keySet());
        }

        @Override
        public byte[] read(String path) {
            return files.get(path);
        }
    }

    @Test
    @DisplayName("ADR-165 D4 : la correspondance de l'administrateur l'emporte sur celle d'un mod, quel que soit son espace de noms")
    void lesCorrespondancesDeLAdministrateurLEmportent() {
        Map<String, byte[]> materiaux = new LinkedHashMap<>();
        materiaux.put("mymod:" + PhysicsMaterials.PREFIX + "stone" + PhysicsMaterials.EXTENSION,
                json("{\"friction\": 0.6, \"restitution\": 0.1}"));
        materiaux.put("mymod:" + PhysicsMaterials.PREFIX + "steel" + PhysicsMaterials.EXTENSION,
                json("{\"friction\": 0.5}"));
        PhysicsMaterials lus = PhysicsMaterials.load(new Source(materiaux));

        Map<String, byte[]> correspondances = new LinkedHashMap<>();
        // « zmod » se lit après « axion » : sans la règle, il l'emporterait.
        correspondances.put("zmod:" + BlockMaterialMappings.PREFIX + "z" + BlockMaterialMappings.EXTENSION,
                json("{\"schema\": 1, \"by_block\": {\"create:casing\": \"mymod:stone\"}}"));
        ServerOverrides surcharges = ServerOverrides.parse("""
                [block_materials]
                "create:casing" = "mymod:steel"
                "#create:metaux" = "mymod:steel"
                "create:verre" = "mymod:inconnu"
                """);
        List<String> appliquees = new ArrayList<>();
        List<String> refusees = new ArrayList<>();
        Map<String, byte[]> sortie = surcharges.apply(ContentKind.BLOCK_MATERIAL, correspondances,
                Set.of("mymod:stone", "mymod:steel"), appliquees, refusees);
        assertTrue(sortie.containsKey(ServerOverrides.BLOCK_MAPPING_KEY));
        assertEquals(2, appliquees.size(), appliquees::toString);
        assertEquals(List.of("block_materials.\"create:verre\" : matériau « mymod:inconnu » inconnu ; aucun mod ne le"
                + " déclare"), refusees, "un matériau inconnu est refusé à l'application, pas dit appliqué");

        BlockMaterialMappings.Result resultat = BlockMaterialMappings.load(new Source(sortie), lus);
        assertTrue(resultat.refusals().isEmpty(), resultat.refusals()::toString);
        assertEquals("mymod:steel", resultat.mapping().resolve("create:casing", List.of()).id());
        assertEquals("mymod:steel", resultat.mapping().resolve("create:autre", List.of("create:metaux")).id());
    }

    @Test
    @DisplayName("ADR-165 D4 : le modèle écrit au premier démarrage ne porte aucune surcharge ; un fichier absent non plus")
    void leModeleEstVide(@TempDir Path dossier) {
        ServerOverrides modele = ServerOverrides.parse(ServerOverrides.template());
        assertTrue(modele.isEmpty());
        assertTrue(modele.refusals().isEmpty(), modele.refusals()::toString);
        assertSame(ServerOverrides.NONE, ServerOverrides.read(dossier.resolve(ServerOverrides.FILE_NAME)));
    }
}
