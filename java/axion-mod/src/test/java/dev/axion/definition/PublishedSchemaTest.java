package dev.axion.definition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T-284 — R-1783 : le schéma publié est celui du validateur, et les definitions
 * du dépôt le respectent.
 */
class PublishedSchemaTest {

    /** Répertoires sans definition, dont le parcours ne ferait que coûter. */
    private static final Set<String> SKIPPED =
            Set.of(".git", ".gradle", ".idea", "build", "target", "run", "run-data", "node_modules");

    private static Path property(String name) {
        String value = System.getProperty(name);
        assertTrue(value != null && !value.isBlank(), name + " non fourni par le build");
        return Path.of(value);
    }

    @Test
    @DisplayName("T-284 : docs/schema/definition-1.json est le rendu exact du schéma 1")
    void schemaPublieAJour() throws IOException {
        Path file = property("axion.schema.file");
        String rendu = SchemaExport.render();

        if (Boolean.getBoolean("axion.schema.update")) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, rendu, StandardCharsets.UTF_8);
        }

        assertTrue(Files.isReadable(file), () -> "schéma publié absent : " + file);
        // Git peut convertir les fins de ligne à l'extraction : seul le contenu compte.
        String publie = Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertEquals(rendu, publie,
                "le schéma publié a divergé du validateur ; le régénérer avec :\n"
                        + "  ./gradlew :axion-mod:test --tests dev.axion.definition.PublishedSchemaTest"
                        + " -Daxion.schema.update=true");
    }

    @Test
    @DisplayName("T-284 : le schéma publié porte les contraintes du validateur")
    void contraintesPubliees() {
        JsonObject document = JsonParser.parseString(SchemaExport.render()).getAsJsonObject();
        JsonObject properties = document.getAsJsonObject("properties");

        assertEquals(SchemaExport.DIALECT, document.get("$schema").getAsString());
        assertFalse(document.get("additionalProperties").getAsBoolean());
        assertEquals("[\"schema\",\"asset\",\"kind\"]", document.get("required").toString());
        assertEquals(1, properties.getAsJsonObject("schema").get("const").getAsInt());
        assertTrue(properties.getAsJsonObject("kind").get("enum").toString().contains("rigid_object"));

        JsonObject parts = properties.getAsJsonObject("parts");
        assertEquals(64, parts.get("maxItems").getAsInt());
        assertEquals("E-3050", parts.get("x-axion-code").getAsString());
        JsonObject mass = parts.getAsJsonObject("items").getAsJsonObject("properties")
                .getAsJsonObject("mass");
        assertEquals("0.001", mass.get("minimum").getAsString());

        JsonObject joint = properties.getAsJsonObject("joints").getAsJsonObject("items")
                .getAsJsonObject("properties");
        assertTrue(joint.getAsJsonObject("type").get("enum").toString().contains("revolute"));
        assertEquals("body", joint.getAsJsonObject("a").get("x-axion-refers").getAsString());
    }

    @Test
    @DisplayName("T-284 : l'expression des sources suit la grammaire du vérificateur")
    void expressionDesSources() {
        Pattern sources = Pattern.compile(SchemaExport.sourcePattern());
        // Mêmes cas que le vérificateur : ceux qu'il accepte, ceux qu'il refuse
        // pour une raison de grammaire (un nom non déclaré n'en est pas une).
        for (String acceptee : new String[] {"var.engine_power", "time.seconds",
                "attach.tow.length", "part.hood.integrity", "wheel.wheel_fl.spin_angle",
                "region.door_l.mean_disp", "particles.tow_rope.torn_ratio",
                "structure.connected_parts_ratio"}) {
            assertTrue(sources.matcher(acceptee).matches(), acceptee);
        }
        for (String refusee : new String[] {"moteur.rpm", "engine.torque", "wheel.health",
                "particles.x.health", "part..health", "time.seconds.extra"}) {
            assertFalse(sources.matcher(refusee).matches(), refusee);
        }
        assertTrue(Pattern.compile(SchemaExport.ASSET_PATTERN).matcher("mymod:models/pickup.glb")
                .matches());
        assertFalse(Pattern.compile(SchemaExport.ASSET_PATTERN)
                .matcher("mymod:axion/models/pickup.glb").matches());
    }

    @Test
    @DisplayName("T-284 : toutes les definitions du dépôt respectent le schéma 1")
    void definitionsDuDepot() throws IOException {
        Path depot = property("axion.repo.dir");
        List<String> refus = validate(find(depot));
        assertTrue(refus.isEmpty(), () -> String.join("\n", refus));
    }

    @Test
    @DisplayName("T-284 : le parcours du dépôt trouve et juge chaque definition")
    void parcoursDuDepot(@TempDir Path depot) throws IOException {
        Path ressources = depot.resolve("java/demo/src/main/resources");
        write(ressources.resolve("assets/demo/axion/models/caisse.obj"), "o caisse\n");
        write(ressources.resolve("data/demo/axion/definitions/caisse.json"),
                "{\"schema\": 1, \"asset\": \"demo:models/caisse.obj\", \"kind\": \"rigid_object\"}");
        write(ressources.resolve("data/demo/axion/definitions/cassee.json"),
                "{\"schema\": 1, \"asset\": \"demo:models/absente.obj\", \"kind\": \"rigid_object\"}");
        // Un build n'est pas une source : sa copie des ressources ne compte pas.
        write(depot.resolve("java/demo/build/resources/main/data/demo/axion/definitions/caisse.json"),
                "{}");

        List<Path> trouvees = find(depot);
        assertEquals(2, trouvees.size(), trouvees::toString);
        List<String> refus = validate(trouvees);
        assertEquals(1, refus.size(), refus::toString);
        assertTrue(refus.get(0).contains("cassee.json"), refus.get(0));
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    /** Toute ressource {@code data/<ns>/axion/definitions/**.json} hors des builds. */
    private static List<Path> find(Path depot) throws IOException {
        List<Path> found = new ArrayList<>();
        Files.walkFileTree(depot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
                return !dir.equals(depot) && SKIPPED.contains(dir.getFileName().toString())
                        ? FileVisitResult.SKIP_SUBTREE
                        : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (resourcesRoot(file) != null) {
                    found.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        found.sort(null);
        return found;
    }

    /**
     * {@return la racine de ressources d'une definition, ou {@code null}}
     *
     * <p>{@code <racine>/data/<ns>/axion/definitions/<chemin>.json}.
     */
    private static Path resourcesRoot(Path file) {
        String name = file.getFileName().toString();
        if (!name.endsWith(DefinitionRegistry.EXTENSION)) {
            return null;
        }
        for (Path dir = file.getParent(); dir != null && dir.getParent() != null;
                dir = dir.getParent()) {
            Path axion = dir.getParent();
            if (dir.getFileName().toString().equals("definitions")
                    && axion.getFileName().toString().equals("axion")
                    && axion.getParent() != null && axion.getParent().getParent() != null
                    && axion.getParent().getParent().getFileName().toString().equals("data")) {
                return axion.getParent().getParent().getParent();
            }
        }
        return null;
    }

    private static List<String> validate(List<Path> definitions) throws IOException {
        List<String> refus = new ArrayList<>();
        for (Path file : definitions) {
            Path racine = resourcesRoot(file);
            Path relatif = racine.resolve("data").relativize(file);
            String namespace = relatif.getName(0).toString();
            String chemin = relatif.subpath(1, relatif.getNameCount()).toString().replace('\\', '/');
            String location = namespace + ":" + chemin;

            // Un modèle existe s'il figure dans les ressources, côté client ou serveur.
            DefinitionRules rules = new DefinitionRules(true, asset -> {
                int colon = asset.indexOf(':');
                String ns = asset.substring(0, colon);
                String path = asset.substring(colon + 1);
                return Files.isRegularFile(racine.resolve("assets").resolve(ns).resolve(path))
                        || Files.isRegularFile(racine.resolve("data").resolve(ns).resolve(path));
            });
            try {
                DefinitionRegistry.read(location, Files.readAllBytes(file), rules);
            } catch (DefinitionException refusal) {
                refus.add(file + " — " + refusal.codeName() + " " + refusal.path() + " : "
                        + refusal.getMessage());
            }
        }
        return refus;
    }
}
