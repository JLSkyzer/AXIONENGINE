package dev.axion.forge;

import dev.axion.forge.net.JoueurSimule;
import dev.axion.net.CorrectionMessage;
import dev.axion.net.Fragments;
import dev.axion.net.InputMessage;
import dev.axion.seat.InputQueue;
import dev.axion.seat.VehicleInput;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * ADR-168, le serveur de la prédiction (tranche 6b), par le vrai canal des entrées et des corrections : il applique
 * une entrée par tick, dans l'ordre des séquences (D3), et corrige le seul conducteur à chaque tick où son véhicule
 * avance (D4).
 */
@GameTestHolder("axion")
@PrefixGameTestTemplate(false)
public final class PredictionGameTests {

    /** Le dessus de la dalle du gabarit {@code plancher_16}, en coordonnées du test. */
    private static final double DESSUS_DALLE = 2.0;

    private static final float[] IDENTITE = {0f, 0f, 0f, 1f};

    /** L'accélérateur à fond : le véhicule de test roule tout droit. */
    private static final VehicleInput PLEIN = new VehicleInput(1f, 0f, 0f, 0f, 0f, (byte) 0, 0);

    /**
     * Ticks pendant lesquels le conducteur envoie une entrée par tick : moins d'une seconde, car avec ses acquittements
     * (un par snapshot), vingt entrées toucheraient la limite de R-1690, 40 trames par seconde.
     */
    private static final int REGULIERS = 12;

    /** Entrées d'une rafale, toutes posées dans un même tick : au-delà de {@link InputQueue#MAX_PENDING}. */
    private static final int RAFALE = 5;

    private PredictionGameTests() {}

    private static byte[] trame(int sequence, AxionEntity vehicule, int siege, VehicleInput entree) {
        return Fragments.split(new InputMessage(sequence, vehicule.getId(), siege, entree).encode(), 0).get(0);
    }

