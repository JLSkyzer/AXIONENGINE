package dev.axion.asset;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Clé de cache d'un asset (C-20 étape 2, C-25).
 *
 * <pre>
 * clé = sha256(contenu || options || COMPILER_VERSION || ABI)
 * </pre>
 *
 * <p>Elle répond à une seule question : <em>ce fichier, compilé par ce
 * compilateur avec ces options, pour cette version d'ABI, a-t-il déjà été
 * compilé ?</em> Les quatre termes comptent. Sans le contenu, une source
 * modifiée passerait pour à jour ; sans les options, deux compilations
 * différentes se partageraient une entrée ; sans la version du compilateur, une
 * correction de C-21 n'atteindrait jamais les assets déjà compilés (R-562,
 * R-892) ; sans l'ABI, un asset resterait en cache après une mise à jour de la
 * frontière qui en change la lecture.
 *
 * <p>La fiche C-20 n'énumère que les trois premiers termes, la fiche C-25 les
 * quatre. C'est la seconde qui fait foi : c'est elle qui spécifie le cache, et
 * un terme en moins produit une entrée reprise à tort.
 *
 * <p>SHA-256 et non une empreinte rapide : une collision ici ne produit pas une
 * erreur, elle produit <strong>le mauvais asset</strong>, silencieusement.
 * C'est le seul endroit du moteur où ce coût se justifie.
 */
public final class AssetKey {

    /** Nom de l'algorithme, tel que {@link MessageDigest} l'attend. */
    private static final String ALGORITHM = "SHA-256";

    /** Séparateur entre les termes concaténés. */
    private static final byte[] SEPARATOR = {0};

    private final String hex;

    private AssetKey(String hex) {
        this.hex = hex;
    }

    /**
     * Calcule la clé d'une source.
     *
     * @param content contenu du fichier source
     * @param options options de compilation, telles qu'elles seront transmises
     * @param compilerVersion version du compilateur natif
     * @param abiVersion version de l'ABI attendue
     * @return la clé, en hexadécimal minuscule
     */
    public static AssetKey of(
            byte[] content, String options, int compilerVersion, int abiVersion) {
        MessageDigest digest = digest();
        digest.update(content);
        digest.update(SEPARATOR);
        digest.update(options.getBytes(StandardCharsets.UTF_8));
        digest.update(SEPARATOR);
        digest.update(intBytes(compilerVersion));
        digest.update(SEPARATOR);
        digest.update(intBytes(abiVersion));
        return new AssetKey(HexFormat.of().formatHex(digest.digest()));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance(ALGORITHM);
        } catch (NoSuchAlgorithmException impossible) {
            // SHA-256 fait partie des algorithmes que toute plateforme Java
            // doit fournir. Son absence signalerait une JVM qui n'en est pas
            // une, et rien de sensé ne peut être tenté à partir de là.
            throw new IllegalStateException(ALGORITHM + " indisponible", impossible);
        }
    }

    private static byte[] intBytes(int value) {
        return new byte[] {
            (byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value
        };
    }

    /** {@return la clé en hexadécimal minuscule, soixante-quatre caractères} */
    public String hex() {
        return hex;
    }

    /**
     * {@return les deux premiers caractères de la clé}
     *
     * <p>C'est le nom du sous-répertoire de cache : {@code
     * <gameDir>/axion/cache/<2 hex>/<clé>.a3d}. Répartir les entrées évite un
     * répertoire de plusieurs milliers de fichiers, que certains systèmes de
     * fichiers traversent mal.
     */
    public String shard() {
        return hex.substring(0, 2);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof AssetKey key && hex.equals(key.hex);
    }

    @Override
    public int hashCode() {
        return hex.hashCode();
    }

    @Override
    public String toString() {
        return hex;
    }
}
