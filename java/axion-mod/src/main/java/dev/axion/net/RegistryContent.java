package dev.axion.net;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Ce que porte {@code S2C_Registry} (21.4, ADR-150 D5) : les textes JSON que le serveur a lus, par
 * famille, chacun sous son identifiant de ressource.
 *
 * <p>Trois familles en M4 : les matériaux physiques, les groupes de collision et les definitions — ce
 * dont une definition a besoin pour se valider chez le client comme chez le serveur. Les sources de
 * dégâts (M6) et les profils d'usure (M9) viendront avec leurs systèmes.
 *
 * <p>Les empreintes (21.3) se calculent sur les textes, triés par identifiant : deux serveurs qui ont
 * lu les mêmes fichiers annoncent la même empreinte, quel que soit l'ordre de leur lecture. Celle des
 * definitions couvre aussi les groupes de collision, qui les paramètrent.
 */
public final class RegistryContent {

    /** Une famille du registre, et le préfixe de ses identifiants de ressource. */
    public enum Family {
        /** Matériaux physiques (DM-07, ADR-145). */
        PHYSICS_MATERIALS(1, "axion/physics_materials/"),
        /** Groupes de collision tiers (ADR-132 A1). */
        COLLISION_GROUPS(2, "axion/collision_groups/"),
        /** Definitions (C-27). */
        DEFINITIONS(3, "axion/definitions/");

        final int code;
        final String prefix;

        Family(int code, String prefix) {
            this.code = code;
            this.prefix = prefix;
        }

        static Family of(int code) throws MalformedPacketException {
            for (Family family : values()) {
                if (family.code == code) {
                    return family;
                }
            }
            throw new MalformedPacketException("famille de registre inconnue : " + code);
        }
    }

    /** Nombre maximal d'entrées d'un registre. */
    static final int MAX_ENTRIES = 16_384;

    /** Taille maximale d'un identifiant, en octets UTF-8. */
    static final int MAX_PATH_BYTES = 256;

    /** Taille maximale d'un fichier du registre, en octets. */
    static final int MAX_FILE_BYTES = 1 << 20;

    /** Un identifiant de ressource : espace de noms, puis chemin, au jeu de caractères de Minecraft. */
    private static final Pattern PATH = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+[.]json");

    /** L'empreinte d'un registre vide : la base de FNV-1a 64. */
    public static final long EMPTY_DIGEST = 0xCBF29CE484222325L;

    private static final long FNV_PRIME = 0x100000001B3L;

    private final Map<Family, SortedMap<String, byte[]>> files;

    private RegistryContent(Map<Family, SortedMap<String, byte[]>> files) {
        this.files = files;
    }

    /**
     * @param materials matériaux physiques, par identifiant de ressource
     * @param groups groupes de collision, par identifiant de ressource
     * @param definitions definitions, par identifiant de ressource
     * @return le registre
     * @throws IllegalArgumentException si un identifiant n'est pas celui d'un fichier de sa famille :
     *     c'est une faute de l'émetteur
     */
    public static RegistryContent of(
            Map<String, byte[]> materials, Map<String, byte[]> groups, Map<String, byte[]> definitions) {
        Map<Family, SortedMap<String, byte[]>> files = new EnumMap<>(Family.class);
        files.put(Family.PHYSICS_MATERIALS, family(Family.PHYSICS_MATERIALS, materials));
        files.put(Family.COLLISION_GROUPS, family(Family.COLLISION_GROUPS, groups));
        files.put(Family.DEFINITIONS, family(Family.DEFINITIONS, definitions));
        return new RegistryContent(files);
    }

