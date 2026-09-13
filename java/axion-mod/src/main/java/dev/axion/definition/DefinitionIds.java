package dev.axion.definition;

import java.nio.charset.StandardCharsets;

/**
 * Identifiant 64 bits d'une definition.
 *
 * <p>Le NBT d'une assembly ne porte pas le nom de sa definition mais un
 * {@code long} (PARTIE 22.2, {@code axion:def}). C'est l'empreinte FNV-1a 64
 * bits de l'identifiant {@code <ns>:<chemin>}, la même que celle des
 * identifiants d'asset (DM-01) et des noms de node, calculée en Rust par
 * {@code ax_model::dm::scene::name_hash}. Deux implémentations, une seule
 * définition : les vecteurs de référence de FNV-1a les tiennent alignées.
 */
public final class DefinitionIds {

    private static final long OFFSET_BASIS = 0xcbf2_9ce4_8422_2325L;
    private static final long PRIME = 0x0000_0100_0000_01b3L;

    private DefinitionIds() {}

    /**
     * {@return l'empreinte FNV-1a 64 bits d'un identifiant, sur ses octets UTF-8}
     *
     * @param id identifiant de definition
     */
    public static long hash(String id) {
        long hash = OFFSET_BASIS;
        for (byte value : id.getBytes(StandardCharsets.UTF_8)) {
            hash ^= value & 0xFF;
            hash *= PRIME;
        }
        return hash;
    }
}
