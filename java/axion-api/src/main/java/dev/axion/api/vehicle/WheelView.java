package dev.axion.api.vehicle;

import dev.axion.api.Experimental;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

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

    /**
     * {@return l'usure de son pneu, de 0 (neuf) à 1 (usé à fond) (ADR-176 D4) ; 0 si son profil de pneu ne déclare pas
     * de {@code wear}}
     */
    default double wear() {
        return 0.0;
    }

    /**
     * {@return l'identifiant du matériau physique que touche la roue (ADR-181), celui d'AXION ou d'un addon ; vide en
     * l'air, ou sur un sol qui n'en déclare pas}
     */
    default Optional<ResourceLocation> surface() {
        return Optional.empty();
    }
}
