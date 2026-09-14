package dev.axion.api;

/**
 * État du runtime AXION vu par un mod tiers (C-01).
 *
 * <p>Dit si le moteur est opérationnel, désactivé ou en cours de démarrage, sans
 * exposer la machine à états interne. Surface complétée avec l'exposition du
 * cycle de vie côté API. Déclarée ici pour que {@link AxionApi#state()} ait son
 * type de retour dès la 1.0.
 */
@Stable
public interface RuntimeState {}
