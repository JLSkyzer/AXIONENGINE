package dev.axion.api;

/**
 * Niveau de qualité d'un sous-système gradué (PARTIE 24.2).
 *
 * <p>De {@link #OFF} (Q-0) à {@link #ULTRA} (Q-4). Chaque niveau est implémenté
 * et testé ; le gouverneur les ajuste, et un niveau forcé par l'utilisateur est
 * respecté.
 */
@Stable
public enum QualityLevel {
    /** Q-0 : sous-système désactivé ou à son repli le plus économe. */
    OFF,
    /** Q-1. */
    LOW,
    /** Q-2. */
    MEDIUM,
    /** Q-3. */
    HIGH,
    /** Q-4 : qualité maximale. */
    ULTRA,
}
