package dev.axion.api.event;

import dev.axion.api.Stable;

/**
 * Racine de tous les événements d'AXION (§23.2).
 *
 * <p>Tout type d'événement l'étend ; l'abonnement typé de {@link EventBus} s'en
 * sert comme borne. Elle ne porte aucun membre : ce qui est commun à tous les
 * événements est leur émission — sur le thread autoritatif, en phase
 * {@code TICK_COLLECT} (R-1760) —, pas une donnée.
 */
@Stable
public interface AxionEvent {}
