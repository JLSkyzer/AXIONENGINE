package dev.axion.forge;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.animal.Pig;
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
                cochon.moveTo(x - 4.0, assembly.getY(), z, 0f, 0f);
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
