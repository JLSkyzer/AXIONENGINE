package dev.axion.content;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.axion.api.content.ContentKind;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** ADR-162 D2, D3 : le registre du contenu enregistré par les mods. */
class ContentRegistryTest {

    @TempDir
    Path temp;

    private Function<String, Path> jar(String mod, Map<String, String> files) throws IOException {
        Path root = temp.resolve(mod);
        for (Map.Entry<String, String> file : files.entrySet()) {
            Path path = root.resolve(file.getKey());
            Files.createDirectories(path.getParent());
            Files.writeString(path, file.getValue(), StandardCharsets.UTF_8);
        }
        return relative -> {
            Path path = root.resolve(relative);
            return Files.exists(path) ? path : null;
        };
    }

    private static List<String> keys(Map<ContentKind, List<ContentRegistry.Entry>> resolved, ContentKind kind) {
        return resolved.get(kind).stream().map(ContentRegistry.Entry::key).toList();
    }

    @Test
    @DisplayName("ADR-162 D3 : un dossier du JAR devient le contenu du mod, dans son espace de noms")
    void unDossierDevientLeContenuDuMod() throws IOException {
        ContentRegistry registry = new ContentRegistry(Long.MAX_VALUE);
        Function<String, Path> police = jar("policemod", Map.of(
                "axion/definitions/vehicules/patrouille.json", "{\"d\":1}",
                "axion/models/patrouille.glb", "glb",
                "axion/models/patrouille.png", "image ignorée",
                "axion/tires/route.json", "{}",
                "axion/physics_materials/acier.json", "{}",
                "axion/autre/ignore.json", "{}",
                "axion/definitions/note.txt", "ignoré"));
        try (ContentRegistry.Session session = registry.open("policemod", police)) {
            session.registerFolder("axion/");
        }
        Map<ContentKind, List<ContentRegistry.Entry>> resolved = registry.resolve();
        assertEquals(List.of("policemod:axion/definitions/vehicules/patrouille.json"),
                keys(resolved, ContentKind.DEFINITION));
        assertEquals(List.of("policemod:axion/models/patrouille.glb"), keys(resolved, ContentKind.MODEL));
        assertEquals(List.of("policemod:axion/tires/route.json"), keys(resolved, ContentKind.TIRE));
        assertEquals(List.of("policemod:axion/physics_materials/acier.json"),
                keys(resolved, ContentKind.PHYSICS_MATERIAL));
        ContentRegistry.Entry definition = resolved.get(ContentKind.DEFINITION).get(0);
        assertEquals("policemod", definition.owner());
        assertTrue(definition.source().contains("axion/definitions/vehicules/patrouille.json"));
        assertArrayEquals("{\"d\":1}".getBytes(StandardCharsets.UTF_8), definition.bytes());
        assertEquals(List.of(), registry.errors());
    }

    @Test
    @DisplayName("ADR-162 D3 : une même clé déclarée deux fois est refusée, en nommant les deux sources")
    void unDoublonEstRefuseEnNommantLesDeuxSources() throws IOException {
        ContentRegistry registry = new ContentRegistry(Long.MAX_VALUE);
        Function<String, Path> mod = jar("mod", Map.of(
                "a/definitions/x.json", "{\"premier\":1}",
                "b/definitions/x.json", "{\"second\":1}"));
        try (ContentRegistry.Session session = registry.open("mod", mod)) {
            session.registerFolder("a");
            session.registerFolder("b");
        }
        Map<ContentKind, List<ContentRegistry.Entry>> resolved = registry.resolve();
        assertArrayEquals("{\"premier\":1}".getBytes(StandardCharsets.UTF_8),
                resolved.get(ContentKind.DEFINITION).get(0).bytes());
        assertEquals(1, registry.errors().size());
        String error = registry.errors().get(0);
        assertTrue(error.contains("doublon") && error.contains("a/definitions/x.json")
                && error.contains("b/definitions/x.json"), error);
    }

    @Test
    @DisplayName("ADR-162 D3 : un mod remplace explicitement l'entrée d'un autre, et la trace nomme les deux")
    void unModRemplaceLEntreeDUnAutre() throws IOException {
        ContentRegistry registry = new ContentRegistry(Long.MAX_VALUE);
        try (ContentRegistry.Session axion = registry.open("axion",
                jar("axion", Map.of("axion/definitions/example/buggy.json", "{\"original\":1}")))) {
            axion.registerFolder("axion");
        }
        try (ContentRegistry.Session pack = registry.open("monpack",
                jar("monpack", Map.of("remplace/buggy.json", "{\"nouveau\":1}")))) {
            pack.replace(ContentKind.DEFINITION, "axion:example/buggy", "remplace/buggy.json");
        }
        ContentRegistry.Entry entry = registry.resolve().get(ContentKind.DEFINITION).get(0);
        assertEquals("axion:axion/definitions/example/buggy.json", entry.key());
        assertEquals("monpack", entry.owner());
        assertTrue(entry.source().contains("remplace l'entrée de axion"), entry.source());
        assertArrayEquals("{\"nouveau\":1}".getBytes(StandardCharsets.UTF_8), entry.bytes());
        assertEquals(List.of(), registry.errors());
    }

