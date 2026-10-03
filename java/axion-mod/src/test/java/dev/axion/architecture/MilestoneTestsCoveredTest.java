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
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * R-2392 — les critères d'acceptation d'un jalon terminé restent vérifiés.
 *
 * <p>La PARTIE 37.2 du cahier des charges nomme, pour chaque jalon, les tests
 * qui l'acceptent. Ce test lit cette liste dans le CDC et vérifie que chaque
 * identifiant est effectivement porté par un test exécuté. Un jalon clos ne
 * peut donc pas se vider en silence : supprimer ou renommer un test de M0 casse
 * le build, ce qu'exige R-2391 — chaque jalon préserve intégralement les
 * précédents.
 *
 * <p>Le CDC reste la source : la liste n'est pas recopiée ici, elle en est
 * extraite. Une modification du cahier des charges se répercute donc d'elle-même.
 *
 * <p>Un test porte son identifiant de deux façons, toutes deux admises : écrit
 * {@code T-231} dans un fichier de test, ou en préfixe du nom de la fonction de
 * test, {@code fn t231_…} — la convention des tests Rust, que ce contrôle ignorait
 * avant la clôture de M1, dont tous les identifiants sont portés ainsi.
 */
class MilestoneTestsCoveredTest {

    /**
     * Jalons dont l'acceptance est prononcée.
     *
     * <p>Une ligne s'ajoute ici quand un jalon est clos, et jamais avant : le
     * test deviendrait rouge pour des tests qui restent à écrire, et un test
     * rouge en permanence ne dit plus rien.
     */
    private static final List<String> COMPLETED_MILESTONES = List.of("M0");

    /**
     * {@return vrai si une source de test porte cet identifiant : écrit tel quel, ou en
     * préfixe d'une fonction — {@code fn t231_…(} en Rust, {@code void t231_…(} en Java}
     *
     * <p>Une fonction, pas une simple mention : le préfixe doit nommer ce que déclare
     * {@code fn} ou {@code void}, et être suivi de ses paramètres.
     *
     * @param content contenu d'un fichier de test
     * @param identifier identifiant, {@code T-231}
     */
    static boolean carries(String content, String identifier) {
        if (content.contains(identifier)) {
            return true;
        }
        String prefix = "t" + identifier.substring(2) + "_";
        return Pattern.compile("\\b(?:fn|void)\\s+" + prefix + "\\w*\\s*\\(").matcher(content).find();
    }

    /** Identifiant seul, ou plage {@code T-100..T-103}. */
    private static final Pattern IDENTIFIER =
            Pattern.compile("T-(\\d{3})(?:\\.\\.T-(\\d{3}))?");

    /**
     * Extrait du CDC les identifiants de test que le jalon déclare.
     *
     * @param spec contenu du cahier des charges
     * @param milestone nom du jalon, par exemple {@code M0}
     * @return les identifiants, plages développées
     */
    private static Set<String> declaredTests(String spec, String milestone) {
        int heading = spec.indexOf("### " + milestone + " ");
        assertTrue(heading >= 0, () -> "jalon " + milestone + " absent de la PARTIE 37.2");

        // Le bloc du jalon s'arrête au jalon suivant : ce qui est entre les
        // deux lui appartient.
        int next = spec.indexOf("\n### ", heading + 1);
        String block = next < 0 ? spec.substring(heading) : spec.substring(heading, next);

        int tests = block.indexOf("Tests :");
        assertTrue(tests >= 0, () -> "le jalon " + milestone + " ne déclare aucun test");
        // La liste peut tenir sur plusieurs lignes ; elle s'arrête à la fin du
        // bloc de code qui la contient.
        int end = block.indexOf("```", tests);
        String list = block.substring(tests, end < 0 ? block.length() : end);

        Set<String> identifiers = new LinkedHashSet<>();
        Matcher matcher = IDENTIFIER.matcher(list);
        while (matcher.find()) {
            int from = Integer.parseInt(matcher.group(1));
            int to = matcher.group(2) == null ? from : Integer.parseInt(matcher.group(2));
            for (int number = from; number <= to; number++) {
                identifiers.add(String.format("T-%03d", number));
            }
        }
        assertFalse(identifiers.isEmpty(), () -> "aucun identifiant lu pour " + milestone);
        return identifiers;
    }

    /**
     * Rend le contenu des fichiers qui portent réellement des tests.
     *
     * <p>Un identifiant cité dans un commentaire de code de production ne
     * prouve rien : seul compte celui que porte un fichier de test.
     */
    private static List<String> testSources() throws IOException {
        List<Path> roots = List.of(
                Path.of(System.getProperty("axion.test.dir")),
                Path.of(System.getProperty("axion.crates.dir")));
        List<String> contents = new ArrayList<>();
        for (Path root : roots) {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    String name = file.getFileName().toString();
                    if (!name.endsWith(".java") && !name.endsWith(".rs")) {
                        continue;
                    }
                    String content = Files.readString(file, StandardCharsets.UTF_8);
                    if (content.contains("@Test") || content.contains("#[test]")) {
                        contents.add(content);
                    }
                }
            }
        }
        return contents;
    }

    @Test
    @DisplayName("R-2392 : tout test déclaré par un jalon terminé existe")
    void testsDesJalonsTerminesPresents() throws IOException {
        String spec = Files.readString(
                Path.of(System.getProperty("axion.spec.file")), StandardCharsets.UTF_8);
        List<String> sources = testSources();

        List<String> missing = new ArrayList<>();
        for (String milestone : COMPLETED_MILESTONES) {
            for (String identifier : declaredTests(spec, milestone)) {
                if (sources.stream().noneMatch(content -> carries(content, identifier))) {
                    missing.add(milestone + " : " + identifier);
                }
            }
        }

        assertTrue(
                missing.isEmpty(),
                () -> "R-2391 violé — des tests d'un jalon clos ont disparu :\n  "
                        + String.join("\n  ", missing));
    }

    @Test
    @DisplayName("Un identifiant compte écrit tel quel ou en nom de fonction de test, pas en simple mention")
    void unIdentifiantSeLitEnToutesLettresOuEnNomDeTest() {
        assertTrue(carries("//! T-231 — structure", "T-231"));
        assertTrue(carries("#[test]\n    fn t231_un_parent_inexistant_est_refuse() {", "T-231"));
        assertTrue(carries("@Test\n    void t231_unCas() {", "T-231"));
        assertFalse(carries("// voir t231_un_cas, ailleurs", "T-231"), "une mention n'est pas un test");
        assertFalse(carries("fn t2310_un_autre_test() {", "T-231"), "T-2310 n'est pas T-231");
        assertFalse(carries("fn xt231_un_cas() {", "T-231"), "le préfixe ouvre le nom");
        assertFalse(carries("let t231_valeur = 3;", "T-231"), "une variable n'est pas une fonction de test");
        assertFalse(carries("let x = t231_aide();", "T-231"), "un appel n'est pas une déclaration");
    }
}
