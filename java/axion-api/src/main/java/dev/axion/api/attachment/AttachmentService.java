package dev.axion.api.attachment;

import dev.axion.api.AxionApi;
import dev.axion.api.Stable;

/**
 * Création et rupture d'attaches entre assemblies (C-48), depuis l'API.
 *
 * <p>Surface complétée avec C-48 (jalon M5). Déclarée ici pour que {@link
 * AxionApi#attachments()} ait son type de retour dès la 1.0.
 */
@Stable
public interface AttachmentService {}
