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
     * @param joints états des joints des assemblies éveillées (ADR-151 D7) ; ceux d'une assembly
     *     endormie n'y sont pas, et restent tels que rapportés en dernier
     * @param wheels états des roues des véhicules éveillés (ADR-159 D9) ; ceux d'un véhicule endormi n'y
     *     sont pas, et restent tels que rapportés en dernier
     * @param vehicles états des motopropulseurs des véhicules éveillés (ADR-163 D1) ; comme les roues
     */
    void applyStates(long tick, List<BodyState> states, List<BodyBounds> bounds, List<JointState> joints,
            List<WheelReport> wheels, List<VehicleReport> vehicles);

    /**
     * Reçoit l'état complet des assemblies exportées au tick donné (ADR-168 D1), après {@link #applyStates} : celles
     * dont l'export a été demandé, avec le statut de leur dernière restauration. Par défaut, rien.
     *
     * @param tick tick serveur du collect
     * @param exported les états exportés, dans l'ordre du natif
     */
    default void applyAssemblyStates(long tick, List<AssemblyStateReport> exported) {}
}
