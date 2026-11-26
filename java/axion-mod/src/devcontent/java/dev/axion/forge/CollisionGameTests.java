package dev.axion.forge;

import dev.axion.api.assembly.Assembly;
import dev.axion.api.assembly.CarryListener;
import dev.axion.api.attach.AttachResult;
import dev.axion.api.vehicle.DriverInput;
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
     * d'un mod apprennent qu'elle monte puis qu'elle descend. ADR-184 D1 : les snapshots la listent à sa place sur le
     * corps, puis plus une fois descendue.
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
                    var portees = assembly.netState().carried().entities();
                    helper.assertTrue(portees.size() == 1 && portees.get(0).entity() == cochon.getId()
                            && portees.get(0).body() == CarrySupports.of(cochon).body(), "les snapshots le listent : " + portees);
                    double[] dite = portees.get(0).local();
                    double[] tenue = CarrySupports.of(cochon).local();
                    for (int axe = 0; axe < 3; axe++) {
                        helper.assertTrue(Math.abs(dite[axe] - tenue[axe]) <= 0.01, "à sa place sur le corps : "
                                + dite[axe] + " pour " + tenue[axe]);
                    }
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
                })
                .thenIdle(2)
                .thenExecute(() -> {
                    helper.assertTrue(assembly.netState().carried().entities().isEmpty(), "descendu : plus listé");
                    cochon.discard();
                })
                .thenSucceed();
    }

    /**
     * ADR-178 : ce qu'une assembly porte ne la freine pas plus que son poids. Trois véhicules partent ensemble, avec la même
     * commande : l'un seul, un joueur debout sur le deuxième, un cochon sur le troisième ; les deux chargés parcourent au moins
     * les six dixièmes de ce que parcourt le premier. Un proxy posé là où l'entité était, ou de masse infinie, les arrêtait.
     */
    @GameTest(template = "test/plancher_16", timeoutTicks = 300)
    public static void unVehiculeQuiPorteResteMobile(GameTestHelper helper) {
        ObservateursDeTest.declarer(helper, new Vec3(8.0, DESSUS_DALLE, 8.0));
        AxionEntity[] vehicules = new AxionEntity[3];
        for (int i = 0; i < 3; i++) {
            vehicules[i] = AssembliesDeTest.poser(helper, "axion:test/vehicule",
                    new Vec3(3.0 + 5.0 * i, DESSUS_DALLE + 0.3, 12.5), IDENTITE);
        }
        Pig cochon = EntityType.PIG.create(helper.getLevel());
        dev.axion.forge.net.JoueurSimule[] joueur = new dev.axion.forge.net.JoueurSimule[1];
        Vec3[] depart = new Vec3[3];
        helper.onEachTick(() -> {
            if (joueur[0] != null && joueur[0].connecte()) {
                joueur[0].tick();
            }
        });
        helper.startSequence()
                .thenWaitUntil(() -> {
                    for (AxionEntity vehicule : vehicules) {
                        helper.assertTrue(vehicule.hasPhysicalBounds(), "les véhicules sont simulés");
                    }
                })
                .thenIdle(30)
                .thenExecute(() -> {
                    AxionEntity porte = vehicules[1];
                    joueur[0] = dev.axion.forge.net.JoueurSimule.rejoindre(helper.getLevel(), "porte",
                            porte.position().add(0.0, 2.5, 0.0));
                    joueur[0].joueur().setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
                    joueur[0].joueur().move(MoverType.SELF, new Vec3(0.0, -3.0, 0.0));
                    cochon.setNoAi(true);
                    helper.getLevel().addFreshEntity(cochon);
                    cochon.moveTo(vehicules[2].getX(), vehicules[2].getY() + 2.5, vehicules[2].getZ(), 0f, 0f);
                    cochon.move(MoverType.SELF, new Vec3(0.0, -3.0, 0.0));
                })
                .thenIdle(10)
                .thenExecute(() -> {
                    helper.assertTrue(CarrySupports.of(joueur[0].joueur()) != null, "le joueur est porté au départ");
                    helper.assertTrue(CarrySupports.of(cochon) != null, "le cochon est porté au départ");
                    for (int i = 0; i < 3; i++) {
                        depart[i] = vehicules[i].position();
                        ForgeAxionApi.view(vehicules[i]).vehicle().orElseThrow()
                                .drive(new DriverInput(0.5, 0, 0, 0, 0, 1));
                    }
                })
                .thenIdle(60)
                .thenExecute(() -> {
                    double[] parcours = new double[3];
                    for (int i = 0; i < 3; i++) {
                        parcours[i] = vehicules[i].position().subtract(depart[i]).horizontalDistance();
                        ForgeAxionApi.view(vehicules[i]).vehicle().orElseThrow().drive(DriverInput.NONE);
                    }
                    boolean joueurPorte = CarrySupports.of(joueur[0].joueur()) != null;
                    boolean cochonPorte = CarrySupports.of(cochon) != null;
                    joueur[0].quitter();
                    cochon.discard();
                    String releve = String.format(java.util.Locale.ROOT, "seul %.2f, avec le joueur %.2f, avec le cochon"
                            + " %.2f blocs ; porté à la fin : joueur %b, cochon %b", parcours[0], parcours[1], parcours[2],
                            joueurPorte, cochonPorte);
                    org.slf4j.LoggerFactory.getLogger("AXION essai").info("[porteur] GameTest : {}", releve);
                    helper.assertTrue(parcours[0] >= 1.0, "le véhicule seul avance : " + releve);
                    helper.assertTrue(parcours[1] >= 0.6 * parcours[0] && parcours[2] >= 0.6 * parcours[0],
                            "ce qu'ils portent ne les arrête pas : " + releve);
                    helper.assertTrue(joueurPorte && cochonPorte, "toujours portés : " + releve);
                })
                .thenSucceed();
    }

    /**
     * R-614, ADR-123 §7 : un véhicule qui heurte une entité la pousse de sa part du choc, {@code J / (m + m_eff)}. Le
     * cochon, sans IA, garde cette vitesse sans s'en déplacer : on la lit. Une impulsion de paire réduite à un seul de
     * ses points de contact le laissait immobile.
     */
    @GameTest(template = "test/plancher_16", timeoutTicks = 200)
    public static void unVehiculeQuiHeurteUneEntiteLaPousse(GameTestHelper helper) {
        ObservateursDeTest.declarer(helper, new Vec3(8.0, DESSUS_DALLE, 8.0));
        AxionEntity vehicule = AssembliesDeTest.poser(helper, "axion:test/vehicule",
                new Vec3(8.0, DESSUS_DALLE + 0.6, 13.0), IDENTITE);
        Pig cochon = EntityType.PIG.create(helper.getLevel());
        double[] recu = {0.0};
        boolean[] parti = {false};
        helper.onEachTick(() -> {
            if (parti[0]) {
                vehicule.drive(new DriverInput(0.6, 0, 0, 0, 0, 1));
                recu[0] = Math.max(recu[0], -cochon.getDeltaMovement().z);
            }
        });
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(vehicule.hasPhysicalBounds(), "le véhicule est simulé"))
                .thenIdle(10)
                .thenExecute(() -> {
                    cochon.setNoAi(true);
                    Vec3 pose = helper.absoluteVec(new Vec3(8.0, DESSUS_DALLE, 5.0));
                    cochon.moveTo(pose.x, pose.y, pose.z, 0f, 0f);
                    helper.getLevel().addFreshEntity(cochon);
                    parti[0] = true;
                })
                // Un dixième de mètre par seconde fait 0,005 bloc par tick ; sa part d'un choc à plus de 3 m/s est bien
                // au-dessus de 0,02.
                .thenWaitUntil(() -> helper.assertTrue(recu[0] > 0.02,
                        "le cochon reçoit sa part du choc : " + recu[0] + " bloc/tick"))
                .thenExecute(() -> {
                    cochon.discard();
                    vehicule.discard();
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

    /** ADR-178 D5 : sur une surface qui se marche, la poussée sort vers le haut : rien ne glisse de côté. */
    @GameTest(template = "test/plancher_16", timeoutTicks = 100)
    public static void laPousseeSurUnePenteQuiSeMarcheSortVersLeHaut(GameTestHelper helper) {
        ObservateursDeTest.declarer(helper, new Vec3(8.0, DESSUS_DALLE, 8.0));
        double demi = Math.toRadians(20.0) / 2.0;
        float[] penchee = {0f, 0f, (float) Math.sin(demi), (float) Math.cos(demi)};
        AxionEntity assembly = AssembliesDeTest.poser(
                helper, "axion:test/siege", new Vec3(8.5, DESSUS_DALLE + 5.0, 8.5), penchee);
        Pig cochon = EntityType.PIG.create(helper.getLevel());
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(assembly.hasPhysicalBounds()
                        && assembly.collisionPoses() != null, "l'assembly est simulée"))
                .thenExecute(() -> {
                    // Les pieds au milieu du dessus penché de la caisse : ses coins du côté haut y sont enfoncés.
                    double[] dessus = BodyFrame.toWorld(assembly.collisionPoses(), 0, new double[] {0.0, 0.98, 0.0});
                    cochon.moveTo(dessus[0], dessus[1], dessus[2], 0f, 0f);
                    Vec3 poussee = AssemblyCollision.pushOut(cochon);
                    helper.assertTrue(poussee != null && poussee.y > 0.01, "la caisse le sort vers le haut : " + poussee);
                    helper.assertTrue(Math.hypot(poussee.x, poussee.z) < 1.0e-9, "sans le pousser de côté : " + poussee);
                    cochon.discard();
                })
                .thenSucceed();
    }

    /**
     * ADR-178 D4 : « au sol » n'est pas demandé au serveur. Vanilla y fait tomber un joueur dans sa propre simulation, contre
     * la pose du moment ; c'est la sonde qui dit s'il repose sur une assembly.
     */
    @GameTest(template = "test/plancher_16", timeoutTicks = 200)
    public static void unJoueurQueVanillaCroitEnLAirResteReconnu(GameTestHelper helper) {
        ObservateursDeTest.declarer(helper, new Vec3(8.0, DESSUS_DALLE, 8.0));
        AxionEntity assembly = AssembliesDeTest.poser(
                helper, "axion:test/siege", new Vec3(8.5, DESSUS_DALLE, 8.5), IDENTITE);
        dev.axion.forge.net.JoueurSimule[] joueur = new dev.axion.forge.net.JoueurSimule[1];
        helper.onEachTick(() -> {
            if (joueur[0] != null && joueur[0].connecte()) {
                joueur[0].tick();
                joueur[0].joueur().setOnGround(false);
            }
        });
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(assembly.hasPhysicalBounds(), "l'assembly est simulée"))
                .thenIdle(20)
                .thenExecute(() -> {
                    joueur[0] = dev.axion.forge.net.JoueurSimule.rejoindre(helper.getLevel(), "en_l_air",
                            new Vec3(assembly.getX(), assembly.getY() + 1.001, assembly.getZ()));
                    joueur[0].joueur().setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
                })
                .thenIdle(5)
                .thenExecute(() -> {
                    CarrySupports.Support support = CarrySupports.of(joueur[0].joueur());
                    // La simulation de vanilla, loin de la boîte d'aujourd'hui de l'assembly, n'efface rien : le support du
                    // serveur est l'affaire de ServerCarry.
                    var moi = joueur[0].joueur();
                    moi.setPos(moi.getX() + 6.0, moi.getY(), moi.getZ());
                    moi.move(MoverType.SELF, new Vec3(0.0, -0.0784, 0.0));
                    helper.assertTrue(support == null || CarrySupports.of(moi) != null,
                            "sa simulation ne le lui retire pas");
                    joueur[0].quitter();
                    helper.assertTrue(support != null && support.assembly() == assembly && support.lag() >= 0,
                            "le serveur le porte : " + (support == null ? "sans support" : "retard " + support.lag()));
                })
                .thenSucceed();
    }

    /**
     * ADR-185 D2 : les snapshots disent un joueur porté à la place qu'il envoie, quand sa position la confirme ; une place
     * qu'elle ne confirme pas est refusée, comptée, et celle du serveur reste.
     */
    @GameTest(template = "test/plancher_16", timeoutTicks = 200)
    public static void laPlaceEnvoyeeParLePorteEstVerifiee(GameTestHelper helper) {
        ObservateursDeTest.declarer(helper, new Vec3(8.0, DESSUS_DALLE, 8.0));
        AxionEntity assembly = AssembliesDeTest.poser(
                helper, "axion:test/siege", new Vec3(8.5, DESSUS_DALLE, 8.5), IDENTITE);
        dev.axion.forge.net.JoueurSimule[] joueur = new dev.axion.forge.net.JoueurSimule[1];
        // L'écart, le long de x, de la place envoyée à celle que le serveur calcule ; NaN : rien n'est envoyé.
        double[] ecart = {Double.NaN};
        long[] refus = new long[1];
        helper.onEachTick(() -> {
            if (joueur[0] == null || !joueur[0].connecte()) {
                return;
            }
            joueur[0].tick();
            CarrySupports.Support support = CarrySupports.of(joueur[0].joueur());
            if (!Double.isNaN(ecart[0]) && support != null && support.local() != null) {
                double[] place = support.local();
                place[0] += ecart[0];
                joueur[0].envoyer(dev.axion.net.Fragments.split(dev.axion.net.CarriedMessage
                        .of(assembly.getId(), support.body(), place).encode(), 0).get(0));
            }
        });
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(assembly.hasPhysicalBounds(), "l'assembly est simulée"))
                .thenIdle(20)
                .thenExecute(() -> {
                    joueur[0] = dev.axion.forge.net.JoueurSimule.rejoindre(helper.getLevel(), "dit",
                            new Vec3(assembly.getX(), assembly.getY() + 1.001, assembly.getZ()));
                    joueur[0].joueur().setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
                })
                .thenIdle(5)
                .thenExecute(() -> ecart[0] = 0.125)
                .thenIdle(3)
                .thenExecute(() -> {
                    double dit = ecartDiffuse(helper, assembly, joueur[0]);
                    helper.assertTrue(Math.abs(dit - 0.125) <= 0.01, "la place envoyée est diffusée : écart " + dit);
                    refus[0] = ServerCarry.refusedPlaces();
                    ecart[0] = 3.0;
                })
                .thenIdle(3)
                .thenExecute(() -> {
                    double dit = ecartDiffuse(helper, assembly, joueur[0]);
                    long comptes = ServerCarry.refusedPlaces() - refus[0];
                    ecart[0] = Double.NaN;
                    joueur[0].quitter();
                    helper.assertTrue(Math.abs(dit) <= 0.01, "une place à 3 blocs de sa position est refusée : écart " + dit);
                    helper.assertTrue(comptes >= 1, "et comptée : " + comptes);
                })
                .thenSucceed();
    }

    /** {@return l'écart, le long de x, de la place diffusée d'un joueur porté à celle que le serveur calcule} */
    private static double ecartDiffuse(GameTestHelper helper, AxionEntity assembly,
            dev.axion.forge.net.JoueurSimule joueur) {
        CarrySupports.Support support = CarrySupports.of(joueur.joueur());
        helper.assertTrue(support != null && support.local() != null, "le joueur est porté");
        for (var portee : assembly.netState().carried().entities()) {
            if (portee.entity() == joueur.joueur().getId()) {
                return portee.local()[0] - support.local()[0];
            }
        }
        helper.fail("les snapshots ne listent pas le joueur porté");
        return Double.NaN;
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
