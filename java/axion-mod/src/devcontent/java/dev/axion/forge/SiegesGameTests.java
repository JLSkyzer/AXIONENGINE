package dev.axion.forge;

import com.mojang.authlib.GameProfile;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
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
            proche(helper, duModele(assembly, 2f, 1f, 0f).add(0, joueur.getMyRidingOffset(), 0), joueur,
                    "le passager est au node sans collider que la portière nomme");
            helper.assertTrue("passager".equals(joueur.getPersistentData().getString(AxionEntity.REMEMBERED_SEAT)),
                    "le joueur garde le nom de son siège");
            joueur.stopRiding();
        });
    }

    /**
     * T-444 : un siège pris refuse un second joueur ; un siège libéré par la descente se reprend ; un
     * joueur remonté de force retrouve le siège qu'il a gardé ; une entité remontée de force dans une
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
                // a descend, puis remonte de force, comme Minecraft le fait au retour d'un joueur : il
                // retrouve le siège conducteur qu'il a gardé.
                a.stopRiding();
                helper.assertTrue(assembly.seatOf(a) == -1, "la descente libère le siège");
                helper.assertTrue(a.startRiding(assembly, true), "remontée forcée");
                helper.assertTrue(assembly.seatOf(a) == 0, "a retrouve son siège, " + assembly.seatOf(a));
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
}
