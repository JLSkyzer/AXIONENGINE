package dev.axion.asset;

/**
 * États d'un asset (SM-01, fiche C-20).
 *
 * <pre>
 * DISCOVERED -&gt; QUEUED -&gt; COMPILING -&gt; COMPILED -&gt; LOADED -&gt; UNLOADED
 * </pre>
 *
 * <p>Plus {@link #CACHED}, qu'un asset atteint sans passer par la compilation,
 * et {@link #FAILED}, dont il ne revient pas tant que sa source n'a pas changé.
 *
 * <p>Les transitions sont vérifiées. Un asset qui passerait de
 * {@code DISCOVERED} à {@code LOADED} sauterait la validation de C-22, et
 * personne ne s'en apercevrait avant que le moteur ne lise des données qu'il
 * n'a jamais contrôlées.
 */
public enum AssetState {
    /** Trouvé dans un pack de ressources, rien de plus. */
    DISCOVERED,
    /** En attente de compilation. */
    QUEUED,
    /** Compilation en cours sur le pool natif. */
    COMPILING,
    /** Compilé pendant cette session. */
    COMPILED,
    /** Repris du cache sans recompilation. */
    CACHED,
    /** Chargé en mémoire native, prêt à servir. */
    LOADED,
    /** Relâché ; sa place est reprise. */
    UNLOADED,
    /** Refusé. L'asset de secours le remplace (R-522). */
    FAILED;

    /**
     * Indique si la transition vers {@code next} est admise.
     *
     * @param next état visé
     * @return vrai si l'enchaînement est prévu par SM-01
     */
    public boolean canTransitionTo(AssetState next) {
        return switch (this) {
            case DISCOVERED -> next == QUEUED || next == CACHED || next == FAILED;
            case QUEUED -> next == COMPILING || next == FAILED;
            case COMPILING -> next == COMPILED || next == FAILED;
            // Un asset compilé comme un asset repris du cache se chargent :
            // c'est le même contenu, seul son chemin diffère.
            case COMPILED, CACHED -> next == LOADED || next == FAILED;
            case LOADED -> next == UNLOADED;
            // Un rechargement de ressources repart de la découverte, y compris
            // pour ce qui avait échoué : c'est la seule façon qu'une source
            // corrigée reprenne sa chance (R-520).
            case UNLOADED, FAILED -> next == DISCOVERED;
        };
    }

    /** {@return vrai si l'asset porte du contenu utilisable} */
    public boolean isUsable() {
        return this == COMPILED || this == CACHED || this == LOADED;
    }

    /** {@return vrai si l'asset attend encore quelque chose} */
    public boolean isPending() {
        return this == DISCOVERED || this == QUEUED || this == COMPILING;
    }
}
