package dev.axion.definition;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.axion.asset.AssetSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-630..T-634 — les cinq definitions de démonstration de R-1790, option A d'ADR-134.
 *
 * <p>Chacune est embarquée sous {@code data/axion/axion/definitions/example/}, ne se charge que
 * si {@code content.examples} est vrai, passe le validateur du schéma 1 (C-27, le même qui juge
 * toute definition) sans aucune faute, et désigne un modèle embarqué.
 *
 * <p>Que ce modèle compile par la chaîne d'assets du jeu est l'autre moitié de chaque test, côté
 * natif : {@code crates/ax-cli/tests/example_models.rs}. JUnit ne peut pas la porter : la tâche
 * {@code test} ne dépend pas de la construction des natifs, et un test qui exigerait la
 * bibliothèque se déclarerait non applicable en CI sans rien prouver. Que les cinq soient bien
 * dans l'artefact, et seules, {@code validateJar} le vérifie sur le JAR produit.
 */
class ExampleDefinitionsTest {

    /** En-tête d'un GLB : « glTF » en ASCII (glTF 2.0, §4.4.2). */
    private static final byte[] GLB_MAGIC = "glTF".getBytes(StandardCharsets.US_ASCII);

    /** Une ressource du mod, telle que {@code processResources} la range dans le JAR. */
    private static byte[] embarquee(String chemin) throws IOException {
        try (InputStream in = ExampleDefinitionsTest.class.getClassLoader().getResourceAsStream(chemin)) {
            assertNotNull(in, "ressource absente des ressources du mod : " + chemin);
            return in.readAllBytes();
        }
    }

    /** {@code <ns>:axion/<chemin>} désigne-t-il une ressource embarquée sous {@code data/} ? */
    private static final Predicate<String> MODELE_EMBARQUE = resolu -> {
        int colon = resolu.indexOf(':');
        String chemin = "data/" + resolu.substring(0, colon) + "/" + resolu.substring(colon + 1);
        return ExampleDefinitionsTest.class.getClassLoader().getResource(chemin) != null;
    };

    /** La seule definition d'un exemple, lue comme le jeu la lit. */
    private record Source(String location, byte[] contenu) implements AssetSource {
        @Override
        public List<String> list() {
            return List.of(location);
        }

        @Override
        public byte[] read(String path) {
            return contenu;
        }
    }

    private static void verifie(String nom, AssemblyKind genre)
            throws IOException, DefinitionException {
        byte[] json = embarquee("data/axion/axion/definitions/example/" + nom + ".json");
        byte[] modele = embarquee("data/axion/axion/models/example/" + nom + ".glb");
        assertArrayEquals(GLB_MAGIC, Arrays.copyOf(modele, 4), nom + ".glb n'est pas un GLB");

        Source source = new Source(DefinitionRegistry.EXAMPLES + nom + ".json", json);

        // Défaut de la release (R-1790) : ignorée, et sans refus — l'avoir désactivée n'est pas
        // une faute de l'auteur.
        DefinitionRegistry sans =
                DefinitionRegistry.load(source, new DefinitionRules(true, MODELE_EMBARQUE, false));
        assertEquals(0, sans.size(), "content.examples faux : l'exemple ne se charge pas");
        assertTrue(sans.refusals().isEmpty(), sans.refusals().toString());

        // Le validateur du schéma 1, sans aucune faute : la liste entière, pas seulement la
        // première, pour qu'un échec les nomme toutes.
        List<DefinitionChecker.Issue> fautes =
                DefinitionChecker.check(StrictJson.parse(json).getAsJsonObject());
        assertTrue(fautes.isEmpty(), nom + " : " + fautes);

        DefinitionRegistry avec =
                DefinitionRegistry.load(source, new DefinitionRules(true, MODELE_EMBARQUE, true));
        assertTrue(avec.refusals().isEmpty(), avec.refusals().toString());
        assertEquals(List.of("axion:example/" + nom), avec.ids());
        Definition definition = avec.get("axion:example/" + nom).orElseThrow();
        assertEquals(genre, definition.kind());
        assertEquals("axion:axion/models/example/" + nom + ".glb", definition.asset());

        // Les noms que la definition désigne dans l'asset y existent. Leur résolution contre
        // l'asset compilé vient avec l'étape 4 de C-27 (M3/M4, ADR-137) : un exemple qui la ferait
        // échouer ce jour-là se voit dès aujourd'hui.
        List<String> manquants = new ArrayList<>();
        designations("$", null, JsonParser.parseString(new String(json, StandardCharsets.UTF_8)),
                noms(modele), manquants);
        assertTrue(manquants.isEmpty(), nom + " désigne ce que son modèle n'a pas : " + manquants);
    }

