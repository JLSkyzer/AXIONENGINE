package dev.axion.asset;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32C;

/**
 * Cache des assets compilés (C-25).
 *
 * <pre>
 * &lt;gameDir&gt;/axion/cache/&lt;2 hex&gt;/&lt;clé&gt;.a3d
 * &lt;gameDir&gt;/axion/cache/index.bin
 * </pre>
 *
 * <p><strong>Hors du monde</strong> (R-563, INV-10). Un cache rangé dans une
 * sauvegarde la ferait grossir de données reconstructibles, et la rendrait non
 * transportable : le même pack de ressources sur une autre machine recompile,
 * c'est tout ce qu'il en coûte.
 *
 * <p>Chaque entrée porte son magic, sa version de schéma et son CRC32C (R-560).
 * Le fichier `.a3d` qu'elle contient a les siens, mais ils ne couvrent que ce
 * qu'ils décrivent : une entrée tronquée par un disque plein resterait un
 * en-tête A3D valide suivi de rien. L'enveloppe du cache voit cela.
 *
 * <p>Une entrée invalide est <strong>supprimée</strong>, pas réparée : sa
 * source est là, et recompiler coûte moins cher que de deviner ce qui manque.
 */
public final class AssetCache {

    /** Magic d'une entrée de cache : « AXCE ». */
    static final int ENTRY_MAGIC = 0x4158_4345;

    /** Version du schéma d'enveloppe. */
    static final int ENTRY_SCHEMA = 1;

    /** Taille de l'enveloppe d'une entrée, en octets. */
    static final int ENTRY_HEADER_BYTES = 16;

    /** Magic de l'index : « AXCI ». */
    private static final int INDEX_MAGIC = 0x4158_4349;

    /** Version du schéma de l'index. */
    private static final int INDEX_SCHEMA = 1;

    /** Nom du fichier d'index. */
    private static final String INDEX_NAME = "index.bin";

    private final Path root;
    private final long maxBytes;

    /** Entrées connues, de la moins récemment employée à la plus récente. */
    private final Map<String, Long> sizes = new LinkedHashMap<>();

    private final List<String> diagnostics = new ArrayList<>();
    private long totalBytes;

    /**
     * Ouvre le cache, en lisant son index s'il existe.
     *
     * @param root répertoire du cache, sous {@code <gameDir>/axion/}
     * @param maxBytes plafond de taille, {@code assets.cache_max_bytes}
     */
    public AssetCache(Path root, long maxBytes) {
        this.root = root;
        this.maxBytes = maxBytes;
        readIndex();
    }

    /**
     * Cherche un asset compilé.
     *
     * @param key clé de l'asset
     * @return le conteneur A3D, ou {@code null} si l'entrée est absente,
     *     illisible ou corrompue
     */
    public byte[] get(AssetKey key) {
        Path file = pathOf(key);
        if (!Files.isReadable(file)) {
            return null;
        }

        byte[] raw;
        try {
            raw = Files.readAllBytes(file);
        } catch (IOException failure) {
            invalidate(key, "lecture impossible : " + failure);
            return null;
        }

        byte[] payload = unwrap(raw);
        if (payload == null) {
            invalidate(key, "entrée corrompue");
            return null;
        }

        // Employée : elle repasse en queue de la file d'éviction.
        sizes.remove(key.hex());
        sizes.put(key.hex(), (long) raw.length);
        return payload;
    }

    /**
     * Range un asset compilé.
     *
     * <p>Un échec d'écriture n'est pas une erreur du moteur : un disque plein
     * fait perdre le bénéfice du cache, pas l'asset. Il est signalé et la
     * partie continue.
     *
     * @param key clé de l'asset
     * @param payload conteneur A3D compilé
     * @return vrai si l'entrée a été écrite
     */
    public boolean put(AssetKey key, byte[] payload) {
        Path file = pathOf(key);
        byte[] wrapped = wrap(payload);
        try {
            Files.createDirectories(file.getParent());
            // Écriture puis renommage : une entrée n'apparaît que complète, et
            // une coupure de courant au mauvais moment laisse un fichier
            // temporaire plutôt qu'une entrée à moitié écrite.
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(temporary, wrapped);
            Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            diagnostics.add("cache : écriture impossible pour " + key.hex() + " — " + failure);
            return false;
        }

        Long previous = sizes.remove(key.hex());
        if (previous != null) {
            totalBytes -= previous;
        }
        sizes.put(key.hex(), (long) wrapped.length);
        totalBytes += wrapped.length;
        evict();
        return true;
    }

    /**
     * Évince les entrées les moins récemment employées (R-561).
     *
     * <p>Le plus ancien part d'abord. Un cache qui grossirait sans fin finirait
     * par occuper plus de place que les mondes qu'il sert.
     */
    private void evict() {
        while (totalBytes > maxBytes && !sizes.isEmpty()) {
            String oldest = sizes.keySet().iterator().next();
            long size = sizes.remove(oldest);
            totalBytes -= size;
            try {
                Files.deleteIfExists(fileOf(oldest));
            } catch (IOException failure) {
                diagnostics.add("cache : éviction impossible pour " + oldest + " — " + failure);
            }
        }
    }

