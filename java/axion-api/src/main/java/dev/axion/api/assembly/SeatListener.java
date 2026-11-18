package dev.axion.api.assembly;

import dev.axion.api.Experimental;
import net.minecraft.world.entity.Entity;

/**
 * Suit les sièges des assemblies au serveur, et peut refuser qu'on y monte ou qu'on en descende (ADR-174 D1, CAP-11).
 * Un mod l'enregistre avec {@link dev.axion.api.content.ContentRegistrar#registerSeatListener} ; AXION l'appelle sur le
 * thread autoritatif, dans l'ordre des mods puis de leur enregistrement.
 *
 * <p>Chaque méthode a un défaut : un écouteur ne redéfinit que ce qui l'intéresse. Le premier refus l'emporte ; le
 * joueur lit sa raison, une clé de traduction, dans la barre d'action. Une exception est journalisée en nommant son mod
 * et ne refuse rien ; au bout de {@code content.listener_strikes} exceptions de suite, l'écouteur est désactivé pour la
 * session. Une éjection — un siège qui disparaît, la mort du passager — ne se refuse pas (R-712).
 */
@Experimental
public interface SeatListener {

    /**
     * Juge une montée, avant qu'elle ait lieu : un joueur qui clique sur un siège, ou un mod qui assoit une entité.
     *
     * @param assembly l'assembly
     * @param seat le nom du siège
     * @param passenger l'entité qui monte
     * @return {@code null} pour la laisser faire, sinon la clé de traduction de la raison du refus
     */
    default String allowEnter(Assembly assembly, String seat, Entity passenger) {
        return null;
    }

    /**
     * Juge une descente voulue par le passager, avant qu'elle ait lieu.
     *
     * @param assembly l'assembly
     * @param seat le nom du siège
     * @param passenger l'entité qui descend
     * @return {@code null} pour la laisser faire, sinon la clé de traduction de la raison du refus
     */
    default String allowExit(Assembly assembly, String seat, Entity passenger) {
        return null;
    }

    /**
     * Une entité vient de s'asseoir.
     *
     * @param assembly l'assembly
     * @param seat le nom du siège
     * @param passenger l'entité
     */
    default void entered(Assembly assembly, String seat, Entity passenger) {}

    /**
     * Une entité vient de quitter son siège.
     *
     * @param assembly l'assembly
     * @param seat le nom du siège qu'elle occupait
     * @param passenger l'entité
     * @param cause {@code dismounted} (elle est descendue) ou {@code ejected} (AXION l'a fait descendre, R-712)
     */
    default void exited(Assembly assembly, String seat, Entity passenger, String cause) {}
}
