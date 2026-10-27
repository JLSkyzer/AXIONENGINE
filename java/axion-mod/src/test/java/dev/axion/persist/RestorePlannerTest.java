package dev.axion.persist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** La restauration depuis le journal (cas 3 de 22.5.3, R-1748), contre un monde factice. */
class RestorePlannerTest {

    /** Un monde où l'on dit quelles entités existent et quels chunks sont chargés. */
    private static final class Monde implements RestorePlanner.World {
        final Set<UUID> presentes = new HashSet<>();
        final Set<String> charges = new HashSet<>();
        final List<UUID> recreees = new ArrayList<>();
        final List<UUID> examinees = new ArrayList<>();
        boolean refuse;

        static String chunk(String dimension, double x, double z) {
            return dimension + "@" + Math.floorDiv((int) Math.floor(x), 16) + "," + Math.floorDiv((int) Math.floor(z), 16);
        }

        @Override
        public boolean exists(UUID uuid) {
            examinees.add(uuid);
            return presentes.contains(uuid);
        }

        @Override
        public boolean entitiesLoaded(String dimension, double x, double z) {
            return charges.contains(chunk(dimension, x, z));
        }

        @Override
        public boolean restore(JournalRecord.Put put) {
            recreees.add(put.uuid());
            if (refuse) {
                return false;
            }
            presentes.add(put.uuid());
            return true;
        }
    }

    private static JournalRecord.Put put(int n) {
        return new JournalRecord.Put(new UUID(7, n), "minecraft:overworld", 16.0 * n + 3, 70, 5, n, new byte[] {1});
    }

    private static String chunkDe(JournalRecord.Put put) {
        return Monde.chunk(put.dimension(), put.x(), put.z());
    }

    @Test
    @DisplayName("R-1745, R-1748 : une assembly dont l'entité existe n'est jamais recréée")
    void entitePresente() {
        RestorePlanner planner = new RestorePlanner();
        Monde monde = new Monde();
        planner.offer(List.of(put(1)));
        monde.presentes.add(put(1).uuid());
        monde.charges.add(chunkDe(put(1)));
        assertEquals(List.of(), planner.tick(monde, 10));
        assertEquals(List.of(), monde.recreees);
        assertEquals(0, planner.pending());
    }

    @Test
    @DisplayName("Cas 3 : chunk chargé, entité absente — recréée une fois, et une seule (R-1748)")
    void entitePerdue() {
        RestorePlanner planner = new RestorePlanner();
        Monde monde = new Monde();
        planner.offer(List.of(put(1)));
        monde.charges.add(chunkDe(put(1)));
        List<RestorePlanner.Outcome> issues = planner.tick(monde, 10);
        assertEquals(1, issues.size());
        assertTrue(issues.get(0).restored());
        assertEquals(put(1).uuid(), issues.get(0).put().uuid());
        assertEquals(List.of(put(1).uuid()), monde.recreees);
        planner.offer(List.of(put(1)));
        planner.tick(monde, 10);
        assertEquals(List.of(put(1).uuid()), monde.recreees, "jamais deux fois");
    }

    @Test
    @DisplayName("Cas 3 : tant que son chunk n'est pas chargé, l'assembly attend")
    void attenteDuChunk() {
        RestorePlanner planner = new RestorePlanner();
        Monde monde = new Monde();
        planner.offer(List.of(put(1)));
        for (int tick = 0; tick < 5; tick++) {
            assertEquals(List.of(), planner.tick(monde, 10));
        }
        assertEquals(1, planner.pending());
        monde.charges.add(chunkDe(put(1)));
        assertEquals(1, planner.tick(monde, 10).size());
        assertEquals(0, planner.pending());
    }

    @Test
    @DisplayName("ADR-153 D6 : un tick examine au plus son budget, à tour de rôle")
    void budget() {
        RestorePlanner planner = new RestorePlanner();
        Monde monde = new Monde();
        List<JournalRecord.Put> dix = new ArrayList<>();
        for (int n = 0; n < 10; n++) {
            dix.add(put(n));
        }
        planner.offer(dix);
        planner.tick(monde, 3);
        assertEquals(3, monde.examinees.size());
        for (int tick = 0; tick < 3; tick++) {
            planner.tick(monde, 3);
        }
        assertEquals(10, new HashSet<>(monde.examinees).size(), "chacune finit par passer");
    }

    @Test
    @DisplayName("R-1748 : une assembly supprimée ou vue avant la fin de la lecture du journal n'est pas recréée")
    void oublieeAvantLaLecture() {
        RestorePlanner planner = new RestorePlanner();
        Monde monde = new Monde();
        planner.forget(put(1).uuid());
        planner.offer(List.of(put(1), put(2)));
        monde.charges.add(chunkDe(put(1)));
        monde.charges.add(chunkDe(put(2)));
        planner.tick(monde, 10);
        assertEquals(List.of(put(2).uuid()), monde.recreees);
    }

    @Test
    @DisplayName("ADR-153 D6 : une assembly oubliée pendant son attente n'est plus examinée")
    void oublieePendantLAttente() {
        RestorePlanner planner = new RestorePlanner();
        Monde monde = new Monde();
        planner.offer(List.of(put(1)));
        planner.tick(monde, 10);
        planner.forget(put(1).uuid());
        monde.charges.add(chunkDe(put(1)));
        monde.examinees.clear();
        planner.tick(monde, 10);
        assertEquals(List.of(), monde.examinees);
        assertEquals(List.of(), monde.recreees);
    }

    @Test
    @DisplayName("Une restauration que le monde refuse est rapportée, et n'est pas retentée")
    void refus() {
        RestorePlanner planner = new RestorePlanner();
        Monde monde = new Monde();
        monde.refuse = true;
        planner.offer(List.of(put(1)));
        monde.charges.add(chunkDe(put(1)));
        List<RestorePlanner.Outcome> issues = planner.tick(monde, 10);
        assertEquals(1, issues.size());
        assertFalse(issues.get(0).restored());
        planner.tick(monde, 10);
        assertEquals(1, monde.recreees.size());
    }
}
