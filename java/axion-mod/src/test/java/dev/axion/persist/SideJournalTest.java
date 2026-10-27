package dev.axion.persist;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Le journal latéral et son fil (R-1744, ADR-153 D6). */
class SideJournalTest {

    @TempDir
    Path dir;

    private final List<String> messages = new CopyOnWriteArrayList<>();

    private SideJournal demarrer() {
        return SideJournal.start(() -> JournalStore.open(dir, Long.MAX_VALUE, Long.MAX_VALUE, messages::add), messages::add);
    }

    private List<JournalRecord.Put> relire() throws IOException {
        try (JournalStore store = JournalStore.open(dir, Long.MAX_VALUE, Long.MAX_VALUE, messages::add)) {
            return store.live();
        }
    }

    private static List<UUID> uuids(List<JournalRecord.Put> puts) {
        return puts.stream().map(JournalRecord.Put::uuid).toList();
    }

    private static UUID uuid(int n) {
        return new UUID(3, n);
    }

    @Test
    @DisplayName("R-1744 : les demandes s'écrivent ; à la fermeture, ce qui attend est écrit")
    void ecritureEtFermeture() throws Exception {
        SideJournal journal = demarrer();
        assertEquals(List.of(), journal.loaded().get(5, TimeUnit.SECONDS));
        journal.put(uuid(1), "minecraft:overworld", 1, 2, 3, 42, () -> new byte[] {9, 8});
        journal.put(uuid(2), "minecraft:the_nether", 4, 5, 6, 43, () -> new byte[] {7});
        journal.remove(uuid(2));
        assertTrue(journal.close(10_000), "le fil s'arrête");
        List<JournalRecord.Put> lu = relire();
        assertEquals(List.of(uuid(1)), uuids(lu));
        assertArrayEquals(new byte[] {9, 8}, lu.get(0).blob());
        assertEquals(42, lu.get(0).definition());
    }

    @Test
    @DisplayName("ADR-153 D6 : le journal lu au démarrage est rendu par loaded()")
    void lecture() throws Exception {
        SideJournal premier = demarrer();
        premier.put(uuid(1), "minecraft:overworld", 1, 2, 3, 42, () -> new byte[] {1});
        premier.close();
        SideJournal second = demarrer();
        List<JournalRecord.Put> lu = second.loaded().get(5, TimeUnit.SECONDS);
        second.close();
        assertEquals(List.of(uuid(1)), uuids(lu));
    }

    @Test
    @DisplayName("R-1744 : le blob est encodé par le fil du journal, jamais par l'appelant")
    void encodageHorsDuThreadAppelant() {
        List<String> fils = new CopyOnWriteArrayList<>();
        SideJournal journal = demarrer();
        journal.put(uuid(1), "minecraft:overworld", 0, 0, 0, 1, () -> {
            fils.add(Thread.currentThread().getName());
            return new byte[0];
        });
        journal.close();
        assertEquals(List.of("AXION journal latéral"), fils);
    }

    @Test
    @DisplayName("ADR-153 D6 : la dernière demande d'une assembly l'emporte — un PUT après un REMOVE la rend vivante")
    void derniereDemande() throws Exception {
        SideJournal journal = demarrer();
        journal.loaded().get(5, TimeUnit.SECONDS);
        journal.remove(uuid(1));
        journal.put(uuid(1), "minecraft:overworld", 0, 0, 0, 1, () -> new byte[] {5});
        journal.close();
        assertEquals(List.of(uuid(1)), uuids(relire()));
    }

    @Test
    @DisplayName("T-713c, R-1746 : un journal qui ne s'ouvre pas ne restaure rien, n'écrit rien, et le dit")
    void illisible() throws Exception {
        SideJournal journal = SideJournal.start(() -> {
            throw new IOException("disque refusé");
        }, messages::add);
        assertEquals(List.of(), journal.loaded().get(5, TimeUnit.SECONDS));
        journal.put(uuid(1), "minecraft:overworld", 0, 0, 0, 1, () -> new byte[] {5});
        assertEquals(0, journal.pending(), "rien ne s'accumule sans journal");
        assertTrue(journal.close(10_000));
        assertTrue(messages.stream().anyMatch(m -> m.contains("illisible") && m.contains("disque refusé")), messages.toString());
        assertEquals(List.of(), relire());
    }

    @Test
    @DisplayName("Un blob qui échoue n'empêche pas les autres d'être écrits, et se signale")
    void blobEnEchec() throws Exception {
        SideJournal journal = demarrer();
        journal.loaded().get(5, TimeUnit.SECONDS);
        journal.put(uuid(1), "minecraft:overworld", 0, 0, 0, 1, () -> {
            throw new IllegalStateException("NBT refusé");
        });
        journal.put(uuid(2), "minecraft:overworld", 0, 0, 0, 1, () -> new byte[] {5});
        journal.close();
        assertEquals(List.of(uuid(2)), uuids(relire()));
        assertTrue(messages.stream().anyMatch(m -> m.contains(uuid(1).toString())), messages.toString());
    }

    @Test
    @DisplayName("Une demande remise après la fermeture est sans effet")
    void apresFermeture() throws Exception {
        SideJournal journal = demarrer();
        journal.close();
        journal.put(uuid(1), "minecraft:overworld", 0, 0, 0, 1, () -> new byte[] {5});
        assertEquals(List.of(), relire());
    }
}
