package dev.axion.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.axion.api.content.ContentKind;
import dev.axion.api.content.DefinitionDocument;
import dev.axion.definition.Definition;
import dev.axion.definition.DefinitionRegistry;
import dev.axion.definition.DefinitionRules;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-162 D3, D4 : une definition construite en Java entre par l'API comme un fichier. */
class DefinitionDocumentTest {

    private static final String JSON = "{\"schema\": 1, \"asset\": \"mod:models/a.glb\", \"kind\": \"rigid_object\","
            + " \"physics\": {\"mass\": 2.5, \"center_of_mass\": [0, 0.5, 0]}}";

    /** Le même contenu que {@link #JSON}, construit en Java, clés dans un autre ordre. */
    private static DefinitionDocument document() {
        Map<String, Object> racine = new LinkedHashMap<>();
        racine.put("kind", "rigid_object");
        racine.put("physics", Map.of("center_of_mass", List.of(0, 0.5, 0), "mass", 2.5f));
        racine.put("asset", "mod:models/a.glb");
        racine.put("schema", 1);
        return DefinitionDocument.of(racine);
    }

    @Test
    @DisplayName("ADR-162 D3 : un document range ses nombres en Long et Double, et se fige")
    void unDocumentSeFige() {
        DefinitionDocument document = document();
        assertEquals(1L, document.root().get("schema"));
        assertEquals(2.5, ((Map<?, ?>) document.root().get("physics")).get("mass"));
        assertThrows(UnsupportedOperationException.class, () -> document.root().put("x", 1));
        assertThrows(UnsupportedOperationException.class,
                () -> ((List<?>) ((Map<?, ?>) document.root().get("physics")).get("center_of_mass")).clear());
        List<Object> liste = new ArrayList<>(List.of(1));
        Map<String, Object> modifiable = new HashMap<>(Map.of("liste", liste));
        DefinitionDocument copie = DefinitionDocument.of(modifiable);
        liste.add(2);
        assertEquals(List.of(1L), copie.root().get("liste"), "le document est une copie");
        assertEquals(document(), document());
    }

