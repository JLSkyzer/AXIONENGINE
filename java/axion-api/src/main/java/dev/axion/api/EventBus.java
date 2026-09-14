package dev.axion.api;

/**
 * Abonnement aux événements d'AXION (§23.2).
 *
 * <p>Tous les événements sont émis sur le thread autoritatif, en phase
 * {@code TICK_COLLECT} (R-1760) ; un handler qui lève est isolé, journalisé et
 * désactivé après cinq échecs (R-1761).
 *
 * <p>Surface complétée par la tranche 2 de C-70, qui apporte les classes
 * d'événements et l'abonnement typé. Déclarée ici pour que {@link
 * AxionApi#events()} ait son type de retour dès la 1.0.
 */
@Stable
public interface EventBus {}
