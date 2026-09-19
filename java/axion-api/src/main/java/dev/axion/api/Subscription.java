package dev.axion.api;

/**
 * Jeton d'un abonnement à {@link EventBus}, révocable une fois.
 *
 * <p>Fermer le jeton retire le handler ; le fermer de nouveau n'a aucun effet.
 * {@link AutoCloseable} pour un usage en {@code try}-with-resources quand
 * l'abonnement ne dure que le temps d'un bloc.
 */
@Stable
public interface Subscription extends AutoCloseable {

    /**
     * Révoque l'abonnement. Idempotent : un second appel ne fait rien.
     *
     * <p>Effet : retire le handler. Thread : quelconque. Coût : négligeable.
     * Échec : aucun ; ne lève pas, contrairement au {@link AutoCloseable#close()}
     * général — un désabonnement n'a aucune raison d'échouer.
     */
    @Override
    void close();
}
