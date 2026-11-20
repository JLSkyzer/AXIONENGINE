package dev.axion.api.assembly;

import dev.axion.api.Experimental;

/**
 * L'énergie d'un véhicule (ADR-176 D3) : le carburant de son réservoir et la charge de sa batterie, tels que le natif les
 * simule — il brûle, il décharge, il recharge, il arrête le moteur au réservoir vide. Une definition qui ne déclare ni
 * réservoir ni batterie rend zéro partout.
 *
 * <p>AXION ne fournit pas de pompe : le contenu la fait, avec {@link #refuel} ou l'action {@code refuel} d'une
 * interaction.
 *
 * <p>Thread : autoritatif uniquement, en lecture comme en écriture ; ailleurs, {@link IllegalStateException} (R-1755).
 */
@Experimental
public interface Energy {

    /** {@return le carburant restant, en litres ; 0 sans réservoir} */
    double fuel();

    /** {@return la capacité du réservoir, en litres ; 0 sans réservoir} */
    double fuelCapacity();

    /** {@return le carburant sur la capacité, de 0 à 1 ; 0 sans réservoir} */
    default double fuelRatio() {
        return fuelCapacity() > 0.0 ? fuel() / fuelCapacity() : 0.0;
    }

    /** {@return la charge de la batterie ; 0 sans batterie} */
    double battery();

    /** {@return la capacité de la batterie ; 0 sans batterie} */
    double batteryCapacity();

    /**
     * Ajoute du carburant, jusqu'à la capacité du réservoir. Le natif le reçoit sans réveiller le véhicule ; un moteur
     * que le réservoir vide avait arrêté ne repart pas seul : il lui faut un nouveau front du contact.
     *
     * @param litres ce qu'on verse, de 0 ou plus
     * @return ce qui est entré dans le réservoir, en litres : moins que demandé s'il est presque plein, 0 sans réservoir
     * @throws IllegalArgumentException si {@code litres} est négatif ou n'est pas fini
     */
    double refuel(double litres);

    /**
     * Pose la charge de la batterie, ramenée à sa capacité. Sans batterie, rien.
     *
     * @param charge la charge, de 0 ou plus
     * @throws IllegalArgumentException si {@code charge} est négative ou n'est pas finie
     */
    void setBattery(double charge);
}
