package dev.axion.forge;

import com.mojang.authlib.GameProfile;
import dev.axion.forge.net.JoueurSimule;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import dev.axion.asset.Anatomy;
import dev.axion.definition.Seating;
import dev.axion.net.AnimationLayers;
import dev.axion.net.Fragments;
import dev.axion.net.InputMessage;
import dev.axion.net.MessageKinds;
import dev.axion.seat.SeatEjection;
import dev.axion.seat.VehicleInput;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.joml.Vector3f;

/**
 * ADR-158 D2, D3 : la montée au clic, la position imposée, l'occupation des sièges (T-440, T-444).
 *
 * <p>L'asset {@code test/siege} est une caisse d'un bloc ; à deux blocs à sa gauche ({@code −x}), le
 * siège du conducteur, cube porteur d'un collider ; à deux blocs à sa droite, une portière, autre cube,
 * et au-dessus d'elle le node du siège passager, sans collider. La definition {@code test/siege} assoit
 * au siège conducteur par un clic sur son cube, au siège passager par un clic sur la portière
 * ({@code sit}).
 *
 * <p>L'assembly est tournée d'un quart de tour autour de {@code y} : la gauche du modèle ({@code −x})
 * est en {@code +z} dans le monde, sa droite en {@code −z}. Le joueur vise depuis le côté, le rayon
 * passant par le seul cube visé.
 */
@GameTestHolder("axion")
@PrefixGameTestTemplate(false)
public final class SiegesGameTests {

    /** Le dessus de la dalle du gabarit {@code plancher}, en coordonnées du test. */
    private static final double DESSUS_DALLE = 2.0;

    /** Un quart de tour autour de {@code y}. */
    private static final float[] QUART = {0f, (float) Math.sqrt(0.5), 0f, (float) Math.sqrt(0.5)};

    /** Hauteur des yeux d'un joueur debout. */
    private static final double YEUX = 1.62;

    private SiegesGameTests() {}

    /**
     * Un joueur factice de Forge, hors de la liste des joueurs : un test qui échoue ne laisse derrière lui
     * aucun joueur que les tests suivants compteraient. Passager, il est tické par son véhicule.
     */
    private static ServerPlayer joueur(GameTestHelper helper, String nom) {
        UUID uuid = UUID.nameUUIDFromBytes(("axion-siege:" + nom).getBytes(StandardCharsets.UTF_8));
        ServerPlayer joueur = FakePlayerFactory.get(helper.getLevel(), new GameProfile(uuid, nom));
        joueur.stopRiding();
        joueur.getPersistentData().remove(AxionEntity.REMEMBERED_SEAT);
        return joueur;
    }

    /** {@code point} du modèle, posé par la pose monde de l'assembly. */
    private static Vec3 duModele(AxionEntity assembly, float x, float y, float z) {
        Vector3f tourne = assembly.bodyRotation().transform(new Vector3f(x, y, z));
        return new Vec3(assembly.getX() + tourne.x, assembly.getY() + tourne.y, assembly.getZ() + tourne.z);
    }

    /** Place le joueur à {@code pieds} et lui fait regarder {@code cible}. */
    private static void viser(ServerPlayer joueur, Vec3 pieds, Vec3 cible) {
        double dx = cible.x - pieds.x;
        double dy = cible.y - (pieds.y + YEUX);
        double dz = cible.z - pieds.z;
        float lacet = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float tangage = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        joueur.moveTo(pieds.x, pieds.y, pieds.z, lacet, tangage);
        joueur.setYHeadRot(lacet);
    }

    /** Vise un point du modèle depuis un pas et demi à côté de lui, du côté {@code cote} du monde en z. */
    private static InteractionResult cliquer(AxionEntity assembly, ServerPlayer joueur, float x, float y, float z) {
        Vec3 cible = duModele(assembly, x, y, z);
        double cote = Math.signum(cible.z - assembly.getZ());
        viser(joueur, new Vec3(cible.x + 1.5, assembly.getY(), cible.z + cote * 0.5), cible);
        return assembly.interact(joueur, InteractionHand.MAIN_HAND);
    }

    private static void proche(GameTestHelper helper, Vec3 attendu, Entity entite, String quoi) {
        double ecart = attendu.distanceTo(entite.position());
        helper.assertTrue(ecart < 1e-3, quoi + " : " + entite.position() + ", " + attendu + " attendu (écart " + ecart + ")");
    }

