package dev.axion.forge;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * ADR-159, tranche 2 : un véhicule sur de vrais blocs (T-320, T-326, D3).
 *
 * <p>La definition {@code test/vehicule} a pour origine le contact des roues au repos (§6.5). À
 * l'équilibre, le ressort se comprime de {@code g / raideur} de son débattement : son origine est sous
 * le sol de cette hauteur.
 */
@GameTestHolder("axion")
@PrefixGameTestTemplate(false)
public final class VehiculesGameTests {

    /** Le dessus de la dalle du gabarit {@code plancher}, en coordonnées du test. */
    private static final double DESSUS_DALLE = 2.0;

    private static final float[] IDENTITE = {0f, 0f, 0f, 1f};

    /** L'enfoncement à l'équilibre : {@code g / raideur · débattement} de la definition, en blocs. */
    private static final double ENFONCEMENT = 9.81 / 25.0 * 0.18;

    private static final Vec3 CENTRE = new Vec3(4.5, DESSUS_DALLE, 4.5);

    private VehiculesGameTests() {}

    private static AxionEntity poser(GameTestHelper helper, double hauteur) {
        ObservateursDeTest.declarer(helper, new Vec3(4.0, DESSUS_DALLE, 4.0));
        return AssembliesDeTest.poser(helper, "axion:test/vehicule", CENTRE.add(0, hauteur, 0), IDENTITE);
    }

    private static double hauteur(GameTestHelper helper, AxionEntity assembly) {
        return helper.relativeVec(assembly.position()).y - DESSUS_DALLE;
    }

    /** T-320 : sur de vrais blocs, il repose à sa compression d'équilibre, sans s'enfoncer ni dériver. */
    @GameTest(template = "test/plancher", timeoutTicks = 200)
    public static void t320ReposeSurSesRoues(GameTestHelper helper) {
        AxionEntity assembly = poser(helper, 0.1);
        helper.startSequence()
                .thenIdle(120)
                .thenExecute(() -> {
                    double h = hauteur(helper, assembly);
                    helper.assertTrue(Math.abs(h + ENFONCEMENT) < 0.03,
                            "hauteur " + h + ", " + (-ENFONCEMENT) + " attendu");
                    Vec3 ici = helper.relativeVec(assembly.position());
                    helper.assertTrue(Math.abs(ici.x - CENTRE.x) < 0.02 && Math.abs(ici.z - CENTRE.z) < 0.02,
                            "dérive : " + ici);
                })
                .thenSucceed();
    }

    /** T-326 : une chute de dix blocs finit posée sur ses roues, sans rebond ni vitesse folle. */
    @GameTest(template = "test/plancher", timeoutTicks = 400)
    public static void t326ChuteDeDixBlocs(GameTestHelper helper) {
        AxionEntity assembly = poser(helper, 10.0);
        double[] plusBasEnsuite = {Double.MAX_VALUE};
        boolean[] touche = {false};
        helper.onEachTick(() -> {
            double h = hauteur(helper, assembly);
            helper.assertTrue(Double.isFinite(h) && h <= 10.5, "hauteur " + h);
            if (h < 1.0) {
                touche[0] = true;
            }
            if (touche[0]) {
                plusBasEnsuite[0] = Math.min(plusBasEnsuite[0], h);
                helper.assertTrue(h < 3.0, "rebond à " + h);
            }
        });
        helper.startSequence()
                .thenIdle(260)
                .thenExecute(() -> {
                    double h = hauteur(helper, assembly);
                    helper.assertTrue(touche[0], "jamais arrivé au sol");
                    helper.assertTrue(Math.abs(h + ENFONCEMENT) < 0.05, "fin à " + h);
                    helper.assertTrue(plusBasEnsuite[0] > -0.6, "écrasé à " + plusBasEnsuite[0]);
                })
                .thenSucceed();
    }

    /**
     * ADR-159 D3 : un véhicule endormi ne touche pas le sol par son châssis ; les blocs qui disparaissent
     * sous ses roues le réveillent quand même, et il tombe.
     */
    @GameTest(template = "test/plancher", timeoutTicks = 400)
    public static void lesBlocsRetiresSousUnVehiculeEndormiLeFontTomber(GameTestHelper helper) {
        AxionEntity assembly = poser(helper, 0.1);
        helper.startSequence()
                .thenIdle(200)
                .thenExecute(() -> {
                    helper.assertTrue(assembly.netSleeping(), "il devrait dormir");
                    for (int x = 0; x < 9; x++) {
                        for (int z = 0; z < 9; z++) {
                            helper.setBlock(new BlockPos(x, 1, z), Blocks.AIR);
                        }
                    }
                })
                .thenWaitUntil(() -> helper.assertTrue(hauteur(helper, assembly) < -0.6,
                        "toujours suspendu : " + hauteur(helper, assembly)))
                .thenSucceed();
    }
}
