package dev.axion.api.content;

import dev.axion.api.AxionApi;
import dev.axion.api.Stable;

/**
 * Accès aux definitions data-driven chargées (C-27), depuis l'API.
 *
 * <p>Surface complétée avec l'exposition des definitions côté API. Déclarée ici
 * pour que {@link AxionApi#definitions()} ait son type de retour dès la 1.0.
 */
@Stable
public interface DefinitionService {}
