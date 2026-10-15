package dev.axion.asset;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
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

    /** Sous-répertoire d'une entrée : les deux premiers chiffres de sa clé. */
    private static final Pattern SHARD = Pattern.compile("[0-9a-f]{2}");

    /** Fichier d'une entrée : sa clé SHA-256 en hexadécimal. */
    private static final Pattern ENTRY_FILE = Pattern.compile("[0-9a-f]{64}\\.a3d");

    /** Fichier temporaire d'une écriture interrompue. */
    private static final Pattern TEMPORARY = Pattern.compile("[0-9a-f]{64}\\.a3d\\.tmp");

    private final Path root;
    private final long maxBytes;
    private final Observer observer;
    private final LongSupplier clock;

    /** Entrées connues, de la moins récemment employée à la plus récente. */
    private final Map<String, Long> sizes = new LinkedHashMap<>();

    private final List<String> diagnostics = new ArrayList<>();
    private long totalBytes;

    /**
     * Ce que le cache rapporte de son activité (R-500) : opérations, erreurs, mémoire et durée
     * des accès au disque.
     *
     * <p>Le cache n'en connaît pas le destinataire : le runtime les publie dans les métriques
     * exportées ({@code dev.axion.diag}). Chaque méthode est appelée sur le thread de
     * l'opération qu'elle rapporte.
     */
    public interface Observer {

        /** Aucun destinataire : rien n'est rapporté. */
        Observer NONE = new Observer() {};

        /** Une entrée valide a été lue. */
        default void hit() {}

        /** Une lecture n'a rien rendu : entrée absente, illisible ou corrompue. */
        default void miss() {}

        /** Une entrée a été écrite. */
        default void written() {}

        /** Une entrée a été évincée par le plafond (R-561). */
        default void evicted() {}

        /** Une entrée illisible ou corrompue a été écartée (R-560). */
        default void invalidated() {}

        /** Une opération sur le disque a échoué ; le cache continue sans elle. */
        default void failed() {}

        /**
         * Temps passé à lire ou à écrire une entrée.
         *
         * @param nanos durée, en nanosecondes
         */
        default void diskNanos(long nanos) {}

        /**
         * Occupation après une opération.
         *
         * @param entries nombre d'entrées connues
         * @param bytes octets qu'elles occupent sur le disque
         */
        default void occupancy(int entries, long bytes) {}
    }

    /**
     * Ouvre le cache : lit son index s'il existe, l'accorde aux fichiers, puis
     * applique le plafond.
     *
     * @param root répertoire du cache, sous {@code <gameDir>/axion/}
     * @param maxBytes plafond de taille, {@code assets.cache_max_bytes}
     */
    public AssetCache(Path root, long maxBytes) {
        this(root, maxBytes, Observer.NONE, System::nanoTime);
    }

    /**
     * Ouvre le cache, en rapportant son activité.
     *
     * @param root répertoire du cache, sous {@code <gameDir>/axion/}
     * @param maxBytes plafond de taille, {@code assets.cache_max_bytes}
     * @param observer destinataire de l'activité du cache
     * @param clock horloge en nanosecondes, pour la durée des accès au disque
     */
    public AssetCache(Path root, long maxBytes, Observer observer, LongSupplier clock) {
        this.root = root;
        this.maxBytes = maxBytes;
        this.observer = observer;
        this.clock = clock;
        readIndex();
        reconcile();
        evict();
        publishOccupancy();
    }

    private void publishOccupancy() {
        observer.occupancy(sizes.size(), totalBytes);
    }

    /**
     * Accorde l'index aux fichiers (R-561).
     *
     * <p>L'index n'est écrit qu'à l'arrêt, et un cache neuf s'ouvre à chaque
     * rechargement de ressources : une entrée rangée depuis le dernier index — par
     * un rechargement précédent de la même session, ou avant un arrêt brutal — est
     * sur le disque sans y figurer. Sans ce rapprochement, elle ne serait jamais
     * comptée ni évincée, et le cache dépasserait son plafond sans le savoir.
     *
     * <p>Les fichiers sont la vérité : une entrée de l'index dont le fichier a
     * disparu sort du compte ; un fichier que l'index ne cite pas entre, comme plus
     * récent que tout ce que l'index connaît, dans l'ordre de sa date. L'index ne
     * garde que ce qu'il sait mieux que le disque : l'ordre d'emploi. Un fichier
     * temporaire laissé par un arrêt en pleine écriture est supprimé.
     */
    private void reconcile() {
        if (!Files.isDirectory(root)) {
            sizes.clear();
            totalBytes = 0;
            return;
        }

        Map<String, BasicFileAttributes> onDisk = new HashMap<>();
        try (DirectoryStream<Path> shards = Files.newDirectoryStream(root, Files::isDirectory)) {
            for (Path shard : shards) {
                String prefix = shard.getFileName().toString();
                if (!SHARD.matcher(prefix).matches()) {
                    continue;
                }
                try (DirectoryStream<Path> files = Files.newDirectoryStream(shard)) {
                    for (Path file : files) {
                        String name = file.getFileName().toString();
                        if (TEMPORARY.matcher(name).matches()) {
                            Files.deleteIfExists(file);
                        } else if (ENTRY_FILE.matcher(name).matches() && name.startsWith(prefix)) {
                            onDisk.put(name.substring(0, 64),
                                    Files.readAttributes(file, BasicFileAttributes.class));
                        }
                    }
                }
            }
        } catch (IOException failure) {
            observer.failed();
            // L'index reste tel qu'il a été lu : moins juste, mais pas faux au point
            // de supprimer quoi que ce soit sur sa seule foi.
            diagnostics.add("cache : arborescence illisible, index gardé tel quel — " + failure);
            return;
        }

        Map<String, Long> reconciled = new LinkedHashMap<>();
        for (String hex : sizes.keySet()) {
            BasicFileAttributes file = onDisk.remove(hex);
            if (file != null) {
                reconciled.put(hex, file.size());
            }
        }
        List<Map.Entry<String, BasicFileAttributes>> unknown = new ArrayList<>(onDisk.entrySet());
        unknown.sort(Comparator
                .comparing((Map.Entry<String, BasicFileAttributes> entry) -> entry.getValue().lastModifiedTime())
                .thenComparing(Map.Entry::getKey));
        for (Map.Entry<String, BasicFileAttributes> entry : unknown) {
            reconciled.put(entry.getKey(), entry.getValue().size());
        }

        sizes.clear();
        sizes.putAll(reconciled);
        totalBytes = 0;
        for (long size : sizes.values()) {
            totalBytes += size;
        }
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
            observer.miss();
            return null;
        }

        byte[] raw;
        long start = clock.getAsLong();
        try {
            raw = Files.readAllBytes(file);
        } catch (IOException failure) {
            observer.diskNanos(clock.getAsLong() - start);
            observer.failed();
            observer.miss();
            invalidate(key, "lecture impossible : " + failure);
            publishOccupancy();
            return null;
        }
        observer.diskNanos(clock.getAsLong() - start);

        byte[] payload = unwrap(raw);
        if (payload == null) {
            observer.miss();
            invalidate(key, "entrée corrompue");
            publishOccupancy();
            return null;
        }

        // Employée : elle repasse en queue de la file d'éviction. Une entrée que
        // l'index ne connaissait pas — rangée par un autre cache sur la même
        // racine depuis l'ouverture — entre dans le compte (R-561).
        Long previous = sizes.remove(key.hex());
        totalBytes += raw.length - (previous == null ? 0 : previous);
        sizes.put(key.hex(), (long) raw.length);
        observer.hit();
        evict();
        publishOccupancy();
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
        long start = clock.getAsLong();
        try {
            Files.createDirectories(file.getParent());
            // Écriture puis renommage : une entrée n'apparaît que complète, et
            // une coupure de courant au mauvais moment laisse un fichier
            // temporaire plutôt qu'une entrée à moitié écrite.
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(temporary, wrapped);
            Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            observer.diskNanos(clock.getAsLong() - start);
            observer.failed();
            diagnostics.add("cache : écriture impossible pour " + key.hex() + " — " + failure);
            return false;
        }
        observer.diskNanos(clock.getAsLong() - start);
        observer.written();

        Long previous = sizes.remove(key.hex());
        if (previous != null) {
            totalBytes -= previous;
        }
        sizes.put(key.hex(), (long) wrapped.length);
        totalBytes += wrapped.length;
        evict();
        publishOccupancy();
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
            observer.evicted();
            try {
                Files.deleteIfExists(fileOf(oldest));
            } catch (IOException failure) {
                observer.failed();
                diagnostics.add("cache : éviction impossible pour " + oldest + " — " + failure);
            }
        }
    }

    private void invalidate(AssetKey key, String reason) {
        // R-560 : supprimée et recompilée. La source est là ; deviner ce qui
        // manque coûterait plus cher que de refaire.
        diagnostics.add("cache : entrée " + key.hex() + " écartée — " + reason);
        observer.invalidated();
        Long size = sizes.remove(key.hex());
        if (size != null) {
            totalBytes -= size;
        }
        try {
            Files.deleteIfExists(pathOf(key));
        } catch (IOException failure) {
            observer.failed();
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
     * <p>L'index n'est pas la vérité : les fichiers le sont, et l'ouverture l'y
     * accorde. Il garde l'ordre d'emploi des entrées, que le disque ne sait pas ;
     * un index perdu ne coûte qu'un ordre d'éviction approché par les dates.
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
            observer.failed();
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
            observer.failed();
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
