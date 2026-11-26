package dev.axion.api.assembly;

import dev.axion.api.Experimental;
import java.util.List;
import java.util.Optional;

/**
 * L'énergie d'un véhicule (ADR-176 D3, ADR-182) : ses réserves, telles que le natif les simule — il brûle celle du moteur,
 * décharge et recharge celle du démarreur, arrête le moteur au réservoir vide. {@link #fuel} et {@link #battery} sont ces
 * deux-là ; {@link #stores} les donne toutes. Une definition sans énergie rend zéro partout.
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

    /** {@return chaque réserve de la definition, dans son ordre ; vide sans énergie (ADR-182 D1)} */
    default List<EnergyStore> stores() {
        return List.of();
    }

    /**
     * {@return la réserve de ce nom ; vide si la definition n'en déclare pas}
     *
     * @param name son nom
     */
    default Optional<EnergyStore> store(String name) {
        return stores().stream().filter(store -> store.name().equals(name)).findFirst();
    }

    /**
     * Ajoute du carburant, jusqu'à la capacité du réservoir. Un réservoir typé (ADR-182 D2) n'accepte rien ainsi : voir
     * {@link EnergyStore#fill}. Le natif le reçoit sans réveiller le véhicule ; un moteur
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
