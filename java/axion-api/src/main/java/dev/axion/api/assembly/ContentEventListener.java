package dev.axion.api.assembly;

import dev.axion.api.Experimental;
import net.minecraft.world.entity.Entity;

/**
 * Reçoit les événements de contenu des assemblies (ADR-174 D2, CAP-02) : ceux qu'une definition déclare dans
 * {@code events} et qu'émettent une action {@code emit_event}, une interaction, ou un mod par
 * {@link Assembly#emit}. Un mod l'enregistre avec
 * {@link dev.axion.api.content.ContentRegistrar#registerContentEventListener}.
 *
 * <p>Au serveur, {@link #onEvent} arrive sur le thread autoritatif, au tick de l'émission. Chez un client,
 * {@link #onClientEvent} arrive sur son thread principal, pour les événements que la definition lui envoie
 * ({@code replicate} : {@code all} aux joueurs qui suivent l'assembly, {@code occupants} à ses occupants) : un son, des
 * particules, un message. Chaque méthode a un défaut. Une exception est journalisée en nommant son mod ; au bout de
 * {@code content.listener_strikes} exceptions de suite, l'écouteur est désactivé pour la session.
 */
@Experimental
public interface ContentEventListener {

    /**
     * Un événement vient d'être émis, au serveur.
     *
     * @param assembly l'assembly qui l'émet
     * @param event son nom dans la definition
     * @param value sa valeur, 1 si l'émission n'en donne pas
     */
    default void onEvent(Assembly assembly, String event, double value) {}

    /**
     * Un événement est arrivé chez ce client.
     *
     * @param assembly l'entité de l'assembly qui l'a émis, dans le niveau du client
     * @param event son nom dans la definition
     * @param value sa valeur
     */
    default void onClientEvent(Entity assembly, String event, double value) {}
}
