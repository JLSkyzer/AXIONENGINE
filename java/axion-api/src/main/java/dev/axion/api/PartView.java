package dev.axion.api;

/**
 * Vue en lecture de l'état d'une part (§23.2, DM-11).
 *
 * <p>Toutes les méthodes sont des lectures d'un instantané autoritatif ; aucune
 * ne modifie l'assembly. Thread : autoritatif (serveur) ou rendu (client,
 * valeurs interpolées). Coût : négligeable. Échec : aucun.
 */
@Stable
public interface PartView {

    /** {@return le nom de la part}. */
    String name();

    /** {@return l'étape de dommage courante}. */
    PartStage stage();

    /** {@return la santé, dans {@code [0, 1]}}. */
    float health();

    /** {@return l'intégrité structurelle, dans {@code [0, 1]}}. */
    float integrity();

    /** {@return l'énergie cumulée absorbée, en joules}. */
    float absorbedEnergy();

    /** {@return vrai si la part est grippée}. */
    boolean isJammed();

    /** {@return vrai si une part INTERNAL a été révélée par le dommage}. */
    boolean isRevealed();
}
