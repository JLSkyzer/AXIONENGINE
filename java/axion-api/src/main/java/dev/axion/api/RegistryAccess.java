package dev.axion.api;

/**
 * Accès aux registres data-driven d'AXION (matériaux physiques, groupes de
 * collision, profils d'usure, règles de réparation ; C-27).
 *
 * <p>Surface complétée avec l'exposition des registres côté API. Déclarée ici
 * pour que {@link AxionApi#registries()} ait son type de retour dès la 1.0.
 */
@Stable
public interface RegistryAccess {}
