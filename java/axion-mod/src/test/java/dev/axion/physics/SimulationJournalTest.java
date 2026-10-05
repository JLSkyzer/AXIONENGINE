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

    /** Budget de la simulation des tests de ticks lents : celui par défaut, 3 ms. */
    private static final long BUDGET = 3_000_000L;

    /** Un pas décomposé, d'un temps mural et d'un temps CPU donnés. */
    private static StepBreakdown pas(long stepNs, long cpuNs) {
        return new StepBreakdown(stepNs, cpuNs, 3_900_000L, 120_000L, 285L);
    }

    @Test
    void unCycleDansSonBudgetNeDitRienEtNeLitPasLExport() {
        SimulationJournal journal = new SimulationJournal();
        int[] lectures = {0};

        journal.recordCycle(10L, 2_000_000L, BUDGET, () -> {
            lectures[0]++;
            return pas(1_900_000L, 0L);
        });

        assertTrue(journal.drain().isEmpty());
        assertEquals(0, lectures[0], "l'export ne se lit que pour un tick à détailler");
    }

    @Test
    void lePremierTickLentEstDitAussitotAvecLePasQuiLExplique() {
        SimulationJournal journal = new SimulationJournal();

        journal.recordCycle(557L, 8_210_000L, BUDGET, () -> pas(8_050_000L, 310_000L));

        List<SimulationJournal.Entry> lignes = journal.drain();
        assertEquals(1, lignes.size());
        assertFalse(lignes.get(0).warning(), "un tick lent est un fait, pas une faute");
        assertEquals(
                "simulation : tick lent au tick 557 — cycle de simulation 8.21 ms pour un budget de"
                        + " 3.00 ms ; pas physique 8.05 ms dont 0.31 ms de calcul : le thread a"
                        + " surtout attendu (intégration 3.90 ms, contacts 0.12 ms, 285 colliders)",
                lignes.get(0).text());
    }

    @Test
    void lesTicksLentsSuivantsSontComptesEtLePireDetailleUneMinutePlusTard() {
        SimulationJournal journal = new SimulationJournal();
        journal.recordCycle(100L, 4_000_000L, BUDGET, () -> pas(3_900_000L, 0L));
        journal.drain();
        int[] lectures = {0};

        journal.recordCycle(150L, 5_000_000L, BUDGET, () -> {
            lectures[0]++;
            return pas(4_900_000L, 4_800_000L);
        });
        journal.recordCycle(160L, 9_000_000L, BUDGET, () -> {
            lectures[0]++;
            return pas(8_900_000L, 8_800_000L);
        });
        journal.recordCycle(170L, 6_000_000L, BUDGET, () -> {
            lectures[0]++;
            return pas(5_900_000L, 5_800_000L);
        });
        journal.recordCycle(171L, 1_000_000L, BUDGET, () -> {
            lectures[0]++;
            return null;
        });
        assertTrue(journal.drain().isEmpty(), "rien avant la minute");
        assertEquals(3, lectures[0], "le pas de chaque tick lent est lu, celui d'un tick nominal non");

        journal.recordCycle(100L + SimulationJournal.PERIOD_TICKS - 1, 1_000_000L, BUDGET, () -> null);
        assertTrue(journal.drain().isEmpty(), "toujours dans la minute");
        journal.recordCycle(100L + SimulationJournal.PERIOD_TICKS, 1_000_000L, BUDGET, () -> null);
        List<String> textes = textes(journal.drain());
        assertEquals(1, textes.size());
        String synthese = textes.get(0);
        assertTrue(synthese.startsWith("simulation : 3 autre(s) tick(s) lent(s) depuis le tick 150 —"
                + " pas physique au-delà du budget pour 3 (3 surtout en calcul) ; le pire, au tick"
                + " 160 — cycle de simulation 9.00 ms"), synthese);
        assertTrue(synthese.contains("le pas a surtout calculé"), synthese);
    }

    @Test
    void laSyntheseCompteLesTicksLentsSelonCeQuAFaitLeurPas() {
        SimulationJournal journal = new SimulationJournal();
        journal.recordCycle(10L, 4_000_000L, BUDGET, () -> pas(3_900_000L, 0L));
        journal.drain();

        journal.recordCycle(11L, 9_000_000L, BUDGET, () -> pas(8_900_000L, 300_000L));
        journal.recordCycle(12L, 5_000_000L, BUDGET, () -> pas(4_900_000L, 4_800_000L));
        journal.recordCycle(13L, 4_000_000L, BUDGET, () -> pas(3_900_000L, 0L));
        journal.recordCycle(14L, 3_500_000L, BUDGET, () -> pas(400_000L, 300_000L));
        journal.recordCycle(15L, 3_500_000L, BUDGET, () -> null);
        journal.flushPendingSlowTicks();

        String synthese = textes(journal.drain()).get(0);
        assertTrue(synthese.startsWith("simulation : 5 autre(s) tick(s) lent(s) depuis le tick 11 —"
                + " pas physique au-delà du budget pour 3 (1 surtout en attente, 1 surtout en calcul,"
                + " 1 sans temps CPU mesuré) ; pas physique dans son budget pour 1 ; pas illisible"
                + " pour 1 ; le pire, au tick 11 —"), synthese);
    }

    @Test
    void lePireDetailleEstLePasLePlusLongAuDelaDuBudget() {
        // Le gouverneur juge le pas, pas le cycle : un cycle long dont le pas tient son budget
        // n'explique pas une dégradation, et ne doit pas cacher le pas qui l'explique.
        SimulationJournal journal = new SimulationJournal();
        journal.recordCycle(10L, 4_000_000L, BUDGET, () -> pas(3_900_000L, 0L));
        journal.drain();

        journal.recordCycle(20L, 12_000_000L, BUDGET, () -> pas(500_000L, 400_000L));
        journal.recordCycle(21L, 5_000_000L, BUDGET, () -> pas(4_200_000L, 300_000L));
        journal.recordCycle(22L, 4_500_000L, BUDGET, () -> pas(4_100_000L, 300_000L));
        journal.flushPendingSlowTicks();

        String synthese = textes(journal.drain()).get(0);
        assertTrue(synthese.contains("le pire, au tick 21 — cycle de simulation 5.00 ms"), synthese);
    }

    @Test
    void lesTicksLentsEncoreComptesSontDitsALArret() {
        SimulationJournal journal = new SimulationJournal();
        journal.flushPendingSlowTicks();
        assertTrue(journal.drain().isEmpty(), "rien de compté, rien à dire");

        journal.recordCycle(10L, 4_000_000L, BUDGET, () -> pas(3_900_000L, 0L));
        journal.recordCycle(30L, 5_000_000L, BUDGET, () -> pas(4_900_000L, 200_000L));
        journal.drain();

        // L'arrêt survient avant la minute qui les aurait dits : ils le sont quand même.
        journal.flushPendingSlowTicks();
        List<String> textes = textes(journal.drain());
        assertEquals(1, textes.size());
        assertTrue(textes.get(0).startsWith("simulation : 1 autre(s) tick(s) lent(s) depuis le tick 30"),
                textes.get(0));

        journal.flushPendingSlowTicks();
        assertTrue(journal.drain().isEmpty(), "une fois dits, ils ne reviennent pas");
    }

    @Test
    void unChangementDePalierSuitLesTicksLentsQuiLExpliquent() {
        SimulationJournal journal = new SimulationJournal();
        journal.recordCycle(10L, 4_000_000L, BUDGET, () -> pas(3_900_000L, 0L));
        journal.drain();
        journal.recordCycle(20L, 5_000_000L, BUDGET, () -> pas(4_900_000L, 200_000L));

        journal.recordDegradation(300L, 1, 3_420_000L, BUDGET);

        List<String> textes = textes(journal.drain());
        assertEquals(2, textes.size());
        assertTrue(textes.get(0).startsWith("simulation : 1 autre(s) tick(s) lent(s) depuis le tick 20"),
                textes.get(0));
        assertTrue(textes.get(1).contains("NORMAL → DEGRADED_1"), textes.get(1));
    }

    @Test
    void ceQuiExpliqueUnTickLentSelonSonPas() {
        assertTrue(premiereLigne(pas(2_500_000L, 2_400_000L))
                .contains("pas physique 2.50 ms, dans son budget : le temps est passé ailleurs"
                        + " dans le cycle"));
        assertTrue(premiereLigne(pas(3_900_000L, 0L))
                .contains("temps CPU non mesuré : sa surveillance s'ouvre à ce dépassement"));
        assertTrue(premiereLigne(pas(4_000_000L, 2_000_000L))
                .contains("dont 2.00 ms de calcul : le pas a surtout calculé"));
        assertTrue(premiereLigne(null)
                .endsWith("pas physique illisible dans l'export des métriques"));
    }

    /** La ligne d'un premier tick lent de 5 ms, dont le pas est {@code step}. */
    private static String premiereLigne(StepBreakdown step) {
        SimulationJournal journal = new SimulationJournal();
        journal.recordCycle(10L, 5_000_000L, BUDGET, () -> step);
        return journal.drain().get(0).text();
    }

    @Test
    void unNouveauServeurRepartDeZeroPourLesTicksLents() {
        SimulationJournal journal = new SimulationJournal();
        journal.recordCycle(5_000L, 4_000_000L, BUDGET, () -> pas(3_900_000L, 0L));
        journal.recordCycle(5_010L, 4_000_000L, BUDGET, () -> pas(3_900_000L, 0L));
        journal.drain();

        // Les ticks d'un nouveau serveur repartent de zéro : son premier tick lent est dit.
        journal.recordCycle(3L, 4_000_000L, BUDGET, () -> pas(3_900_000L, 0L));
        List<String> textes = textes(journal.drain());
        assertEquals(1, textes.size());
        assertTrue(textes.get(0).startsWith("simulation : tick lent au tick 3 —"), textes.get(0));
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
