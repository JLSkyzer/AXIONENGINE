package dev.axion.physics;

import java.util.List;

/**
 * Reçoit les états de corps collectés à la fin d'un tick (IF-03, C-40).
 *
 * <p>Couture symétrique de {@link SimCommandProvider} : le cycle de simulation
 * ({@code AxionRuntime.driveSimulation}) remet les {@link BodyState} du tick à cette
 * couture, que la couche Forge implémente pour repositionner les entités liées (boucle
 * C-40 ↔ C-50). Le runtime, agnostique de Forge, ne connaît que cette méthode.
 */
@FunctionalInterface
public interface SimStateSink {

    /**
     * Applique les états collectés au tick donné (routés par handle vers les entités).
     *
     * @param tick numéro du tick
     * @param states états de corps décodés, dans l'ordre déterministe du natif (R-1020)
     */
    void applyStates(long tick, List<BodyState> states);
}
