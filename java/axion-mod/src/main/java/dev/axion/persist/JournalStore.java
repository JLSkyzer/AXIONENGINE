package dev.axion.persist;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Les segments du journal latéral sur disque (R-1743, R-1744, R-1746, ADR-153 D6).
 *
 * <p>Un dossier par monde, des segments {@code assemblies.<n>.journal} en ajout seul, lus dans l'ordre de
 * {@code n} : un {@link JournalRecord.Put} plus récent remplace le précédent de la même assembly, un
 * {@link JournalRecord.Remove} l'efface. Un segment commence par un magic et une version.
 *
 * <ul>
 *   <li><strong>Une session, un segment.</strong> Les écritures vont dans un segment neuf, créé à la première
 *       d'entre elles : la fin tronquée d'un segment qu'un arrêt brutal a interrompu reste isolée, et ne
 *       cache jamais ce qui s'écrit ensuite.
 *   <li><strong>Lecture tolérante.</strong> Un enregistrement au CRC faux est ignoré et compté ; une fin
 *       tronquée arrête la lecture du segment, ce qui précède reste lu. Un segment à l'en-tête illisible est
 *       renommé {@code .corrupt}, signalé, et ne compte plus : jamais supprimé (R-1747).
 *   <li><strong>Compactage</strong> quand les enregistrements vivants tombent sous la moitié des octets, au-delà
 *       d'un plancher : un segment neuf reçoit les vivants — écrit à part, forcé sur le disque, renommé —,
 *       puis les anciens sont supprimés du plus ancien au plus récent. Un arrêt à n'importe quel moment
 *       laisse un état relu à l'identique.
 *   <li><strong>Plafond</strong> {@code persistence.journal_max_bytes} : au-delà, compactage ; si les vivants
 *       le dépassent encore, éviction des entrées les plus anciennes — dernière écriture la plus ancienne —,
 *       chacune signalée avec son UUID et sa cause (R-1747).
 * </ul>
 *
 * <p>Pas de fil à lui : un seul fil s'en sert, celui du {@link SideJournal}.
 */
public final class JournalStore implements Closeable {

    /** Magic d'un segment : {@code AXJL}. */
    static final byte[] MAGIC = {'A', 'X', 'J', 'L'};

    /** Version du format d'un segment. */
    static final int VERSION = 1;

    /** Magic et version, en octets. */
    static final int HEADER = MAGIC.length + 2;

    private static final Pattern SEGMENT = Pattern.compile("assemblies\\.(\\d+)\\.journal");

    /** Une assembly vivante et la taille de son dernier enregistrement. */
    private record Live(JournalRecord.Put put, int size) {}

    private final Path dir;
    private final long maxBytes;
    private final long compactionFloor;
    private final Consumer<String> report;

    /** Les vivants, de la dernière écriture la plus ancienne à la plus récente. */
    private final LinkedHashMap<UUID, Live> live = new LinkedHashMap<>();

    /** Somme des tailles encodées des vivants. */
    private long liveBytes;

    /** Taille de chaque segment, par numéro. */
    private final TreeMap<Long, Long> segments = new TreeMap<>();

    /** Segment ouvert en écriture, et son numéro ; {@code null} avant la première écriture de la session. */
    private FileChannel out;

    private long current;

    private int crcErrors;
    private int truncated;
    private int corruptSegments;
    private int evicted;

    private JournalStore(Path dir, long maxBytes, long compactionFloor, Consumer<String> report) {
        this.dir = dir;
        this.maxBytes = maxBytes;
        this.compactionFloor = compactionFloor;
        this.report = report;
    }

    /**
     * Ouvre le journal d'un monde, et lit ses segments. Un dossier absent donne un journal vide ; rien
     * n'est créé avant la première écriture.
     *
     * @param dir dossier du monde, {@code <gameDir>/axion/world/<worldId>}
     * @param maxBytes plafond, {@code persistence.journal_max_bytes}
     * @param compactionFloor taille en deçà de laquelle le journal ne se compacte pas
     * @param report où dire ce que la lecture et l'écriture trouvent d'anormal
     * @throws IOException si le dossier existe mais ne se lit pas
     */
    public static JournalStore open(Path dir, long maxBytes, long compactionFloor, Consumer<String> report)
            throws IOException {
        JournalStore store = new JournalStore(dir, maxBytes, compactionFloor, report);
        if (Files.isDirectory(dir)) {
            store.read();
        }
        return store;
    }

