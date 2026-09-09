package dev.axion.config;

import dev.axion.config.ConfigSchema.Option;
import dev.axion.config.ConfigSchema.Scope;
import java.util.List;
import java.util.Map;

/**
 * Configuration résolue d'une portée (C-04).
 *
 * <p>Toute option déclarée au schéma est présente : une valeur lue et validée,
 * ou le défaut. Une lecture ne peut donc jamais échouer faute de valeur, ce qui
 * évite d'avoir à traiter l'absence à chaque appel.
 *
 * <p>Les valeurs refusées ne sont pas silencieusement écartées : elles figurent
 * dans {@link #issues()}, que le bootstrap journalise et que
 * {@code /axion status} affiche.
 *
 * @param scope portée dont cette configuration est issue
 * @param values valeur retenue pour chaque chemin d'option
 * @param issues problèmes rencontrés pendant le chargement
 */
public record AxionConfig(Scope scope, Map<String, Object> values, List<ConfigIssue> issues) {

    /** Un problème rencontré au chargement, conservé pour le diagnostic.
     *
     * @param path chemin de l'option concernée
     * @param message description du problème, destinée au journal
     */
    public record ConfigIssue(String path, String message) {}

    /**
     * Construit une configuration en figeant les collections reçues.
     *
     * @param scope portée
     * @param values valeurs résolues
     * @param issues problèmes rencontrés
     */
    public AxionConfig {
        values = Map.copyOf(values);
        issues = List.copyOf(issues);
    }

    /**
     * {@return la valeur booléenne de l'option}
     *
     * @param path chemin de l'option
     * @throws IllegalArgumentException si le chemin n'est pas déclaré, ou ne
     *     désigne pas un booléen — c'est une erreur de programmation, pas une
     *     erreur de configuration
     */
    public boolean getBoolean(String path) {
        return value(path, Boolean.class);
    }

    /**
     * {@return la valeur entière de l'option}
     *
     * @param path chemin de l'option
     * @throws IllegalArgumentException si le chemin n'est pas déclaré ou ne
     *     désigne pas un entier
     */
    public long getInt(String path) {
        return value(path, Long.class);
    }

    /**
     * {@return la valeur flottante de l'option}
     *
     * @param path chemin de l'option
     * @throws IllegalArgumentException si le chemin n'est pas déclaré ou ne
     *     désigne pas un flottant
     */
    public double getFloat(String path) {
        return value(path, Double.class);
    }

    /**
     * {@return la valeur textuelle de l'option}
     *
     * @param path chemin de l'option
     * @throws IllegalArgumentException si le chemin n'est pas déclaré ou ne
     *     désigne pas une chaîne
     */
    public String getString(String path) {
        return value(path, String.class);
    }

    private <T> T value(String path, Class<T> type) {
        Object value = values.get(path);
        if (value == null) {
            throw new IllegalArgumentException(
                    "option non déclarée dans " + scope.fileName() + " : " + path);
        }
        if (!type.isInstance(value)) {
            throw new IllegalArgumentException(path
                    + " est un " + value.getClass().getSimpleName()
                    + ", pas un " + type.getSimpleName());
        }
        return type.cast(value);
    }

    /**
     * {@return l'option du schéma portant ce chemin}
     *
     * @param scope portée à interroger
     * @param path chemin recherché
     */
    public static Option option(Scope scope, String path) {
        return scope.options().stream()
                .filter(option -> option.path().equals(path))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "option non déclarée dans " + scope.fileName() + " : " + path));
    }
}
