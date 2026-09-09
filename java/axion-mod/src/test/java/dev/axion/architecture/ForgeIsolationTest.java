package dev.axion.architecture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-020 — R-401 : seul {@code dev.axion.forge} connaît Forge.
 *
 * <p>Cette règle est la condition de tout le reste. Si une classe du moteur
 * importait {@code net.minecraftforge}, elle deviendrait intestable sans
 * démarrer le jeu, et le portage vers une autre plateforme cesserait d'être
 * envisageable. La violation ne casse rien à la compilation : sans ce test,
 * elle passerait inaperçue jusqu'à ce qu'il soit trop tard pour la corriger à
 * bon compte.
 *
 * <p>La vérification porte sur les sources plutôt que sur le bytecode : un
 * import est ce qu'un relecteur voit, et le message d'échec peut alors nommer le
 * fichier et la ligne.
 */
class ForgeIsolationTest {

    /** Seul paquet autorisé à importer Forge. */
    private static final String ALLOWED_PACKAGE_PATH = "dev/axion/forge/";

    private static final String FORBIDDEN_IMPORT = "import net.minecraftforge";

    private static Path sourceRoot() {
        String dir = System.getProperty("axion.source.dir");
        assertTrue(dir != null && !dir.isBlank(), "axion.source.dir non fourni par le build");
        Path root = Path.of(dir);
        assertTrue(Files.isDirectory(root), () -> "répertoire de sources introuvable : " + root);
        return root;
    }

    private static List<Path> javaSources(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .toList();
        }
    }

    @Test
    @DisplayName("T-020 : aucune classe hors de dev.axion.forge n'importe net.minecraftforge")
    void forgeResteConfineDansSonPaquet() throws IOException {
        Path root = sourceRoot();
        List<String> violations = new ArrayList<>();
        int inspected = 0;

        for (Path source : javaSources(root)) {
            inspected++;
            String relative = root.relativize(source).toString().replace('\\', '/');
            if (relative.startsWith(ALLOWED_PACKAGE_PATH)) {
                continue;
            }
            List<String> lines = Files.readAllLines(source, StandardCharsets.UTF_8);
            for (int index = 0; index < lines.size(); index++) {
                if (lines.get(index).stripLeading().startsWith(FORBIDDEN_IMPORT)) {
                    violations.add(relative + ":" + (index + 1) + " — " + lines.get(index).strip());
                }
            }
        }

        // Un test qui n'inspecterait rien passerait sans rien garantir.
        assertTrue(inspected > 5, "trop peu de sources inspectées : " + inspected);
        assertTrue(
                violations.isEmpty(),
                () -> "R-401 violé sur " + violations.size() + " ligne(s) :\n  "
                        + String.join("\n  ", violations));
    }

    @Test
    @DisplayName("Le paquet dev.axion.forge existe et importe bien Forge")
    void lePaquetForgeExisteEtEstLeSeulConcerne() throws IOException {
        Path root = sourceRoot();
        Path forge = root.resolve(ALLOWED_PACKAGE_PATH);
        assertTrue(Files.isDirectory(forge), "dev.axion.forge absent");

        // Contrepartie du test précédent : si Forge n'était plus importé nulle
        // part, l'isolation serait triviale et le premier test ne prouverait
        // plus rien.
        boolean importsForge = false;
        for (Path source : javaSources(forge)) {
            String content = Files.readString(source, StandardCharsets.UTF_8);
            if (content.contains(FORBIDDEN_IMPORT)) {
                importsForge = true;
                break;
            }
        }
        assertTrue(importsForge, "aucune classe de dev.axion.forge n'importe Forge");
    }

    @Test
    @DisplayName("Le point d'entrée @Mod vit dans dev.axion.forge, pas dans dev.axion")
    void pointDEntreeDansLePaquetForge() throws IOException {
        Path root = sourceRoot();

        // ADR-100 : l'annotation @Mod vient de net.minecraftforge, elle ne peut
        // donc pas être portée par une classe de dev.axion sans violer R-401.
        // On cherche l'annotation elle-même, en tête de ligne : la Javadoc de
        // ces classes mentionne @Mod pour l'expliquer, et confondre les deux
        // ferait échouer le test sur son propre commentaire.
        assertFalse(
                carriesModAnnotation(root.resolve("dev/axion/AxionMod.java")),
                "AxionMod ne doit pas porter @Mod");
        assertTrue(
                carriesModAnnotation(root.resolve("dev/axion/forge/AxionForgeEntrypoint.java")),
                "le point d'entrée a perdu son annotation");
    }

    /** Indique si le fichier porte l'annotation {@code @Mod} sur une déclaration. */
    private static boolean carriesModAnnotation(Path source) throws IOException {
        for (String line : Files.readAllLines(source, StandardCharsets.UTF_8)) {
            if (line.stripLeading().startsWith("@Mod(")) {
                return true;
            }
        }
        return false;
    }
}
