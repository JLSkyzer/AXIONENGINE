package dev.axion.api.assembly;

import dev.axion.api.Experimental;
import java.util.Map;
import net.minecraft.world.entity.player.Player;

/**
 * Une action qu'un mod ajoute à la liste fermée d'une definition (ADR-174 D6, CAP-38) : une étape
 * {@code {"<modId>:<nom>": {…}}} de la liste {@code actions} d'une interaction, jouée à son rang, au même tick que les
 * autres. Elle s'enregistre par {@code ContentRegistrar.registerAction}.
 *
 * <p>Thread : autoritatif, au serveur. Une exception n'interrompt pas la liste ; {@code content.listener_strikes}
 * exceptions de suite désactivent l'action pour la session ({@code E-1012}), comme un écouteur.
 */
@Experimental
@FunctionalInterface
public interface ActionHandler {

    /**
     * Joue l'action.
     *
     * @param assembly l'assembly
     * @param parameters ses paramètres, tels que la definition les donne : objets en {@code Map}, tableaux en
     *     {@code List}, nombres en {@code Double}, textes, booléens ; immuables
     * @param actor le joueur à l'origine de la liste, {@code null} sans (un déclencheur, l'API)
     */
    void perform(Assembly assembly, Map<String, Object> parameters, Player actor);
}
