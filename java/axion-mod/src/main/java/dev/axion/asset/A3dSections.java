package dev.axion.asset;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Localisateur de sections dans un conteneur A3D (C-24, PARTIE 7).
 *
 * <p>Java détient les A3D compilés (registre d'assets) et, pour créer un corps
 * physique (C-32, {@code CREATE_ASSEMBLY}, Option A), doit en extraire les octets
 * de la section {@code PHYS} pour les joindre à la commande {@code SIM_IN}. Ce
 * lecteur ne parcourt que l'en-tête (64 o) et la table des sections (32 o par
 * entrée) — la disposition figée est côté natif ({@code crates/ax-asset/src/a3d},
 * {@code write.rs}/{@code mod.rs}) et little-endian (R-271).
 *
 * <p>Il ne revérifie pas les CRC : les octets viennent du compilateur natif ou du
 * cache, et le natif les revalide au chargement (R-882, C-22). Il refuse en
 * revanche ce qu'il ne peut pas rendre correctement — magic absent, version
 * majeure inconnue (R-890), section compressée ({@code zstd}, que Java ne décode
 * pas) ou débordant du fichier —, plutôt que de rendre des octets faux.
 */
public final class A3dSections {

    /** Les quatre octets de tête d'un fichier A3D ({@code "A3D\0"}). */
    private static final byte[] MAGIC = {'A', '3', 'D', 0};

    /** Taille de l'en-tête, en octets. */
    private static final int HEADER_BYTES = 64;

    /** Taille d'une entrée de la table des sections, en octets. */
    private static final int ENTRY_BYTES = 32;

    /** Seule version majeure du format reconnue (R-890). */
    private static final int VERSION_MAJOR = 1;

    /** Drapeau d'entrée : la charge utile est compressée en zstd. */
    private static final int FLAG_COMPRESSED = 1;

    /** Position du champ {@code section_count} dans l'en-tête. */
    private static final int SECTION_COUNT_OFFSET = 12;

    private A3dSections() {}

    /**
     * Extrait les octets stockés d'une section, ou {@code null} si elle est
     * absente.
     *
     * <p>Une section absente n'est pas une erreur : un asset sans collider n'a pas
     * de section {@code PHYS}, et l'appelant y répond en ne créant pas de corps.
     *
     * @param file octets complets d'un conteneur A3D
     * @param tag tag de quatre caractères ASCII (p. ex. {@code "PHYS"})
     * @return les octets décompressés de la section, ou {@code null} si absente
     * @throws IllegalArgumentException si {@code file} n'est pas un A3D lisible
     *     (magic, version, bornes) ou si {@code tag} n'a pas quatre octets ASCII
     * @throws IllegalStateException si la section existe mais est compressée
     */
    public static byte[] section(byte[] file, String tag) {
        byte[] wanted = tagBytes(tag);
        if (file.length < HEADER_BYTES) {
            throw new IllegalArgumentException("fichier A3D tronqué : " + file.length + " octets");
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (file[i] != MAGIC[i]) {
                throw new IllegalArgumentException("ce n'est pas un fichier A3D");
            }
        }
        ByteBuffer b = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
        int major = b.getShort(4) & 0xFFFF;
        if (major != VERSION_MAJOR) {
            throw new IllegalArgumentException("version majeure A3D " + major + " inconnue");
        }

        long count = b.getInt(SECTION_COUNT_OFFSET) & 0xFFFF_FFFFL;
        long tableEnd = (long) HEADER_BYTES + count * ENTRY_BYTES;
        if (tableEnd > file.length) {
            throw new IllegalArgumentException("table des sections hors des bornes du fichier");
        }

        for (long i = 0; i < count; i++) {
            int base = HEADER_BYTES + (int) (i * ENTRY_BYTES);
            if (!matches(file, base, wanted)) {
                continue;
            }
            int flags = b.getInt(base + 4);
            long offset = b.getLong(base + 8);
            long size = b.getLong(base + 16);
            if ((flags & FLAG_COMPRESSED) != 0) {
                throw new IllegalStateException("section " + tag + " compressée : non gérée côté Java");
            }
            if (offset < 0 || size < 0 || offset + size > file.length) {
                throw new IllegalArgumentException("section " + tag + " hors des bornes du fichier");
            }
            byte[] out = new byte[(int) size];
            System.arraycopy(file, (int) offset, out, 0, (int) size);
            return out;
        }
        return null;
    }

    /** {@return les quatre octets ASCII d'un tag} */
    private static byte[] tagBytes(String tag) {
        if (tag.length() != 4) {
            throw new IllegalArgumentException("un tag A3D fait quatre caractères : " + tag);
        }
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            char c = tag.charAt(i);
            if (c > 0x7F) {
                throw new IllegalArgumentException("un tag A3D est en ASCII : " + tag);
            }
            bytes[i] = (byte) c;
        }
        return bytes;
    }

    /** {@return vrai si les quatre octets à {@code base} sont ceux de {@code wanted}} */
    private static boolean matches(byte[] file, int base, byte[] wanted) {
        for (int i = 0; i < 4; i++) {
            if (file[base + i] != wanted[i]) {
                return false;
            }
        }
        return true;
    }
}
