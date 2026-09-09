package dev.axion.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-014 — INV-06 : aucun système d'AXION ne référence RUSTFORGE-X hors de C-76.
 *
 * <p>Le contrat d'implémentation est net : AXION est indépendant, fonctionne
 * sans RUSTFORGE-X sans dégradation d'aucune sorte, et ne le déclare nulle part
 * comme dépendance. R-2330 précise que la seule mention admise hors documentation
 * est la chaîne {@code "rustforgex"} dans C-76 et dans les tests de coexistence.
 *
 * <p>Une dépendance de ce genre ne s'installe jamais d'un coup : elle commence
 * par une condition « si l'autre mod est là », puis par un appel direct. Ce test
 * arrête la pente à la première marche.
 */
class RustforgexIsolationTest {

    /** Nom recherché, en minuscules. */
    private static final String MARKER = "rustforgex";

    /**
     * Emplacements où la mention est légitime.
     *
     * <p>C-76 est le pont d'interopérabilité optionnel, réflexif et isolé. Le
     * registre de configuration porte les options {@code integration.rustforgex.*}
     * que l'ANNEXE A.3 déclare — les retirer serait diverger du cahier des
     * charges, pas gagner en indépendance.
     */
    private static final List<String> ALLOWED_PATHS = List.of(
            // C-76, le pont optionnel lui-même.
            "dev/axion/integration/rustforgex/",
            // Le registre de configuration et la classe qu'il engendre : les
            // options integration.rustforgex.* sont déclarées par l'ANNEXE A.3.
            "ax-model/src/config/",
            "dev/axion/config/ConfigSchema.java");

    private static List<Path> sourcesUnder(String property, String extension) throws IOException {
        String dir = System.getProperty(property);
        assertTrue(dir != null && !dir.isBlank(), property + " non fourni par le build");
        Path root = Path.of(dir);
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(extension))
                    .toList();
        }
    }

    private static List<String> scan(List<Path> sources, Path root) throws IOException {
        List<String> hits = new ArrayList<>();
        for (Path source : sources) {
            String relative = root.relativize(source).toString().replace('\\', '/');
            if (ALLOWED_PATHS.stream().anyMatch(relative::contains)) {
                continue;
            }
            List<String> lines = Files.readAllLines(source, StandardCharsets.UTF_8);
            for (int index = 0; index < lines.size(); index++) {
                if (lines.get(index).toLowerCase(Locale.ROOT).contains(MARKER)) {
                    hits.add(relative + ":" + (index + 1));
                }
            }
        }
        return hits;
    }

    @Test
    @DisplayName("T-014 : aucune mention de RUSTFORGE-X côté Java, hors C-76")
    void aucuneMentionEnJava() throws IOException {
        Path root = Path.of(System.getProperty("axion.source.dir"));
        List<String> hits = scan(sourcesUnder("axion.source.dir", ".java"), root);

        assertTrue(
                hits.isEmpty(),
                () -> "INV-06 violé — AXION ne référence RUSTFORGE-X que dans C-76 :\n  "
                        + String.join("\n  ", hits));
    }

    @Test
    @DisplayName("T-014 : aucune mention de RUSTFORGE-X côté natif, hors registre de configuration")
    void aucuneMentionEnRust() throws IOException {
        Path root = Path.of(System.getProperty("axion.crates.dir"));
        List<String> hits = scan(sourcesUnder("axion.crates.dir", ".rs"), root);

        assertTrue(
                hits.isEmpty(),
                () -> "INV-06 violé côté natif :\n  " + String.join("\n  ", hits));
    }

    @Test
    @DisplayName("R-2330 : aucun artefact de RUSTFORGE-X n'est declaré comme dépendance")
    void aucuneDependanceDeclaree() throws IOException {
        // mods.toml ne doit le déclarer ni comme dépendance obligatoire, ni
        // comme dépendance optionnelle : le contrat d'implémentation l'exclut
        // des deux (point 6).
        Path modsToml = Path.of(System.getProperty("axion.source.dir"))
                .getParent()
                .resolve("resources/META-INF/mods.toml");
        assertTrue(Files.isReadable(modsToml), () -> "mods.toml introuvable : " + modsToml);

        String content = Files.readString(modsToml, StandardCharsets.UTF_8)
                .toLowerCase(Locale.ROOT);
        // La mention en commentaire est volontaire et explique la règle ; seule
        // une déclaration de dépendance serait une violation.
        assertTrue(
                !content.contains("modid = \"rustforgex\""),
                "mods.toml déclare RUSTFORGE-X comme dépendance");
    }
}
