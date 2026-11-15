package dev.axion.api.attach;

import dev.axion.api.Experimental;
import dev.axion.api.assembly.Assembly;
import java.util.Optional;

/**
 * Suit les attaches du serveur, et peut en refuser (ADR-171 D4, CAP-16). Un mod l'enregistre avec
 * {@link dev.axion.api.content.ContentRegistrar#registerAttachmentListener} ; AXION l'appelle sur le thread autoritatif,
 * pour les attaches que pose un joueur comme pour celles d'un autre mod.
 *
 * <p>Chaque méthode a un défaut : un écouteur ne redéfinit que ce qui l'intéresse. Une exception d'un écouteur est
 * journalisée en nommant son mod ; elle ne refuse rien.
 */
@Experimental
public interface AttachmentListener {

    /**
     * Juge une attache avant qu'elle soit posée.
     *
     * @param assembly l'assembly d'où part l'attache
     * @param socket son socket
     * @param other l'autre assembly, vide pour un ancrage au monde
     * @param otherSocket son socket, {@code null} pour un ancrage
     * @param kind le type retenu
     * @return faux pour la refuser
     */
    default boolean allow(Assembly assembly, String socket, Optional<Assembly> other, String otherSocket, String kind) {
        return true;
    }

    /**
     * Une attache vient d'être posée.
     *
     * @param assembly l'assembly d'où elle part
     * @param socket son socket
     * @param other l'autre assembly, vide pour un ancrage
     * @param otherSocket son socket, {@code null} pour un ancrage
     * @param kind son type
     */
    default void attached(Assembly assembly, String socket, Optional<Assembly> other, String otherSocket, String kind) {}

    /**
     * Une attache vient d'être défaite.
     *
     * @param assembly l'assembly d'où elle partait
     * @param socket son socket
     * @param other l'autre assembly, vide pour un ancrage ou si elle a disparu
     * @param otherSocket son socket, {@code null} pour un ancrage
     * @param cause {@code detached} (un geste ou l'API), {@code broken} (au-delà de sa force), {@code misaligned}
     *     (désalignée trop longtemps), {@code magnet_off} (aimant éteint), {@code removed} (une extrémité a disparu)
     */
    default void detached(Assembly assembly, String socket, Optional<Assembly> other, String otherSocket,
            String cause) {}
}
