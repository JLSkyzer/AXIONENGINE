package dev.axion.api.vehicle;

import dev.axion.api.Experimental;

/** L'état d'une roue au début d'un tick (ADR-167 D9) : ce qu'un contrôleur en lit. */
@Experimental
public interface WheelView {

    /** {@return son node dans le modèle} */
    String node();

    /** {@return vrai si elle touche le sol} */
    boolean grounded();

    /**
     * {@return sa compression, en fraction du débattement : 0 à sa position dans le modèle posé, positive quand elle
     * monte vers la caisse}
     */
    double compression();

    /** {@return sa vitesse de rotation, en rad/s, positive en marche avant} */
    double spin();

    /** {@return son angle de braquage, en radians} */
    double steer();

    /** {@return son glissement longitudinal, sans unité : 0 quand elle roule sans patiner ni bloquer} */
    double slipLong();

    /** {@return son glissement latéral, en radians} */
    double slipLat();
}
