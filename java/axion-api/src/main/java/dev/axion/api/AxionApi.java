package dev.axion.api;

import java.util.Optional;

/**
 * Façade de l'API publique d'AXION ENGINE (§23.2, C-70).
 *
 * <p>Point d'entrée unique d'un mod tiers vers le moteur. Obtenue par {@link
 * #get()} lorsque l'appelant sait AXION présent, ou par {@link #getIfReady()}
 * sinon. Les accesseurs rendent les services, dont chacun documente ses propres
 * contrats de thread et d'échec.
 */
@Stable
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

    /** {@return la version de l'API}. */
    ApiVersion version();

    /** {@return l'état du runtime}. */
    RuntimeState state();

    /** {@return le profil de qualité effectif}. */
    QualityProfile quality();

    /** {@return le service des assets}. */
    AssetService assets();

    /** {@return le service des definitions}. */
    DefinitionService definitions();

    /** {@return le service des assemblies}. */
    AssemblyService assemblies();

    /** {@return le service physique}. */
    PhysicsService physics();

    /** {@return le service de dommage}. */
    DamageService damage();

    /** {@return le service de déformation}. */
    DeformationService deformation();

    /** {@return le service des attaches}. */
    AttachmentService attachments();

    /** {@return le bus d'événements}. */
    EventBus events();

    /** {@return l'accès aux registres data-driven}. */
    RegistryAccess registries();

    /** {@return l'accès aux diagnostics}. */
    Diagnostics diagnostics();
}
