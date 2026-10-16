package dev.axion.definition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.axion.asset.AssetSource;
import dev.axion.world.BlockMaterials;
import dev.axion.world.BlockMaterials.Material;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T-841 — §11.4 : mappage bloc → matériau, fusion des fichiers, priorité des étiquettes (ADR-145). */
class BlockMaterialMappingsTest {

    /** Fichiers simulés, par chemin de ressource. */
    private static final class FakeSource implements AssetSource {
        private final Map<String, byte[]> files = new LinkedHashMap<>();

        FakeSource put(String location, String content) {
            files.put(location, content.getBytes(StandardCharsets.UTF_8));
            return this;
        }

        FakeSource mapping(String id, String content) {
            int colon = id.indexOf(':');
            return put(id.substring(0, colon) + ":" + BlockMaterialMappings.PREFIX + id.substring(colon + 1)
                    + BlockMaterialMappings.EXTENSION, content);
        }

        FakeSource material(String id, String content) {
            int colon = id.indexOf(':');
            return put(id.substring(0, colon) + ":" + PhysicsMaterials.PREFIX + id.substring(colon + 1)
                    + PhysicsMaterials.EXTENSION, content);
        }

        @Override
        public List<String> list() {
            return List.copyOf(files.keySet());
        }

        @Override
        public byte[] read(String path) {
            return files.get(path);
        }
    }

    /** Glace, bois et pierre, aux contacts reconnaissables. */
    private static final PhysicsMaterials MATERIALS = PhysicsMaterials.load(new FakeSource()
            .material("mymod:ice", "{\"friction\": 0.05}")
            .material("mymod:wood", "{\"friction\": 0.7}")
            .material("mymod:stone", "{\"friction\": 0.6, \"restitution\": 0.1}")
            .material("mymod:bouncy", "{\"friction\": 0.8, \"restitution\": 0.8}"));

    private static Material contact(double friction, double restitution) {
        return new Material((float) friction, (float) restitution);
    }

    @Test
    @DisplayName("T-841 : bloc exact, puis la première étiquette déclarée que le bloc porte, puis le défaut (R-1040)")
    void ordreDeResolution() {
        BlockMaterialMappings.Result result = BlockMaterialMappings.load(new FakeSource().mapping("mymod:base",
                "{\"schema\": 1, \"default\": \"mymod:stone\","
                        + " \"by_tag\": {\"mymod:glissant\": \"mymod:ice\", \"minecraft:logs\": \"mymod:wood\"},"
                        + " \"by_block\": {\"minecraft:slime_block\": \"mymod:bouncy\"}}"), MATERIALS);
        assertTrue(result.refusals().isEmpty(), result.refusals()::toString);
        BlockMaterials mapping = result.mapping();

        assertEquals(contact(0.8, 0.8), mapping.resolve("minecraft:slime_block", List.of("minecraft:logs")),
                "le bloc exact prime sur ses étiquettes");
        assertEquals(contact(0.7, 0), mapping.resolve("minecraft:oak_log", List.of("minecraft:logs")));
        // Le bloc porte les deux étiquettes, dans l'ordre inverse : celle déclarée d'abord l'emporte.
        assertEquals(contact(0.05, 0), mapping.resolve("x:y", List.of("minecraft:logs", "mymod:glissant")));
        assertEquals(contact(0.6, 0.1), mapping.resolve("minecraft:dirt", List.of("x:inconnue")));
    }

    @Test
    @DisplayName("ADR-145 : les fichiers se fusionnent dans l'ordre des identifiants ; un plus tardif remplace, et le dit")
    void fusionParIdentifiant() {
        BlockMaterialMappings.Result result = BlockMaterialMappings.load(new FakeSource()
                // Listés dans le désordre : seul l'identifiant ordonne.
                .mapping("zpack:surcharge", "{\"schema\": 1, \"default\": \"mymod:wood\","
                        + " \"by_tag\": {\"minecraft:logs\": \"mymod:ice\", \"zpack:neuf\": \"mymod:bouncy\"},"
                        + " \"by_block\": {\"minecraft:ice\": \"mymod:stone\"}}")
                .mapping("axion:base", "{\"schema\": 1, \"default\": \"mymod:stone\","
                        + " \"by_tag\": {\"minecraft:logs\": \"mymod:wood\", \"minecraft:planks\": \"mymod:wood\"},"
                        + " \"by_block\": {\"minecraft:ice\": \"mymod:ice\"}}"), MATERIALS);
        assertTrue(result.refusals().isEmpty(), result.refusals()::toString);
        BlockMaterials mapping = result.mapping();

        assertEquals(contact(0.7, 0), mapping.fallback(), "default de zpack, plus tardif");
        assertEquals(contact(0.6, 0.1), mapping.resolve("minecraft:ice", List.of()));
        assertEquals(contact(0.05, 0), mapping.resolve("minecraft:oak_log", List.of("minecraft:logs")));
        // L'étiquette reprise garde sa place : avant planks, elle-même avant la nouvelle de zpack.
        assertEquals(contact(0.05, 0), mapping.resolve("b:c", List.of("zpack:neuf", "minecraft:logs")));
        assertEquals(contact(0.7, 0), mapping.resolve("b:c", List.of("zpack:neuf", "minecraft:planks")));

        assertEquals(3, result.notes().size(), result.notes()::toString);
        assertTrue(result.notes().stream().allMatch(note -> note.startsWith("zpack:axion/block_materials/surcharge.json")
                && note.contains("axion:axion/block_materials/base.json")), result.notes()::toString);
    }

