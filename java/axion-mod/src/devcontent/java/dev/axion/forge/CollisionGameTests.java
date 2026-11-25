package dev.axion.forge;

import dev.axion.api.assembly.Assembly;
import dev.axion.api.assembly.CarryListener;
import dev.axion.api.attach.AttachResult;
import dev.axion.content.ContentRegistry.ListenerEntry;
import dev.axion.physics.BodyFrame;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * ADR-178 D1 (CAP-14) : une entité n'est plus laissée traverser une assembly. {@code Entity.collide} rend le déplacement de
 * vanilla raccourci par les colliders de l'assembly (dev.axion.mixin) ; ici, une caisse d'un bloc posée sur la dalle, et un joueur,
 * puis un cochon, qui tombent de trois blocs au-dessus d'elle.
 */
@GameTestHolder("axion")
@PrefixGameTestTemplate(false)
public final class CollisionGameTests {

    /** Le dessus de la dalle du gabarit {@code plancher}, en coordonnées du test. */
    private static final double DESSUS_DALLE = 2.0;

    private static final float[] IDENTITE = {0f, 0f, 0f, 1f};

    private CollisionGameTests() {}

    /**
     * Un joueur qui tombe sur la caisse y reste, posé sur elle, au sol ; un autre, qui tombe à côté, atteint la dalle. Sans
     * l'injection, les deux auraient traversé : l'assembly n'a pas de collision vanilla.
     */
    @GameTest(template = "test/plancher", timeoutTicks = 200)
    public static void unJoueurQuiTombeSurUneAssemblyYRepose(GameTestHelper helper) {
        AxionEntity assembly = AssembliesDeTest.poser(
                helper, "axion:test/siege", new Vec3(4.5, DESSUS_DALLE, 4.5), IDENTITE);
        ServerPlayer dessus = SiegesGameTests.joueur(helper, "collision_dessus");
        ServerPlayer a_cote = SiegesGameTests.joueur(helper, "collision_a_cote");
        boolean[] fait = {false};
        helper.succeedWhen(() -> {
            helper.assertTrue(assembly.hasPhysicalBounds(), "l'assembly est simulée");
            if (!fait[0]) {
                fait[0] = true;
                double x = assembly.getX();
                double z = assembly.getZ();
                dessus.moveTo(x, assembly.getY() + 3.0, z, 0f, 0f);
                // Trois blocs sur le côté : hors de la caisse et de ses deux cubes (les sièges, à deux blocs de part et
                // d'autre), que la dalle seule porte.
                a_cote.moveTo(x, assembly.getY() + 3.0, z + 3.0, 0f, 0f);
                // Plus que la hauteur de chute : chacun doit finir arrêté, ce que vanilla lit comme « au sol ».
                dessus.move(MoverType.SELF, new Vec3(0.0, -3.5, 0.0));
                a_cote.move(MoverType.SELF, new Vec3(0.0, -3.5, 0.0));
            }
            double sol = assembly.getY();
            helper.assertTrue(dessus.getY() > sol + 0.9 && dessus.getY() < sol + 1.2,
                    "sur la caisse, à un bloc de la dalle : y = " + (dessus.getY() - sol));
            helper.assertTrue(dessus.onGround(), "au sol, posé sur l'assembly");
            helper.assertTrue(a_cote.getY() < sol + 0.1, "à côté, sur la dalle : y = " + (a_cote.getY() - sol));
            helper.assertTrue(a_cote.onGround(), "à côté, au sol");
        });
    }