    /**
     * T-440 : un clic sur le cube du siège conducteur y assoit le joueur ; un clic sur la caisse, qui ne
     * porte rien, ne fait rien ; un clic sur la portière assoit au siège passager. Chaque fois, le
     * passager est à la pose monde de son node {@code SEAT}, son décalage de monture compris, à 1 mm près,
     * l'assembly tournée d'un quart de tour.
     */
    @GameTest(template = "test/plancher", timeoutTicks = 200)
    public static void unClicSurUnSiegeYAssoitEtLePassagerSuitSonNode(GameTestHelper helper) {
        ObservateursDeTest.declarer(helper, new Vec3(4.0, DESSUS_DALLE, 4.0));
        AxionEntity assembly = AssembliesDeTest.poser(
                helper, "axion:test/siege", new Vec3(4.5, DESSUS_DALLE, 4.5), QUART);
        ServerPlayer joueur = joueur(helper, "conducteur");
        boolean[] fait = {false};
        helper.succeedWhen(() -> {
            helper.assertTrue(assembly.hasPhysicalBounds(), "l'assembly est simulée");
            if (!fait[0]) {
                fait[0] = true;
                helper.assertTrue(cliquer(assembly, joueur, 0f, 0.5f, 0f) == InteractionResult.PASS,
                        "la caisse ne porte ni siège ni interaction");
                helper.assertTrue(joueur.getVehicle() == null, "personne n'est assis");
                helper.assertTrue(cliquer(assembly, joueur, -2f, 0.5f, 0f) == InteractionResult.CONSUME,
                        "le cube du siège conducteur assoit");
            }
            helper.assertTrue(joueur.getVehicle() == assembly, "le joueur est passager de l'assembly");
            helper.assertTrue(assembly.seatOf(joueur) == 0, "au siège conducteur, " + assembly.seatOf(joueur));
            assembly.positionRider(joueur);
            proche(helper, duModele(assembly, -2f, 0f, 0f).add(0, joueur.getMyRidingOffset(), 0), joueur,
                    "le conducteur est à son node");

            helper.assertTrue(cliquer(assembly, joueur, 2f, 0.5f, 0f) == InteractionResult.CONSUME,
                    "la portière assoit au siège passager");
            helper.assertTrue(assembly.seatOf(joueur) == 1, "il a changé de siège, " + assembly.seatOf(joueur));
            assembly.positionRider(joueur);
            proche(helper, duModele(assembly, 2f, 1.1f, 0f).add(0, joueur.getMyRidingOffset(), 0), joueur,
                    "le passager est au node sans collider que la portière nomme");
            helper.assertTrue("passager".equals(joueur.getPersistentData().getString(AxionEntity.REMEMBERED_SEAT)),
                    "le joueur garde le nom de son siège");
            joueur.stopRiding();
        });
    }

    /**
     * T-444 : un siège pris refuse un second joueur ; un siège libéré par la descente se reprend ; une
     * entité remontée de force dans une
     * assembly pleine en descend au tick suivant.
     */
    @GameTest(template = "test/plancher", timeoutTicks = 200)
    public static void unSiegePrisRefuseEtUneAssemblyPleineNeGardePersonneDeTrop(GameTestHelper helper) {
        ObservateursDeTest.declarer(helper, new Vec3(4.0, DESSUS_DALLE, 4.0));
        AxionEntity assembly = AssembliesDeTest.poser(
                helper, "axion:test/siege", new Vec3(4.5, DESSUS_DALLE, 4.5), QUART);
        ServerPlayer a = joueur(helper, "premier");
        ServerPlayer b = joueur(helper, "second");
        ServerPlayer c = joueur(helper, "troisieme");
        int[] etape = {0};
        helper.succeedWhen(() -> {
            helper.assertTrue(assembly.hasPhysicalBounds(), "l'assembly est simulée");
            if (etape[0] == 0) {
                helper.assertTrue(cliquer(assembly, a, -2f, 0.5f, 0f) == InteractionResult.CONSUME, "a s'assoit");
                helper.assertTrue(cliquer(assembly, b, -2f, 0.5f, 0f) == InteractionResult.PASS,
                        "le siège de a refuse b");
                helper.assertTrue(b.getVehicle() == null, "b reste debout");
                helper.assertTrue(cliquer(assembly, b, 2f, 0.5f, 0f) == InteractionResult.CONSUME,
                        "b prend le siège passager");
                // a descend, puis remonte de force : le siège qu'il vient de libérer est le seul libre.
                a.stopRiding();
                helper.assertTrue(assembly.seatOf(a) == -1, "la descente libère le siège");
                helper.assertFalse(a.getPersistentData().contains(AxionEntity.REMEMBERED_SEAT),
                        "une descente oublie le siège : il ne serait pas rendu au retour");
                helper.assertTrue(a.startRiding(assembly, true), "remontée forcée");
                helper.assertTrue(assembly.seatOf(a) == 0, "a reprend le siège libre, " + assembly.seatOf(a));
                // L'assembly est pleine : c, remonté de force, n'a pas de siège.
                helper.assertTrue(c.startRiding(assembly, true), "remontée forcée dans une assembly pleine");
                helper.assertTrue(assembly.seatOf(c) == -1, "aucun siège pour c");
                etape[0] = 1;
            }
            helper.assertTrue(c.getVehicle() == null, "c est descendu au tick suivant");
            helper.assertTrue(assembly.seatOf(a) == 0 && assembly.seatOf(b) == 1, "a et b gardent leurs sièges");
            a.stopRiding();
            b.stopRiding();
        });
    }

