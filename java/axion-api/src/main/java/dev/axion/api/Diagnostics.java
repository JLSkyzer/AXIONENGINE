package dev.axion.api;

/**
 * Accès en lecture aux diagnostics et métriques du moteur (C-15, C-73).
 *
 * <p>Surface complétée avec l'exposition des diagnostics côté API. Déclarée ici
 * pour que {@link AxionApi#diagnostics()} ait son type de retour dès la 1.0.
 */
@Stable
public interface Diagnostics {}
