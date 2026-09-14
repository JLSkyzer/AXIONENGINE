package dev.axion.api;

/**
 * Origine d'un impact (C-41), portée par un {@link ImpactSpec}.
 *
 * <p>Surface complétée avec C-41 (jalon M6). Déclarée ici pour que {@link
 * ImpactSpec.Builder#source(ImpactSource)} ait son type de paramètre dès la 1.0.
 */
@Stable
public interface ImpactSource {}
