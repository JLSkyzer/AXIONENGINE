package dev.axion.content;

import dev.axion.api.ApiVersion;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * La version de l'API d'AXION qu'un addon exige (ADR-166 D1, ADR-162 D11).
 *
 * <p>Un addon la déclare dans son {@code mods.toml}, sans code :
 *
 * <pre>{@code
 * [modproperties.example_addon]
 *     axion_api = "1.0"
 * }</pre>
 *
 * <p>Logique pure, sans Forge. La couche Forge lit cette propriété pour chaque mod chargé, à la construction d'AXION ;
 * un addon que l'API présente ne satisfait pas — ou dont la valeur est mal écrite — reçoit un avertissement de
 * chargement, et son contenu n'est pas enregistré : son fournisseur n'est pas appelé. Le jeu démarre (ADR-166 Q1).
 */
public final class ApiRequirements {

    /** Le nom de la propriété, dans {@code [modproperties.<modId>]}. */
    public static final String PROPERTY = "axion_api";

    /** {@code MAJEUR.MINEUR} ou {@code MAJEUR.MINEUR.CORRECTIF}. */
    private static final Pattern FORMAT = Pattern.compile("(\\d{1,5})\\.(\\d{1,5})(?:\\.(\\d{1,5}))?");

    private static volatile Set<String> refused = Set.of();

    private ApiRequirements() {
        throw new AssertionError("classe utilitaire, non instanciable");
    }

    /**
     * Un addon dont l'exigence n'est pas satisfaite.
     *
     * @param modId l'addon
     * @param declared ce qu'il a écrit, tel quel
     * @param required la version exigée, absente si la valeur est mal écrite
     * @param present la version de l'API présente
     */
    public record Refusal(String modId, String declared, Optional<ApiVersion> required, ApiVersion present) {

        /** {@return le message du journal, qui dit quoi faire} */
        public String describe() {
            return required
                    .map(version -> modId + " exige l'API AXION " + version + ", AXION fournit " + present
                            + " : son contenu n'est pas chargé ; mettre AXION à jour")
                    .orElse(modId + " : « " + PROPERTY + " = " + declared + " » n'est pas une version d'API (attendu "
                            + "\"1.0\" ou \"1.0.0\", entre guillemets) : son contenu n'est pas chargé");
        }
    }

    /**
     * {@return la version qu'une valeur exige, ou vide si elle est mal écrite} Seule une chaîne est admise : un nombre
     * TOML ({@code axion_api = 1.10}) perdrait son zéro final et dirait 1.1.
     *
     * @param value la valeur lue dans {@code mods.toml}
     */
    public static Optional<ApiVersion> parse(Object value) {
        if (!(value instanceof String text)) {
            return Optional.empty();
        }
        Matcher match = FORMAT.matcher(text.trim());
        if (!match.matches()) {
            return Optional.empty();
        }
        int patch = match.group(3) == null ? 0 : Integer.parseInt(match.group(3));
        return Optional.of(new ApiVersion(Integer.parseInt(match.group(1)), Integer.parseInt(match.group(2)), patch));
    }

    /**
     * {@return les addons dont l'exigence n'est pas satisfaite, triés par modId}
     *
     * @param declared pour chaque mod qui déclare la propriété, sa valeur
     * @param present la version de l'API présente
     */
    public static List<Refusal> check(Map<String, Object> declared, ApiVersion present) {
        List<Refusal> refusals = new ArrayList<>();
        new TreeMap<>(declared).forEach((modId, value) -> {
            Optional<ApiVersion> required = parse(value);
            if (required.isEmpty() || !present.satisfies(required.get())) {
                refusals.add(new Refusal(modId, String.valueOf(value), required, present));
            }
        });
        return refusals;
    }

    /**
     * Retient les addons refusés, que l'enregistrement du contenu écarte.
     *
     * @param refusals les refus de {@link #check}
     */
    public static void publish(Collection<Refusal> refusals) {
        refused = refusals.stream().map(Refusal::modId).collect(Collectors.toUnmodifiableSet());
    }

    /**
     * {@return vrai si cet addon a été refusé pour sa version d'API}
     *
     * @param modId l'addon
     */
    public static boolean isRefused(String modId) {
        return refused.contains(modId);
    }
}
