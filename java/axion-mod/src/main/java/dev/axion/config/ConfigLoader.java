package dev.axion.config;

import com.electronwill.nightconfig.core.file.FileConfig;
import dev.axion.config.AxionConfig.ConfigIssue;
import dev.axion.config.ConfigSchema.Option;
import dev.axion.config.ConfigSchema.Scope;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/**
 * Chargement de la configuration d'AXION (C-04).
 *
 * <p>La chaîne suit celle du cahier des charges, dans cet ordre :
 *
 * <pre>
 * défauts compilés (ConfigSchema, généré depuis ax-model)
 *   -&gt; &lt;configDir&gt;/axion-*.toml
 *   -&gt; propriétés -Daxion.&lt;chemin&gt;=&lt;valeur&gt;
 *   -&gt; validation (type, plage, défaut)
 * </pre>
 *
 * <p>Chaque étage ne peut que remplacer une valeur déjà valide par une autre
 * valeur valide. Une entrée refusée laisse en place celle de l'étage précédent
 * et produit un {@link ConfigIssue} : le mod démarre toujours, avec une
 * configuration cohérente, et le problème reste visible.
 *
 * <p>Exigences : R-430, R-431.
 */
public final class ConfigLoader {

    /** Racine des fichiers de référence embarqués dans le JAR. */
    private static final String RESOURCE_ROOT = "/axion/config/";

    private ConfigLoader() {
        throw new AssertionError("classe utilitaire, non instanciable");
    }

    /**
     * Charge la configuration d'une portée.
     *
     * @param scope portée à charger
     * @param configDir répertoire de configuration du jeu
     * @param systemProperties propriétés système, source des surcharges
     *     {@code -Daxion.*}
     * @return la configuration résolue, jamais {@code null}
     */
    public static AxionConfig load(Scope scope, Path configDir, Properties systemProperties) {
        Map<String, Object> values = new LinkedHashMap<>();
        List<ConfigIssue> issues = new ArrayList<>();

        for (Option option : scope.options()) {
            values.put(option.path(), option.defaultValue());
        }

        applyFile(scope, configDir, values, issues);
        applySystemProperties(scope, systemProperties, values, issues);

        return new AxionConfig(scope, values, issues);
    }

    /**
     * Écrit le fichier de référence de la portée s'il n'existe pas encore.
     *
     * <p>Le contenu est la ressource générée depuis {@code ax-model} : il porte
     * les mêmes défauts que {@link ConfigSchema}, avec le domaine de chaque
     * option en commentaire.
     *
     * @param scope portée concernée
     * @param configDir répertoire de configuration du jeu
     * @return {@code true} si le fichier a été créé
     * @throws IOException si le répertoire ou le fichier ne peut pas être écrit
     */
    public static boolean writeReferenceIfAbsent(Scope scope, Path configDir) throws IOException {
        Path target = configDir.resolve(scope.fileName());
        if (Files.exists(target)) {
            return false;
        }
        Files.createDirectories(configDir);
        try (InputStream source =
                ConfigLoader.class.getResourceAsStream(RESOURCE_ROOT + scope.fileName())) {
            if (source == null) {
                throw new IOException("ressource de configuration absente du JAR : "
                        + RESOURCE_ROOT + scope.fileName());
            }
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return true;
    }

    private static void applyFile(
            Scope scope, Path configDir, Map<String, Object> values, List<ConfigIssue> issues) {
        Path path = configDir.resolve(scope.fileName());
        if (!Files.isReadable(path)) {
            // Absence de fichier : les défauts font foi. Ce n'est pas un
            // problème, c'est le premier démarrage.
            return;
        }

        try (FileConfig file = FileConfig.of(path)) {
            file.load();
            for (Option option : scope.options()) {
                Object raw = file.get(option.path());
                if (raw == null) {
                    // Une option absente du fichier garde son défaut : un
                    // fichier partiel reste valide, et une option ajoutée par
                    // une nouvelle version ne casse pas une installation.
                    continue;
                }
                accept(option, raw, "fichier " + scope.fileName(), values, issues);
            }
        } catch (RuntimeException failure) {
            // Un fichier illisible ou syntaxiquement invalide ne doit pas
            // empêcher le jeu de démarrer : les défauts prennent le relais.
            issues.add(new ConfigIssue(
                    scope.fileName(),
                    "fichier illisible, valeurs par défaut conservées : " + failure));
        }
    }

    private static void applySystemProperties(
            Scope scope,
            Properties systemProperties,
            Map<String, Object> values,
            List<ConfigIssue> issues) {
        for (Option option : scope.options()) {
            String raw = systemProperties.getProperty(option.systemProperty());
            if (raw == null) {
                continue;
            }
            accept(option, raw, "propriété -D" + option.systemProperty(), values, issues);
        }
    }

    private static void accept(
            Option option,
            Object raw,
            String origin,
            Map<String, Object> values,
            List<ConfigIssue> issues) {
        Optional<Object> coerced = ConfigValidation.coerce(option, raw);
        if (coerced.isPresent()) {
            values.put(option.path(), coerced.get());
            return;
        }
        issues.add(new ConfigIssue(
                option.path(),
                origin + " : « " + raw + " » refusé, "
                        + ConfigValidation.describeKind(option.kind())
                        + " attendu dans " + ConfigValidation.describeDomain(option)
                        + " ; valeur précédente conservée"));
    }
}