    /**
     * T-444, le chemin réel, joueur simulé entier : à sa déconnexion l'assembly reste au monde, le joueur en
     * descend ; à son retour il retrouve son siège.
     */
    @GameTest(template = "test/plancher", timeoutTicks = 300)
    public static void unJoueurQuiSeDeconnecteLaisseLAssemblyEtRetrouveSonSiege(GameTestHelper helper) {
        ObservateursDeTest.declarer(helper, new Vec3(4.0, DESSUS_DALLE, 4.0));
        AxionEntity assembly = AssembliesDeTest.poser(
                helper, "axion:test/siege", new Vec3(4.5, DESSUS_DALLE, 4.5), QUART);
        Vec3 ou = helper.absoluteVec(new Vec3(1.0, DESSUS_DALLE, 1.0));
        JoueurSimule[] simule = {JoueurSimule.rejoindre(helper.getLevel(), "revenant", ou)};
        helper.onEachTick(() -> simule[0].tick());
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(assembly.hasPhysicalBounds(), "l'assembly est simulée"))
                .thenExecute(() -> helper.assertTrue(assembly.sit(simule[0].joueur(), 1), "assis au siège passager"))
                .thenExecuteAfter(2, () -> {
                    simule[0].quitter();
                    helper.assertFalse(assembly.isRemoved(), "l'assembly reste au monde : " + assembly.getRemovalReason());
                    helper.assertTrue(assembly.getPassengers().isEmpty(), "son siège est libre");
                    simule[0] = JoueurSimule.rejoindre(helper.getLevel(), "revenant", ou);
                })
                .thenWaitUntil(() -> helper.assertTrue(simule[0].joueur().getVehicle() == assembly, "le joueur est rassis"))
                .thenExecute(() -> {
                    helper.assertTrue(assembly.seatOf(simule[0].joueur()) == 1,
                            "au siège qu'il occupait, " + assembly.seatOf(simule[0].joueur()));
                    simule[0].quitter();
                })
                .thenSucceed();
    }

    /** T-444 : à son retour, son siège pris, le joueur reçoit le premier libre. */
    @GameTest(template = "test/plancher", timeoutTicks = 300)
    public static void unJoueurDontLeSiegeEstPrisRecoitLePremierLibre(GameTestHelper helper) {
        ObservateursDeTest.declarer(helper, new Vec3(4.0, DESSUS_DALLE, 4.0));
        AxionEntity assembly = AssembliesDeTest.poser(
                helper, "axion:test/siege", new Vec3(4.5, DESSUS_DALLE, 4.5), QUART);
        Vec3 ou = helper.absoluteVec(new Vec3(1.0, DESSUS_DALLE, 1.0));
        JoueurSimule[] simule = {JoueurSimule.rejoindre(helper.getLevel(), "supplante", ou)};
        helper.onEachTick(() -> simule[0].tick());
        ServerPlayer autre = joueur(helper, "supplante_autre");
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(assembly.hasPhysicalBounds(), "l'assembly est simulée"))
                .thenExecute(() -> helper.assertTrue(assembly.sit(simule[0].joueur(), 1), "assis au siège passager"))
                .thenExecuteAfter(2, () -> {
                    simule[0].quitter();
                    helper.assertTrue(assembly.sit(autre, 1), "un autre prend le siège");
                    simule[0] = JoueurSimule.rejoindre(helper.getLevel(), "supplante", ou);
                })
                .thenWaitUntil(() -> helper.assertTrue(simule[0].joueur().getVehicle() == assembly, "le joueur est rassis"))
                .thenExecute(() -> {
                    helper.assertTrue(assembly.seatOf(simule[0].joueur()) == 0, "au premier libre");
                    autre.stopRiding();
                    simule[0].quitter();
                })
                .thenSucceed();
    }

    /** ADR-158 : un joueur revenu qui a déjà marché n'est pas ramené de force sur son siège. */
    @GameTest(template = "test/plancher", timeoutTicks = 300)
    public static void unJoueurRevenuQuiABougeNEstPasRassis(GameTestHelper helper) {
        ObservateursDeTest.declarer(helper, new Vec3(4.0, DESSUS_DALLE, 4.0));
        AxionEntity assembly = AssembliesDeTest.poser(
                helper, "axion:test/siege", new Vec3(4.5, DESSUS_DALLE, 4.5), QUART);
        Vec3 ou = helper.absoluteVec(new Vec3(1.0, DESSUS_DALLE, 1.0));
        JoueurSimule[] simule = {JoueurSimule.rejoindre(helper.getLevel(), "marcheur", ou)};
        helper.onEachTick(() -> simule[0].tick());
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(assembly.hasPhysicalBounds(), "l'assembly est simulée"))
                .thenExecute(() -> helper.assertTrue(assembly.sit(simule[0].joueur(), 1), "assis au siège passager"))
                .thenExecuteAfter(2, () -> {
                    simule[0].quitter();
                    simule[0] = JoueurSimule.rejoindre(helper.getLevel(), "marcheur", ou);
                    simule[0].joueur().moveTo(ou.x + 4.0, ou.y, ou.z);
                })
                .thenExecuteAfter(3, () -> {
                    helper.assertTrue(simule[0].joueur().getVehicle() == null, "il a repris sa route");
                    helper.assertFalse(simule[0].joueur().getPersistentData().contains(AxionEntity.REMEMBERED_SEAT),
                            "son siège est oublié");
                    simule[0].quitter();
                })
                .thenSucceed();
    }

    /** ADR-158 : l'assembly supprimée pendant son absence, le joueur revient à pied, et ses clés sont oubliées. */
    @GameTest(template = "test/plancher", timeoutTicks = 400)
    public static void unJoueurDontLAssemblyADisparuRevientAPied(GameTestHelper helper) {
        ObservateursDeTest.declarer(helper, new Vec3(4.0, DESSUS_DALLE, 4.0));
        AxionEntity assembly = AssembliesDeTest.poser(
                helper, "axion:test/siege", new Vec3(4.5, DESSUS_DALLE, 4.5), QUART);
        Vec3 ou = helper.absoluteVec(new Vec3(1.0, DESSUS_DALLE, 1.0));
        JoueurSimule[] simule = {JoueurSimule.rejoindre(helper.getLevel(), "pieton", ou)};
        helper.onEachTick(() -> simule[0].tick());
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(assembly.hasPhysicalBounds(), "l'assembly est simulée"))
                .thenExecute(() -> helper.assertTrue(assembly.sit(simule[0].joueur(), 1), "assis au siège passager"))
                .thenExecuteAfter(2, () -> {
                    simule[0].quitter();
                    assembly.discard();
                    simule[0] = JoueurSimule.rejoindre(helper.getLevel(), "pieton", ou);
                    helper.assertTrue(simule[0].joueur().getPersistentData().contains(AxionEntity.REMEMBERED_SEAT),
                            "son fichier avait gardé son siège");
                })
                .thenExecuteAfter(205, () -> {
                    helper.assertTrue(simule[0].joueur().getVehicle() == null, "il revient à pied");
                    helper.assertFalse(simule[0].joueur().getPersistentData().contains(AxionEntity.REMEMBERED_SEAT),
                            "l'attente finie, le siège est oublié");
                    simule[0].quitter();
                })
                .thenSucceed();
    }

    /** Rassoit un joueur au siège passager, de force : Minecraft refuse une remontée trop rapprochée. */
    private static void rasseoir(AxionEntity assembly, ServerPlayer joueur) {
        if (!assembly.reseat(joueur, "passager") || assembly.seatOf(joueur) != 1) {
            throw new GameTestAssertException("le joueur n'est pas rassis au siège passager");
        }
    }

    /** Rassoit le joueur, et lui fait tenir le sneak. */
    private static void descendre(AxionEntity assembly, ServerPlayer joueur) {
        rasseoir(assembly, joueur);
        joueur.setShiftKeyDown(true);
    }

    private static void pierre(GameTestHelper helper, Vec3 ou) {
        helper.getLevel().setBlockAndUpdate(BlockPos.containing(ou), Blocks.STONE.defaultBlockState());
    }

    /** Le joueur a quitté l'assembly, et se tient à la place attendue, à 1 cm près. */
    private static void sorti(GameTestHelper helper, Vec3 attendu, ServerPlayer joueur, String quoi) {
        helper.assertTrue(joueur.getVehicle() == null, quoi + " : il est descendu");
        double ecart = attendu.distanceTo(joueur.position());
        helper.assertTrue(ecart < 0.01, quoi + " : " + joueur.position() + ", " + attendu + " attendu");
        helper.assertTrue(helper.getLevel().noCollision(joueur, joueur.getBoundingBox().deflate(1.0e-3)),
                quoi + " : sa boîte ne touche aucun bloc");
    }

    /**
     * T-441 (R-711) : au sneak, une sortie dans un collider de l'assembly est écartée ; la première sortie
     * libre, dans l'ordre déclaré ; prise, la suivante ; toutes
     * prises, la position du siège ; prise aussi, la place libre la plus proche — un bloc au-dessus. Rien de
     * libre : le joueur reste assis, sans recommencer la recherche à chaque tick ; de la place revenue, il
     * descend.
     */
    @GameTest(template = "test/plancher", timeoutTicks = 400)
    public static void laDescenteSuitSesSortiesPuisLeSiegePuisLaPlaceLaPlusProche(GameTestHelper helper) {
        ObservateursDeTest.declarer(helper, new Vec3(4.0, DESSUS_DALLE, 4.0));
        AxionEntity assembly = AssembliesDeTest.poser(
                helper, "axion:test/siege", new Vec3(4.5, DESSUS_DALLE, 4.5), QUART);
        ServerPlayer joueur = joueur(helper, "descendeur");
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(assembly.hasPhysicalBounds(), "l'assembly est simulée"))
                .thenIdle(40)
                .thenExecute(() -> {
                    // La première sortie du conducteur tombe dans la caisse : seul le natif le voit.
                    helper.assertTrue(assembly.reseat(joueur, "conducteur") && assembly.seatOf(joueur) == 0, "assis");
                    joueur.setShiftKeyDown(true);
                })
                .thenExecuteAfter(2, () -> {
                    sorti(helper, duModele(assembly, -3f, 0f, 0f), joueur, "la sortie dans un collider est écartée");
                    descendre(assembly, joueur);
                })
                .thenExecuteAfter(2, () -> {
                    sorti(helper, duModele(assembly, 2f, 1.1f, 1.5f), joueur, "la première sortie");
                    pierre(helper, duModele(assembly, 2f, 1.1f, 1.5f));
                    descendre(assembly, joueur);
                })
                .thenExecuteAfter(2, () -> {
                    sorti(helper, duModele(assembly, 2f, 1.1f, -1.5f), joueur, "la seconde sortie, la première prise");
                    pierre(helper, duModele(assembly, 2f, 1.1f, -1.5f));
                    descendre(assembly, joueur);
                })
                .thenExecuteAfter(2, () -> {
                    sorti(helper, duModele(assembly, 2f, 1.1f, 0f), joueur, "le siège, les sorties prises");
                    pierre(helper, duModele(assembly, 2f, 1.1f, 0f));
                    descendre(assembly, joueur);
                })
                .thenExecuteAfter(2, () -> {
                    sorti(helper, duModele(assembly, 2f, 2.1f, 0f), joueur, "la place la plus proche, le siège pris");
                    assembly.exitProbe((x, y, z) -> false);
                    descendre(assembly, joueur);
                })
                .thenExecuteAfter(2, () -> {
                    helper.assertTrue(joueur.getVehicle() == assembly && assembly.seatOf(joueur) == 1,
                            "rien de libre : il reste assis");
                    assembly.exitProbe(null);
                    joueur.setShiftKeyDown(true);
                })
                .thenExecuteAfter(2, () -> {
                    helper.assertTrue(joueur.getVehicle() == assembly,
                            "la recherche ne se refait pas à chaque tick");
                })
                .thenExecuteAfter(25, () -> joueur.setShiftKeyDown(true))
                .thenExecuteAfter(2, () -> {
                    sorti(helper, duModele(assembly, 2f, 2.1f, 0f), joueur, "de la place revenue, il descend");
                })
                .thenSucceed();
    }

    /**
     * T-957 (R-712) : le node qui porte un siège, ou un ancêtre, est perdu : au tick suivant l'occupant est
     * éjecté, à une place sûre, l'éjection est retenue avec sa cause, et les sièges perdus ne reçoivent plus
     * personne.
     */
    @GameTest(template = "test/plancher", timeoutTicks = 200)
    public static void unOccupantEstEjecteSiSonSiegeEstDetache(GameTestHelper helper) {
        ObservateursDeTest.declarer(helper, new Vec3(4.0, DESSUS_DALLE, 4.0));
        AxionEntity assembly = AssembliesDeTest.poser(
                helper, "axion:test/siege", new Vec3(4.5, DESSUS_DALLE, 4.5), QUART);
        ServerPlayer joueur = joueur(helper, "ejecte");
        ServerPlayer autre = joueur(helper, "ejecte_autre");
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(assembly.hasPhysicalBounds(), "l'assembly est simulée"))
                .thenIdle(40)
                .thenExecute(() -> {
                    rasseoir(assembly, joueur);
                    Seating seating = AxionEntities.seating(assembly.definitionId()).orElseThrow();
                    Anatomy anatomie = AxionEntities.summary(assembly.definitionId()).orElseThrow().anatomy();
                    // Le parent du node du siège : la caisse, qui porte les deux sièges.
                    assembly.loseNode(anatomie.parent(seating.seats().get(1).node()), SeatEjection.Cause.DETACHED);
                    helper.assertTrue(joueur.getVehicle() == assembly, "l'éjection attend le tick suivant");
                })
                .thenExecuteAfter(2, () -> {
                    sorti(helper, duModele(assembly, 2f, 1.1f, 1.5f), joueur, "éjecté à sa première sortie libre");
                    helper.assertTrue(new SeatEjection("passager", joueur.getId(), SeatEjection.Cause.DETACHED)
                            .equals(assembly.lastEjection()), "l'éjection retenue : " + assembly.lastEjection());
                    helper.assertFalse(assembly.sit(autre, 1), "le siège perdu ne reçoit plus personne");
                    helper.assertFalse(assembly.sit(autre, 0), "l'autre siège, porté par le même node, non plus");
                })
                .thenSucceed();
    }

    /** ADR-158 D5 : une assembly supprimée fait descendre son passager par la même règle, pas à la verticale. */
    @GameTest(template = "test/plancher", timeoutTicks = 200)
    public static void uneAssemblySupprimeeFaitDescendreSonPassager(GameTestHelper helper) {
        ObservateursDeTest.declarer(helper, new Vec3(4.0, DESSUS_DALLE, 4.0));
        AxionEntity assembly = AssembliesDeTest.poser(
                helper, "axion:test/siege", new Vec3(4.5, DESSUS_DALLE, 4.5), QUART);
        ServerPlayer joueur = joueur(helper, "supprime");
        Vec3[] sortie = new Vec3[1];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(assembly.hasPhysicalBounds(), "l'assembly est simulée"))
                .thenIdle(40)
                .thenExecute(() -> {
                    rasseoir(assembly, joueur);
                    sortie[0] = duModele(assembly, 2f, 1.1f, 1.5f);
                    assembly.discard();
                    sorti(helper, sortie[0], joueur, "à sa première sortie libre");
                    helper.assertTrue(assembly.lastEjection() != null
                            && assembly.lastEjection().cause() == SeatEjection.Cause.ASSEMBLY_REMOVED,
                            "l'éjection retenue : " + assembly.lastEjection());
                })
                .thenSucceed();
    }

    private static byte[] trame(int sequence, AxionEntity assembly, int siege, VehicleInput entree) {
        return Fragments.split(new InputMessage(sequence, assembly.getId(), siege, entree).encode(), 0).get(0);
    }

    /**
     * T-442 (R-710, INV-02), de bout en bout par le canal : l'entrée d'un siège non occupé, une séquence
     * rétrograde, un message mal formé sont ignorés ; un NaN, une valeur hors bornes, un contrôle non déclaré
     * sont ramenés dans les bornes ; un débit au-delà de R-1690 est limité ; un siège libéré ou muet ne commande
     * plus rien ; et rien de tout cela ne déplace l'assembly.
     */
    @GameTest(template = "test/plancher", timeoutTicks = 300)
    public static void lesEntreesDuClientSontValideesAuServeur(GameTestHelper helper) {
        ObservateursDeTest.declarer(helper, new Vec3(4.0, DESSUS_DALLE, 4.0));
        AxionEntity assembly = AssembliesDeTest.poser(
                helper, "axion:test/siege", new Vec3(4.5, DESSUS_DALLE, 4.5), QUART);
        JoueurSimule simule = JoueurSimule.rejoindre(
                helper.getLevel(), "pilote", helper.absoluteVec(new Vec3(1.0, DESSUS_DALLE, 1.0)));
        helper.onEachTick(simule::tick);
        ServerPlayer joueur = simule.joueur();
        VehicleInput plein = new VehicleInput(0.5f, 1f, -0.25f, 1f, 1f, (byte) 3, 7);
        VehicleInput borne = new VehicleInput(0.5f, 0f, -0.25f, 0f, 0f, (byte) 0, 0);
        Vec3[] avant = new Vec3[1];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(assembly.hasPhysicalBounds(), "l'assembly est simulée"))
                .thenIdle(40)
                .thenExecute(() -> {
                    avant[0] = assembly.position();
                    helper.assertTrue(assembly.reseat(joueur, "passager"), "assis à la place du passager");
                    simule.envoyer(trame(1, assembly, 0, plein));
                })
                .thenExecuteAfter(1, () -> {
                    helper.assertTrue(VehicleInput.NONE.equals(assembly.seatInput(0)), "un passager ne conduit pas");
                    joueur.stopRiding();
                    helper.assertTrue(assembly.reseat(joueur, "conducteur"), "assis au volant");
                    simule.envoyer(trame(2, assembly, 0, plein));
                })
                .thenExecuteAfter(1, () -> {
                    helper.assertTrue(borne.equals(assembly.seatInput(0)), "contrôles déclarés seuls : " + assembly.seatInput(0));
                    simule.envoyer(trame(1, assembly, 0, new VehicleInput(1f, 0f, 1f, 0f, 0f, (byte) 0, 0)));
                    simule.envoyer(new byte[] {0, (byte) MessageKinds.INPUT, 1});
                })
                .thenExecuteAfter(1, () -> {
                    helper.assertTrue(borne.equals(assembly.seatInput(0)), "séquence rétrograde et message coupé ignorés");
                    simule.envoyer(trame(3, assembly, 0, new VehicleInput(Float.NaN, 0f, -9f, 0f, 0f, (byte) 0, 0)));
                })
                .thenExecuteAfter(1, () -> {
                    helper.assertTrue(new VehicleInput(0f, 0f, -1f, 0f, 0f, (byte) 0, 0).equals(assembly.seatInput(0)),
                            "NaN à zéro, hors bornes ramené : " + assembly.seatInput(0));
                    joueur.stopRiding();
                    helper.assertTrue(VehicleInput.NONE.equals(assembly.seatInput(0)), "un siège libéré ne commande plus");
                    helper.assertTrue(assembly.reseat(joueur, "conducteur"), "rassis");
                    simule.envoyer(trame(4, assembly, 0, plein));
                })
                .thenExecuteAfter(1, () -> helper.assertTrue(borne.equals(assembly.seatInput(0)), "de nouveau au volant"))
                .thenExecuteAfter(10, () -> helper.assertTrue(VehicleInput.NONE.equals(assembly.seatInput(0)),
                        "rien depuis net.extrapolation_max_ms : plus d'entrée"))
                .thenExecute(() -> {
                    for (int sequence = 100; sequence < 160; sequence++) {
                        simule.envoyer(trame(sequence, assembly, 0, plein));
                    }
                })
                .thenExecuteAfter(1, () -> {
                    helper.assertTrue(simule.synchronisation().lastTickLimited() > 0, "R-1690 : le flot est limité");
                    helper.assertTrue(assembly.position().distanceTo(avant[0]) < 0.01,
                            "INV-02 : rien de tout cela n'a déplacé l'assembly");
                    simule.quitter();
                })
                .thenSucceed();
    }

    /**
     * T-443 : un clic sur le coulisseau bascule son clip, à l'endroit puis à l'envers ; un clic sur le socle le
     * rejoue depuis son début ; un second client le voit, par les snapshots.
     */
    @GameTest(template = "test/plancher", timeoutTicks = 300)
    public static void unClicJoueLaClipDeSonNodeEtUnSecondClientLeVoit(GameTestHelper helper) {
        ObservateursDeTest.declarer(helper, new Vec3(4.0, DESSUS_DALLE, 4.0));
        AxionEntity assembly = AssembliesDeTest.poser(helper, "axion:test/anime/glissiere_clic",
                new Vec3(4.5, DESSUS_DALLE, 4.5), new float[] {0f, 0f, 0f, 1f});
        ServerPlayer joueur = joueur(helper, "cliqueur");
        JoueurSimule temoin = JoueurSimule.rejoindre(
                helper.getLevel(), "temoin", helper.absoluteVec(new Vec3(4.0, DESSUS_DALLE, 7.0)));
        helper.onEachTick(temoin::tick);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(assembly.hasPhysicalBounds(), "l'assembly est simulée"))
                .thenIdle(40)
                .thenExecute(() -> {
                    helper.assertTrue(cliquer(assembly, joueur, 0f, 1.25f, 0f) == InteractionResult.CONSUME, "le coulisseau");
                    AnimationLayers.Layer couche = assembly.animation().state().layer(1);
                    helper.assertTrue(couche.playing() && couche.speed() == 1f, "joué à l'endroit, à la couche déclarée : " + couche);
                })
                .thenWaitUntil(() -> temoinVoit(helper, temoin, assembly, 1f))
                .thenExecute(() -> {
                    helper.assertTrue(cliquer(assembly, joueur, 0f, 1.25f, 0f) == InteractionResult.CONSUME, "le coulisseau, encore");
                    helper.assertTrue(assembly.animation().state().layer(1).speed() == -1f, "à l'envers");
                })
                .thenWaitUntil(() -> temoinVoit(helper, temoin, assembly, -1f))
                .thenExecute(() -> {
                    helper.assertTrue(cliquer(assembly, joueur, 0f, 0.5f, 0f) == InteractionResult.CONSUME, "le socle");
                    AnimationLayers.Layer couche = assembly.animation().state().layer(1);
                    helper.assertTrue(couche.speed() == 1f && couche.origin() == 0f, "rejoué depuis son début : " + couche);
                    temoin.quitter();
                })
                .thenSucceed();
    }

    private static void temoinVoit(GameTestHelper helper, JoueurSimule temoin, AxionEntity assembly, float vitesse) {
        boolean vu = false;
        for (JoueurSimule.Recu recu : temoin.recus()) {
            vu = recu.handle() == assembly.getId() && recu.state().animation().layer(1).playing()
                    && recu.state().animation().layer(1).speed() == vitesse || vu;
        }
        helper.assertTrue(vu, "le témoin voit la vitesse " + vitesse);
    }

    /** T-443 : une action sans système compile, et un clic reste sans effet. */
    @GameTest(template = "test/plancher", timeoutTicks = 200)
    public static void uneActionSansSystemeResteSansEffet(GameTestHelper helper) {
        ObservateursDeTest.declarer(helper, new Vec3(4.0, DESSUS_DALLE, 4.0));
        AxionEntity assembly = AssembliesDeTest.poser(helper, "axion:test/anime/glissiere_reparer",
                new Vec3(4.5, DESSUS_DALLE, 4.5), new float[] {0f, 0f, 0f, 1f});
        ServerPlayer joueur = joueur(helper, "reparateur");
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(assembly.hasPhysicalBounds(), "l'assembly est simulée"))
                .thenIdle(40)
                .thenExecute(() -> {
                    helper.assertTrue(cliquer(assembly, joueur, 0f, 0.5f, 0f) == InteractionResult.PASS, "sans effet");
                    helper.assertTrue(assembly.animation().state().equals(AnimationLayers.NONE), "aucun clip joué");
                })
                .thenSucceed();
    }
}
