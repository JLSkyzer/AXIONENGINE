package dev.axion.config;

import dev.axion.config.ConfigSchema.DomainKind;
import dev.axion.config.ConfigSchema.Kind;
import dev.axion.config.ConfigSchema.Option;
import java.util.Optional;

/**
 * Validation d'une valeur de configuration contre le schéma (C-04).
 *
 * <p>Aucune valeur venant d'un fichier ou d'une propriété système n'est utilisée
 * sans passer par ici : l'interdiction 3.13 refuse de faire confiance à une
 * donnée d'origine externe, fichier de configuration compris.
 *
 * <p>Une valeur rejetée n'interrompt pas le démarrage. Le défaut est conservé et
 * le problème signalé : une faute de frappe dans un fichier ne doit pas rendre
 * le jeu injouable, mais elle ne doit pas non plus passer inaperçue.
 */
public final class ConfigValidation {

    private ConfigValidation() {
        throw new AssertionError("classe utilitaire, non instanciable");
    }

    /**
     * Convertit et valide une valeur brute pour une option.
     *
     * @param option option visée
     * @param raw valeur telle qu'elle a été lue, jamais {@code null}
     * @return la valeur convertie au type de l'option, ou vide si elle est
     *     refusée
     */
    public static Optional<Object> coerce(Option option, Object raw) {
        return switch (option.kind()) {
            case BOOLEAN -> asBoolean(raw);
            case INTEGER -> asLong(raw).filter(value -> inIntRange(option, value)).map(v -> (Object) v);
            case FLOAT -> asDouble(raw).filter(value -> inFloatDomain(option, value)).map(v -> (Object) v);
            case STRING -> asString(raw).filter(value -> inStringDomain(option, value)).map(v -> (Object) v);
        };
    }

    /**
     * Décrit pourquoi une valeur est refusée, pour le message de diagnostic.
     *
     * @param option option visée
     * @return la description du domaine admis
     */
    public static String describeDomain(Option option) {
        var domain = option.domain();
        return switch (domain.kind()) {
            case BOOLEAN -> "true | false";
            case INT_RANGE -> domain.max() == Double.MAX_VALUE
                    ? (long) domain.min() + ".."
                    : (long) domain.min() + ".." + (long) domain.max();
            case FLOAT_RANGE -> domain.max() == Double.MAX_VALUE
                    ? domain.min() + ".."
                    : domain.min() + ".." + domain.max();
            case FLOAT_SET, ENUMERATION -> String.join(" | ", domain.allowed());
            case KEYWORD_OR_COUNT -> String.join(" | ", domain.allowed()) + " | <entier>";
            case FREE_TEXT -> "texte libre";
        };
    }

    private static Optional<Object> asBoolean(Object raw) {
        if (raw instanceof Boolean value) {
            return Optional.of(value);
        }
        // Une propriété système arrive toujours sous forme de chaîne.
        if (raw instanceof String text) {
            if ("true".equalsIgnoreCase(text)) {
                return Optional.of(Boolean.TRUE);
            }
            if ("false".equalsIgnoreCase(text)) {
                return Optional.of(Boolean.FALSE);
            }
        }
        return Optional.empty();
    }

    private static Optional<Long> asLong(Object raw) {
        if (raw instanceof Long value) {
            return Optional.of(value);
        }
        if (raw instanceof Integer value) {
            return Optional.of(value.longValue());
        }
        // Un flottant n'est pas silencieusement tronqué : « 4.7 » là où un
        // entier est attendu est une erreur de saisie, pas une valeur de 4.
        if (raw instanceof String text) {
            try {
                return Optional.of(Long.parseLong(text.trim()));
            } catch (NumberFormatException ignored) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static Optional<Double> asDouble(Object raw) {
        if (raw instanceof Number value) {
            return Optional.of(value.doubleValue());
        }
        if (raw instanceof String text) {
            try {
                return Optional.of(Double.parseDouble(text.trim()));
            } catch (NumberFormatException ignored) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static Optional<String> asString(Object raw) {
        return raw instanceof String text ? Optional.of(text) : Optional.empty();
    }

    private static boolean inIntRange(Option option, long value) {
        var domain = option.domain();
        if (domain.kind() != DomainKind.INT_RANGE) {
            return false;
        }
        if (value < domain.min()) {
            return false;
        }
        return domain.max() == Double.MAX_VALUE || value <= domain.max();
    }

    private static boolean inFloatDomain(Option option, double value) {
        var domain = option.domain();
        // NaN et les infinis n'appartiennent à aucun domaine ; une comparaison
        // naïve laisserait passer NaN, qui est faux pour tout opérateur.
        if (!Double.isFinite(value)) {
            return false;
        }
        return switch (domain.kind()) {
            case FLOAT_RANGE -> value >= domain.min()
                    && (domain.max() == Double.MAX_VALUE || value <= domain.max());
            // Les valeurs admises sont comparées telles qu'elles sont écrites,
            // pour ne pas dépendre de l'arrondi d'une conversion.
            case FLOAT_SET -> domain.allowed().stream()
                    .anyMatch(allowed -> Double.parseDouble(allowed) == value);
            default -> false;
        };
    }

    private static boolean inStringDomain(Option option, String value) {
        var domain = option.domain();
        return switch (domain.kind()) {
            case ENUMERATION -> domain.allowed().contains(value);
            case KEYWORD_OR_COUNT -> domain.allowed().contains(value) || isPositiveInteger(value);
            case FREE_TEXT -> true;
            default -> false;
        };
    }

    private static boolean isPositiveInteger(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            if (!Character.isDigit(value.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    /**
     * {@return le type attendu par l'option, pour un message de diagnostic}
     *
     * @param kind type de l'option
     */
    public static String describeKind(Kind kind) {
        return switch (kind) {
            case BOOLEAN -> "booléen";
            case INTEGER -> "entier";
            case FLOAT -> "flottant";
            case STRING -> "chaîne";
        };
    }
}
