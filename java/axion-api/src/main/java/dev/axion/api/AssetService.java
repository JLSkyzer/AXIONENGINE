package dev.axion.api;

/**
 * Accès aux assets compilés (C-20..C-25), depuis l'API.
 *
 * <p>Surface complétée avec l'exposition des assets côté API. Déclarée ici pour
 * que {@link AxionApi#assets()} ait son type de retour dès la 1.0 ; les méthodes
 * s'y ajouteront de façon compatible (R-1751).
 */
@Stable
public interface AssetService {}
