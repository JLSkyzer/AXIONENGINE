package dev.axion.persist;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.CRC32C;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Le journal latéral sur disque (R-1743, R-1746, R-1747, ADR-153 D6) : format, lecture tolérante,
 * compactage et plafond.
 */
class JournalStoreTest {

    private static final long SANS_PLAFOND = Long.MAX_VALUE;

    @TempDir
    Path dir;

    private final List<String> messages = new ArrayList<>();

    private JournalStore ouvrir(long plafond, long plancher) throws IOException {
        return JournalStore.open(dir, plafond, plancher, messages::add);
    }

    private JournalStore ouvrir() throws IOException {
        return ouvrir(SANS_PLAFOND, SANS_PLAFOND);
    }

    private static UUID uuid(int n) {
        return new UUID(0x1234L, n);
    }

    private static JournalRecord.Put put(int n, int tailleBlob) {
        byte[] blob = new byte[tailleBlob];
        for (int index = 0; index < blob.length; index++) {
            blob[index] = (byte) (n + index);
        }
        return new JournalRecord.Put(uuid(n), "minecraft:overworld", n + 0.5, 64.25, -n - 0.75, 0xABCDL + n, blob);
    }

    private static List<UUID> uuids(JournalStore store) {
        return store.live().stream().map(JournalRecord.Put::uuid).toList();
    }