    @Test
    @DisplayName("ADR-162 D3 : deux mods qui remplacent la même entrée sont refusés tous les deux")
    void deuxRemplacementsDeLaMemeEntreeSontRefuses() throws IOException {
        ContentRegistry registry = new ContentRegistry(Long.MAX_VALUE);
        try (ContentRegistry.Session axion = registry.open("axion",
                jar("axion", Map.of("axion/tires/route.json", "{\"original\":1}")))) {
            axion.registerFolder("axion");
        }
        for (String mod : List.of("un", "deux")) {
            try (ContentRegistry.Session session = registry.open(mod, jar(mod, Map.of("p.json", "{\"" + mod + "\":1}")))) {
                session.replace(ContentKind.TIRE, "axion:route", "p.json");
            }
        }
        ContentRegistry.Entry entry = registry.resolve().get(ContentKind.TIRE).get(0);
        assertEquals("axion", entry.owner(), "l'original reste");
        String error = registry.errors().get(0);
        assertTrue(error.contains("plusieurs mods") && error.contains("un") && error.contains("deux"), error);
    }

    @Test
    @DisplayName("ADR-162 D3 : une cible de remplacement inconnue, ou un dossier absent, est consigné sans lever")
    void lesErreursDeContenuSontConsignees() throws IOException {
        ContentRegistry registry = new ContentRegistry(Long.MAX_VALUE);
        try (ContentRegistry.Session session = registry.open("mod", jar("mod", Map.of("p.json", "{}")))) {
            session.registerFolder("absent/");
            session.replace(ContentKind.DEFINITION, "axion:inexistante", "p.json");
        }
        registry.resolve();
        List<String> errors = registry.errors();
        assertEquals(2, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("absent"), errors.get(0));
        assertTrue(errors.get(1).contains("axion:axion/definitions/inexistante.json"), errors.get(1));
    }

    @Test
    @DisplayName("ADR-162 D3 : un mod ne se remplace pas lui-même, ni n'écrit hors de son JAR")
    void lesAppelsInvalidesLevent() throws IOException {
        ContentRegistry registry = new ContentRegistry(Long.MAX_VALUE);
        ContentRegistry.Session session = registry.open("mod", jar("mod", Map.of("p.json", "{}")));
        assertThrows(IllegalArgumentException.class,
                () -> session.replace(ContentKind.DEFINITION, "mod:x", "p.json"));
        assertThrows(IllegalArgumentException.class, () -> session.registerFolder("../ailleurs"));
        assertThrows(IllegalArgumentException.class, () -> session.registerFolder("/racine"));
        assertThrows(IllegalArgumentException.class, () -> session.registerFolder(""));
        assertThrows(IllegalArgumentException.class,
                () -> session.replace(ContentKind.DEFINITION, "pas-un-identifiant", "p.json"));
        assertThrows(IllegalArgumentException.class, () -> registry.open("Mod Majuscule", r -> null));
    }

    @Test
    @DisplayName("ADR-162 D3 : un registraire n'est valable que pendant l'appel de son fournisseur")
    void unRegistraireFermeLeve() throws IOException {
        ContentRegistry registry = new ContentRegistry(Long.MAX_VALUE);
        ContentRegistry.Session session = registry.open("mod", jar("mod", Map.of("axion/tires/a.json", "{}")));
        session.close();
        assertThrows(IllegalStateException.class, () -> session.registerFolder("axion"));
        assertThrows(IllegalStateException.class,
                () -> session.replace(ContentKind.TIRE, "autre:a", "axion/tires/a.json"));
    }

    @Test
    @DisplayName("ADR-139 : un voisin de modèle au-delà de la limite est consigné, pas retenu")
    void unVoisinTropGrosEstConsigne() throws IOException {
        ContentRegistry registry = new ContentRegistry(4);
        try (ContentRegistry.Session session = registry.open("mod", jar("mod", Map.of(
                "axion/models/a.glb", "glb volumineux mais source",
                "axion/models/a.bin", "voisin trop gros")))) {
            session.registerFolder("axion");
        }
        assertEquals(List.of("mod:axion/models/a.glb"), keys(registry.resolve(), ContentKind.MODEL));
        assertTrue(registry.errors().get(0).contains("a.bin"), registry.errors().toString());
    }

    @Test
    @DisplayName("ADR-162 D2 : la source enregistrée rend les clés et le contenu aux registres")
    void laSourceEnregistreeRendLesCles() throws IOException {
        ContentRegistry registry = new ContentRegistry(Long.MAX_VALUE);
        try (ContentRegistry.Session session = registry.open("mod",
                jar("mod", Map.of("axion/definitions/b.json", "{\"b\":1}", "axion/definitions/a.json", "{}")))) {
            session.registerFolder("axion");
        }
        RegisteredSource source = new RegisteredSource(registry.resolve().get(ContentKind.DEFINITION));
        assertEquals(List.of("mod:axion/definitions/a.json", "mod:axion/definitions/b.json"), source.list());
        assertArrayEquals("{\"b\":1}".getBytes(StandardCharsets.UTF_8), source.read("mod:axion/definitions/b.json"));
        assertTrue(source.ownerOf("mod:axion/definitions/a.json").startsWith("mod ("));
        assertThrows(IOException.class, () -> source.read("mod:axion/definitions/c.json"));
    }
}