    private void read() throws IOException {
        TreeMap<Long, Path> found = new TreeMap<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                Matcher matcher = SEGMENT.matcher(name);
                if (matcher.matches()) {
                    try {
                        found.put(Long.parseLong(matcher.group(1)), file);
                    } catch (NumberFormatException e) {
                        report.accept("segment ignoré, numéro hors de portée : " + name);
                    }
                } else if (name.endsWith(".journal.tmp")) {
                    // Un compactage interrompu avant son renommage : les segments qu'il remplaçait sont
                    // tous là, il n'apportait rien de plus qu'eux.
                    Files.deleteIfExists(file);
                }
            }
        }
        for (Map.Entry<Long, Path> segment : found.entrySet()) {
            readSegment(segment.getKey(), segment.getValue());
        }
    }

    private void readSegment(long number, Path file) throws IOException {
        long size = Files.size(file);
        if (size > Integer.MAX_VALUE - 8) {
            setAside(file, "segment de " + size + " octets, trop grand pour être lu");
            return;
        }
        byte[] bytes = Files.readAllBytes(file);
        ByteBuffer in = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (!header(in)) {
            setAside(file, "en-tête illisible");
            return;
        }
        segments.put(number, size);
        while (in.hasRemaining()) {
            int start = in.position();
            if (in.remaining() < JournalRecord.FRAMING) {
                truncated(file, start);
                return;
            }
            int type = Byte.toUnsignedInt(in.get());
            long length = Integer.toUnsignedLong(in.getInt());
            if (length > in.remaining() - 4L) {
                truncated(file, start);
                return;
            }
            int end = in.position() + (int) length;
            int next = end + 4;
            if (in.getInt(end) != JournalRecord.crc(bytes, start, end - start)) {
                crcErrors++;
                in.position(next);
                continue;
            }
            try {
                remember(JournalRecord.decode(type, in.slice(in.position(), (int) length)), next - start);
            } catch (IllegalArgumentException e) {
                crcErrors++;
                report.accept("enregistrement illisible dans " + file.getFileName() + " à l'octet " + start
                        + " : " + e.getMessage());
            }
            in.position(next);
        }
    }

    private static boolean header(ByteBuffer in) {
        if (in.remaining() < HEADER) {
            return false;
        }
        for (byte b : MAGIC) {
            if (in.get() != b) {
                return false;
            }
        }
        return Short.toUnsignedInt(in.getShort()) == VERSION;
    }

    private void truncated(Path file, int offset) {
        truncated++;
        report.accept("fin tronquée de " + file.getFileName() + " à l'octet " + offset
                + " : ce qui précède est lu, le reste ignoré");
    }

    private void setAside(Path file, String reason) throws IOException {
        corruptSegments++;
        Path aside = file.resolveSibling(file.getFileName() + ".corrupt");
        Files.move(file, aside, StandardCopyOption.REPLACE_EXISTING);
        report.accept("segment " + file.getFileName() + " écarté (" + reason + "), gardé sous "
                + aside.getFileName());
    }

    private void remember(JournalRecord record, int size) {
        Live previous = live.remove(record.uuid());
        if (previous != null) {
            liveBytes -= previous.size();
        }
        if (record instanceof JournalRecord.Put put) {
            live.put(put.uuid(), new Live(put, size));
            liveBytes += size;
        }
    }

    /** {@return les assemblies vivantes du journal, de la dernière écriture la plus ancienne à la plus récente} */
    public List<JournalRecord.Put> live() {
        return live.values().stream().map(Live::put).toList();
    }

    /**
     * Ajoute des enregistrements, dans l'ordre, puis compacte ou évince si le journal le demande.
     *
     * @throws IOException si l'écriture échoue
     */
    public void apply(List<JournalRecord> records) throws IOException {
        if (records.isEmpty()) {
            return;
        }
        List<byte[]> encoded = new ArrayList<>(records.size());
        int total = 0;
        for (JournalRecord record : records) {
            byte[] bytes = JournalRecord.encode(record);
            encoded.add(bytes);
            total += bytes.length;
        }
        ByteBuffer batch = ByteBuffer.allocate(total);
        encoded.forEach(batch::put);
        batch.flip();
        ensureSegment();
        while (batch.hasRemaining()) {
            out.write(batch);
        }
        out.force(false);
        segments.merge(current, (long) total, Long::sum);
        for (int index = 0; index < records.size(); index++) {
            remember(records.get(index), encoded.get(index).length);
        }
        maintain();
    }

    private void ensureSegment() throws IOException {
        if (out != null) {
            return;
        }
        Files.createDirectories(dir);
        current = segments.isEmpty() ? 0 : segments.lastKey() + 1;
        out = FileChannel.open(segmentPath(current), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        ByteBuffer header = headerBytes();
        while (header.hasRemaining()) {
            out.write(header);
        }
        segments.put(current, (long) HEADER);
    }

    private static ByteBuffer headerBytes() {
        ByteBuffer header = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN);
        header.put(MAGIC).putShort((short) VERSION).flip();
        return header;
    }

    private Path segmentPath(long number) {
        return dir.resolve("assemblies." + number + ".journal");
    }

    private void maintain() throws IOException {
        long total = totalBytes();
        if (total > maxBytes) {
            evictOldest();
            compact();
        } else if (total >= compactionFloor && liveBytes * 2 < total) {
            compact();
        }
    }

    private void evictOldest() {
        Iterator<Live> oldest = live.values().iterator();
        while (HEADER + liveBytes > maxBytes && oldest.hasNext()) {
            Live entry = oldest.next();
            oldest.remove();
            liveBytes -= entry.size();
            evicted++;
            report.accept("entrée évincée du journal latéral, plafond persistence.journal_max_bytes de " + maxBytes
                    + " octets atteint : assembly " + entry.put().uuid() + " (" + entry.put().dimension() + ")");
        }
    }

    /** Réécrit les vivants dans un segment neuf, puis supprime les anciens, du plus ancien au plus récent. */
    private void compact() throws IOException {
        long next = segments.lastKey() + 1;
        Path tmp = dir.resolve("assemblies." + next + ".journal.tmp");
        long size = HEADER;
        try (FileChannel compacted = FileChannel.open(
                tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer header = headerBytes();
            while (header.hasRemaining()) {
                compacted.write(header);
            }
            for (Live entry : live.values()) {
                ByteBuffer bytes = ByteBuffer.wrap(JournalRecord.encode(entry.put()));
                size += bytes.remaining();
                while (bytes.hasRemaining()) {
                    compacted.write(bytes);
                }
            }
            compacted.force(true);
        }
        out.close();
        out = null;
        Files.move(tmp, segmentPath(next), StandardCopyOption.ATOMIC_MOVE);
        for (Iterator<Long> old = segments.keySet().iterator(); old.hasNext(); ) {
            Path file = segmentPath(old.next());
            try {
                Files.deleteIfExists(file);
                old.remove();
            } catch (IOException e) {
                // Relu au prochain démarrage avant le segment compacté, qui le remplace : sans effet.
                report.accept("segment compacté " + file.getFileName() + " non supprimé : " + e.getMessage());
            }
        }
        segments.put(next, size);
        current = next;
        out = FileChannel.open(segmentPath(next), StandardOpenOption.WRITE, StandardOpenOption.APPEND);
    }

    /** {@return la taille du journal sur le disque, segments écartés exclus} */
    public long totalBytes() {
        return segments.values().stream().mapToLong(Long::longValue).sum();
    }

    /** {@return la taille encodée des vivants} */
    public long liveBytes() {
        return liveBytes;
    }

    /** {@return le nombre de segments du journal, segments écartés exclus} */
    public int segmentCount() {
        return segments.size();
    }

    /** {@return les enregistrements ignorés à la lecture, CRC faux ou contenu illisible} */
    public int crcErrors() {
        return crcErrors;
    }

    /** {@return les segments à la fin tronquée} */
    public int truncatedSegments() {
        return truncated;
    }

    /** {@return les segments écartés, à l'en-tête illisible} */
    public int corruptSegments() {
        return corruptSegments;
    }

    /** {@return les entrées évincées par le plafond} */
    public int evicted() {
        return evicted;
    }

    @Override
    public void close() throws IOException {
        if (out != null) {
            out.close();
            out = null;
        }
    }
}
