package dev.axion.physics;

import java.util.List;

/**
 * Reçoit les événements physiques collectés à la fin d'un tick (R-1010, IF-03).
 *
 * <p>Couture symétrique de {@link SimStateSink} : le cycle de simulation
 * ({@code AxionRuntime.driveSimulation}) remet les {@link PhysicsEvent} du tick à cette couture,
 * sur le thread autoritatif (R-1010), <em>après</em> les états — une entité liée est déjà à sa
 * place quand un effet la touche. La couche Forge l'implémente pour les effets sur les entités
 * vanilla (R-614, ADR-123 §7) ; le runtime, agnostique de Forge, ne connaît que cette méthode.
 */
@FunctionalInterface
public interface SimEventSink {

    /**
     * Applique les événements collectés au tick donné.
     *
     * @param tick numéro du tick
     * @param events événements décodés, dans l'ordre déterministe du natif (R-1020)
     */
    void applyEvents(long tick, List<PhysicsEvent> events);
}
