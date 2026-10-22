package dev.axion.forge;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * ADR-151 D2 : une assembly porte plusieurs corps, un par node racine que sa definition déclare.
 *
 * <p>L'asset {@code test/deux_corps} porte deux cubes, {@code a} à l'origine et {@code b} décalé de
 * deux blocs en x, chacun avec son collider. La definition {@code test/deux_corps} en fait deux
 * corps ; {@code test/deux_cubes}, sans {@code bodies}, un seul, aux colliders posés par leurs
 * nodes.
 */
@GameTestHolder("axion")
@PrefixGameTestTemplate(false)
public final class MultiCorpsGameTests {

    /** Le dessus de la dalle du gabarit {@code plancher}, en coordonnées du test. */
    private static final double DESSUS_DALLE = 2.0;

    private MultiCorpsGameTests() {}

    /**
     * Le cube {@code a} repose sur un pilier ; {@code b}, à côté, au-dessus du vide, tombe seul
     * sur la dalle — les deux corps ne sont liés par rien. L'entité suit le corps racine et reste
     * sur le pilier ; son emprise couvre les deux corps.
     */
    @GameTest(template = "test/plancher", timeoutTicks = 200)
    public static void unCorpsSansAppuiTombeSeulEtLEntiteSuitLaRacine(GameTestHelper helper) {
        ObservateursDeTest.declarer(helper, new Vec3(4.0, DESSUS_DALLE, 4.0));
        helper.setBlock(BlockPos.containing(4.0, DESSUS_DALLE, 4.0), Blocks.STONE);
        AxionEntity assembly = AssembliesDeTest.poser(
                helper, "axion:test/deux_corps", new Vec3(4.5, DESSUS_DALLE + 1.0, 4.5), new float[] {0f, 0f, 0f, 1f});
        double surLaDalle = helper.absoluteVec(new Vec3(0, DESSUS_DALLE, 0)).y;
        double surLePilier = surLaDalle + 1.0;
        helper.succeedWhen(() -> {
            List<AxionEntity.BodyPose> autres = assembly.secondaryBodies();
            helper.assertTrue(autres.size() == 1, "un second corps attendu, " + autres.size() + " rapportés");
            helper.assertTrue(
                    Math.abs(assembly.getY() - surLePilier) < 0.05,
                    "la racine reste sur le pilier : y = " + assembly.getY() + ", " + surLePilier + " attendu");
            helper.assertTrue(
                    Math.abs(autres.get(0).y() - surLaDalle) < 0.05,
                    "le second corps repose sur la dalle : y = " + autres.get(0).y() + ", " + surLaDalle + " attendu");
            AABB emprise = assembly.getBoundingBox();
            helper.assertTrue(
                    Math.abs(emprise.getXsize() - 3.0) < 0.05 && Math.abs(emprise.getYsize() - 2.0) < 0.05,
                    "l'emprise couvre les deux corps : " + emprise.getXsize() + " × " + emprise.getYsize());
        });
    }

    /**
     * Le même asset, le corps racine déclaré au node {@code b} : l'entité suit {@code b}, qui tombe
     * sur la dalle, tandis que {@code a} reste sur le pilier — le corps racine se reconnaît à son
     * node, quel que soit son rang dans l'asset.
     */
    @GameTest(template = "test/plancher", timeoutTicks = 200)
    public static void lEntiteSuitLeCorpsRacineDeclareQuelQueSoitSonNode(GameTestHelper helper) {
        ObservateursDeTest.declarer(helper, new Vec3(4.0, DESSUS_DALLE, 4.0));
        helper.setBlock(BlockPos.containing(4.0, DESSUS_DALLE, 4.0), Blocks.STONE);
        AxionEntity assembly = AssembliesDeTest.poser(
                helper, "axion:test/deux_corps_inverse", new Vec3(4.5, DESSUS_DALLE + 1.0, 4.5),
                new float[] {0f, 0f, 0f, 1f});
        double surLaDalle = helper.absoluteVec(new Vec3(0, DESSUS_DALLE, 0)).y;
        double surLePilier = surLaDalle + 1.0;
        helper.succeedWhen(() -> {
            List<AxionEntity.BodyPose> autres = assembly.secondaryBodies();
            helper.assertTrue(autres.size() == 1, "un second corps attendu, " + autres.size() + " rapportés");
            helper.assertTrue(
                    Math.abs(assembly.getY() - surLaDalle) < 0.05,
                    "l'entité suit b, tombé sur la dalle : y = " + assembly.getY() + ", " + surLaDalle + " attendu");
            helper.assertTrue(
                    Math.abs(autres.get(0).y() - surLePilier) < 0.05,
                    "a reste sur le pilier : y = " + autres.get(0).y() + ", " + surLePilier + " attendu");
        });
    }

    /**
     * Sans {@code bodies}, un seul corps : ses deux colliders sont posés par leurs nodes, l'un à
     * l'origine, l'autre à deux blocs — l'emprise fait trois blocs de large, et non un, comme
     * lorsque le natif ignorait la pose des nodes.
     */
    @GameTest(template = "test/plancher", timeoutTicks = 100)
    public static void unSeulCorpsPoseSesCollidersParLeursNodes(GameTestHelper helper) {
        ObservateursDeTest.declarer(helper, new Vec3(4.0, DESSUS_DALLE, 4.0));
        AxionEntity assembly = AssembliesDeTest.poser(
                helper, "axion:test/deux_cubes", new Vec3(4.5, DESSUS_DALLE + 6.0, 4.5), new float[] {0f, 0f, 0f, 1f});
        double depart = assembly.getY();
        helper.succeedWhen(() -> {
            helper.assertTrue(
                    assembly.hasPhysicalBounds() && assembly.getY() < depart - 0.01, "aucun état encore appliqué");
            AABB emprise = assembly.getBoundingBox();
            helper.assertTrue(
                    Math.abs(emprise.getXsize() - 3.0) < 0.05,
                    "les deux colliders, posés par leurs nodes : largeur " + emprise.getXsize() + ", 3 attendue");
            helper.assertTrue(assembly.secondaryBodies().isEmpty(), "un seul corps");
        });
    }
}
