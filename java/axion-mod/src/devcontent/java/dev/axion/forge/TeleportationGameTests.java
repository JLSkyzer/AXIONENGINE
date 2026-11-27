package dev.axion.forge;

import dev.axion.api.assembly.Assembly;
import dev.axion.api.attach.AttachResult;
import dev.axion.api.attach.AttachmentListener;
import dev.axion.content.ContentRegistry.AttachmentListenerEntry;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** ADR-183 D2 (CAP-34) : une assembly se téléporte avec ce qui lui est attaché. */
@GameTestHolder("axion")
@PrefixGameTestTemplate(false)
public final class TeleportationGameTests {

    /** Le dessus de la dalle du gabarit, en coordonnées du test. */
    private static final double DESSUS_DALLE = 2.0;

    private static final float[] DROIT = {0f, 0f, 0f, 1f};

    private static final String MAILLON = "axion:test/maillon";

    private TeleportationGameTests() {}

    /**
     * {@code /axion tp} pose le maillon tracteur à la position demandée, tourné au lacet demandé ; l'attelé le suit à sa
     * place relative, la corde tient, et l'ancre à un bloc est défaite, cause {@code teleported}.
     */
    @GameTest(template = "test/plancher_16", timeoutTicks = 200)
    public static void adr183_laCommandeTeleporteLEnsembleEtDefaitLesAncres(GameTestHelper helper) {
        ObservateursDeTest.declarer(helper, new Vec3(8.0, DESSUS_DALLE, 8.0));
        AxionEntity a = AssembliesDeTest.poser(helper, MAILLON, new Vec3(4.5, DESSUS_DALLE + 0.05, 4.0), DROIT);
        AxionEntity b = AssembliesDeTest.poser(helper, MAILLON, new Vec3(4.5, DESSUS_DALLE + 0.05, 6.0), DROIT);
        BlockPos ancre = helper.absolutePos(new BlockPos(4, (int) DESSUS_DALLE + 4, 8));
        MinecraftServer serveur = helper.getLevel().getServer();
        List<String> causes = new ArrayList<>();
        List<AttachmentListenerEntry> avant = AttachmentRuntime.publishedListeners();
        AttachmentListener ecouteur = new AttachmentListener() {
            @Override
            public void detached(Assembly assembly, String socket, java.util.Optional<Assembly> other,
                    String otherSocket, String cause) {
                if (assembly.entity() == a || assembly.entity() == b) {
                    causes.add(cause);
                }
            }
        };
        Vec3 arrivee = helper.absoluteVec(new Vec3(10.5, DESSUS_DALLE + 0.3, 10.5));
        Vec3[] ecart = new Vec3[1];
        org.joml.Quaternionf[] avantLeSaut = new org.joml.Quaternionf[1];
        helper.startSequence()
                // La création native s'étale sous budget (R-1730) : la scène attend que les deux corps existent.
                .thenWaitUntil(() -> helper.assertTrue(a.hasPhysicalBounds() && b.hasPhysicalBounds(), "simulés"))
                .thenIdle(10)
                .thenExecute(() -> {
                    List<AttachmentListenerEntry> avec = new ArrayList<>(avant);
                    avec.add(new AttachmentListenerEntry("essai", ecouteur));
                    AttachmentRuntime.publishListeners(avec);
                    AttachResult corde = ForgeAxionApi.view(a).attachments()
                            .attach("avant", ForgeAxionApi.view(b), "arriere", "rope");
                    helper.assertTrue(corde.status() == AttachResult.Status.ATTACHED, "corde : " + corde.message());
                    AttachResult ancrage = ForgeAxionApi.view(b).attachments()
                            .anchor("avant", ancre.getX(), ancre.getY(), ancre.getZ());
                    helper.assertTrue(ancrage.status() == AttachResult.Status.ATTACHED, "ancre : " + ancrage.message());
                })
                .thenIdle(5)
                .thenExecute(() -> {
                    ecart[0] = b.position().subtract(a.position());
                    avantLeSaut[0] = a.orientation();
                    String commande = String.format(java.util.Locale.ROOT, "axion tp %s %.3f %.3f %.3f 90",
                            a.getUUID(), arrivee.x, arrivee.y, arrivee.z);
                    List<String> reponse = ParametresGameTests.commande(serveur, commande);
                    helper.assertTrue(reponse.size() == 1 && reponse.get(0).contains("téléportée"), "réponse : " + reponse);
                })
                .thenIdle(2)
                .thenExecute(() -> {
                    double a1 = a.position().subtract(arrivee).horizontalDistance();
                    helper.assertTrue(a1 < 0.3, "le tracteur est arrivé : à " + a1 + " bloc");
                    // Le mouvement de la racine : de son orientation d'avant à celle du lacet 90, droite.
                    org.joml.Quaternionf tour = new org.joml.Quaternionf().rotationY((float) (Math.PI / 2))
                            .mul(new org.joml.Quaternionf(avantLeSaut[0]).conjugate());
                    org.joml.Vector3f tourne = tour.transform(new org.joml.Vector3f(
                            (float) ecart[0].x, (float) ecart[0].y, (float) ecart[0].z));
                    Vec3 attendu = new Vec3(tourne.x, tourne.y, tourne.z);
                    Vec3 relatif = b.position().subtract(a.position());
                    helper.assertTrue(relatif.distanceTo(attendu) < 0.4,
                            "l'attelé le suit, à sa place relative tournée : " + relatif + " pour " + attendu);
                })
                .thenIdle(10)
                .thenExecute(() -> {
                    try {
                        double a2 = a.position().subtract(arrivee).horizontalDistance();
                        helper.assertTrue(a2 < 1.0, "le tracteur reste à l'arrivée : à " + a2 + " bloc");
                        helper.assertTrue(ForgeAxionApi.view(a).attachments().list().size() == 1, "la corde tient");
                        helper.assertTrue(ForgeAxionApi.view(b).attachments().list().size() == 1,
                                "l'ancre est défaite : " + ForgeAxionApi.view(b).attachments().list());
                        helper.assertTrue(causes.contains("teleported"), "cause : " + causes);
                    } finally {
                        AttachmentRuntime.publishListeners(avant);
                    }
                    a.discard();
                    b.discard();
                })
                .thenSucceed();
    }

