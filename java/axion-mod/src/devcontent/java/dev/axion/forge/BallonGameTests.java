package dev.axion.forge;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * Le ballon de test d'ADR-149 : le matériau de sa part traverse la frontière et décide du contact.
 *
 * <p>{@code axion:test/ballon} est une sphère d'un bloc de diamètre (modèle Blender,
 * {@code devcontent/blender/ballon_adr-149.blend}) dont la part nomme {@code axion:test/balle} :
 * restitution 0,8 en mode {@code max}, qui l'emporte sur la pierre de la dalle. Lâché à côté d'un
 * cube d'acier générique, il rebondit quand le cube ne rebondit pas, puis se pose. Sans la table des
 * matériaux au natif, ou sans l'index de la part sur son collider, il ne rebondirait pas.
 */
@GameTestHolder("axion")
@PrefixGameTestTemplate(false)
public final class BallonGameTests {

    /** Le dessus de la dalle du gabarit {@code plancher}, en coordonnées du test. */
    private static final double DESSUS_DALLE = 2.0;

    /** Hauteur de lâcher au-dessus de la dalle, en blocs. */
    private static final double LACHER = 3.0;

    private static final float[] DROIT = {0f, 0f, 0f, 1f};

    /** Rayon du ballon : son origine est en son centre, qui repose à un rayon de la dalle. */
    private static final double RAYON = 0.5;

    private BallonGameTests() {}

    /**
     * Ce que l'on relève d'un corps lâché : sa hauteur la plus haute après avoir touché la dalle,
     * mesurée depuis sa hauteur de repos — celle de son origine posé sur la dalle.
     */
    private static final class Releve {
        private final AxionEntity corps;
        private final double repos;
        private boolean touche;
        private double plusHaut = Double.NEGATIVE_INFINITY;
        private double precedente;
        private int immobile;

        Releve(AxionEntity corps, double repos) {
            this.corps = corps;
            this.repos = repos;
            this.precedente = corps.getY();
        }

        void tick() {
            double y = corps.getY();
            if (y < repos + 0.2) {
                touche = true;
            }
            if (touche) {
                plusHaut = Math.max(plusHaut, y - repos);
            }
            immobile = Math.abs(y - precedente) < 1e-4 ? immobile + 1 : 0;
            precedente = y;
        }
    }

    @GameTest(template = "test/plancher", timeoutTicks = 400)
    public static void leBallonRebonditLaOuLAcierNeRebonditPasPuisSePose(GameTestHelper helper) {
        ObservateursDeTest.declarer(helper, new Vec3(4.0, DESSUS_DALLE, 4.0));
        AxionEntity ballon = AssembliesDeTest.poser(
                helper, "axion:test/ballon", new Vec3(2.5, DESSUS_DALLE + LACHER, 4.5), DROIT);
        AxionEntity cube = AssembliesDeTest.poser(
                helper, "axion:test_cube", new Vec3(6.5, DESSUS_DALLE + LACHER, 4.5), DROIT);
        double sol = helper.absoluteVec(new Vec3(0, DESSUS_DALLE, 0)).y;
        // Le cube de test a son origine à sa base, le ballon en son centre.
        Releve deBallon = new Releve(ballon, sol + RAYON);
        Releve deCube = new Releve(cube, sol);
        double chute = ballon.getY() - (sol + RAYON);
        helper.onEachTick(() -> {
            deBallon.tick();
            deCube.tick();
            helper.assertTrue(
                    ballon.getY() > sol + RAYON - 0.25, "le ballon a traversé la dalle : y = " + ballon.getY());
        });
        helper.succeedWhen(() -> {
            // e = 0,8 : il remonte vers e² × chute ; au moins un tiers de la chute, marge comprise.
            helper.assertTrue(
                    deBallon.plusHaut > chute / 3.0,
                    "le ballon remonte de " + deBallon.plusHaut + " après la dalle, lâché de " + chute);
            helper.assertTrue(
                    deCube.touche && deCube.plusHaut < 0.05,
                    "le cube d'acier générique rebondit de " + deCube.plusHaut);
            // Puis il se pose : à un rayon de la dalle, immobile — un collider de rayon 0,5.
            helper.assertTrue(deBallon.immobile >= 20, "le ballon bouge encore");
            helper.assertTrue(
                    Math.abs(ballon.getY() - (sol + RAYON)) < 0.05,
                    "le ballon repose à y = " + ballon.getY() + ", attendu " + (sol + RAYON));
        });
    }
}