    private List<Path> segments() throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(f -> f.getFileName().toString().matches("assemblies\\.\\d+\\.journal")).sorted().toList();
        }
    }

    private static void assertMemeAssembly(JournalRecord.Put attendu, JournalRecord.Put lu) {
        assertEquals(attendu.uuid(), lu.uuid());
        assertEquals(attendu.dimension(), lu.dimension());
        assertEquals(attendu.x(), lu.x());
        assertEquals(attendu.y(), lu.y());
        assertEquals(attendu.z(), lu.z());
        assertEquals(attendu.definition(), lu.definition());
        assertArrayEquals(attendu.blob(), lu.blob());
    }

    @Test
    @DisplayName("R-1743 : ce qui s'écrit se relit — position, dimension, definition et blob ; un REMOVE efface")
    void allerRetour() throws IOException {
        try (JournalStore store = ouvrir()) {
            store.apply(List.of(put(1, 40), put(2, 0), put(3, 7)));
            store.apply(List.of(new JournalRecord.Remove(uuid(2)), put(1, 12)));
        }
        try (JournalStore relu = ouvrir()) {
            List<JournalRecord.Put> vivants = relu.live();
            assertEquals(2, vivants.size());
            assertMemeAssembly(put(3, 7), vivants.get(0));
            assertMemeAssembly(put(1, 12), vivants.get(1));
            assertEquals(0, relu.crcErrors());
            assertEquals(List.of(), messages);
        }
    }

    @Test
    @DisplayName("ADR-153 D6 : chaque session écrit dans un segment neuf, ouvert à sa première écriture")
    void unSegmentParSession() throws IOException {
        try (JournalStore store = ouvrir()) {
            assertFalse(Files.exists(dir.resolve("assemblies.0.journal")), "rien n'est créé avant d'écrire");
            store.apply(List.of(put(1, 4)));
        }
        try (JournalStore store = ouvrir()) {
            store.apply(List.of(put(2, 4)));
        }
        assertEquals(List.of(dir.resolve("assemblies.0.journal"), dir.resolve("assemblies.1.journal")), segments());
        try (JournalStore relu = ouvrir()) {
            assertEquals(List.of(uuid(1), uuid(2)), uuids(relu));
        }
    }

    @Test
    @DisplayName("ADR-153 D6 : le format — en-tête AXJL v1, puis type, longueur, contenu et CRC-32C en little-endian")
    void format() throws IOException {
        UUID uuid = new UUID(0x0102030405060708L, 0x090A0B0C0D0E0F10L);
        try (JournalStore store = ouvrir()) {
            store.apply(List.of(new JournalRecord.Remove(uuid)));
        }
        byte[] octets = Files.readAllBytes(dir.resolve("assemblies.0.journal"));
        assertEquals(6 + 1 + 4 + 16 + 4, octets.length);
        ByteBuffer in = ByteBuffer.wrap(octets).order(ByteOrder.LITTLE_ENDIAN);
        assertArrayEquals(new byte[] {'A', 'X', 'J', 'L', 1, 0}, Arrays.copyOf(octets, 6));
        assertEquals(JournalRecord.REMOVE, octets[6]);
        assertEquals(16, in.getInt(7));
        assertEquals(0x0102030405060708L, in.getLong(11));
        assertEquals(0x090A0B0C0D0E0F10L, in.getLong(19));
        CRC32C crc = new CRC32C();
        crc.update(octets, 6, 1 + 4 + 16);
        assertEquals((int) crc.getValue(), in.getInt(27));
    }

    @Test
    @DisplayName("ADR-153 D6 : un enregistrement au CRC faux est ignoré et compté, les autres sont lus")
    void crcFaux() throws IOException {
        try (JournalStore store = ouvrir()) {
            store.apply(List.of(put(1, 8), put(2, 8), put(3, 8)));
        }
        Path segment = dir.resolve("assemblies.0.journal");
        byte[] octets = Files.readAllBytes(segment);
        int deuxieme = JournalStore.HEADER + JournalRecord.encode(put(1, 8)).length;
        octets[deuxieme + 1 + 4 + 20] ^= 0x40; // dans le contenu du deuxième
        Files.write(segment, octets);
        try (JournalStore relu = ouvrir()) {
            assertEquals(List.of(uuid(1), uuid(3)), uuids(relu));
            assertEquals(1, relu.crcErrors());
        }
    }

    @Test
    @DisplayName("ADR-153 D6 : une fin tronquée garde ce qui précède, et la session suivante écrit à part")
    void finTronquee() throws IOException {
        try (JournalStore store = ouvrir()) {
            store.apply(List.of(put(1, 8), put(2, 30)));
        }
        Path segment = dir.resolve("assemblies.0.journal");
        byte[] octets = Files.readAllBytes(segment);
        Files.write(segment, Arrays.copyOf(octets, octets.length - 11));
        try (JournalStore store = ouvrir()) {
            assertEquals(List.of(uuid(1)), uuids(store));
            assertEquals(1, store.truncatedSegments());
            assertTrue(messages.stream().anyMatch(m -> m.contains("fin tronquée")), messages.toString());
            store.apply(List.of(put(3, 8)));
        }
        try (JournalStore relu = ouvrir()) {
            assertEquals(List.of(uuid(1), uuid(3)), uuids(relu));
        }
    }

    @Test
    @DisplayName("R-1747 : un segment à l'en-tête illisible est écarté sous .corrupt, signalé, jamais supprimé")
    void enTeteIllisible() throws IOException {
        try (JournalStore store = ouvrir()) {
            store.apply(List.of(put(1, 8)));
        }
        try (JournalStore store = ouvrir()) {
            store.apply(List.of(put(2, 8)));
        }
        Path premier = dir.resolve("assemblies.0.journal");
        byte[] octets = Files.readAllBytes(premier);
        octets[0] = 'Z';
        Files.write(premier, octets);
        try (JournalStore relu = ouvrir()) {
            assertEquals(List.of(uuid(2)), uuids(relu));
            assertEquals(1, relu.corruptSegments());
            assertEquals(1, relu.segmentCount());
        }
        assertArrayEquals(octets, Files.readAllBytes(dir.resolve("assemblies.0.journal.corrupt")));
        assertTrue(messages.stream().anyMatch(m -> m.contains("écarté")), messages.toString());
    }

    @Test
    @DisplayName("T-713c : un journal absent s'ouvre vide, sans rien créer")
    void absent() throws IOException {
        Path absent = dir.resolve("monde");
        try (JournalStore store = JournalStore.open(absent, SANS_PLAFOND, SANS_PLAFOND, messages::add)) {
            assertEquals(List.of(), store.live());
        }
        assertFalse(Files.exists(absent));
    }

    @Test
    @DisplayName("ADR-153 D6 : sous la moitié d'octets vivants, le journal se compacte en un segment des seuls vivants")
    void compactage() throws IOException {
        try (JournalStore store = ouvrir(SANS_PLAFOND, 0)) {
            for (int fois = 0; fois < 10; fois++) {
                store.apply(List.of(put(1, 100)));
            }
            store.apply(List.of(put(2, 5), new JournalRecord.Remove(uuid(2)), put(3, 5)));
            assertEquals(1, store.segmentCount());
            assertTrue(store.liveBytes() * 2 >= store.totalBytes(), store.liveBytes() + " vivants pour " + store.totalBytes());
        }
        assertEquals(1, segments().size());
        try (JournalStore relu = ouvrir()) {
            assertEquals(List.of(uuid(1), uuid(3)), uuids(relu));
            assertMemeAssembly(put(1, 100), relu.live().get(0));
        }
    }

    @Test
    @DisplayName("ADR-153 D6 : sous le plancher, pas de compactage")
    void plancher() throws IOException {
        try (JournalStore store = ouvrir(SANS_PLAFOND, 1 << 20)) {
            for (int fois = 0; fois < 10; fois++) {
                store.apply(List.of(put(1, 100)));
            }
            assertTrue(store.totalBytes() > 10 * 100, "tout est encore là : " + store.totalBytes());
        }
    }

    @Test
    @DisplayName("ADR-153 D6 : un compactage interrompu avant la suppression des anciens segments se relit à l'identique")
    void compactageInterrompu() throws IOException {
        try (JournalStore store = ouvrir()) {
            store.apply(List.of(put(1, 50), put(2, 500), put(3, 50)));
        }
        try (JournalStore store = ouvrir()) {
            store.apply(List.of(new JournalRecord.Remove(uuid(2)), put(1, 60)));
        }
        Map<Path, byte[]> avant = new HashMap<>();
        for (Path segment : segments()) {
            avant.put(segment, Files.readAllBytes(segment));
        }
        try (JournalStore store = ouvrir(SANS_PLAFOND, 0)) {
            store.apply(List.of(put(4, 1)));
            assertEquals(1, store.segmentCount(), "le compactage a eu lieu");
        }
        // L'arrêt survient entre le renommage du segment compacté et la suppression des anciens.
        for (Map.Entry<Path, byte[]> ancien : avant.entrySet()) {
            Files.write(ancien.getKey(), ancien.getValue());
        }
        Files.write(dir.resolve("assemblies.99.journal.tmp"), new byte[] {1, 2, 3});
        try (JournalStore relu = ouvrir()) {
            assertEquals(List.of(uuid(3), uuid(1), uuid(4)), uuids(relu));
            assertMemeAssembly(put(1, 60), relu.live().get(1));
            assertEquals(0, relu.crcErrors());
        }
        assertFalse(Files.exists(dir.resolve("assemblies.99.journal.tmp")), "le reste d'un compactage est retiré");
    }

    @Test
    @DisplayName("R-1746, R-1747 : au-delà du plafond, les entrées à la dernière écriture la plus ancienne sont évincées, chacune signalée")
    void plafond() throws IOException {
        int une = JournalRecord.encode(put(1, 200)).length;
        long plafond = JournalStore.HEADER + 3L * une;
        try (JournalStore store = ouvrir(plafond, SANS_PLAFOND)) {
            store.apply(List.of(put(1, 200), put(2, 200), put(3, 200)));
            store.apply(List.of(put(1, 200))); // la 1 redevient la plus récente
            assertEquals(0, store.evicted(), "les vivants tiennent : le compactage suffit");
            store.apply(List.of(put(4, 200)));
            assertEquals(1, store.evicted());
            assertTrue(store.totalBytes() <= plafond, store.totalBytes() + " > " + plafond);
        }
        assertTrue(messages.stream().anyMatch(m -> m.contains(uuid(2).toString()) && m.contains("journal_max_bytes")),
                messages.toString());
        try (JournalStore relu = ouvrir()) {
            assertEquals(List.of(uuid(3), uuid(1), uuid(4)), uuids(relu));
        }
    }
}
