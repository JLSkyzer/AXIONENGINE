package dev.axion.lifecycle;

/**
 * Phases du cycle de vie d'AXION dans son hôte (C-01).
 *
 * <pre>
 * UNLOADED -&gt; CONSTRUCTED -&gt; SETUP -&gt; LOAD_COMPLETE
 *          -&gt; RUNNING_CLIENT | RUNNING_SERVER -&gt; STOPPING -&gt; UNLOADED
 * </pre>
 *
 * <p>Les transitions sont vérifiées : une phase sautée ou répétée signale un
 * enchaînement d'événements inattendu, qu'il vaut mieux constater que subir.
 */
public enum LifecyclePhase {
    /** Rien n'est chargé, ou tout a été relâché. */
    UNLOADED,
    /** Le mod est construit ; la plateforme est connue. */
    CONSTRUCTED,
    /** Configuration lue et runtime natif démarré, ou désactivé. */
    SETUP,
    /** Tous les mods sont chargés. */
    LOAD_COMPLETE,
    /** Un client tourne. */
    RUNNING_CLIENT,
    /** Un serveur tourne. */
    RUNNING_SERVER,
    /** Arrêt en cours : les ressources natives sont relâchées. */
    STOPPING;

    /**
     * Indique si la transition vers {@code next} est admise.
     *
     * @param next phase visée
     * @return vrai si l'enchaînement est prévu par le cycle de vie
     */
    public boolean canTransitionTo(LifecyclePhase next) {
        return switch (this) {
            case UNLOADED -> next == CONSTRUCTED;
            case CONSTRUCTED -> next == SETUP || next == STOPPING;
            case SETUP -> next == LOAD_COMPLETE || next == STOPPING;
            // Un serveur intégré démarre depuis un client déjà lancé : la
            // transition vers RUNNING_SERVER reste donc ouverte ici.
            case LOAD_COMPLETE -> next == RUNNING_CLIENT || next == RUNNING_SERVER
                    || next == STOPPING;
            case RUNNING_CLIENT -> next == RUNNING_SERVER || next == STOPPING;
            case RUNNING_SERVER -> next == RUNNING_CLIENT || next == STOPPING;
            case STOPPING -> next == UNLOADED;
        };
    }

    /** {@return vrai si un runtime est en cours d'exécution} */
    public boolean isRunning() {
        return this == RUNNING_CLIENT || this == RUNNING_SERVER;
    }
}
