package dev.axion.net;

/**
 * Compression des charges utiles réseau (C-51, ADR-150 D5) : en jeu, le zstd du natif
 * ({@link NativeNetCompression}).
 */
public interface NetCompression {

    /**
     * @param payload charge utile à compresser
     * @return une trame zstd
     * @throws IllegalStateException si la compression est refusée : c'est une faute de l'émetteur,
     *     pas une donnée reçue
     */
    byte[] compress(byte[] payload);

    /**
     * Décompresse une trame venue du réseau, sans jamais dépasser {@code maxBytes} (R-1691).
     *
     * @param frame trame zstd reçue
     * @param maxBytes taille maximale admise du contenu
     * @return le contenu
     * @throws MalformedPacketException si ce n'est pas une trame zstd, si elle déclare plus que la
     *     borne, ou si elle ment sur sa taille
     */
    byte[] decompress(byte[] frame, int maxBytes) throws MalformedPacketException;
}