    /**
     * Téléportée avant que le moteur ne simule ses membres (leur création native s'étale sous budget, R-1730),
     * l'assembly emmène quand même ce qui lui est attaché, d'un même mouvement.
     */
    @GameTest(template = "test/plancher_16", timeoutTicks = 200)
    public static void adr183_unEnsembleSansCorpsSeTeleporteEnsemble(GameTestHelper helper) {
        ObservateursDeTest.declarer(helper, new Vec3(8.0, DESSUS_DALLE, 8.0));
        Vec3 saut = new Vec3(6.0, 0.25, 6.0);
        AxionEntity[] paire = new AxionEntity[2];
        Vec3[] attendus = new Vec3[2];
        helper.startSequence()
                .thenExecute(() -> {
                    // Dans le même tick que leur apparition : aucun corps natif.
                    AxionEntity a = AssembliesDeTest.poser(helper, MAILLON,
                            new Vec3(4.5, DESSUS_DALLE + 0.05, 4.0), DROIT);
                    AxionEntity b = AssembliesDeTest.poser(helper, MAILLON,
                            new Vec3(4.5, DESSUS_DALLE + 0.05, 6.0), DROIT);
                    paire[0] = a;
                    paire[1] = b;
                    AttachResult corde = ForgeAxionApi.view(a).attachments()
                            .attach("avant", ForgeAxionApi.view(b), "arriere", "rope");
                    helper.assertTrue(corde.status() == AttachResult.Status.ATTACHED, "corde : " + corde.message());
                    attendus[0] = a.position().add(saut);
                    attendus[1] = b.position().add(saut);
                    ForgeAxionApi.view(a).teleport(new dev.axion.api.assembly.Transform(attendus[0], a.orientation(),
                            new Vec3(1, 1, 1)), true);
                    helper.assertTrue(b.position().distanceTo(attendus[1]) < 1.0e-3,
                            "l'attelé suit aussitôt : " + b.position() + " pour " + attendus[1]);
                })
                .thenWaitUntil(() -> helper.assertTrue(paire[0].hasPhysicalBounds() && paire[1].hasPhysicalBounds(),
                        "simulés"))
                .thenIdle(10)
                .thenExecute(() -> {
                    for (int i = 0; i < 2; i++) {
                        double ecart = paire[i].position().subtract(attendus[i]).horizontalDistance();
                        helper.assertTrue(ecart < 0.5, "le maillon " + i + " est à l'arrivée : à " + ecart + " bloc");
                    }
                    helper.assertTrue(ForgeAxionApi.view(paire[0]).attachments().list().size() == 1, "la corde tient");
                    paire[0].discard();
                    paire[1].discard();
                })
                .thenSucceed();
    }

