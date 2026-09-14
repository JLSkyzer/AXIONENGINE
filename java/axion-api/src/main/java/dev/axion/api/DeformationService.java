package dev.axion.api;

/**
 * Réglages globaux de la déformation continue (C-42), depuis l'API.
 *
 * <p>Surface complétée avec C-42 (jalon M6). La lecture et la restauration par
 * assembly passent par {@link DeformationView} ; ce service porte ce qui est
 * global. Déclaré ici pour que {@link AxionApi#deformation()} ait son type de
 * retour dès la 1.0.
 */
@Stable
public interface DeformationService {}
