package dev.axion.api.vehicle;

import dev.axion.api.Experimental;

/**
 * Un contrôleur de véhicule (ADR-167 D9, registre {@code axion:vehicle_controller}, 23.3) : à chaque tick, il lit l'état
 * du véhicule au début du tick et écrit ses sorties — des modificateurs pour ce tick, le couple de chaque roue, des
 * variables.
 *
 * <p>Un mod l'enregistre pendant sa phase d'enregistrement
 * ({@link dev.axion.api.content.ContentRegistrar#registerController}) ; une definition le nomme
 * ({@code vehicle.controllers}), ou l'API l'attache à une instance ({@link VehicleControl#attach}).
 *
 * <p><strong>Son contrat</strong> : une fonction de ce qu'il lit, sans état caché. S'il a un état, il vit dans les
 * variables du véhicule : c'est ainsi qu'il est sauvegardé, envoyé aux joueurs et rejoué. Il est appelé sur le thread
 * autoritatif, entre deux pas, avant que les entrées partent au moteur physique ; le client l'appellera pour prédire.
 * Plusieurs contrôleurs d'un même véhicule lisent le même état, si bien que leur ordre ne compte pas.
 *
 * <p><strong>Son coût</strong> est mesuré contrôleur par contrôleur (R-1770). Un dépassement répété de son budget, ou
 * une exception répétée, le désactive pour la session, avec un message qui le nomme ainsi que son mod
 * ({@code E-1011}) ; le véhicule roule alors sans ses sorties.
 */
@Experimental
@FunctionalInterface
public interface VehicleController {

    /**
     * Un tick du véhicule.
     *
     * @param vehicle l'état du véhicule au début du tick
     * @param out ce que le contrôleur écrit pour ce tick ; valable pendant cet appel seulement
     */
    void control(VehicleView vehicle, ControllerOutput out);
}
