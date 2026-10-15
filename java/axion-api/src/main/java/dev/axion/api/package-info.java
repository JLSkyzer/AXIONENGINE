/**
 * API publique d'AXION ENGINE (C-70).
 *
 * <p>Ce module est <strong>publié indépendamment</strong> du mod (R-1750) et ne
 * dépend ni de l'implémentation, ni du natif : il n'expose aucun type Rapier,
 * glam ou handle natif brut (R-1753). Il dépend en revanche des types de
 * Minecraft ({@code Level}, {@code Entity}, {@code Vec3}, {@code DamageSource})
 * et de JOML ({@code Quaternionf}, {@code Matrix4f}) qu'il fait figurer dans sa
 * surface.
 *
 * <p><strong>Versionnement.</strong> L'API suit un versionnement sémantique
 * propre, indépendant de celui du mod (R-1751) ; la compatibilité binaire
 * ascendante est garantie sur toute la série 1.x. Un type ou une méthode ajouté
 * l'est de façon compatible ; rien de publié n'est retiré ni changé de
 * signature en 1.x.
 *
 * <p><strong>Stabilité.</strong> Chaque type porte
 * {@link dev.axion.api.Stable @Stable} ou {@link dev.axion.api.Experimental @Experimental}
 * (R-1752) ; aucun {@code @Internal} ne figure dans ce module.
 *
 * <p><strong>Threads.</strong> Toute méthode mutante ne s'appelle que sur le
 * thread autoritatif et lève {@link IllegalStateException} ailleurs (R-1755) ;
 * chaque méthode documente son effet, le thread autorisé, son coût et ses
 * conditions d'échec (R-1754).
 *
 * <p><strong>État de C-70.</strong> Les deux premières tranches portent la façade
 * {@link dev.axion.api.AxionApi}, tout ce que sa surface d'assembly et de dommage
 * référence, et les dix-huit événements du §23.2 avec leur
 * {@link dev.axion.api.EventBus}, transcrits du cahier des charges. Les services
 * seulement nommés y sont déclarés comme interfaces vides, complétées par leur
 * composant à son jalon. Le mod n'enregistre encore aucun fournisseur : tant
 * qu'il n'en a pas, {@link dev.axion.api.AxionApi#get()} lève et
 * {@link dev.axion.api.AxionApi#getIfReady()} rend vide.
 */
package dev.axion.api;
