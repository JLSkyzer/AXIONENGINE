package dev.axion.api.assembly;

import dev.axion.api.Experimental;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Un modificateur : ce qu'une cause fait à un paramètre d'une instance (ADR-167 D3).
 *
 * <p>La valeur effective d'un paramètre compose sa base avec les modificateurs de toutes ses causes, comme les
 * attributs de Minecraft :
 *
 * <pre>{@code valeur = (base + Σ add) × (1 + Σ multiply_base) × Π (1 + multiply_total)}</pre>
 *
 * <p>Chaque somme et chaque produit se calculent dans l'ordre des identifiants de cause : le résultat est le même au
 * bit près partout où il se calcule. Une cause ne porte qu'un modificateur par paramètre ; en reposer un le remplace.
 *
 * @param cause qui modifie : un identifiant {@code <espace de noms>:<chemin>}, celui du mod qui le pose
 *     ({@code upgrades:stage2}) ; l'espace de noms {@code axion} est réservé au moteur
 * @param parameter le paramètre modifié, par exemple {@link Parameters#ENGINE_TORQUE}
 * @param operation l'opération
 * @param amount la quantité, finie : ajoutée pour {@link Operation#ADD}, un taux pour les deux autres
 *     ({@code 0.25} : +25 %)
 */
@Experimental
public record Modifier(String cause, String parameter, Operation operation, double amount) {

    /** La forme d'un identifiant de cause. */
    private static final Pattern CAUSE = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");

    /**
     * Valide le modificateur.
     *
     * @throws IllegalArgumentException si la cause n'est pas un identifiant à espace de noms, ou si la quantité
     *     n'est pas finie
     * @throws NullPointerException si un champ est nul
     */
    public Modifier {
        Objects.requireNonNull(cause, "cause");
        Objects.requireNonNull(parameter, "parameter");
        Objects.requireNonNull(operation, "operation");
        if (!CAUSE.matcher(cause).matches()) {
            throw new IllegalArgumentException(
                    "cause « " + cause + " » : un identifiant <espace de noms>:<chemin> est attendu");
        }
        if (!Double.isFinite(amount)) {
            throw new IllegalArgumentException("cause « " + cause + " » : quantité non finie");
        }
    }

    /** L'opération d'un modificateur, comme celles des attributs de Minecraft. */
    public enum Operation {
        /** Ajoutée à la base. */
        ADD,
        /** Un taux appliqué à la base augmentée des ajouts ; les taux de toutes les causes s'additionnent. */
        MULTIPLY_BASE,
        /** Un taux appliqué au résultat ; chaque cause multiplie par {@code 1 + quantité}. */
        MULTIPLY_TOTAL;

        /** {@return le nom de l'opération dans une definition et une commande} */
        public String key() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }
}
