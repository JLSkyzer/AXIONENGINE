package dev.axion.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * ADR-123 §8, §9, §11 : les faits de simulation sont dits, jamais tus, sans inonder (FM-20,
 * R-180, FM-21, FM-22, R-1880).
 */
class SimulationJournalTest {

    /** Événement mono-corps de l'assembly {@code (index, 1)}. */
    private static PhysicsEvent fait(int kind, int index, int data) {
        return new PhysicsEvent(
                kind, index, 1, 0, 0, 0, 0, new float[3], new float[3], 0, 0, 0, 0, 0, 0, data);
    }

    private static PhysicsEvent restauration(int index) {
        return fait(
                PhysicsEventCodes.Kind.RECOVERED, index, PhysicsEventCodes.Data.RECOVERED_INVALID_STATE);
    }

    private static List<String> textes(List<SimulationJournal.Entry> entrees) {
        return entrees.stream().map(SimulationJournal.Entry::text).toList();
    }

    @Test
    void uneRestaurationEstSignaleeEnE2030AvecLAssembly() {
        SimulationJournal journal = new SimulationJournal();
        journal.setDescriber(index -> "l'assembly #" + index + " (axion:test_cube) en 1.0 2.0 3.0");

        journal.recordEvents(10L, List.of(restauration(5)));

        List<SimulationJournal.Entry> lignes = journal.drain();
        assertEquals(1, lignes.size());
        assertTrue(lignes.get(0).warning(), "un code d'erreur demande l'attention");
        String ligne = lignes.get(0).text();
        assertTrue(ligne.startsWith("E-2030 : "), ligne);
        assertTrue(ligne.contains("l'assembly #5 (axion:test_cube) en 1.0 2.0 3.0"), ligne);
        assertTrue(ligne.contains("signaler le scénario"), ligne);
    }

    @Test
    void unMemeFaitNeRevientQuUneFoisParMinuteDeJeu() {
        SimulationJournal journal = new SimulationJournal();

        journal.recordEvents(10L, List.of(restauration(5)));
        journal.recordEvents(11L, List.of(restauration(5)));
        journal.recordEvents(10L + SimulationJournal.PERIOD_TICKS - 1, List.of(restauration(5)));
        assertEquals(1, journal.drain().size(), "une ligne par minute de jeu");

        journal.recordEvents(10L + SimulationJournal.PERIOD_TICKS, List.of(restauration(5)));
        assertEquals(1, journal.drain().size(), "une minute plus tard, le fait revient");
    }

    @Test
    void leDebitCompteParAssemblyEtParFait() {
        SimulationJournal journal = new SimulationJournal();

        journal.recordEvents(10L, List.of(
                restauration(5),
                restauration(6),
                fait(PhysicsEventCodes.Kind.CLAMPED, 5, PhysicsEventCodes.Data.CLAMPED_VELOCITY),
                fait(PhysicsEventCodes.Kind.CLAMPED, 5, PhysicsEventCodes.Data.CLAMPED_STACKING)));

        assertEquals(4, journal.drain().size(), "quatre faits distincts, quatre lignes");
    }

    @Test
    void chaqueCodeDeBorneEstDitSelonSonSens() {
        SimulationJournal journal = new SimulationJournal();

        journal.recordEvents(10L, List.of(
                fait(PhysicsEventCodes.Kind.CLAMPED, 1, PhysicsEventCodes.Data.CLAMPED_VELOCITY),
                fait(PhysicsEventCodes.Kind.CLAMPED, 2, PhysicsEventCodes.Data.CLAMPED_BUDGET),
                fait(PhysicsEventCodes.Kind.CLAMPED, 3, PhysicsEventCodes.Data.CLAMPED_STACKING),
                fait(PhysicsEventCodes.Kind.CLAMPED, 4, 99),
                fait(PhysicsEventCodes.Kind.RECOVERED, 5, 7)));

        List<SimulationJournal.Entry> lignes = journal.drain();
        List<String> textes = textes(lignes);
        assertEquals("vitesse de l'assembly #1 bornée (R-180)", textes.get(0));
        assertTrue(textes.get(1).startsWith("l'assembly #2 endormie") && textes.get(1).contains("FM-21"));
        assertTrue(textes.get(2).startsWith("l'assembly #3 agitée") && textes.get(2).contains("FM-22"));
        assertEquals("l'assembly #4 : grandeur bornée (code 99)", textes.get(3));
        assertEquals("l'assembly #5 restaurée à un état valide (code 7)", textes.get(4));
        // Une borne est une correction ordinaire ; une restauration, une anomalie.
        assertFalse(lignes.get(0).warning());
        assertTrue(lignes.get(4).warning());
    }

    @Test
    void lesAutresGenresNeSontPasDesFaitsDeJournal() {
        SimulationJournal journal = new SimulationJournal();

        journal.recordEvents(10L, List.of(
                fait(PhysicsEventCodes.Kind.SLEEP, 1, 0),
                fait(PhysicsEventCodes.Kind.WAKE, 1, 0),
                fait(PhysicsEventCodes.Kind.CONTACT_START, 1, 0)));

        assertTrue(journal.drain().isEmpty());
    }

    @Test
    void uneRafaleEstPlafonneeEtLeResteCompte() {
        SimulationJournal journal = new SimulationJournal();
        List<PhysicsEvent> rafale = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            rafale.add(fait(PhysicsEventCodes.Kind.CLAMPED, index, PhysicsEventCodes.Data.CLAMPED_BUDGET));
        }

        journal.recordEvents(10L, rafale);