    /**
     * ADR-178 : une assembly dit ce qu'elle porte, et une definition {@code "carry": false} arrête ce qui tombe sur elle sans
     * rien porter.
     */
    @GameTest(template = "test/plancher", timeoutTicks = 200)
    public static void uneAssemblyDitCeQuEllePorteEtCarryFalseNePorteRien(GameTestHelper helper) {
        AxionEntity porteuse = AssembliesDeTest.poser(
                helper, "axion:test/siege", new Vec3(4.5, DESSUS_DALLE, 4.5), IDENTITE);
        AxionEntity sans = AssembliesDeTest.poser(
                helper, "axion:test/siege_sans_porteur", new Vec3(4.5, DESSUS_DALLE, 9.5), IDENTITE);
        Pig surPorteuse = EntityType.PIG.create(helper.getLevel());
        Pig surSans = EntityType.PIG.create(helper.getLevel());
        boolean[] fait = {false};
        helper.succeedWhen(() -> {
            helper.assertTrue(porteuse.hasPhysicalBounds() && sans.hasPhysicalBounds(), "les assemblies sont simulées");
            if (!fait[0]) {
                fait[0] = true;
                for (Object[] paire : new Object[][] {{surPorteuse, porteuse}, {surSans, sans}}) {
                    Pig cochon = (Pig) paire[0];
                    AxionEntity assembly = (AxionEntity) paire[1];
                    cochon.setNoAi(true);
                    helper.getLevel().addFreshEntity(cochon);
                    cochon.moveTo(assembly.getX(), assembly.getY() + 3.0, assembly.getZ(), 0f, 0f);
                    cochon.move(MoverType.SELF, new Vec3(0.0, -3.5, 0.0));
                }
            }
            helper.assertTrue(surPorteuse.getY() - porteuse.getY() > 0.9 && surSans.getY() - sans.getY() > 0.9,
                    "les deux caisses arrêtent leur cochon");
            var api = dev.axion.api.AxionApi.get().assemblies();
            helper.assertTrue(api.byEntity(porteuse).orElseThrow().carried().contains(surPorteuse),
                    "la porteuse dit porter son cochon : " + api.byEntity(porteuse).orElseThrow().carried());
            helper.assertTrue(api.byEntity(sans).orElseThrow().carried().isEmpty(), "carry: false ne porte rien");
            surPorteuse.discard();
            surSans.discard();
        });
    }

