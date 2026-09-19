package dev.axion.api;

import java.util.function.Consumer;

/**
 * Abonnement aux événements d'AXION (§23.2).
 *
 * <p>Tous les événements sont émis sur le thread autoritatif, en phase
 * {@code TICK_COLLECT} (R-1760) : un handler s'exécute donc là, jamais depuis un
 * worker, et ne doit pas bloquer. Un handler qui lève une exception est isolé et
 * journalisé — avec le mod propriétaire quand il est identifiable — puis
 * désactivé après cinq échecs (R-1761), sans affecter les autres abonnés.
 *
 * <p>Les types d'événements sont les dix-huit classes {@code *Event} du §23.2,
 * toutes {@link AxionEvent}. En 1.0 elles portent leur type stable et leur
 * abonnement ; leurs accesseurs s'ajoutent de façon compatible (R-1751) à mesure
 * que leurs composants producteurs existent.
 */
@Stable
public interface EventBus {

    /**
     * Abonne un handler à un type d'événement.
     *
     * <p>Le handler reçoit tout événement de type {@code type} — ou d'un
     * sous-type — émis après l'abonnement, jusqu'à ce que le jeton rendu soit
     * fermé.
     *
     * <p>Effet : enregistre l'abonnement. Thread : appelable de n'importe où ; le
     * handler, lui, s'exécute toujours sur le thread autoritatif. Coût :
     * négligeable. Échec : aucun.
     *
     * @param type classe de l'événement écouté
     * @param handler traitement, exécuté à chaque émission
     * @param <E> type de l'événement
     * @return un jeton pour se désabonner ; jamais nul
     */
    <E extends AxionEvent> Subscription subscribe(Class<E> type, Consumer<E> handler);
}
