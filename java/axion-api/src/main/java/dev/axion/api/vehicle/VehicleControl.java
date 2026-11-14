package dev.axion.api.vehicle;

import dev.axion.api.Experimental;
import java.util.List;

/**
 * Un véhicule vu par l'API (ADR-159 D11, ADR-167 D9), rendu par {@link dev.axion.api.assembly.Assembly#vehicle()} : son
 * état, ses contrôleurs, et son pilotage par un mod.
 *
 * <p>Le couple d'une roue ne se pose que par un contrôleur, jamais par un appel ponctuel : un appel ne se rejouerait
 * pas chez le client qui prédit.
 *
 * <p>Thread : autoritatif. Au client, {@link #controllers()} se lit ; {@link #state()} se lit pour le véhicule que le
 * joueur conduit et prédit, dont les contrôleurs tournent aussi au client à chaque tick prédit et rejoué (ADR-168 D6) ;
 * l'état des autres véhicules et le pilotage y lèvent {@link IllegalStateException} (R-1755).
 */
@Experimental
public interface VehicleControl {

    /**
     * {@return son état au début du tick} Au client, celui du véhicule que le joueur prédit, au début de son dernier tick
     * prédit.
     *
     * @throws IllegalStateException au client pour un véhicule que le joueur ne prédit pas, hors du thread autoritatif,
     *     ou tant que sa definition n'est pas compilée
     */
    VehicleView state();

    /** {@return les identifiants de ses contrôleurs : ceux de sa definition, puis ceux que l'API a attachés} */
    List<String> controllers();

    /**
     * Attache un contrôleur à ce véhicule seul. Il est sauvegardé avec lui et part aux joueurs qui le suivent ; les
     * paramètres de son mod existent dès lors sur ce véhicule.
     *
     * @param controller l'identifiant du contrôleur, {@code <espace de noms>:<chemin>}
     * @throws IllegalArgumentException si aucun mod ne l'enregistre, s'il est déjà attaché, ou si le véhicule en porte
     *     déjà 8, le plafond
     * @throws IllegalStateException au client, ou hors du thread autoritatif
     */
    void attach(String controller);

    /**
     * Retire un contrôleur que l'API avait attaché. Ceux de la definition restent : ils font partie du véhicule.
     *
     * @param controller l'identifiant du contrôleur
     * @return vrai s'il était attaché par l'API
     * @throws IllegalStateException au client, ou hors du thread autoritatif
     */
    boolean detach(String controller);

    /**
     * Conduit le véhicule à la place d'un joueur (pilotage par un mod) : l'entrée vaut jusqu'à la suivante, et relâche
     * le frein de stationnement qu'un véhicule sans conducteur garde serré ; pour le tenir à l'arrêt, serrer le frein à
     * main ({@code handbrake} à 1). Un joueur qui prend le volant reprend la conduite ; sorti, il laisse le véhicule
     * sans conducteur, frein de stationnement serré.
     *
     * @param input l'entrée
     * @throws IllegalStateException si un joueur tient le volant — le message le nomme —, au client, ou hors du
     *     thread autoritatif
     */
    void drive(DriverInput input);
}
