package dev.axion.api.attach;

import dev.axion.api.Experimental;
import dev.axion.api.assembly.Assembly;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Les attaches d'une assembly (ADR-171 D4) : celles de ses sockets, qu'elle soit d'un côté ou de l'autre.
 *
 * <p>Thread : les méthodes qui posent, défont ou pilotent ne s'appellent que sur le thread autoritatif du serveur, et
 * lèvent {@link IllegalStateException} ailleurs (R-1755) ; {@link #list()} se lit sur le serveur.
 */
@Experimental
public interface Attachments {

    /**
     * Attache un socket de cette assembly à un socket d'une autre, du premier type que les deux acceptent, dans l'ordre
     * {@code rigid}, {@code hitch}, {@code rope}, {@code winch}, {@code magnet}.
     *
     * @param socket le socket de cette assembly, d'où part l'attache : ses paramètres l'emportent (ADR-171 Q5)
     * @param other l'autre assembly, dans la même dimension
     * @param otherSocket son socket
     * @return le résultat ; l'attache existe dès le tick suivant
     */
    default AttachResult attach(String socket, Assembly other, String otherSocket) {
        return attach(socket, other, otherSocket, null);
    }

    /**
     * Attache un socket de cette assembly à un socket d'une autre, d'un type imposé.
     *
     * @param socket le socket de cette assembly
     * @param other l'autre assembly
     * @param otherSocket son socket
     * @param kind le type, que les deux sockets acceptent ; {@code null} : le premier qu'ils ont en commun
     * @return le résultat
     */
    AttachResult attach(String socket, Assembly other, String otherSocket, String kind);

    /**
     * Ancre un socket au bloc {@code (x, y, z)} de la dimension de l'assembly ; le socket déclare {@code anchor}.
     *
     * @param socket le socket
     * @param x coordonnée du bloc
     * @param y coordonnée du bloc
     * @param z coordonnée du bloc
     * @return le résultat
     */
    AttachResult anchor(String socket, int x, int y, int z);

    /**
     * Défait l'attache d'un socket.
     *
     * @param socket le socket
     * @return vrai si une attache le tenait
     */
    boolean detach(String socket);

    /** {@return les attaches de cette assembly, dans l'ordre où elles ont été posées} */
    List<Attachment> list();

    /**
     * La longueur cible du treuil d'un socket, rejointe à sa vitesse, dans ses bornes (ADR-171 D5). Elle vaut jusqu'à ce
     * que la variable de commande du treuil, s'il en a une, change.
     *
     * @param socket le socket, attaché en {@code winch}
     * @param length la longueur, en blocs
     * @return vrai si le socket tient un treuil
     */
    boolean setWinchLength(String socket, double length);

    /**
     * Une attache, telle que le serveur la voit au dernier tick.
     *
     * @param socket le socket de cette assembly
     * @param other l'autre assembly, vide pour un ancrage au monde
     * @param otherSocket son socket, {@code null} pour un ancrage
     * @param anchor le bloc d'ancrage {@code {x, y, z}}, vide entre deux assemblies
     * @param kind le type
     * @param length la longueur d'une corde ou d'un treuil, en blocs ; 0 sinon
     * @param distance l'écart entre les deux ancrages, en blocs
     * @param tension la force qu'elle transmet, en newtons
     * @param owner le mod qui l'a posée, {@code axion} pour un geste du joueur
     */
    record Attachment(String socket, Optional<UUID> other, String otherSocket, Optional<int[]> anchor, String kind,
            double length, double distance, double tension, String owner) {}
}
