package dev.axion.api.assembly;

import dev.axion.api.Experimental;
import net.minecraft.world.entity.Entity;

/**
 * Suit, au serveur, ce qui monte sur une assembly et en descend (ADR-178, CAP-14) : un joueur ou une entité posés sur un de ses
 * corps, qu'elle porte. Un mod l'enregistre avec {@link dev.axion.api.content.ContentRegistrar#registerCarryListener} ; AXION
 * l'appelle sur le thread autoritatif, dans l'ordre des mods puis de leur enregistrement.
 *
 * <p>Chaque méthode a un défaut : un écouteur ne redéfinit que ce qui l'intéresse. Une exception est journalisée en nommant son
 * mod ; au bout de {@code content.listener_strikes} exceptions de suite, l'écouteur est désactivé pour la session.
 */
@Experimental
public interface CarryListener {

    /**
     * Une entité vient de se poser sur l'assembly, qui la porte.
     *
     * @param assembly l'assembly
     * @param entity l'entité
     */
    default void carried(Assembly assembly, Entity entity) {}

    /**
     * L'assembly ne porte plus cette entité : elle en est descendue, a sauté, s'est posée ailleurs, ou l'assembly disparaît.
     *
     * @param assembly l'assembly
     * @param entity l'entité
     */
    default void dropped(Assembly assembly, Entity entity) {}
}
