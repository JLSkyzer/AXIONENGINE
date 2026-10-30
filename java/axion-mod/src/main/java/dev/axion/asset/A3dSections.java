package dev.axion.asset;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32C;

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
 * pas lui-même) ou débordant du fichier —, plutôt que de rendre des octets faux.
 * Une section compressée se lit en lui donnant un {@link Decompressor} : celui du
 * natif (ADR-155, la section {@code ANIM}).
 *
 * <p>Il récrit aussi l'identifiant d'asset de l'en-tête, avec son CRC
 * ({@link #withAssetId}) : c'est ce que le cache, indexé par le seul contenu, demande.
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

    /** Position du champ {@code asset_id} dans l'en-tête. */
    private static final int ASSET_ID_OFFSET = 16;

    /** Position du champ {@code header_crc32c} dans l'en-tête. */
    private static final int HEADER_CRC_OFFSET = 36;

    /** Position de {@code size_uncompressed} dans une entrée de la table. */
    private static final int UNCOMPRESSED_SIZE_OFFSET = 24;

    private A3dSections() {}

    /** Décompresse une trame zstd, celle qu'écrit le compilateur natif. */
    @FunctionalInterface
    public interface Decompressor {

        /**
         * @param frame la trame, telle que la section la stocke
         * @param size la taille que l'entrée de la section déclare au contenu
         * @return le contenu
         * @throws IllegalArgumentException si la trame n'est pas du zstd, ou dépasse {@code size}
         */
        byte[] decompress(byte[] frame, int size);
    }

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
        return section(file, tag, null);
    }

    /**
     * Extrait les octets d'une section, décompressés si elle l'est, ou {@code null} si elle est
     * absente.
     *
     * @param file octets complets d'un conteneur A3D
     * @param tag tag de quatre caractères ASCII
     * @param zstd décompresse une section compressée ; {@code null} : la refuser
     * @return les octets de la section, ou {@code null} si absente
     * @throws IllegalArgumentException si {@code file} n'est pas un A3D lisible, si {@code tag} n'a pas
     *     quatre octets ASCII, ou si la section décompressée n'a pas la taille que son entrée déclare
     * @throws IllegalStateException si la section est compressée et {@code zstd} absent
     */
    public static byte[] section(byte[] file, String tag, Decompressor zstd) {
        byte[] wanted = tagBytes(tag);
        ByteBuffer b = header(file);

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
            if ((flags & FLAG_COMPRESSED) != 0 && zstd == null) {
                throw new IllegalStateException("section " + tag + " compressée : non gérée côté Java");
            }
            if (offset < 0 || size < 0 || offset + size > file.length) {
                throw new IllegalArgumentException("section " + tag + " hors des bornes du fichier");
            }
            byte[] out = new byte[(int) size];
            System.arraycopy(file, (int) offset, out, 0, (int) size);
            if ((flags & FLAG_COMPRESSED) == 0) {
                return out;
            }
            int declared = b.getInt(base + UNCOMPRESSED_SIZE_OFFSET);
            if (declared < 0) {
                throw new IllegalArgumentException("section " + tag + " : taille décompressée " + declared);
            }
            byte[] content = zstd.decompress(out, declared);
            if (content.length != declared) {
                throw new IllegalArgumentException("section " + tag + " : " + content.length
                        + " octets décompressés, " + declared + " déclarés");
            }
            return content;
        }
        return null;
    }

    /**
     * {@return le conteneur {@code file} au nom de l'asset {@code assetId} : lui-même s'il le porte
     * déjà, sinon une copie dont l'en-tête porte {@code assetId} et le CRC qui va avec}
     *
     * <p>La clé du cache ne tient qu'au contenu (C-25), quand l'en-tête porte l'identifiant de
     * l'asset compilé (PARTIE 7), que le natif confronte à celui qu'on lui annonce au chargement
     * ({@code E-2002}). Deux sources aux mêmes octets sous deux chemins partagent donc une entrée
     * qui ne porte le nom que de l'une ; l'autre la reprend sous le sien. Rien d'autre ne change :
     * le compilateur n'écrit l'identifiant que là. Le CRC couvre les 64 octets de l'en-tête, son
     * propre champ annulé, comme {@code header_checksum} le calcule côté natif.
     *
     * @param file octets complets d'un conteneur A3D
     * @param assetId identifiant de l'asset qui le reprend
     * @throws IllegalArgumentException si {@code file} n'est pas un A3D lisible (taille, magic,
     *     version majeure)
     */
    public static byte[] withAssetId(byte[] file, long assetId) {
        if (header(file).getLong(ASSET_ID_OFFSET) == assetId) {
            return file;
        }
        byte[] copy = file.clone();
        ByteBuffer b = ByteBuffer.wrap(copy).order(ByteOrder.LITTLE_ENDIAN);
        b.putLong(ASSET_ID_OFFSET, assetId);
        b.putInt(HEADER_CRC_OFFSET, 0);
        CRC32C crc = new CRC32C();
        crc.update(copy, 0, HEADER_BYTES);
        b.putInt(HEADER_CRC_OFFSET, (int) crc.getValue());
        return copy;
    }

    /**
     * {@return une vue little-endian de {@code file}, dont l'en-tête est lisible}
     *
     * @throws IllegalArgumentException si {@code file} est plus court qu'un en-tête, sans le magic,
     *     ou d'une version majeure inconnue (R-890)
     */
    private static ByteBuffer header(byte[] file) {
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
        return b;
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
