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

    /**
     * Un ascenseur : un mob debout sur une assembly figée la suit quand un mod la mène vers le haut, puis vers le bas,
     * puis de côté.
     */
    @GameTest(template = "test/plancher_16", timeoutTicks = 300)
    public static void adr183_unAscenseurMeneEmporteCeQuiSeTientDessus(GameTestHelper helper) {
        ObservateursDeTest.declarer(helper, new Vec3(8.0, DESSUS_DALLE, 8.0));
        AxionEntity plateau = AssembliesDeTest.poser(helper, CAISSE, new Vec3(4.5, DESSUS_DALLE, 4.5), DROIT);
        net.minecraft.world.entity.animal.Pig cochon =
                net.minecraft.world.entity.EntityType.PIG.create(helper.getLevel());
        Vec3[] depart = new Vec3[1];
        double[] ecart = new double[1];
        Vec3[] but = new Vec3[1];
        Runnable mener = () -> ForgeAxionApi.view(plateau).setKinematicTransform(
                new Transform(but[0], plateau.orientation(), new Vec3(1, 1, 1)));
        java.util.function.Consumer<String> suit = ou -> {
            double vertical = cochon.getY() - plateau.getY() - ecart[0];
            double horizontal = cochon.position().subtract(plateau.position()).horizontalDistance();
            helper.assertTrue(Math.abs(vertical) < 0.25 && horizontal < 1.0, ou + " : le cochon suit, à " + vertical
                    + " de haut et " + horizontal + " de côté ; plateau " + plateau.position() + ", cochon "
                    + cochon.position());
        };
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(plateau.hasPhysicalBounds() && plateau.carryPoses(0) != null,
                        "simulé"))
                .thenIdle(10)
                .thenExecute(() -> {
                    ForgeAxionApi.view(plateau).setFrozen(true);
                    cochon.setNoAi(true);
                    helper.getLevel().addFreshEntity(cochon);
                    cochon.moveTo(plateau.getX(), plateau.getY() + 2.0, plateau.getZ(), 0f, 0f);
                    cochon.move(net.minecraft.world.entity.MoverType.SELF, new Vec3(0.0, -2.0, 0.0));
                    helper.assertTrue(CarrySupports.of(cochon) != null, "le cochon repose sur lui");
                })
                .thenIdle(5)
                .thenExecute(() -> {
                    depart[0] = plateau.position();
                    but[0] = depart[0];
                    ecart[0] = cochon.getY() - plateau.getY();
                })
                .thenExecuteFor(30, () -> {
                    but[0] = but[0].add(0.0, 0.1, 0.0);
                    mener.run();
                })
                .thenIdle(5)
                .thenExecute(() -> {
                    helper.assertTrue(plateau.getY() - depart[0].y > 2.8, "monté : " + (plateau.getY() - depart[0].y));
                    suit.accept("en haut");
                })
                .thenExecuteFor(30, () -> {
                    but[0] = but[0].add(0.0, -0.1, 0.0);
                    mener.run();
                })
                .thenIdle(5)
                .thenExecute(() -> suit.accept("redescendu"))
                .thenExecuteFor(30, () -> {
                    but[0] = but[0].add(0.15, 0.0, 0.0);
                    mener.run();
                })
                .thenIdle(5)
                .thenExecute(() -> {
                    helper.assertTrue(plateau.getX() - depart[0].x > 4.0, "mené de côté");
                    suit.accept("de côté");
                    cochon.discard();
                    plateau.discard();
                })
                .thenSucceed();
    }

    /** Figée et immobile, une assembly n'envoie plus d'état aux joueurs (R-1663) ; menée, elle en envoie de nouveau. */
    @GameTest(template = "test/plancher_16", timeoutTicks = 200)
    public static void adr183_uneAssemblyFigeeImmobileNEnvoiePlusRien(GameTestHelper helper) {
        AxionEntity assembly = AssembliesDeTest.poser(helper, "axion:test/vehicule",
                new Vec3(8.0, DESSUS_DALLE + 2.0, 8.0), DROIT);
        dev.axion.forge.net.JoueurSimule[] temoin = new dev.axion.forge.net.JoueurSimule[1];
        helper.onEachTick(() -> {
            if (temoin[0] != null && temoin[0].connecte()) {
                temoin[0].tick();
            }
        });
        Vec3[] place = new Vec3[1];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(assembly.hasPhysicalBounds(), "simulée"))
                .thenExecute(() -> {
                    temoin[0] = dev.axion.forge.net.JoueurSimule.rejoindre(helper.getLevel(), "temoin_gel",
                            helper.absoluteVec(new Vec3(4.0, DESSUS_DALLE, 4.0)));
                    ForgeAxionApi.view(assembly).setFrozen(true);
                    place[0] = assembly.position();
                })
                .thenIdle(40)
                .thenExecute(() -> temoin[0].drainRecus())
                .thenIdle(40)
                .thenExecute(() -> {
                    long recus = temoin[0].drainRecus().stream().filter(r -> r.handle() == assembly.getId()).count();
                    helper.assertTrue(recus <= 1, "immobile, rien : " + recus + " état(s) en 40 ticks");
                })
                .thenExecuteFor(10, () -> {
                    place[0] = place[0].add(0.1, 0.0, 0.0);
                    ForgeAxionApi.view(assembly).setKinematicTransform(
                            new Transform(place[0], assembly.orientation(), new Vec3(1, 1, 1)));
                })
                .thenIdle(2)
                .thenExecute(() -> {
                    try {
                        long recus = temoin[0].drainRecus().stream().filter(r -> r.handle() == assembly.getId()).count();
                        helper.assertTrue(recus >= 5, "menée, ses états partent : " + recus);
                    } finally {
                        temoin[0].quitter();
                        assembly.discard();
                    }
                })
                .thenSucceed();
    }

    /**
     * Menée contre un mob qui se tient sur son chemin, une assembly figée le pousse devant elle (D4) ; un porte-armure,
     * que Minecraft ne tient pas pour poussable, reste à sa place, comme contre un véhicule qui roule.
     */
    @GameTest(template = "test/plancher_16", timeoutTicks = 200)
    public static void adr183_uneAssemblyMeneePousseUnMobSurSonChemin(GameTestHelper helper) {
        ObservateursDeTest.declarer(helper, new Vec3(8.0, DESSUS_DALLE, 8.0));
        AxionEntity mur = AssembliesDeTest.poser(helper, CAISSE, new Vec3(4.5, DESSUS_DALLE + 0.05, 8.5), DROIT);
        net.minecraft.world.entity.animal.Pig cochon =
                net.minecraft.world.entity.EntityType.PIG.create(helper.getLevel());
        net.minecraft.world.entity.decoration.ArmorStand mannequin =
                net.minecraft.world.entity.EntityType.ARMOR_STAND.create(helper.getLevel());
        Vec3[] but = new Vec3[1];
        double[] depart = new double[1];
        Vec3[] place = new Vec3[1];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(mur.hasPhysicalBounds() && mur.carryPoses(0) != null, "simulé"))
                .thenIdle(10)
                .thenExecute(() -> {
                    ForgeAxionApi.view(mur).setFrozen(true);
                    Vec3 devant = helper.absoluteVec(new Vec3(7.0, DESSUS_DALLE, 8.5));
                    cochon.setNoAi(false);
                    cochon.goalSelector.removeAllGoals(goal -> true);
                    cochon.moveTo(devant.x, devant.y, devant.z, 0f, 0f);
                    helper.getLevel().addFreshEntity(cochon);
                    Vec3 plus = helper.absoluteVec(new Vec3(9.5, DESSUS_DALLE, 8.5));
                    mannequin.moveTo(plus.x, plus.y, plus.z, 0f, 0f);
                    mannequin.setNoGravity(true);
                    helper.getLevel().addFreshEntity(mannequin);
                })
                .thenIdle(10)
                .thenExecute(() -> {
                    but[0] = mur.position();
                    depart[0] = cochon.getX();
                    place[0] = mannequin.position();
                })
                .thenExecuteFor(40, () -> {
                    but[0] = but[0].add(0.1, 0.0, 0.0);
                    ForgeAxionApi.view(mur).setKinematicTransform(
                            new Transform(but[0], mur.orientation(), new Vec3(1, 1, 1)));
                })
                .thenIdle(10)
                .thenExecute(() -> {
                    double pousse = cochon.getX() - depart[0];
                    helper.assertTrue(pousse > 1.0, "poussé : de " + pousse + " bloc ; mur en " + mur.position()
                            + ", cochon en " + cochon.position() + ", emprise " + mur.getBoundingBox());
                    helper.assertTrue(mannequin.position().distanceTo(place[0]) < 0.01,
                            "le porte-armure reste : " + mannequin.position() + " pour " + place[0]);
                    cochon.discard();
                    mannequin.discard();
                    mur.discard();
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
