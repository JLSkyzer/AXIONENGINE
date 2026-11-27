package dev.axion.forge;

import dev.axion.api.assembly.Assembly;
import dev.axion.api.assembly.Transform;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** ADR-183 D3, D4 (CAP-34) : une assembly se fige, se sauve figée et se mène. */
@GameTestHolder("axion")
@PrefixGameTestTemplate(false)
public final class GelGameTests {

    /** Le dessus de la dalle du gabarit, en coordonnées du test. */
    private static final double DESSUS_DALLE = 2.0;

    private static final float[] DROIT = {0f, 0f, 0f, 1f};

    private static final String CAISSE = "axion:test/siege";

    private GelGameTests() {}

    /**
     * {@code /axion freeze} fige une assembly en l'air : ni la pesanteur ni une impulsion ne la déplacent ; sauvée
     * figée, elle l'est encore relue ; {@code off} la rend au moteur, et elle retombe.
     */
    @GameTest(template = "test/plancher_16", timeoutTicks = 200)
    public static void adr183_laCommandeFigeUneAssemblyQuiSeGarde(GameTestHelper helper) {
        ObservateursDeTest.declarer(helper, new Vec3(8.0, DESSUS_DALLE, 8.0));
        AxionEntity assembly = AssembliesDeTest.poser(helper, CAISSE, new Vec3(4.5, DESSUS_DALLE + 3.0, 4.5), DROIT);
        MinecraftServer serveur = helper.getLevel().getServer();
        Vec3[] place = new Vec3[1];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(assembly.hasPhysicalBounds(), "l'assembly est simulée"))
                .thenExecute(() -> {
                    java.util.List<String> reponse = ParametresGameTests.commande(serveur,
                            "axion freeze " + assembly.getUUID());
                    helper.assertTrue(reponse.size() == 1 && reponse.get(0).contains("figée"), "réponse : " + reponse);
                    helper.assertTrue(ForgeAxionApi.view(assembly).isFrozen(), "l'API la dit figée");
                })
                .thenIdle(3)
                .thenExecute(() -> {
                    place[0] = assembly.position();
                    ForgeAxionApi.view(assembly).applyImpulse(new Vec3(5000.0, 0.0, 0.0), null);
                })
                .thenIdle(20)
                .thenExecute(() -> {
                    helper.assertTrue(assembly.position().distanceTo(place[0]) < 0.01,
                            "figée, elle ne bouge pas : " + assembly.position() + " pour " + place[0]);
                    CompoundTag sauvegarde = new CompoundTag();
                    assembly.saveWithoutId(sauvegarde);
                    helper.assertTrue(sauvegarde.getBoolean("axion:frozen"), "le gel se sauve : " + sauvegarde);
                    AxionEntity relue = AxionEntities.ASSEMBLY.get().create(helper.getLevel());
                    relue.load(sauvegarde);
                    helper.assertTrue(ForgeAxionApi.view(relue).isFrozen(), "relue figée");
                    java.util.List<String> reponse = ParametresGameTests.commande(serveur,
                            "axion freeze " + assembly.getUUID() + " off");
                    helper.assertTrue(reponse.size() == 1 && reponse.get(0).contains("rendue"), "réponse : " + reponse);
                    CompoundTag encore = new CompoundTag();
                    assembly.saveWithoutId(encore);
                    helper.assertFalse(encore.contains("axion:frozen"), "défigée, la clé part");
                })
                .thenWaitUntil(() -> helper.assertTrue(assembly.getY() < place[0].y - 1.0, "elle retombe"))
                .thenExecute(assembly::discard)
                .thenSucceed();
    }

    /** {@code setKinematicTransform} mène une assembly figée à la pose demandée ; une dynamique le refuse. */
    @GameTest(template = "test/plancher_16", timeoutTicks = 200)
    public static void adr183_uneAssemblyFigeeSeMene(GameTestHelper helper) {
        ObservateursDeTest.declarer(helper, new Vec3(8.0, DESSUS_DALLE, 8.0));
        AxionEntity assembly = AssembliesDeTest.poser(helper, CAISSE, new Vec3(4.5, DESSUS_DALLE + 0.05, 4.5), DROIT);
        Vec3[] depart = new Vec3[1];
        int[] pas = new int[1];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(assembly.hasPhysicalBounds(), "l'assembly est simulée"))
                .thenExecute(() -> {
                    Assembly vue = ForgeAxionApi.view(assembly);
                    Transform ici = new Transform(assembly.position(), assembly.orientation(), new Vec3(1, 1, 1));
                    boolean refusee = false;
                    try {
                        vue.setKinematicTransform(ici);
                    } catch (IllegalStateException attendu) {
                        refusee = true;
                    }
                    helper.assertTrue(refusee, "une assembly dynamique ne se mène pas");
                    vue.setFrozen(true);
                    depart[0] = assembly.position();
                })
                .thenExecuteFor(20, () -> {
                    pas[0]++;
                    Vec3 but = depart[0].add(0.0, 0.1 * pas[0], 0.0);
                    ForgeAxionApi.view(assembly).setKinematicTransform(
                            new Transform(but, assembly.orientation(), new Vec3(1, 1, 1)));
                })
                .thenIdle(3)
                .thenExecute(() -> {
                    double monte = assembly.getY() - depart[0].y;
                    helper.assertTrue(Math.abs(monte - 0.1 * pas[0]) < 0.05,
                            "menée de " + 0.1 * pas[0] + " blocs : montée de " + monte);
                    assembly.discard();
                })
                .thenSucceed();
    }

    /** Le conducteur d'une assembly figée reçoit, au plus une fois par seconde, la correction qui lui rend la main. */
    @GameTest(template = "test/plancher_16", timeoutTicks = 100)
    public static void adr183_lAssemblyFigeeRendLaMainAuConducteurUneFoisParSeconde(GameTestHelper helper) {
        ObservateursDeTest.declarer(helper, new Vec3(8.0, DESSUS_DALLE, 8.0));
        AxionEntity assembly = AssembliesDeTest.poser(helper, CAISSE, new Vec3(4.5, DESSUS_DALLE + 0.05, 4.5), DROIT);
        dev.axion.net.CorrectionMessage.State etat = new dev.axion.net.CorrectionMessage.State(1, new byte[16]);
        helper.startSequence()
                .thenExecute(() -> {
                    ForgeAxionApi.view(assembly).setFrozen(true);
                    helper.assertTrue(assembly.releaseCorrection(1000, etat) != null, "aussitôt figée");
                    helper.assertTrue(assembly.releaseCorrection(1001, etat) == null, "pas à chaque tick");
                    helper.assertTrue(assembly.releaseCorrection(1000 + AxionEntity.SLEEPING_CORRECTION_TICKS, etat) != null,
                            "une par seconde");
                    ForgeAxionApi.view(assembly).setFrozen(false);
                    ForgeAxionApi.view(assembly).setFrozen(true);
                    helper.assertTrue(assembly.releaseCorrection(1002 + AxionEntity.SLEEPING_CORRECTION_TICKS, etat) != null,
                            "figée de nouveau, aussitôt");
                    assembly.discard();
                })
                .thenSucceed();
    }
}
