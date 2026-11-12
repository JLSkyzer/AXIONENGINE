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

    /** Ticks pendant lesquels le conducteur envoie une entrée par tick. */
    private static final int REGULIERS = 20;

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
                .thenIdle(2)
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
}