    private static SortedMap<String, byte[]> family(Family family, Map<String, byte[]> entries) {
        SortedMap<String, byte[]> sorted = new TreeMap<>();
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            if (!belongs(family, entry.getKey())) {
                throw new IllegalArgumentException("identifiant hors de sa famille " + family + " : "
                        + entry.getKey());
            }
            sorted.put(entry.getKey(), entry.getValue().clone());
        }
        return sorted;
    }

    private static boolean belongs(Family family, String path) {
        int colon = path.indexOf(':');
        return PATH.matcher(path).matches()
                && !path.contains("..")
                && path.startsWith(family.prefix, colon + 1)
                && path.getBytes(StandardCharsets.UTF_8).length <= MAX_PATH_BYTES;
    }

    /**
     * @param family famille
     * @return ses fichiers, triés par identifiant, en lecture seule
     */
    public SortedMap<String, byte[]> files(Family family) {
        return Collections.unmodifiableSortedMap(files.get(family));
    }

    /** {@return l'empreinte des definitions et des groupes de collision (21.3 {@code definitions_hash})} */
    public long definitionsHash() {
        return digest(EMPTY_DIGEST, Family.COLLISION_GROUPS, Family.DEFINITIONS);
    }

    /** {@return l'empreinte des matériaux physiques (21.3 {@code materials_hash})} */
    public long materialsHash() {
        return digest(EMPTY_DIGEST, Family.PHYSICS_MATERIALS);
    }

    private long digest(long seed, Family... families) {
        long hash = seed;
        for (Family family : families) {
            // Le chemin porte le préfixe de sa famille : il suffit à les distinguer. La longueur du
            // texte sépare les entrées : sans elle, deux découpages du même flux se confondraient.
            for (Map.Entry<String, byte[]> entry : files.get(family).entrySet()) {
                byte[] path = entry.getKey().getBytes(StandardCharsets.UTF_8);
                hash = fnv(hash, path);
                hash = fnv(hash, (byte) 0);
                int length = entry.getValue().length;
                for (int shift = 24; shift >= 0; shift -= 8) {
                    hash = fnv(hash, (byte) (length >>> shift));
                }
                hash = fnv(hash, entry.getValue());
            }
        }
        return hash;
    }

    private static long fnv(long hash, byte value) {
        return (hash ^ (value & 0xFF)) * FNV_PRIME;
    }

    private static long fnv(long hash, byte[] values) {
        long out = hash;
        for (byte value : values) {
            out = fnv(out, value);
        }
        return out;
    }

    /** {@return le registre codé, avant compression} */
    byte[] encode() {
        int count = files.values().stream().mapToInt(Map::size).sum();
        WireWriter writer = new WireWriter(1024).varUInt(count);
        for (Family family : Family.values()) {
            for (Map.Entry<String, byte[]> entry : files.get(family).entrySet()) {
                writer.u8(family.code).utf8(entry.getKey()).bytes(entry.getValue());
            }
        }
        return writer.toByteArray();
    }

    /**
     * @param plain registre codé, décompressé
     * @return le registre lu
     * @throws MalformedPacketException si une borne est dépassée, un identifiant mal formé ou hors de
     *     sa famille, ou une entrée en double
     */
    static RegistryContent decode(byte[] plain) throws MalformedPacketException {
        WireReader reader = new WireReader(plain);
        int count = reader.varUInt();
        if (count > MAX_ENTRIES) {
            throw new MalformedPacketException("registre de " + count + " entrées, au-delà de " + MAX_ENTRIES);
        }
        Map<Family, SortedMap<String, byte[]>> files = new EnumMap<>(Family.class);
        for (Family family : Family.values()) {
            files.put(family, new TreeMap<>());
        }
        for (int index = 0; index < count; index++) {
            Family family = Family.of(reader.u8());
            String path = reader.utf8(MAX_PATH_BYTES);
            if (!belongs(family, path)) {
                throw new MalformedPacketException("identifiant de registre invalide pour " + family + " : " + path);
            }
            byte[] json = reader.bytes(MAX_FILE_BYTES);
            if (files.get(family).put(path, json) != null) {
                throw new MalformedPacketException("entrée de registre en double : " + path);
            }
        }
        reader.expectEnd();
        return new RegistryContent(files);
    }
}
