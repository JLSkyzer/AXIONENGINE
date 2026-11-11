package dev.axion.api;

import dev.axion.api.assembly.AssemblyService;

import java.util.Optional;

/**
 * Façade de l'API publique d'AXION ENGINE (§23.2, C-70).
 *
 * <p>Point d'entrée unique d'un mod tiers vers le moteur. Obtenue par {@link
 * #get()} lorsque l'appelant sait AXION présent, ou par {@link #getIfReady()}
 * sinon. Les accesseurs rendent les services, dont chacun documente ses propres
 * contrats de thread et d'échec.
 *
 * <p><strong>Une surface réelle.</strong> La façade ne porte que les services que
 * le moteur rend vraiment (ADR-162 D1) : aucun accesseur ne lève « pas encore
 * disponible ». Les services que le §23.2 nomme et que leur composant n'a pas
 * encore livrés — physique, dommage, déformation, attaches, événements, registres,
 * diagnostics, qualité — y entrent à leur jalon, en méthodes {@code default} qui ne
 * cassent aucun mod. Le contenu, lui, ne passe pas par cette façade : un mod
 * l'envoie à AXION avant qu'elle existe ({@link dev.axion.api.content.ContentProvider}).
 */
@Experimental
public interface AxionApi {

    /**
     * {@return l'API d'AXION}
     *
     * <p>Effet : résout le fournisseur d'implémentation. Thread : quelconque.
     * Coût : négligeable après le premier appel (résultat mémorisé). Échec : lève
     * {@link IllegalStateException} si AXION est absent ou désactivé — préférer
     * {@link #getIfReady()} en cas de doute.
     */
    static AxionApi get() {
        return AxionApiProvider.require();
    }

    /**
     * {@return l'API d'AXION si elle est disponible}
     *
     * <p>Effet : résout le fournisseur sans lever. Thread : quelconque. Coût :
     * négligeable après le premier appel. Échec : vide si AXION est absent ou pas
     * encore prêt.
     */
    static Optional<AxionApi> getIfReady() {
        return AxionApiProvider.get();
    }

    /** {@return la version de l'API}. Thread : quelconque. */
    ApiVersion version();

    /** {@return le service des assemblies}. Thread : quelconque ; chaque méthode dit le sien. */
    AssemblyService assemblies();
}