    /**
     * D3 et D4 : une entrée par tick, et une correction par tick qui acquitte la séquence appliquée — l'écart entre le
     * tick de la correction et cette séquence ne varie pas. Une rafale rattrape en sautant aux plus récentes. Le
     * passager ne reçoit rien ; le conducteur parti, plus rien ne part.
     */
    @GameTest(template = "test/plancher_16", timeoutTicks = 400)
    public static void leServeurAppliqueUneEntreeParTickEtCorrigeLeConducteur(GameTestHelper helper) {
        Vec3 depart = new Vec3(8.0, DESSUS_DALLE, 12.5);
        ObservateursDeTest.declarer(helper, depart);
        AxionEntity vehicule = AssembliesDeTest.poser(helper, "axion:test/vehicule", depart, IDENTITE);
        JoueurSimule conducteur = JoueurSimule.rejoindre(
                helper.getLevel(), "predit_conducteur", helper.absoluteVec(new Vec3(2.0, DESSUS_DALLE, 12.5)));
        JoueurSimule passager = JoueurSimule.rejoindre(
                helper.getLevel(), "predit_passager", helper.absoluteVec(new Vec3(14.0, DESSUS_DALLE, 12.5)));
        boolean[] regulier = {false};
        int[] sequence = {0};
        List<CorrectionMessage> recues = new ArrayList<>();
        helper.onEachTick(() -> {
            conducteur.tick();
            passager.tick();
            recues.addAll(conducteur.drainCorrections());
            int siege = vehicule.seatOf(conducteur.joueur());
            if (regulier[0] && siege >= 0) {
                conducteur.envoyer(trame(++sequence[0], vehicule, siege, PLEIN));
            }
        });
        int[] avantRafale = new int[1];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(vehicule.hasPhysicalBounds(), "le véhicule est simulé"))
                .thenIdle(30)
                .thenExecute(() -> {
                    helper.assertTrue(vehicule.reseat(conducteur.joueur(), "conducteur"), "au volant");
                    helper.assertTrue(vehicule.reseat(passager.joueur(), "passager"), "le passager");
                    recues.clear();
                    regulier[0] = true;
                })
                .thenIdle(REGULIERS)
                .thenExecute(() -> regulier[0] = false)
                // Un tick encore : la dernière envoyée s'applique.
                .thenExecuteAfter(1, () -> {
                    helper.assertTrue(recues.size() >= REGULIERS - 4, "une correction par tick : " + recues.size()
                            + " en " + REGULIERS + " ticks");
                    List<CorrectionMessage> appliquees = recues.stream().filter(CorrectionMessage::applied).toList();
                    helper.assertTrue(appliquees.size() >= REGULIERS - 6, "des entrées appliquées : " + appliquees.size());
                    long ecart = appliquees.get(0).serverTick() - appliquees.get(0).appliedSequence();
                    for (int i = 0; i < appliquees.size(); i++) {
                        CorrectionMessage correction = appliquees.get(i);
                        helper.assertTrue(correction.entity() == vehicule.getId()
                                && correction.states().size() == 1
                                && correction.states().get(0).entity() == vehicule.getId(),
                                "la correction du véhicule, un état : " + correction);
                        helper.assertTrue(correction.serverTick() - correction.appliedSequence() == ecart,
                                "D3 : une entrée par tick, sans saut ni redite — tick " + correction.serverTick()
                                        + ", séquence " + correction.appliedSequence() + ", écart attendu " + ecart);
                        if (i > 0) {
                            helper.assertTrue(correction.serverTick() == appliquees.get(i - 1).serverTick() + 1,
                                    "D4 : à chaque tick : " + appliquees.get(i - 1).serverTick() + " puis "
                                            + correction.serverTick());
                        }
                    }
                    helper.assertTrue(vehicule.driverSequence() == sequence[0],
                            "la dernière envoyée est appliquée : " + vehicule.driverSequence() + ", " + sequence[0]);
                })
                // Une seconde neuve : vingt entrées et vingt acquittements par seconde touchent déjà la limite de R-1690
                // (40 trames), et la rafale, tombée dans la même seconde, y perdait ses dernières (CI, 2026-11-15).
                .thenIdle(20)
                .thenExecute(() -> {
                    // La rafale : plus que la file n'en garde, toutes dans ce tick.
                    avantRafale[0] = sequence[0];
                    int siege = vehicule.seatOf(conducteur.joueur());
                    for (int i = 0; i < RAFALE; i++) {
                        conducteur.envoyer(trame(++sequence[0], vehicule, siege, PLEIN));
                    }
                    recues.clear();
                })
                .thenIdle(4)
                .thenExecute(() -> {
                    List<Long> acquittees = recues.stream().map(CorrectionMessage::appliedSequence).distinct().toList();
                    int premiere = avantRafale[0] + RAFALE - InputQueue.MAX_PENDING + 1;
                    helper.assertTrue(acquittees.contains((long) premiere) && acquittees.contains((long) sequence[0]),
                            "D3 : la file saute aux " + InputQueue.MAX_PENDING + " plus récentes, " + premiere + " puis "
                                    + sequence[0] + " : " + acquittees);
                    helper.assertTrue(acquittees.stream().noneMatch(s -> s > avantRafale[0] && s < premiere),
                            "les plus anciennes sont sautées : " + acquittees);
                    helper.assertTrue(passager.drainCorrections().isEmpty(), "D4 : le passager ne reçoit rien");
                    conducteur.joueur().stopRiding();
                })
                .thenIdle(2)
                .thenExecute(recues::clear)
                .thenIdle(5)
                .thenExecute(() -> {
                    helper.assertTrue(recues.isEmpty(), "le conducteur parti, plus rien : " + recues.size());
                    conducteur.quitter();
                    passager.quitter();
                })
                .thenSucceed();
    }

    /**
     * ADR-171 D7, Q8 (CAP-37) : la correction du conducteur porte l'état de tout l'ensemble de son véhicule — le
     * véhicule d'abord, puis la remorque qu'il tire à la corde.
     */
    @GameTest(template = "test/plancher_16", timeoutTicks = 300)
    public static void adr171_LaCorrectionPorteLEnsembleDuVehicule(GameTestHelper helper) {
        Vec3 depart = new Vec3(8.0, DESSUS_DALLE + 0.3, 6.0);
        ObservateursDeTest.declarer(helper, depart);
        AxionEntity vehicule = AssembliesDeTest.poser(helper, "axion:test/remorqueuse", depart, IDENTITE);
        AxionEntity remorque = AssembliesDeTest.poser(helper, "axion:test/remorque",
                new Vec3(8.0, DESSUS_DALLE + 0.3, 11.0), IDENTITE);
        JoueurSimule conducteur = JoueurSimule.rejoindre(
                helper.getLevel(), "ensemble_conducteur", helper.absoluteVec(new Vec3(2.0, DESSUS_DALLE, 6.0)));
        boolean[] regulier = {false};
        int[] sequence = {0};
        List<CorrectionMessage> recues = new ArrayList<>();
        helper.onEachTick(() -> {
            conducteur.tick();
            recues.addAll(conducteur.drainCorrections());
            int siege = vehicule.seatOf(conducteur.joueur());
            if (regulier[0] && siege >= 0) {
                conducteur.envoyer(trame(++sequence[0], vehicule, siege, VehicleInput.NONE));
            }
        });
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(vehicule.simulated() && remorque.simulated(), "simulés"))
                .thenExecute(() -> {
                    dev.axion.api.attach.AttachResult corde = ForgeAxionApi.view(vehicule).attachments().attach(
                            "attelage", ForgeAxionApi.view(remorque), "attelage", "rope");
                    helper.assertTrue(corde.attached(), "la corde : " + corde.message());
                    helper.assertTrue(vehicule.reseat(conducteur.joueur(), "conducteur"), "au volant");
                    recues.clear();
                    regulier[0] = true;
                })
                .thenIdle(REGULIERS)
                .thenExecute(() -> {
                    regulier[0] = false;
                    List<CorrectionMessage> deux = recues.stream().filter(c -> c.states().size() == 2).toList();
                    helper.assertTrue(!deux.isEmpty(), "des corrections portent deux états : "
                            + recues.stream().map(c -> c.states().size()).toList());
                    for (CorrectionMessage correction : deux) {
                        helper.assertTrue(correction.states().get(0).entity() == vehicule.getId()
                                        && correction.states().get(1).entity() == remorque.getId(),
                                "le véhicule d'abord, puis sa remorque : " + correction.states().stream()
                                        .map(CorrectionMessage.State::entity).toList());
                    }
                    conducteur.joueur().stopRiding();
                    conducteur.quitter();
                })
                .thenSucceed();
    }

    /** Entrées d'une rafale réglée, toutes posées dans un même tick : au-delà de {@link InputQueue#MAX_PACED}. */
    private static final int RAFALE_REGLEE = InputQueue.MAX_PACED + 5;

    private static byte[] trameReglee(int sequence, AxionEntity vehicule, int siege, VehicleInput entree) {
        return Fragments.split(new InputMessage(sequence, vehicule.getId(), siege, entree, true).encode(), 0).get(0);
    }

    /**
     * T-417 (ADR-168 D9, INV-02) : sous la prédiction, une entrée falsifiée est ignorée. Le conducteur ne commande
     * rien ; un passager, puis un joueur à pied, envoient l'accélérateur à fond pour le siège du conducteur, en se
     * disant réglés ; le passager renvoie au serveur une correction reçue — un état, que seul le serveur écrit. Rien ne
     * part, rien ne bouge, l'acquittement du conducteur suit ses seules entrées. Puis une rafale réglée au-delà de
     * {@link InputQueue#MAX_PACED} : la file rattrape, elle ne s'allonge pas sans fin.
     */
    @GameTest(template = "test/plancher_16", timeoutTicks = 400)
    public static void t417_uneEntreeFalsifieeEstIgnoree(GameTestHelper helper) {
        Vec3 depart = new Vec3(8.0, DESSUS_DALLE, 12.5);
        ObservateursDeTest.declarer(helper, depart);
        AxionEntity vehicule = AssembliesDeTest.poser(helper, "axion:test/vehicule", depart, IDENTITE);
        JoueurSimule conducteur = JoueurSimule.rejoindre(
                helper.getLevel(), "t417_conducteur", helper.absoluteVec(new Vec3(2.0, DESSUS_DALLE, 12.5)));
        JoueurSimule passager = JoueurSimule.rejoindre(
                helper.getLevel(), "t417_passager", helper.absoluteVec(new Vec3(14.0, DESSUS_DALLE, 12.5)));
        JoueurSimule pieton = JoueurSimule.rejoindre(
                helper.getLevel(), "t417_pieton", helper.absoluteVec(new Vec3(14.0, DESSUS_DALLE, 4.0)));
        boolean[] falsifier = {false};
        int[] sequences = {0, 0, 0};
        List<CorrectionMessage> recues = new ArrayList<>();
        Vec3[] avant = new Vec3[1];
        helper.onEachTick(() -> {
            conducteur.tick();
            passager.tick();
            pieton.tick();
            recues.addAll(conducteur.drainCorrections());
            if (!falsifier[0]) {
                return;
            }
            int siege = vehicule.seatOf(conducteur.joueur());
            conducteur.envoyer(trameReglee(++sequences[0], vehicule, siege, VehicleInput.NONE));
            passager.envoyer(trameReglee(++sequences[1], vehicule, siege, PLEIN));
            pieton.envoyer(trameReglee(++sequences[2], vehicule, siege, PLEIN));
            if (!recues.isEmpty()) {
                // Un état renvoyé : celui que le serveur vient d'écrire, ce qu'un client falsifié a de plus crédible.
                passager.envoyer(Fragments.split(recues.get(recues.size() - 1).encode(), 0).get(0));
            }
        });
        long[] sauteesAvant = new long[1];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(vehicule.hasPhysicalBounds(), "le véhicule est simulé"))
                .thenIdle(30)
                .thenExecute(() -> {
                    helper.assertTrue(vehicule.reseat(conducteur.joueur(), "conducteur"), "au volant");
                    helper.assertTrue(vehicule.reseat(passager.joueur(), "passager"), "le passager");
                    avant[0] = vehicule.position();
                    falsifier[0] = true;
                })
                // Dix ticks : le conducteur envoie une entrée par tick et acquitte chaque snapshot ; vingt de chaque
                // toucheraient la limite de R-1690, qui jetterait ses propres entrées.
                .thenIdle(10)
                .thenExecute(() -> falsifier[0] = false)
                .thenExecuteAfter(1, () -> {
                    int siege = vehicule.seatOf(conducteur.joueur());
                    helper.assertTrue(VehicleInput.NONE.equals(vehicule.seatInput(siege)),
                            "le siège du conducteur n'applique que ses entrées : " + vehicule.seatInput(siege));
                    helper.assertTrue(vehicule.driverSequence() == sequences[0],
                            "l'acquittement suit les seules entrées du conducteur : " + vehicule.driverSequence()
                                    + ", " + sequences[0]);
                    helper.assertTrue(vehicule.position().distanceTo(avant[0]) < 0.01,
                            "INV-02 : ni les entrées d'autrui ni l'état renvoyé n'ont déplacé le véhicule : "
                                    + vehicule.position().distanceTo(avant[0]) + " m");
                    helper.assertTrue(passager.drainCorrections().isEmpty() && pieton.drainCorrections().isEmpty(),
                            "D4 : seul le conducteur reçoit des corrections");
                })
                // Une seconde de silence : le débit de R-1690 se rouvre, la rafale passe entière.
                .thenIdle(25)
                .thenExecute(() -> {
                    int siege = vehicule.seatOf(conducteur.joueur());
                    sauteesAvant[0] = vehicule.seatCadence(siege)[1];
                    for (int i = 0; i < RAFALE_REGLEE; i++) {
                        conducteur.envoyer(trameReglee(++sequences[0], vehicule, siege, PLEIN));
                    }
                })
                .thenExecuteAfter(3, () -> {
                    long sautees = vehicule.seatCadence(vehicule.seatOf(conducteur.joueur()))[1] - sauteesAvant[0];
                    helper.assertTrue(sautees == RAFALE_REGLEE - InputQueue.MAX_PACED,
                            "D9 : la rafale réglée rattrape aux " + InputQueue.MAX_PACED + " plus récentes : "
                                    + sautees + " sautée(s)");
                    helper.assertTrue(vehicule.driverQueueDepth() <= InputQueue.MAX_PACED,
                            "la file ne s'allonge pas : " + vehicule.driverQueueDepth());
                    conducteur.quitter();
                    passager.quitter();
                    pieton.quitter();
                })
                .thenSucceed();
    }
}