    /**
     * ADR-178 D4 : une entité que le serveur déplace lui-même reste à sa place sur une assembly qui tombe, et les écouteurs
     * d'un mod apprennent qu'elle monte puis qu'elle descend.
     */
    @GameTest(template = "test/plancher_16", timeoutTicks = 200)
    public static void uneEntiteSuitLAssemblyQuiTombeEtSesEcouteursLApprennent(GameTestHelper helper) {
        ObservateursDeTest.declarer(helper, new Vec3(8.0, DESSUS_DALLE, 8.0));
        // Lâchée de six blocs : elle tombe une seconde, comme un ascenseur qui descend.
        AxionEntity assembly = AssembliesDeTest.poser(
                helper, "axion:test/siege", new Vec3(8.5, DESSUS_DALLE + 6.0, 8.5), IDENTITE);
        Pig cochon = EntityType.PIG.create(helper.getLevel());
        List<String> journal = new ArrayList<>();
        CarryListener ecouteur = new CarryListener() {
            @Override
            public void carried(Assembly a, Entity entity) {
                if (a.entity() == assembly) {
                    journal.add("monte");
                }
            }

            @Override
            public void dropped(Assembly a, Entity entity) {
                if (a.entity() == assembly) {
                    journal.add("descend");
                }
            }
        };
        List<ListenerEntry<CarryListener>> avant = ContentListeners.carriers();
        // Ceux des mods restent publiés ; l'écouteur d'essai ne suit que son assembly, les GameTests tournant ensemble.
        List<ListenerEntry<CarryListener>> avec = new ArrayList<>(avant);
        avec.add(new ListenerEntry<>("essai", ecouteur));
        double[] place = new double[3];
        double[] depart = new double[1];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(assembly.hasPhysicalBounds()
                        && assembly.carryPoses(0) != null, "l'assembly est simulée"))
                .thenExecute(() -> {
                    ContentListeners.publishCarry(avec);
                    cochon.setNoAi(true);
                    helper.getLevel().addFreshEntity(cochon);
                    cochon.moveTo(assembly.getX(), assembly.getY() + 2.0, assembly.getZ(), 0f, 0f);
                    cochon.move(MoverType.SELF, new Vec3(0.0, -2.0, 0.0));
                    helper.assertTrue(CarrySupports.of(cochon) != null, "le cochon repose sur la caisse");
                })
                .thenIdle(2)
                .thenExecute(() -> {
                    double[] local = BodyFrame.toLocal(assembly.carryPoses(0), CarrySupports.of(cochon).body(),
                            cochon.getX(), cochon.getY(), cochon.getZ());
                    System.arraycopy(local, 0, place, 0, 3);
                    depart[0] = assembly.carryPoses(0)[1];
                })
                .thenWaitUntil(() -> {
                    double tombee = depart[0] - assembly.carryPoses(0)[1];
                    helper.assertTrue(tombee >= 1.0, "la caisse est descendue de " + tombee + " bloc");
                })
                .thenExecute(() -> {
                    CarrySupports.Support support = CarrySupports.of(cochon);
                    helper.assertTrue(support != null && support.assembly() == assembly, "le cochon est resté porté");
                    double[] local = BodyFrame.toLocal(assembly.carryPoses(0), support.body(), cochon.getX(),
                            cochon.getY(), cochon.getZ());
                    double derive = Math.sqrt(Math.pow(local[0] - place[0], 2) + Math.pow(local[1] - place[1], 2)
                            + Math.pow(local[2] - place[2], 2));
                    helper.assertTrue(derive <= 0.05, "sa place sur la caisse a dérivé de " + derive + " bloc");
                    // Il quitte le plateau : un pas dans le vide, loin de lui.
                    cochon.moveTo(assembly.getX(), assembly.getY() + 4.0, assembly.getZ() + 4.0, 0f, 0f);
                    cochon.move(MoverType.SELF, new Vec3(0.0, -0.1, 0.0));
                    ContentListeners.publishCarry(avant);
                    helper.assertTrue(journal.equals(List.of("monte", "descend")), "les écouteurs : " + journal);
                    cochon.discard();
                })
                .thenSucceed();
    }

    /** ADR-178 : dans un ensemble attaché (ADR-171), une entité est portée une fois, par le membre qu'elle touche. */
    @GameTest(template = "test/plancher_16", timeoutTicks = 200)
    public static void unEnsembleAttachePorteParLeMembreTouche(GameTestHelper helper) {
        ObservateursDeTest.declarer(helper, new Vec3(8.0, DESSUS_DALLE, 8.0));
        AxionEntity a = AssembliesDeTest.poser(
                helper, "axion:test/maillon", new Vec3(6.0, DESSUS_DALLE + 0.05, 4.0), IDENTITE);
        AxionEntity b = AssembliesDeTest.poser(
                helper, "axion:test/maillon", new Vec3(7.2, DESSUS_DALLE + 0.05, 4.0), IDENTITE);
        Pig cochon = EntityType.PIG.create(helper.getLevel());
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(a.hasPhysicalBounds() && b.hasPhysicalBounds(),
                        "les maillons sont simulés"))
                .thenIdle(20)
                .thenExecute(() -> {
                    var attache = ForgeAxionApi.view(a).attachments().attach("avant", ForgeAxionApi.view(b), "arriere",
                            "rope");
                    helper.assertTrue(attache.status() == AttachResult.Status.ATTACHED, "attachés : " + attache.message());
                })
                .thenIdle(5)
                .thenExecute(() -> {
                    cochon.setNoAi(true);
                    helper.getLevel().addFreshEntity(cochon);
                    cochon.moveTo(b.getX(), b.getY() + 2.0, b.getZ(), 0f, 0f);
                    cochon.move(MoverType.SELF, new Vec3(0.0, -2.0, 0.0));
                    helper.assertTrue(ForgeAxionApi.view(b).carried().contains(cochon),
                            "le membre touché le porte : " + ForgeAxionApi.view(b).carried());
                    helper.assertTrue(!ForgeAxionApi.view(a).carried().contains(cochon), "l'autre ne le porte pas");
                    cochon.discard();
                })
                .thenSucceed();
    }

    /**
     * ADR-178 D5 : la poussée sort une entité d'une assembly sans la mettre dans un bloc — contre un mur, elle s'arrête au
     * mur.
     */
    @GameTest(template = "test/plancher", timeoutTicks = 200)
    public static void laPousseeSArreteAuxBlocs(GameTestHelper helper) {
        AxionEntity assembly = AssembliesDeTest.poser(
                helper, "axion:test/siege", new Vec3(4.5, DESSUS_DALLE, 4.9), IDENTITE);
        // La face +z de la caisse est à 5,4 ; le mur commence à 6.
        helper.setBlock(new BlockPos(4, 2, 6), Blocks.STONE);
        Pig cochon = EntityType.PIG.create(helper.getLevel());
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(assembly.hasPhysicalBounds(), "l'assembly est simulée"))
                .thenIdle(20)
                .thenExecute(() -> {
                    // Enfoncé de 0,35 dans la face +z, à 0,05 du mur : la plus courte sortie le mettrait dedans.
                    Vec3 pose = helper.absoluteVec(new Vec3(4.5, DESSUS_DALLE, 5.5));
                    cochon.moveTo(pose.x, pose.y, pose.z, 0f, 0f);
                    Vec3 poussee = AssemblyCollision.pushOut(cochon);
                    helper.assertTrue(poussee != null && poussee.z > 0.01,
                            "la caisse le pousse vers le mur : " + poussee);
                    helper.assertTrue(helper.getLevel().noCollision(cochon,
                            cochon.getBoundingBox().move(poussee).deflate(1.0e-4)), "sans l'y mettre : " + poussee);
                    cochon.discard();
                })
                .thenSucceed();
    }

    /** Un cochon, comme un joueur : toute entité est arrêtée, et un mur arrête la marche. */
    @GameTest(template = "test/plancher", timeoutTicks = 200)
    public static void uneEntiteEstArreteeParLesCollidersDUneAssembly(GameTestHelper helper) {
        AxionEntity assembly = AssembliesDeTest.poser(
                helper, "axion:test/siege", new Vec3(4.5, DESSUS_DALLE, 4.5), IDENTITE);
        Pig cochon = EntityType.PIG.create(helper.getLevel());
        boolean[] fait = {false};
        helper.succeedWhen(() -> {
            helper.assertTrue(assembly.hasPhysicalBounds(), "l'assembly est simulée");
            if (!fait[0]) {
                fait[0] = true;
                double x = assembly.getX();
                double z = assembly.getZ();
                cochon.setNoAi(true);
                helper.getLevel().addFreshEntity(cochon);
                // À quatre blocs du centre, côté -x, au ras de la dalle : il marche de trois blocs vers le cube du siège gauche,
                // dont la face est à 2,5 du centre.
                double dalle = helper.absoluteVec(new Vec3(0.0, DESSUS_DALLE, 0.0)).y;
                cochon.moveTo(x - 4.0, dalle, z, 0f, 0f);
                cochon.move(MoverType.SELF, new Vec3(3.0, 0.0, 0.0));
            }
            double ecart = assembly.getX() - cochon.getX();
            // Sa demi-largeur est de 0,45 : son centre s'arrête à 2,95 du centre de l'assembly, pas à 1 (sans collision).
            helper.assertTrue(ecart > 2.8, "le cochon s'arrête devant le cube (centre à " + ecart + " du sien)");
            helper.assertTrue(ecart < 3.1, "mais il l'a rejoint : " + ecart);
            cochon.discard();
        });
    }
}
