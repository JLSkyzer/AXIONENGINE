package dev.axion.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-023 — R-443 : tout code {@code E-xxxx} est documenté en ANNEXE A.1.
 *
 * <p>Un code d'erreur qui apparaît dans un message sans figurer dans l'annexe
 * est inutilisable : l'utilisateur qui le recherche ne trouve rien, et le
 * support n'a rien à lui dire. Inversement, inventer un code en cours de route
 * reviendrait à étendre l'annexe sans y toucher — ce que l'obligation 4.8
 * interdit.
 *
 * <p>Le test cherche les codes tels qu'ils sont écrits dans les commentaires et
 * les messages, des deux côtés de la frontière, et les confronte à l'annexe du
 * cahier des charges.
 */
class ErrorCodesDocumentedTest {

    private static final Pattern ERROR_CODE = Pattern.compile("E-(\\d{4})");

    private static Path spec() {
        String path = System.getProperty("axion.spec.file");
        assertTrue(path != null && !path.isBlank(), "axion.spec.file non fourni par le build");
        Path spec = Path.of(path);
        assertTrue(Files.isReadable(spec), () -> "cahier des charges introuvable : " + spec);
        return spec;
    }

    /** Codes déclarés dans l'ANNEXE A.1, qui fait autorité. */
    private static Set<String> documentedCodes() throws IOException {
        String content = Files.readString(spec(), StandardCharsets.UTF_8);
        int start = content.indexOf("## ANNEXE A.1");
        assertTrue(start >= 0, "ANNEXE A.1 introuvable dans le cahier des charges");
        int end = content.indexOf("\n## ANNEXE A.2", start);
        String annex = end > start ? content.substring(start, end) : content.substring(start);

        Set<String> codes = new TreeSet<>();
        Matcher matcher = ERROR_CODE.matcher(annex);
        while (matcher.find()) {
            codes.add(matcher.group());
        }
        return codes;
    }

    private static Map<String, String> usedCodes(String property, String extension)
            throws IOException {
        String dir = System.getProperty(property);
        assertTrue(dir != null && !dir.isBlank(), property + " non fourni par le build");
        Path root = Path.of(dir);

        Map<String, String> found = new LinkedHashMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path source : files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(extension))
                    .toList()) {
                List<String> lines = Files.readAllLines(source, StandardCharsets.UTF_8);
                for (int index = 0; index < lines.size(); index++) {
                    Matcher matcher = ERROR_CODE.matcher(lines.get(index));
                    while (matcher.find()) {
                        found.putIfAbsent(
                                matcher.group(),
                                root.relativize(source) + ":" + (index + 1));
                    }
                }
            }
        }
        return found;
    }

    @Test
    @DisplayName("T-023 : tout code d'erreur cité côté Java figure dans l'ANNEXE A.1")
    void codesJavaDocumentes() throws IOException {
        Set<String> documented = documentedCodes();
        assertTrue(documented.size() > 20, "annexe trop courte : " + documented.size());

        Map<String, String> used = usedCodes("axion.source.dir", ".java");
        assertTrue(!used.isEmpty(), "aucun code d'erreur trouvé dans les sources");

        List<String> undocumented = used.entrySet().stream()
                .filter(entry -> !documented.contains(entry.getKey()))
                .map(entry -> entry.getKey() + " (" + entry.getValue() + ")")
                .toList();

        assertTrue(
                undocumented.isEmpty(),
                () -> "R-443 violé — codes absents de l'ANNEXE A.1 :\n  "
                        + String.join("\n  ", undocumented));
    }

    @Test
    @DisplayName("T-023 : tout code d'erreur cité côté natif figure dans l'ANNEXE A.1")
    void codesRustDocumentes() throws IOException {
        Set<String> documented = documentedCodes();
        Map<String, String> used = usedCodes("axion.crates.dir", ".rs");
        assertTrue(!used.isEmpty(), "aucun code d'erreur trouvé dans les crates");

        List<String> undocumented = used.entrySet().stream()
                .filter(entry -> !documented.contains(entry.getKey()))
                .map(entry -> entry.getKey() + " (" + entry.getValue() + ")")
                .toList();

        assertTrue(
                undocumented.isEmpty(),
                () -> "R-443 violé côté natif :\n  " + String.join("\n  ", undocumented));
    }

    @Test
    @DisplayName("Les codes employés le sont avec leur valeur numérique négative")
    void codesNumeriquesCoherents() throws IOException {
        // DM-19 range les codes par domaine, en négatif : E-1004 vaut -1004.
        // Un code cité dans un commentaire mais dont la constante porterait une
        // autre valeur serait pire que pas de code du tout.
        Path root = Path.of(System.getProperty("axion.source.dir"));
        try (Stream<Path> files = Files.walk(root)) {
            for (Path source : files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .toList()) {
                for (String line : Files.readAllLines(source, StandardCharsets.UTF_8)) {
                    Matcher declaration = Pattern
                            .compile("int\\s+E_\\w+\\s*=\\s*(-\\d{4})")
                            .matcher(line);
                    while (declaration.find()) {
                        String value = declaration.group(1);
                        String expected = "E-" + value.substring(1);
                        assertTrue(
                                Files.readString(source, StandardCharsets.UTF_8).contains(expected),
                                () -> source.getFileName() + " : la constante " + value
                                        + " ne cite pas " + expected);
                    }
                }
            }
        }
    }
}
