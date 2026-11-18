package dev.axion.api.vehicle;

import dev.axion.api.Experimental;
import java.util.List;

/**
 * L'état d'un véhicule au début d'un tick (ADR-167 D9, R-651) : ce que ses contrôleurs lisent, et ce que
 * {@link VehicleControl#state()} rend. Un instantané : il ne change pas pendant qu'on le lit.
 */
@Experimental
public interface VehicleView {

    /** {@return sa vitesse le long de son avant, en m/s, négative en marche arrière} */
    double speed();

    /** {@return le régime de son moteur, en tr/min ; 0 sans moteur} */
    double rpm();

    /** {@return le rapport engagé : 0 au point mort, négatif en marche arrière} */
    int gear();

    /** {@return l'entrée qui le conduit : celle du joueur au volant, ou celle qu'un mod a posée ; aucune sinon} */
    DriverInput input();

    /** {@return ses roues, dans l'ordre de la definition} */
    List<WheelView> wheels();

    /**
     * {@return la valeur d'une variable du véhicule}
     *
     * @param name son nom dans la definition
     * @throws IllegalArgumentException si la definition ne la déclare pas
     */
    double variable(String name);

    /**
     * {@return la valeur d'un paramètre, composée sans les sorties des contrôleurs} : chaque contrôleur lit la même,
     * quel que soit leur ordre.
     *
     * @param name son nom, celui d'AXION ({@code engine.torque}) ou celui d'un addon ({@code turbo:boost_max})
     * @throws IllegalArgumentException si le véhicule n'a pas ce paramètre
     */
    double parameter(String name);

    /**
     * {@return vrai si le véhicule a ce paramètre}
     *
     * @param name son nom
     */
    boolean hasParameter(String name);

    /**
     * {@return la valeur d'une source procédurale (ANNEXE A.4), au début du tick} — {@code world.time},
     * {@code world.rain}, {@code vehicle.speed}, {@code var.phares}… Le nom et la valeur sont ceux d'une piste
     * procédurale de la definition : le même code les évalue. Au serveur, le monde se lit au point de l'assembly ; au
     * client qui prédit, dans son propre niveau (ADR-173 D1). Thread : celui du contrôleur.
     *
     * @param name le nom de la source
     * @throws IllegalArgumentException si le nom n'est pas une source de l'ANNEXE A.4, ou nomme une roue, un siège, un
     *     socket ou une variable que la definition n'a pas
     */
    default double source(String name) {
        throw new UnsupportedOperationException("source : cette vue ne lit pas les sources procédurales");
    }
}
