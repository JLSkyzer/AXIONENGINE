package dev.axion.asset;

import dev.axion.bridge.BufferKinds;
import dev.axion.bridge.CborWriter;
import dev.axion.bridge.NativeBridge;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Compilateur d'assets adossé au runtime natif (IF-06).
 *
 * <p>Le protocole est celui d'IF-02 : la source est écrite dans le tampon
 * {@code ASSET_IN}, puis {@code axion_asset_compile} est appelé avec sa
 * longueur. R-313 interdit à une fonction FFI d'allouer côté Java, et faire
 * traverser un pointeur de plus n'apporterait qu'un pointeur de plus à valider.
 *
 * <p>Le tampon est <strong>ré-acquis à chaque soumission</strong>, jamais
 * conservé : R-270 autorise sa réallocation entre deux ticks, et une vue
 * périmée écrirait dans de la mémoire qui ne lui appartient plus.
 *
 * <p><strong>Fichiers voisins</strong> (ADR-139, R-830). La première soumission ne porte que
 * la source. Si elle référence un {@code .mtl} ou un buffer externe, le sondage rend
 * {@code NEEDS_FILES} avec leurs noms : ce compilateur les demande à {@link SiblingFiles} et
 * relance la source avec eux, présents ou marqués absents, dans une table qui la suit dans
 * {@code ASSET_IN}. Le registre n'en voit rien : pour lui, et pour SM-01, le travail continue,
 * sous l'identifiant rendu par la première soumission.
 */
public final class NativeAssetCompiler implements AssetCompiler {

    /** Position de la génération dans l'en-tête d'un tampon (IF-02). */
    private static final int GENERATION_OFFSET = 8;

    /** Passages au plus pour une même compilation (ADR-139). */
    static final int MAX_PASSES = 4;

    /** Entrée de la table : fichier fourni, ou absent (ADR-139). */
    private static final int FILE_PRESENT = 0;
    private static final int FILE_ABSENT = 1;

    private final long context;
    private final long[] scratch = new long[NativeBridge.ASSET_POLL_SLOTS];

    /** Compilations en cours, par l'identifiant rendu au registre. */
    private final Map<Integer, Pending> pending = new HashMap<>();

    /** Ce qu'il faut pour relancer une compilation avec ses fichiers voisins. */
    private static final class Pending {
        private final long assetId;
        private final int format;
        private final byte[] source;
        private final byte[] optionsCbor;
        private final SiblingFiles siblings;
        /** Fichiers déjà envoyés, par nom normalisé ; une valeur nulle : absent. */
        private final TreeMap<String, byte[]> files = new TreeMap<>();
        private int nativeJob;
        private int passes = 1;

        private Pending(
                long assetId, int format, byte[] source, byte[] optionsCbor,
                SiblingFiles siblings, int nativeJob) {
            this.assetId = assetId;
            this.format = format;
            this.source = source;
            this.optionsCbor = optionsCbor;
            this.siblings = siblings;
            this.nativeJob = nativeJob;
        }
    }

    /**
     * Crée un compilateur adossé à un contexte natif.
     *
     * @param context jeton de contexte
     */
    public NativeAssetCompiler(long context) {
        this.context = context;
    }

    @Override
    public int submit(long assetId, int format, byte[] source, byte[] options, SiblingFiles siblings) {
        // Le tampon est partagé avec le chargement des maillages côté client
        // (ADR-119) : toute la séquence se fait sous le verrou des tampons d'asset.
        synchronized (AssetBuffers.LOCK) {
            // Le texte des options traverse en chaîne CBOR, hors tampon partagé :
            // quelques centaines d'octets, que le natif copie avant de rendre la main.
            byte[] optionsCbor = options.length == 0 ? null : CborWriter.encodeText(options);
            int job = submitLocked(assetId, format, source, new byte[0], optionsCbor);
            if (job > 0) {
                pending.put(job, new Pending(assetId, format, source, optionsCbor, siblings, job));
            }
            return job;
        }
    }

    @Override
    public void abandon(int jobId) {
        synchronized (AssetBuffers.LOCK) {
            pending.remove(jobId);
        }
    }

    private int submitLocked(
            long assetId, int format, byte[] source, byte[] table, byte[] optionsCbor) {
        long total = (long) source.length + table.length;
        ByteBuffer buffer = NativeBridge.acquire(context, BufferKinds.ASSET_IN, total);
        if (buffer == null) {
            return NativeBridge.E_INVALID_BUFFER;
        }
        int generation = buffer.getInt(GENERATION_OFFSET);

        // La charge utile commence après l'en-tête : ce qui précède décrit le
        // tampon, et l'écraser rendrait la vue invalide (R-271).
        buffer.position(BufferKinds.HEADER_BYTES);
        if (buffer.remaining() < total) {
            NativeBridge.release(context, BufferKinds.ASSET_IN, generation);
            return NativeBridge.E_INVALID_BUFFER;
        }
        buffer.put(source);
        buffer.put(table);

        int job = NativeBridge.compileAsset(
                context, assetId, format, source.length, table.length, optionsCbor);

        // Le tampon est rendu aussitôt : le natif a copié la source, et le
        // garder fausserait le bilan d'allocations de l'arrêt (R-322). C'est ce
        // bilan qui a signalé l'oubli au premier démarrage réel.
        NativeBridge.release(context, BufferKinds.ASSET_IN, generation);
        return job;
    }

    @Override
    public CompileStatus poll(int jobId) {
        // Le sondage d'une compilation aboutie écrit lui-même ASSET_OUT côté natif :
        // il entre sous le verrou avec la relecture qui le suit (ADR-119 §8).
        synchronized (AssetBuffers.LOCK) {
            Pending job = pending.get(jobId);
            CompileStatus status = pollLocked(job == null ? jobId : job.nativeJob, job);
            if (status.state() != AssetState.COMPILING) {
                pending.remove(jobId);
            }
            return status;
        }
    }

