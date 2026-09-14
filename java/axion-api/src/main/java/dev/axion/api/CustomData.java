package dev.axion.api;

/**
 * Données personnalisées qu'un mod tiers attache à une assembly.
 *
 * <p>Persistées avec l'assembly, plafonnées (PARTIE 22.1). Surface complétée
 * avec l'exposition du stockage personnalisé côté API. Déclarée ici pour que
 * {@link Assembly#customData()} ait son type de retour dès la 1.0.
 */
@Stable
public interface CustomData {}