    @Test
    @DisplayName("R-581 : ni null, ni nombre non fini, ni objet quelconque ; le refus nomme le chemin")
    void lesValeursHorsJsonSontRefusees() {
        Map<String, Object> nul = new HashMap<>();
        nul.put("asset", null);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> DefinitionDocument.of(nul))
                .getMessage().startsWith("$.asset : null"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> DefinitionDocument.of(
                Map.of("physics", Map.of("mass", Double.NaN)))).getMessage().startsWith("$.physics.mass"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> DefinitionDocument.of(
                Map.of("sockets", List.of("a", new Object())))).getMessage().startsWith("$.sockets[1]"));
        Runnable lambda = () -> { };
        assertThrows(IllegalArgumentException.class, () -> DefinitionDocument.of(Map.of("x", lambda)));
    }

    @Test
    @DisplayName("CAP-47 : la definition Java et le fichier de même contenu ont la même empreinte")
    void memeContenuMemeEmpreinte() throws Exception {
        DefinitionRules regles = new DefinitionRules(true, "mod:axion/models/a.glb"::equals);
        String lieu = "mod:axion/definitions/a.json";
        Definition fichier = DefinitionRegistry.load(new FakeSource(lieu, JSON.getBytes(StandardCharsets.UTF_8)),
                regles).get("mod:a").orElseThrow();
        Definition java = DefinitionRegistry.load(new FakeSource(lieu, ContentRegistry.canonicalBytes(document())),
                regles).get("mod:a").orElseThrow();
        assertEquals(fichier.sha256(), java.sha256());
        assertEquals(fichier.root(), java.root());
    }

    @Test
    @DisplayName("ADR-162 D3 : register range la definition Java sous la clé de son fichier, au nom du mod")
    void registerRangeLaDefinition() {
        ContentRegistry registry = new ContentRegistry(Long.MAX_VALUE);
        try (ContentRegistry.Session session = registry.open("policemod", relative -> null)) {
            session.register("vehicules/patrouille", document());
        }
        ContentRegistry.Entry entry = registry.resolve().get(ContentKind.DEFINITION).get(0);
        assertEquals("policemod:axion/definitions/vehicules/patrouille.json", entry.key());
        assertEquals("policemod", entry.owner());
        assertTrue(entry.source().contains("builder"), entry.source());
        assertEquals("{\"asset\":\"mod:models/a.glb\",\"kind\":\"rigid_object\","
                + "\"physics\":{\"center_of_mass\":[0,0.5,0],\"mass\":2.5},\"schema\":1}",
                new String(entry.bytes(), StandardCharsets.UTF_8));
        assertTrue(registry.errors().isEmpty(), registry.errors().toString());
    }

    @Test
    @DisplayName("ADR-162 D3 : un fichier et une definition Java de même nom sont un doublon, refusé en nommant les deux")
    void unFichierEtUneDefinitionJavaSontUnDoublon(@org.junit.jupiter.api.io.TempDir Path temp) throws Exception {
        Path jar = temp.resolve("mod");
        java.nio.file.Files.createDirectories(jar.resolve("axion/definitions"));
        java.nio.file.Files.writeString(jar.resolve("axion/definitions/a.json"), JSON);
        ContentRegistry registry = new ContentRegistry(Long.MAX_VALUE);
        try (ContentRegistry.Session session = registry.open("mod", relative -> jar.resolve(relative))) {
            session.registerFolder("axion");
            session.register("a", document());
        }
        assertEquals(1, registry.resolve().get(ContentKind.DEFINITION).size());
        String erreur = String.join("\n", registry.errors());
        assertTrue(erreur.contains("doublon refusé") && erreur.contains("axion/definitions/a.json")
                && erreur.contains("builder"), erreur);
    }

    @Test
    @DisplayName("ADR-162 D3 : un mod remplace par une definition Java la definition d'un autre ; la trace nomme les deux")
    void replaceParUneDefinitionJava() {
        ContentRegistry registry = new ContentRegistry(Long.MAX_VALUE);
        try (ContentRegistry.Session axion = registry.open("axion", relative -> null)) {
            axion.register("cible", DefinitionDocument.of(Map.of("schema", 1)));
        }
        try (ContentRegistry.Session pack = registry.open("monpack", relative -> null)) {
            pack.replace("axion:cible", document());
        }
        ContentRegistry.Entry entry = registry.resolve().get(ContentKind.DEFINITION).get(0);
        assertEquals("axion:axion/definitions/cible.json", entry.key());
        assertEquals("monpack", entry.owner());
        assertTrue(entry.source().contains("monpack — builder") && entry.source().contains("axion"), entry.source());
    }

    @Test
    @DisplayName("ADR-162 D3 : un nom mal formé, un auto-remplacement ou un registraire fermé lèvent")
    void lesAppelsInvalidesLevent() {
        ContentRegistry registry = new ContentRegistry(Long.MAX_VALUE);
        ContentRegistry.Session session = registry.open("mod", relative -> null);
        for (String nom : List.of("", "a.json", "/a", "a/", "a//b", "../a", "A", "a b")) {
            assertThrows(IllegalArgumentException.class, () -> session.register(nom, document()), nom);
        }
        assertThrows(IllegalArgumentException.class, () -> session.replace("mod:a", document()));
        assertThrows(IllegalArgumentException.class, () -> session.replace("pas-un-identifiant", document()));
        session.close();
        assertThrows(IllegalStateException.class, () -> session.register("a", document()));
        assertThrows(IllegalStateException.class, () -> session.replace("autre:a", document()));
    }

    /** Une source d'un seul fichier, lue comme le jeu la lit. */
    private record FakeSource(String location, byte[] contenu) implements dev.axion.asset.AssetSource {
        @Override
        public List<String> list() {
            return List.of(location);
        }

        @Override
        public byte[] read(String path) {
            return contenu;
        }
    }
}
