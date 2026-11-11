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
 * <p><strong>État de C-70.</strong> La surface ne porte que ce que le moteur rend
 * vraiment (ADR-162 D1) : la façade {@link dev.axion.api.AxionApi} et le service
 * des assemblies, l'enregistrement du contenu
 * ({@link dev.axion.api.content.ContentProvider}), la description d'un impact
 * ({@link dev.axion.api.damage.ImpactSpec}). Les services, vues et événements que
 * le §23.2 nomme et que leur composant n'a pas encore livrés y entrent à leur
 * jalon. La surface nouvelle est {@code @Experimental} jusqu'au prononcé de M5.
 */
package dev.axion.api;
