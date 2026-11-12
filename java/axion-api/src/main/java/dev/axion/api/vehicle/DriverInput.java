package dev.axion.api.vehicle;

import dev.axion.api.Experimental;

/**
 * Ce qui conduit un véhicule (12.8) : celle d'un joueur au volant, ou celle qu'un mod pose
 * ({@link VehicleControl#drive}). Les valeurs sont bornées à leur application : une valeur hors de sa plage compte pour
 * sa borne, une valeur non finie pour zéro.
 *
 * @param throttle accélérateur, dans [0, 1]
 * @param brake frein, dans [0, 1]
 * @param steer direction, dans [-1, 1], positive à droite
 * @param clutch embrayage, dans [0, 1] : 1 débraye
 * @param handbrake frein à main, dans [0, 1]
 * @param gear rapport demandé à une boîte manuelle : 0 au point mort, négatif en marche arrière ; ignoré d'une boîte
 *     automatique
 */
@Experimental
public record DriverInput(double throttle, double brake, double steer, double clutch, double handbrake, int gear) {

    /** Aucune entrée : rien n'accélère, rien ne freine. */
    public static final DriverInput NONE = new DriverInput(0, 0, 0, 0, 0, 0);
}
