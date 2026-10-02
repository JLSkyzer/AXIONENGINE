package dev.axion.physics;

import java.util.List;

/**
 * Reçoit les états de corps collectés à la fin d'un tick (IF-03, C-40).
 *
 * <p>Couture symétrique de {@link SimCommandProvider} : le cycle de simulation
 * ({@code AxionRuntime.driveSimulation}) remet les {@link BodyState} du tick et leurs
 * {@link BodyBounds} à cette couture, que la couche Forge implémente pour repositionner les
 * entités liées et caler leur hitbox (boucle C-40 ↔ C-50, R-702). Le runtime, agnostique de
 * Forge, ne connaît que cette méthode.
 */
@FunctionalInterface
public interface SimStateSink {

    /**
     * Applique les états collectés au tick donné (routés par handle vers les entités).
     *
     * @param tick numéro du tick
     * @param states états de corps décodés, dans l'ordre déterministe du natif (R-1020)
     * @param bounds emprises des mêmes corps (ADR-120) : {@code bounds.get(i)} est celle de
     *     {@code states.get(i)} ; liste vide si le natif ne les rapporte pas
     */
    void applyStates(long tick, List<BodyState> states, List<BodyBounds> bounds);
}
