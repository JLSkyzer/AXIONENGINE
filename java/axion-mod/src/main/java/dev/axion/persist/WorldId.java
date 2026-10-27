package dev.axion.persist;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * L'identifiant d'un monde pour son journal latéral (R-1743, ADR-153 D6) : les 16 premiers chiffres
 * hexadécimaux du SHA-256 du chemin canonique du niveau et de sa graine.
 *
 * <p>Stable tant que le monde garde son dossier et sa graine. Un monde supprimé puis recréé au même
 * chemin avec la même graine retrouve le même identifiant, donc le même journal : c'est la limite de la
 * définition du cahier des charges.
 */
public final class WorldId {

    private WorldId() {}

    /**
     * {@return l'identifiant d'un monde}
     *
     * @param canonicalPath chemin canonique du dossier du niveau
     * @param seed graine du monde
     */
    public static String of(String canonicalPath, long seed) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(canonicalPath.getBytes(StandardCharsets.UTF_8));
            sha.update((byte) 0);
            sha.update(Long.toString(seed).getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(sha.digest(), 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 est garanti par la plateforme Java", e);
        }
    }
}
