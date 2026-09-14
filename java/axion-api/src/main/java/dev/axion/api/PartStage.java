package dev.axion.api;

/**
 * Étape de dommage d'une part (DM-11, §13.4).
 *
 * <p>La progression est monotone d'{@link #INTACT} vers {@link #DESTROYED} ;
 * {@link #DETACHED} en sort : la part appartient alors à une assembly de débris.
 */
@Stable
public enum PartStage {
    /** Aucun dommage. */
    INTACT,
    /** Rayures de surface, sans perte de fonction. */
    SCRATCHED,
    /** Déformée, fonction dégradée. */
    DAMAGED,
    /** Lourdement endommagée. */
    HEAVY,
    /** Détruite. */
    DESTROYED,
    /** Détachée de l'assembly. */
    DETACHED,
}
