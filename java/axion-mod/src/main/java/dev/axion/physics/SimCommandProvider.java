package dev.axion.physics;

/**
 * Fournit le flux de commandes {@code SIM_IN} d'un tick (IF-03).
 *
 * <p>Couture entre le cycle de simulation ({@code AxionRuntime.driveSimulation}) et les
 * sources de commandes couplées au jeu (tuiles du monde C-38, à venir : création d'assembly
 * C-40, environnement de dimension). Le runtime, agnostique de Forge, appelle simplement
 * cette méthode chaque tick et soumet le flux rendu ; l'implémentation vit côté Forge.
 */
@FunctionalInterface
public interface SimCommandProvider {

    /**
     * {@return les commandes à soumettre pour le tick donné (jamais {@code null} ; un flux
     * vide est valide)}
     *
     * @param tick numéro du tick courant
     */
    SimCommandStream commandsForTick(long tick);
}
