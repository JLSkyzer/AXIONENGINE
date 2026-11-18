package dev.axion.forge;

import dev.axion.api.assembly.Assembly;
import dev.axion.api.assembly.InteractionListener;
import dev.axion.api.assembly.SeatListener;
import dev.axion.content.ContentRegistry.ListenerEntry;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * ADR-174, tranche 9c : les écouteurs des sièges et des interactions jugent un geste avant qu'il ait lieu — le premier
 * refus l'emporte —, sont prévenus après ; une exception ne refuse rien, et des exceptions de suite désactivent
 * l'écouteur (R-1770).
 */
@GameTestHolder("axion")
@PrefixGameTestTemplate(false)
public final class EcouteursGameTests {

    private static final String DEFINITION = "axion:test/siege_actions";

    /** Le dessus de la dalle du gabarit {@code plancher}, en coordonnées du test. */
    private static final double DESSUS_DALLE = 2.0;

    private EcouteursGameTests() {}

    @GameTest(template = "test/plancher", timeoutTicks = 200)
    public static void adr174_LesEcouteursJugentEtSuivent(GameTestHelper helper) {
        ObservateursDeTest.declarer(helper, new Vec3(4.0, DESSUS_DALLE, 4.0));
        AxionEntity assembly = AssembliesDeTest.poser(helper, DEFINITION, new Vec3(4.5, DESSUS_DALLE, 4.5),
                new float[] {0f, 0f, 0f, 1f});
        ServerPlayer joueur = SiegesGameTests.joueur(helper, "ecoute");
        List<String> journal = new ArrayList<>();
        boolean[] refuserSiege = {true};
        boolean[] refuserInteraction = {true};
        SeatListener sieges = new SeatListener() {
            @Override
            public String allowEnter(Assembly a, String seat, Entity passenger) {
                if (a.entity() != assembly) {
                    return null;
                }
                journal.add("juge " + seat);
                return refuserSiege[0] ? "axion.test.siege_refuse" : null;
            }

            @Override
            public void entered(Assembly a, String seat, Entity passenger) {
                if (a.entity() == assembly) {
                    journal.add("monté " + seat);
                }
            }

            @Override
            public void exited(Assembly a, String seat, Entity passenger, String cause) {
                if (a.entity() == assembly) {
                    journal.add("descendu " + seat + " " + cause);
                }
            }
        };
        SeatListener casse = new SeatListener() {
            @Override
            public String allowEnter(Assembly a, String seat, Entity passenger) {
                if (a.entity() != assembly) {
                    return null;
                }
                throw new IllegalStateException("écouteur d'essai cassé exprès");
            }
        };
        InteractionListener interactions = new InteractionListener() {
            @Override
            public String allow(Assembly a, String node, Player player, String action) {
                if (a.entity() != assembly) {
                    return null;
                }
                journal.add("juge " + node + " " + action);
                return refuserInteraction[0] ? "axion.test.interaction_refusee" : null;
            }

            @Override
            public void interacted(Assembly a, String node, Player player, String action) {
                if (a.entity() != assembly) {
                    return;
                }
                journal.add("joué " + node + " " + action);
            }
        };
        List<ListenerEntry<SeatListener>> sieges0 = ContentListeners.seats();
        List<ListenerEntry<InteractionListener>> interactions0 = ContentListeners.interactions();
        // Le cassé d'abord : son exception ne refuse rien, le suivant juge encore. Ceux des mods restent publiés ; les
        // écouteurs d'essai ne jugent que leur assembly, les GameTests tournant ensemble.
        List<ListenerEntry<SeatListener>> avecSieges = new ArrayList<>(sieges0);
        avecSieges.add(new ListenerEntry<>("essai", casse));
        avecSieges.add(new ListenerEntry<>("essai", sieges));
        List<ListenerEntry<InteractionListener>> avecInteractions = new ArrayList<>(interactions0);
        avecInteractions.add(new ListenerEntry<>("essai", interactions));
        ContentListeners.publish(avecSieges, avecInteractions);
        ContentListeners.limit(3);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(assembly.hasPhysicalBounds(), "l'assembly est simulée"))
                .thenIdle(20)
                .thenExecute(() -> {
                    try {
                        helper.assertTrue(!assembly.sit(joueur, 0), "la montée refusée n'a pas lieu");
                        helper.assertTrue(!assembly.sit(joueur, 0), "refusée encore");
                        helper.assertTrue(joueur.getVehicle() == null, "le joueur n'est pas assis");
                        helper.assertTrue(journal.equals(List.of("juge conducteur", "juge conducteur")),
                                "journal : " + journal);

                        helper.assertTrue(SiegesGameTests.cliquer(assembly, joueur, 2f, 0.5f, 0f)
                                == InteractionResult.CONSUME, "un refus consomme le clic");
                        helper.assertTrue(assembly.nodeStates().of(AxionEntities.seating(DEFINITION).orElseThrow()
                                .actionNodes().get("caisse_vue")) == null, "l'interaction refusée n'a rien changé");
                        refuserInteraction[0] = false;
                        helper.assertTrue(SiegesGameTests.cliquer(assembly, joueur, 2f, 0.5f, 0f)
                                == InteractionResult.CONSUME, "l'interaction a lieu");
                        helper.assertTrue(journal.containsAll(List.of("juge porte_d actions", "joué porte_d actions")),
                                "journal : " + journal);

                        refuserSiege[0] = false;
                        helper.assertTrue(assembly.sit(joueur, 0), "la montée acceptée a lieu");
                        helper.assertTrue(journal.contains("monté conducteur"), "journal : " + journal);
                        helper.assertTrue(ContentListeners.disabled(casse),
                                "trois exceptions de suite désactivent l'écouteur cassé");
                        joueur.stopRiding();
                        helper.assertTrue(journal.get(journal.size() - 1).startsWith("descendu conducteur"),
                                "journal : " + journal);
                    } finally {
                        ContentListeners.publish(sieges0, interactions0);
                        ContentListeners.limit(20);
                    }
                })
                .thenSucceed();
    }
}