    @Test
    @DisplayName("ADR-145 : une entrée qui nomme un matériau inconnu est refusée, nommée ; le reste du fichier vaut")
    void entreesRefusees() {
        BlockMaterialMappings.Result result = BlockMaterialMappings.load(new FakeSource().mapping("mymod:base",
                "{\"schema\": 1, \"default\": \"mymod:absent\","
                        + " \"by_tag\": {\"Pas Un Id\": \"mymod:ice\", \"minecraft:logs\": 3},"
                        + " \"by_block\": {\"minecraft:ice\": \"mymod:ice\", \"minecraft:sand\": \"mymod:sable\"}}"),
                MATERIALS);
        List<String> refus = result.refusals();
        assertEquals(4, refus.size(), refus::toString);
        assertTrue(refus.get(0).contains("$.default : matériau « mymod:absent » inconnu"), refus::toString);
        assertTrue(refus.stream().anyMatch(r -> r.contains("$.by_tag[\"Pas Un Id\"]")), refus::toString);
        assertTrue(refus.stream().anyMatch(r -> r.contains("$.by_tag[\"minecraft:logs\"] : attendu l'identifiant")),
                refus::toString);
        assertTrue(refus.stream().anyMatch(r -> r.contains("« mymod:sable » inconnu")), refus::toString);
        assertEquals(contact(0.05, 0), result.mapping().resolve("minecraft:ice", List.of()), "l'entrée valide reste");
        // Sans default valide, l'acier générique (R-1032).
        PhysicsMaterials.Material generique = PhysicsMaterials.genericSteel();
        assertEquals(contact(generique.friction(), generique.restitution()), result.mapping().fallback());
    }

    @Test
    @DisplayName("§11.4 : schema 1 obligatoire, clés connues seules ; un fichier fautif est refusé entier")
    void formeDuFichier() {
        for (String[] cas : new String[][] {
            {"{\"default\": \"mymod:ice\"}", "E-7001", "$.schema : champ obligatoire"},
            {"{\"schema\": 2}", "E-7003", "$.schema"},
            {"{\"schema\": 1, \"blocks\": {}}", "E-7001", "$.blocks : champ inconnu"},
            {"[]", "E-7001", "un mappage bloc → matériau est un objet JSON"},
        }) {
            BlockMaterialMappings.Result result = BlockMaterialMappings.load(
                    new FakeSource().mapping("mymod:base", cas[0]), MATERIALS);
            assertEquals(1, result.refusals().size(), result.refusals()::toString);
            assertTrue(result.refusals().get(0).startsWith(cas[1] + " mymod:axion/block_materials/base.json")
                    && result.refusals().get(0).contains(cas[2]), result.refusals()::toString);
        }
    }

    @Test
    @DisplayName("ADR-145 : le mappage livré et la bibliothèque de base se chargent sans refus, contact conservé")
    void donneesLivrees() throws IOException {
        Path data = Path.of(System.getProperty("axion.repo.dir"), "java", "axion-mod", "src", "main",
                "resources", "data");
        PhysicsMaterials materials = PhysicsMaterials.load(source(data, PhysicsMaterials.PREFIX));
        assertTrue(materials.refusals().isEmpty(), materials.refusals()::toString);
        for (String id : List.of("axion:steel", "axion:aluminium", "axion:plastic", "axion:glass",
                "axion:rubber", "axion:wood", "axion:stone", "axion:fabric", "axion:flesh",
                "axion:concrete", "axion:carbon_fiber")) {
            assertTrue(materials.knows(id), "bibliothèque de base de R-1030 : " + id);
        }

        BlockMaterialMappings.Result result = BlockMaterialMappings.load(
                source(data, BlockMaterialMappings.PREFIX), materials);
        assertTrue(result.refusals().isEmpty(), result.refusals()::toString);
        BlockMaterials mapping = result.mapping();
        // Le comportement des tuiles d'avant ADR-145 : glace glissante, slime qui rebondit.
        assertEquals(contact(0.05, 0), mapping.resolve("minecraft:ice", List.of()));
        assertEquals(contact(0.02, 0), mapping.resolve("minecraft:blue_ice", List.of()));
        assertEquals(contact(0.8, 0.8), mapping.resolve("minecraft:slime_block", List.of()));
        assertEquals(contact(1.4, 0), mapping.resolve("minecraft:honey_block", List.of()));
        assertEquals(contact(0.7, 0), mapping.resolve("minecraft:oak_log", List.of("minecraft:logs")));
        assertEquals(contact(0.9, 0), mapping.resolve("minecraft:white_wool", List.of("minecraft:wool")));
        assertEquals(contact(0.6, 0), mapping.fallback());
    }

    /** {@return les fichiers {@code data/<ns>/<prefix>**.json} d'une racine, comme le jeu les énumère} */
    private static AssetSource source(Path data, String prefix) throws IOException {
        Map<String, Path> files = new TreeMap<>();
        try (Stream<Path> namespaces = Files.list(data)) {
            for (Path namespace : namespaces.toList()) {
                Path dir = namespace.resolve(prefix);
                if (!Files.isDirectory(dir)) {
                    continue;
                }
                try (Stream<Path> walk = Files.walk(dir)) {
                    for (Path file : walk.filter(Files::isRegularFile).toList()) {
                        files.put(namespace.getFileName() + ":"
                                + namespace.relativize(file).toString().replace('\\', '/'), file);
                    }
                }
            }
        }
        return new AssetSource() {
            @Override
            public List<String> list() {
                return List.copyOf(files.keySet());
            }

            @Override
            public byte[] read(String path) throws IOException {
                return Files.readAllBytes(files.get(path));
            }
        };
    }
}
