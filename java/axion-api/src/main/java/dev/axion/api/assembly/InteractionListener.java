package dev.axion.api.assembly;

import dev.axion.api.Experimental;
import net.minecraft.world.entity.player.Player;

/**
 * Suit les interactions des assemblies au serveur, et peut en refuser (ADR-174 D1, CAP-11) : le clic d'un joueur sur un
 * node qui porte une interaction de la definition. Un mod l'enregistre avec
 * {@link dev.axion.api.content.ContentRegistrar#registerInteractionListener} ; AXION l'appelle sur le thread autoritatif,
 * dans l'ordre des mods puis de leur enregistrement.
 *
 * <p>Chaque méthode a un défaut. Le premier refus l'emporte : rien de l'interaction n'a lieu, et le joueur lit sa
 * raison, une clé de traduction, dans la barre d'action. Une exception est journalisée en nommant son mod et ne refuse
 * rien ; au bout de {@code content.listener_strikes} exceptions de suite, l'écouteur est désactivé pour la session.
 */
@Experimental
public interface InteractionListener {

    /**
     * Juge une interaction avant qu'elle ait lieu.
     *
     * @param assembly l'assembly
     * @param node le nom du node qui porte l'interaction
     * @param player le joueur qui clique
     * @param action l'action de l'interaction, son nom dans la definition ({@code set_variable}, {@code actions}…)
     * @return {@code null} pour la laisser faire, sinon la clé de traduction de la raison du refus
     */
    default String allow(Assembly assembly, String node, Player player, String action) {
        return null;
    }

    /**
     * Une interaction vient d'avoir son effet.
     *
     * @param assembly l'assembly
     * @param node le nom du node qui la porte
     * @param player le joueur
     * @param action l'action de l'interaction
     */
    default void interacted(Assembly assembly, String node, Player player, String action) {}
}