    private CompileStatus pollLocked(int nativeJob, Pending job) {
        int code = NativeBridge.pollAsset(context, nativeJob, scratch);
        if (code != NativeBridge.OK) {
            // Un jeton périmé ou un contexte fermé : le travail est perdu, et
            // le dire vaut mieux que de sonder indéfiniment.
            return CompileStatus.failed(code);
        }

        int state = (int) scratch[0];
        return switch (state) {
            case NativeBridge.ASSET_COMPILED -> {
                byte[] payload = readOutput(scratch[1]);
                yield payload == null
                        ? CompileStatus.failed(NativeBridge.E_INVALID_BUFFER)
                        : CompileStatus.compiled(payload);
            }
            case NativeBridge.ASSET_FAILED -> CompileStatus.failed((int) scratch[2]);
            case NativeBridge.ASSET_NEEDS_FILES -> supply(job, scratch[1]);
            default -> CompileStatus.pending();
        };
    }

    /**
     * Relance une compilation avec les fichiers voisins qu'elle demande (ADR-139).
     *
     * <p>Chaque nom demandé est cherché dans les voisins de la source ; un fichier introuvable
     * part marqué absent, et la compilation fait alors ce que fait {@code axion-cli} d'une
     * lecture qui échoue. La table envoyée cumule les passages : un nom déjà fourni ne se
     * redemande pas.
     *
     * @param job compilation à relancer, ou {@code null} si elle n'est pas connue
     * @param size longueur de la liste des noms dans le tampon de sortie
     */
    private CompileStatus supply(Pending job, long size) {
        byte[] list = readOutput(size);
        if (job == null || list == null || job.passes >= MAX_PASSES) {
            return CompileStatus.failed(NativeBridge.E_INVALID_BUFFER);
        }
        List<String> names = decodeNames(list);
        if (names == null) {
            return CompileStatus.failed(NativeBridge.E_INVALID_BUFFER);
        }
        for (String name : names) {
            job.files.put(name, job.siblings.read(name));
        }
        byte[] table = table(job.files);
        if (table == null) {
            return CompileStatus.failed(NativeBridge.E_INVALID_BUFFER);
        }
        int next = submitLocked(job.assetId, job.format, job.source, table, job.optionsCbor);
        if (next <= 0) {
            return CompileStatus.failed(next);
        }
        job.nativeJob = next;
        job.passes++;
        return CompileStatus.pending();
    }

    /**
     * La table des fichiers voisins (ADR-139), petit-boutiste : nombre, puis par fichier la
     * longueur du nom, le drapeau, la longueur des octets, le nom et les octets.
     *
     * @param files fichiers par nom normalisé, triés ; une valeur nulle : absent
     * @return la table, ou {@code null} si elle dépasse ce qu'un tableau peut porter
     */
    static byte[] table(Map<String, byte[]> files) {
        long size = 4;
        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            size += 16L + entry.getKey().getBytes(StandardCharsets.UTF_8).length;
            size += entry.getValue() == null ? 0 : entry.getValue().length;
        }
        if (size > Integer.MAX_VALUE - 8) {
            return null;
        }
        ByteBuffer out = ByteBuffer.allocate((int) size).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(files.size());
        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            byte[] name = entry.getKey().getBytes(StandardCharsets.UTF_8);
            byte[] data = entry.getValue();
            out.putInt(name.length);
            out.putInt(data == null ? FILE_ABSENT : FILE_PRESENT);
            out.putLong(data == null ? 0 : data.length);
            out.put(name);
            if (data != null) {
                out.put(data);
            }
        }
        return out.array();
    }

    /**
     * La liste de {@code NEEDS_FILES} : nombre, puis par nom sa longueur et ses octets UTF-8,
     * petit-boutiste (ADR-139).
     *
     * @return les noms, ou {@code null} si la liste est mal formée
     */
    static List<String> decodeNames(byte[] bytes) {
        ByteBuffer in = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (in.remaining() < 4) {
            return null;
        }
        int count = in.getInt();
        if (count < 0 || count > in.remaining() / 4) {
            return null;
        }
        List<String> names = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            if (in.remaining() < 4) {
                return null;
            }
            int length = in.getInt();
            if (length < 0 || length > in.remaining()) {
                return null;
            }
            byte[] name = new byte[length];
            in.get(name);
            names.add(new String(name, StandardCharsets.UTF_8));
        }
        return in.hasRemaining() ? null : names;
    }

    /**
     * Relit la charge utile du tampon de sortie.
     *
     * <p>Le tampon est relâché aussitôt : il servira à la compilation suivante
     * (R-270), et ce qu'on y laisserait appartiendrait alors à un autre asset.
     * Le laisser acquis fausserait par ailleurs le bilan de l'arrêt (R-322).
     *
     * @param size taille annoncée par le natif
     * @return la charge utile, ou {@code null} si le tampon est inaccessible
     */
    private byte[] readOutput(long size) {
        ByteBuffer buffer = NativeBridge.acquire(context, BufferKinds.ASSET_OUT, 0);
        if (buffer == null) {
            return null;
        }

        int generation = buffer.getInt(GENERATION_OFFSET);
        int available = Math.max(0, buffer.capacity() - BufferKinds.HEADER_BYTES);
        int length = (int) Math.min(size, available);
        byte[] payload = new byte[length];
        buffer.position(BufferKinds.HEADER_BYTES);
        buffer.get(payload);

        NativeBridge.release(context, BufferKinds.ASSET_OUT, generation);
        return payload;
    }
}