        List<String> textes = textes(journal.drain());
        assertEquals(SimulationJournal.MAX_FACTS_PER_TICK + 1, textes.size());
        String synthese = textes.get(textes.size() - 1);
        assertTrue(synthese.startsWith("12 autre(s) fait(s) de simulation au tick 10"), synthese);

        // Les faits comptés sur la synthèse ont été dits : ils suivent le débit, eux aussi.
        journal.recordEvents(11L, rafale);
        assertTrue(journal.drain().isEmpty());
    }

    @Test
    void unNouveauServeurRepartDeZero() {
        SimulationJournal journal = new SimulationJournal();
        journal.recordEvents(50_000L, List.of(restauration(5)));
        journal.drain();

        // Les ticks d'un nouveau serveur repartent de zéro : le fait n'est pas « récent ».
        journal.recordEvents(5L, List.of(restauration(5)));
        assertEquals(1, journal.drain().size());
    }

    @Test
    void unNouveauServeurRepartDeZeroAvantMemeLaPremierePurge() {
        // Encore dans la première minute de jeu : aucune purge n'a eu lieu.
        SimulationJournal journal = new SimulationJournal();
        journal.recordEvents(100L, List.of(restauration(5)));
        journal.drain();

        journal.recordEvents(5L, List.of(restauration(5)));
        assertEquals(1, journal.drain().size(), "un tick antérieur à la dernière ligne ouvre une époque");
    }

    @Test
    void laPurgeDUneNouvelleEpoqueOublieToutLAncienne() {
        SimulationJournal journal = new SimulationJournal();
        journal.recordEvents(50_000L, List.of(restauration(5)));
        // Un autre fait ouvre la nouvelle époque, et la purge passe.
        journal.recordEvents(5L, List.of(restauration(6)));
        journal.drain();

        // Bien plus tard dans la nouvelle époque, le fait de l'ancienne ne retient plus rien.
        journal.recordEvents(50_010L, List.of(restauration(5)));
        assertEquals(1, journal.drain().size());
    }

    @Test
    void chaqueChangementDePalierEstJournaliseAvecSaCause() {
        SimulationJournal journal = new SimulationJournal();
        assertEquals(0, journal.degradationLevel());
        assertEquals(-1L, journal.lastP95Ns());

        journal.recordDegradation(300L, 1, 3_420_000L, 3_000_000L);
        List<SimulationJournal.Entry> descente = journal.drain();
        assertEquals(1, descente.size());
        assertTrue(descente.get(0).warning());
        String ligne = descente.get(0).text();
        // R-1880 : la métrique, la valeur, le budget.
        assertTrue(ligne.contains("NORMAL → DEGRADED_1"), ligne);
        assertTrue(ligne.contains("descente"), ligne);
        assertTrue(ligne.contains("3.42 ms"), ligne);
        assertTrue(ligne.contains("3.00 ms"), ligne);
        assertTrue(ligne.contains("axion.sim.p95_ns"), ligne);
        assertTrue(ligne.contains("budgets.sim_ns_per_tick"), ligne);
        assertEquals(1, journal.degradationLevel());
        assertEquals(3_420_000L, journal.lastP95Ns());

        // Même palier : rien à dire, mais le p95 relevé suit.
        journal.recordDegradation(301L, 1, 3_500_000L, 3_000_000L);
        assertTrue(journal.drain().isEmpty());
        assertEquals(3_500_000L, journal.lastP95Ns());

        journal.recordDegradation(1200L, 0, 1_100_000L, 3_000_000L);
        List<SimulationJournal.Entry> remontee = journal.drain();
        assertFalse(remontee.get(0).warning(), "une remontée est une bonne nouvelle");
        assertTrue(remontee.get(0).text().contains("DEGRADED_1 → NORMAL"), remontee.get(0).text());
        assertTrue(remontee.get(0).text().contains("remontée"), remontee.get(0).text());
        assertEquals(0, journal.degradationLevel());
    }

    @Test
    void lePalierSAfficheAvecSonP95HorsDuNominal() {
        assertEquals("NORMAL", SimulationJournal.describe(0, 1_000_000L, 3_000_000L));
        assertEquals("DEGRADED_2", SimulationJournal.describe(2, -1L, 3_000_000L), "sans relevé");
        assertEquals(
                "DEGRADED_2 — p95 du tick 3.42 ms pour un budget de 3.00 ms",
                SimulationJournal.describe(2, 3_420_000L, 3_000_000L));
        assertEquals("palier 7", SimulationJournal.levelName(7), "un palier inconnu reste nommé");
        assertEquals("palier -1", SimulationJournal.levelName(-1));
    }

    @Test
    void lesNotesEtLaVidange() {
        SimulationJournal journal = new SimulationJournal();
        assertTrue(journal.drain().isEmpty());

        journal.note(true, "collect refusé au tick 3 (code -5)");
        List<SimulationJournal.Entry> lignes = journal.drain();
        assertEquals(List.of(new SimulationJournal.Entry(true, "collect refusé au tick 3 (code -5)")), lignes);
        assertTrue(journal.drain().isEmpty(), "une ligne vidée ne revient pas");
    }

    @Test
    void sansDescripteurUneAssemblyEstNommeeParSonIndex() {
        SimulationJournal journal = new SimulationJournal();
        journal.setDescriber(index -> "l'assembly nommée");
        journal.setDescriber(null);

        journal.recordEvents(10L, List.of(
                fait(PhysicsEventCodes.Kind.CLAMPED, 42, PhysicsEventCodes.Data.CLAMPED_VELOCITY)));

        assertEquals(List.of("vitesse de l'assembly #42 bornée (R-180)"), textes(journal.drain()));
    }
}