    /** Ce qu'un GLB nomme : ses nodes, ses meshes, et ses nodes de rôle {@code socket}. */
    private record Noms(Set<String> nodes, Set<String> meshes, Set<String> sockets) {}

    /** Lit le bloc JSON d'un GLB (glTF 2.0, §4.4.3 : il suit l'en-tête de 12 octets). */
    private static Noms noms(byte[] glb) {
        ByteBuffer octets = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN);
        int longueur = octets.getInt(12);
        JsonObject gltf = JsonParser.parseString(new String(glb, 20, longueur, StandardCharsets.UTF_8))
                .getAsJsonObject();
        Set<String> nodes = new HashSet<>();
        Set<String> sockets = new HashSet<>();
        for (JsonElement element : gltf.getAsJsonArray("nodes")) {
            JsonObject node = element.getAsJsonObject();
            String nomNode = node.get("name").getAsString();
            nodes.add(nomNode);
            JsonObject extras = node.getAsJsonObject("extras");
            JsonObject axion = extras == null ? null : extras.getAsJsonObject("axion");
            if (axion != null && axion.has("role") && "socket".equals(axion.get("role").getAsString())) {
                sockets.add(nomNode);
            }
        }
        Set<String> meshes = new HashSet<>();
        for (JsonElement mesh : gltf.getAsJsonArray("meshes")) {
            meshes.add(mesh.getAsJsonObject().get("name").getAsString());
        }
        return new Noms(nodes, meshes, sockets);
    }

    /**
     * Parcourt une definition et relève chaque désignation d'un élément de l'asset que le modèle
     * ne porte pas. Les clés sont celles que le schéma 1 marque {@code x-axion-refers} vers un
     * node ou un mesh ; {@code sockets[]} déclare des sockets que l'asset doit porter (§9).
     */
    private static void designations(
            String chemin, String cle, JsonElement valeur, Noms noms, List<String> manquants) {
        if (valeur.isJsonObject()) {
            for (Map.Entry<String, JsonElement> membre : valeur.getAsJsonObject().entrySet()) {
                designations(chemin + "." + membre.getKey(), membre.getKey(), membre.getValue(),
                        noms, manquants);
            }
        } else if (valeur.isJsonArray()) {
            int index = 0;
            for (JsonElement element : valeur.getAsJsonArray()) {
                designations(chemin + "[" + index++ + "]", cle, element, noms, manquants);
            }
        } else if (valeur.isJsonPrimitive() && valeur.getAsJsonPrimitive().isString() && cle != null) {
            String designe = valeur.getAsString();
            Set<String> connus = switch (cle) {
                case "root_node", "node", "b_node", "hide_node", "anchors" -> noms.nodes();
                case "mesh", "swap_mesh" -> noms.meshes();
                case "sockets" -> noms.sockets();
                default -> null;
            };
            if (connus != null && !connus.contains(designe)) {
                manquants.add(chemin + " = " + designe);
            }
        }
    }

    @Test
    @DisplayName("T-630 : axion:example/crate — embarquée, chargée si content.examples, schéma 1 sans faute")
    void t630Caisse() throws IOException, DefinitionException {
        verifie("crate", AssemblyKind.RIGID_OBJECT);
    }

    @Test
    @DisplayName("T-631 : axion:example/buggy — embarquée, chargée si content.examples, schéma 1 sans faute")
    void t631Buggy() throws IOException, DefinitionException {
        verifie("buggy", AssemblyKind.VEHICLE);
    }

    @Test
    @DisplayName("T-632 : axion:example/flag — embarquée, chargée si content.examples, schéma 1 sans faute")
    void t632Drapeau() throws IOException, DefinitionException {
        verifie("flag", AssemblyKind.STATIC);
    }

    @Test
    @DisplayName("T-633 : axion:example/barrier — embarquée, chargée si content.examples, schéma 1 sans faute")
    void t633Barriere() throws IOException, DefinitionException {
        verifie("barrier", AssemblyKind.STRUCTURE);
    }

    @Test
    @DisplayName("T-634 : axion:example/winch_rope — embarquée, chargée si content.examples, schéma 1 sans faute")
    void t634Treuil() throws IOException, DefinitionException {
        verifie("winch_rope", AssemblyKind.MACHINE);
    }
}
