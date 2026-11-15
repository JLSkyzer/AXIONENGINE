package dev.axion.attach;

import java.util.UUID;

/**
 * Une attache telle que l'assembly d'où elle part la garde (ADR-171 D7, R-692) : de quoi la rétablir au chargement,
 * même si l'autre extrémité ne l'est pas encore — elle reste alors suspendue, jamais supprimée.
 *
 * @param socket le socket de cette assembly
 * @param other l'autre assembly, {@code null} pour un ancrage au monde
 * @param otherSocket son socket, {@code null} pour un ancrage
 * @param anchor le bloc d'ancrage {@code {x, y, z}}, {@code null} entre deux assemblies
 * @param kind le type
 * @param length la longueur d'une corde ou d'un treuil au dernier tick, en blocs ; {@code NaN} sans
 * @param owner le mod qui l'a posée
 */
public record AttachmentRecord(String socket, UUID other, String otherSocket, int[] anchor, String kind,
        double length, String owner) {

    /** Au plus autant d'attaches gardées par assembly : ses sockets (ADR-171 D2). */
    public static final int MAX = 32;

    /** {@return vrai si l'attache ancre au monde} */
    public boolean anchored() {
        return other == null;
    }

    /**
     * {@return la même attache, à une autre longueur}
     *
     * @param newLength la longueur, en blocs
     */
    public AttachmentRecord withLength(double newLength) {
        return new AttachmentRecord(socket, other, otherSocket, anchor, kind, newLength, owner);
    }
}
