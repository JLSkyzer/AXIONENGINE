package dev.axion.architecture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-007 — INV-19 : audit statique du registre de budgets.
 *
 * <p>R-1850 : « tout sous-système possède un budget déclaré, une métrique de
 * consommation, une métrique de dépassement, un comportement en surcharge et un
 * fallback. L'absence de l'un des cinq est un défaut bloquant. »
 *
 * <p>Ce test tient les deux premiers maillons : la table 25.2 du cahier des
 * charges est <strong>la</strong> liste des budgets, et le registre natif doit
 * la porter entièrement. Les tests de {@code ax-model} vérifient ensuite que
 * chaque budget a une valeur configurable et ses deux métriques ; ils comparent
 * deux listes que nous écrivons toutes les deux, et ne verraient donc pas un
 * budget oublié des deux côtés. C'est ce que ce test-ci voit.
 *
 * <p>Les deux maillons restants — comportement en surcharge et fallback —
 * relèvent de la machine de dégradation SM-02 (PARTIE 25.6), qui arrive avec
 * C-77. Ils ne sont pas vérifiés ici, et le dire vaut mieux que le laisser
 * croire.
 */
class BudgetRegistryTest {

    /** Ligne de la table 25.2 : {@code | Libellé | `clé` | défaut | portée |}. */
    private static final Pattern TABLE_ROW =
            Pattern.compile("^\\|[^|]+\\|\\s*`([a-z0-9_.]+)`\\s*\\|[^|]+\\|[^|]+\\|\\s*$");

    /** Titre de la section qui porte la table. */
    private static final String SECTION = "## 25.2 Budgets par défaut";

    private static String spec() throws IOException {
        String path = System.getProperty("axion.spec.file");
        assertTrue(path != null && !path.isBlank(), "axion.spec.file non fourni par le build");
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }

    /** {@return les clés de configuration que la table 25.2 déclare} */
    private static Set<String> declaredBudgets(String spec) {
        int start = spec.indexOf(SECTION);
        assertTrue(start >= 0, () -> "section absente du cahier des charges : " + SECTION);
        int end = spec.indexOf("\n## ", start + 1);
        String section = end < 0 ? spec.substring(start) : spec.substring(start, end);

        Set<String> keys = new LinkedHashSet<>();
        for (String line : section.lines().toList()) {
            Matcher matcher = TABLE_ROW.matcher(line);
            if (matcher.matches()) {
                keys.add(matcher.group(1));
            }
        }
        assertFalse(keys.isEmpty(), "aucun budget lu dans la table 25.2");
        return keys;
    }

    private static Path registry() {
        return Path.of(System.getProperty("axion.crates.dir"))
                .resolve("ax-model/src/budgets.rs");
    }

    @Test
    @DisplayName("T-007 : tout budget de la table 25.2 figure au registre natif")
    void toutBudgetDeLaSpecFigureAuRegistre() throws IOException {
        Set<String> declared = declaredBudgets(spec());
        Path registry = registry();
        assertTrue(Files.isReadable(registry), () -> "registre introuvable : " + registry);
        String content = Files.readString(registry, StandardCharsets.UTF_8);

        List<String> missing = new ArrayList<>();
        for (String key : declared) {
            if (!content.contains("\"" + key + "\"")) {
                missing.add(key);
            }
        }

        assertTrue(
                missing.isEmpty(),
                () -> "INV-19 violé — budgets déclarés par la PARTIE 25.2 et absents du "
                        + "registre :\n  " + String.join("\n  ", missing));
    }

    @Test
    @DisplayName("T-007 : le registre natif n'invente aucun budget")
    void leRegistreNInventeAucunBudget() throws IOException {
        Set<String> declared = declaredBudgets(spec());
        String content = Files.readString(registry(), StandardCharsets.UTF_8);

        // Les chemins de configuration cités par le registre, tels qu'il les
        // écrit : `"budgets.quelque_chose"`.
        Matcher matcher = Pattern.compile("\"((?:budgets|net)\\.[a-z0-9_]+)\"").matcher(content);
        List<String> unexpected = new ArrayList<>();
        while (matcher.find()) {
            String key = matcher.group(1);
            if (!declared.contains(key)) {
                unexpected.add(key);
            }
        }

        assertTrue(
                unexpected.isEmpty(),
                () -> "budgets absents de la PARTIE 25.2 — un budget inventé n'a ni "
                        + "stratégie de dégradation ni fallback :\n  "
                        + String.join("\n  ", unexpected));
    }
}
