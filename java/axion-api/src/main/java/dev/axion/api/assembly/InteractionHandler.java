package dev.axion.api.assembly;

import dev.axion.api.Experimental;
import java.util.Map;
import net.minecraft.world.entity.player.Player;

/**
 * Une interaction qu'un mod ajoute à la liste fermée d'une definition (ADR-174 D6, CAP-38) : une entrée
 * {@code interaction} dont l'{@code action} est {@code <modId>:<nom>}, ou {@code open_container}, qu'AXION délègue à
 * l'interaction de ce nom qu'un mod enregistre. Elle s'enregistre par {@code ContentRegistrar.registerInteraction}.
 *
 * <p>Thread : autoritatif, au serveur, après que les {@link InteractionListener} l'ont laissée faire. Une exception
 * vaut « sans effet » ; {@code content.listener_strikes} exceptions de suite la désactivent pour la session
 * ({@code E-1012}).
 */
@Experimental
@FunctionalInterface
public interface InteractionHandler {

    /**
     * Joue l'interaction.
     *
     * @param assembly l'assembly
     * @param node le nom du node qui la porte
     * @param player le joueur qui clique
     * @param parameters ses {@code parameters}, tels que la definition les donne ; immuables
     * @return vrai si elle a eu son effet : la liste {@code actions} de l'interaction la suit alors
     */
    boolean interact(Assembly assembly, String node, Player player, Map<String, Object> parameters);
}