    /**
     * Téléportée au loin avec son conducteur, l'assembly l'emmène : il reste à son siège, et elle reste suivie à
     * l'arrivée. Sans quoi le chunk d'arrivée, tenu cinq ticks par la téléportation, se décharge avec elle.
     */
    @GameTest(template = "test/plancher_16", timeoutTicks = 200)
    public static void adr183_leConducteurSuitSonVehiculeAuLoin(GameTestHelper helper) {
        AxionEntity assembly = AssembliesDeTest.poser(helper, "axion:test/vehicule",
                new Vec3(8.0, DESSUS_DALLE + 0.1, 8.0), DROIT);
        dev.axion.forge.net.JoueurSimule[] conducteur = new dev.axion.forge.net.JoueurSimule[1];
        helper.onEachTick(() -> {
            if (conducteur[0] != null && conducteur[0].connecte()) {
                conducteur[0].tick();
            }
        });
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(assembly.hasPhysicalBounds(), "simulée"))
                .thenExecute(() -> {
                    conducteur[0] = dev.axion.forge.net.JoueurSimule.rejoindre(helper.getLevel(), "conducteur_tp",
                            helper.absoluteVec(new Vec3(5.0, DESSUS_DALLE, 8.0)));
                    helper.assertTrue(assembly.reseat(conducteur[0].joueur(), "conducteur"), "assis au volant");
                })
                .thenIdle(10)
                .thenExecute(() -> ForgeAxionApi.view(assembly).teleport(new dev.axion.api.assembly.Transform(
                        assembly.position().add(1000.0, 1.0, 0.0), assembly.orientation(), new Vec3(1, 1, 1)), true))
                .thenIdle(40)
                .thenExecute(() -> {
                    try {
                        helper.assertTrue(!assembly.isRemoved(), "suivie à l'arrivée : " + assembly.getRemovalReason());
                        helper.assertTrue(conducteur[0].joueur().getVehicle() == assembly, "le conducteur reste assis : "
                                + conducteur[0].joueur().getVehicle());
                        double ecart = conducteur[0].joueur().position().distanceTo(assembly.position());
                        helper.assertTrue(ecart < 6.0, "il est avec elle : à " + ecart + " blocs");
                    } finally {
                        conducteur[0].quitter();
                        assembly.discard();
                    }
                })
                .thenSucceed();
    }

    /** Une arrivée hors du monde de Minecraft est refusée, à la commande comme à l'API, et rien ne bouge. */
    @GameTest(template = "test/plancher_16", timeoutTicks = 100)
    public static void adr183_uneArriveeHorsDuMondeEstRefusee(GameTestHelper helper) {
        AxionEntity assembly = AssembliesDeTest.poser(helper, MAILLON, new Vec3(4.5, DESSUS_DALLE + 0.05, 4.0),
                DROIT);
        helper.startSequence()
                .thenExecute(() -> {
                    Vec3 avant = assembly.position();
                    Vec3 dehors = new Vec3(0.0, 3.0e7, 0.0);
                    List<String> reponse = ParametresGameTests.commande(helper.getLevel().getServer(),
                            "axion tp " + assembly.getUUID() + " 0 30000000 0");
                    helper.assertTrue(reponse.size() == 1 && reponse.get(0).contains("hors du monde"),
                            "réponse : " + reponse);
                    Assembly vue = ForgeAxionApi.view(assembly);
                    dev.axion.api.assembly.Transform but = new dev.axion.api.assembly.Transform(dehors,
                            new org.joml.Quaternionf(), new Vec3(1, 1, 1));
                    helper.assertTrue(refuse(() -> vue.teleport(but, true)), "teleport refusée");
                    vue.setFrozen(true);
                    helper.assertTrue(refuse(() -> vue.setKinematicTransform(but)), "setKinematicTransform refusée");
                    helper.assertTrue(assembly.position().equals(avant), "rien n'a bougé : " + assembly.position());
                    assembly.discard();
                })
                .thenSucceed();
    }

    private static boolean refuse(Runnable demande) {
        try {
            demande.run();
            return false;
        } catch (IllegalArgumentException attendu) {
            return true;
        }
    }

    /** Ce qu'une assembly téléportée portait reste où il est : un cochon debout dessus ne la suit pas. */
    @GameTest(template = "test/plancher_16", timeoutTicks = 200)
    public static void adr183_ceQueLAssemblyPortaitReste(GameTestHelper helper) {
        ObservateursDeTest.declarer(helper, new Vec3(8.0, DESSUS_DALLE, 8.0));
        AxionEntity assembly = AssembliesDeTest.poser(helper, "axion:test/siege",
                new Vec3(4.5, DESSUS_DALLE, 4.5), DROIT);
        Pig cochon = EntityType.PIG.create(helper.getLevel());
        Vec3 arrivee = helper.absoluteVec(new Vec3(11.5, DESSUS_DALLE + 0.1, 11.5));
        Vec3[] place = new Vec3[1];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(assembly.hasPhysicalBounds() && assembly.carryPoses(0) != null,
                        "l'assembly est simulée"))
                .thenIdle(10)
                .thenExecute(() -> {
                    cochon.setNoAi(true);
                    helper.getLevel().addFreshEntity(cochon);
                    cochon.moveTo(assembly.getX(), assembly.getY() + 2.0, assembly.getZ(), 0f, 0f);
                    cochon.move(MoverType.SELF, new Vec3(0.0, -2.0, 0.0));
                    helper.assertTrue(CarrySupports.of(cochon) != null, "le cochon repose sur elle");
                })
                .thenIdle(3)
                .thenExecute(() -> {
                    place[0] = cochon.position();
                    ForgeAxionApi.view(assembly).teleport(new dev.axion.api.assembly.Transform(arrivee,
                            new org.joml.Quaternionf(), new Vec3(1, 1, 1)), true);
                })
                .thenIdle(5)
                .thenExecute(() -> {
                    helper.assertTrue(assembly.position().subtract(arrivee).horizontalDistance() < 0.3,
                            "l'assembly est arrivée : " + assembly.position());
                    helper.assertTrue(cochon.position().subtract(place[0]).horizontalDistance() < 0.5,
                            "le cochon est resté : " + cochon.position() + " pour " + place[0]);
                    cochon.discard();
                    assembly.discard();
                })
                .thenSucceed();
    }
}