    private void invalidate(AssetKey key, String reason) {
        // R-560 : supprimée et recompilée. La source est là ; deviner ce qui
        // manque coûterait plus cher que de refaire.
        diagnostics.add("cache : entrée " + key.hex() + " écartée — " + reason);
        Long size = sizes.remove(key.hex());
        if (size != null) {
            totalBytes -= size;
        }
        try {
            Files.deleteIfExists(pathOf(key));
        } catch (IOException failure) {
            diagnostics.add("cache : suppression impossible pour " + key.hex() + " — " + failure);
        }
    }

    /** Enveloppe une charge utile : magic, schéma, longueur, CRC32C. */
    private static byte[] wrap(byte[] payload) {
        CRC32C crc = new CRC32C();
        crc.update(payload);

        ByteBuffer buffer = ByteBuffer.allocate(ENTRY_HEADER_BYTES + payload.length)
                .order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(ENTRY_MAGIC);
        buffer.putInt(ENTRY_SCHEMA);
        buffer.putInt(payload.length);
        buffer.putInt((int) crc.getValue());
        buffer.put(payload);
        return buffer.array();
    }

    /** Rend la charge utile d'une entrée, ou {@code null} si elle est fautive. */
    static byte[] unwrap(byte[] raw) {
        if (raw.length < ENTRY_HEADER_BYTES) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        if (buffer.getInt() != ENTRY_MAGIC || buffer.getInt() != ENTRY_SCHEMA) {
            return null;
        }

        int length = buffer.getInt();
        int expected = buffer.getInt();
        // La longueur annoncée est vérifiée **avant** d'allouer : c'est elle
        // qui dimensionnerait le tableau, et une entrée altérée peut annoncer
        // n'importe quoi.
        if (length < 0 || length != raw.length - ENTRY_HEADER_BYTES) {
            return null;
        }

        byte[] payload = new byte[length];
        buffer.get(payload);

        CRC32C crc = new CRC32C();
        crc.update(payload);
        return (int) crc.getValue() == expected ? payload : null;
    }

    private Path pathOf(AssetKey key) {
        return root.resolve(key.shard()).resolve(key.hex() + ".a3d");
    }

    private Path fileOf(String hex) {
        return root.resolve(hex.substring(0, 2)).resolve(hex + ".a3d");
    }

    /**
     * Écrit l'index sur le disque.
     *
     * <p>L'index n'est pas la vérité : les fichiers le sont. Il évite de
     * parcourir l'arborescence au démarrage, et un index perdu ne coûte qu'un
     * cache qui se remplit de nouveau.
     *
     * @return vrai si l'index a été écrit
     */
    public boolean writeIndex() {
        ByteBuffer buffer = ByteBuffer.allocate(16 + sizes.size() * 40)
                .order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(INDEX_MAGIC);
        buffer.putInt(INDEX_SCHEMA);
        buffer.putInt(sizes.size());
        buffer.putInt(0);
        for (Map.Entry<String, Long> entry : sizes.entrySet()) {
            buffer.put(java.util.HexFormat.of().parseHex(entry.getKey()));
            buffer.putLong(entry.getValue());
        }

        byte[] body = new byte[buffer.position()];
        buffer.flip();
        buffer.get(body);

        try {
            Files.createDirectories(root);
            Files.write(root.resolve(INDEX_NAME), wrap(body));
            return true;
        } catch (IOException failure) {
            diagnostics.add("cache : index non écrit — " + failure);
            return false;
        }
    }

    private void readIndex() {
        Path index = root.resolve(INDEX_NAME);
        if (!Files.isReadable(index)) {
            return;
        }

        byte[] body;
        try {
            body = unwrap(Files.readAllBytes(index));
        } catch (IOException failure) {
            diagnostics.add("cache : index illisible — " + failure);
            return;
        }
        if (body == null || body.length < 16) {
            diagnostics.add("cache : index corrompu, reconstruit à l'usage");
            return;
        }

        ByteBuffer buffer = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN);
        if (buffer.getInt() != INDEX_MAGIC || buffer.getInt() != INDEX_SCHEMA) {
            diagnostics.add("cache : index d'une autre version, reconstruit à l'usage");
            return;
        }

        int count = buffer.getInt();
        buffer.getInt();
        if (count < 0 || body.length != 16 + (long) count * 40) {
            diagnostics.add("cache : index incohérent, reconstruit à l'usage");
            return;
        }

        byte[] key = new byte[32];
        for (int entry = 0; entry < count; entry++) {
            buffer.get(key);
            long size = buffer.getLong();
            sizes.put(java.util.HexFormat.of().formatHex(key), size);
            totalBytes += size;
        }
    }

    /** {@return le nombre d'entrées connues} */
    public int size() {
        return sizes.size();
    }

    /** {@return la taille cumulée des entrées, en octets} */
    public long totalBytes() {
        return totalBytes;
    }

    /** {@return les diagnostics accumulés} */
    public List<String> diagnostics() {
        return List.copyOf(diagnostics);
    }
}
