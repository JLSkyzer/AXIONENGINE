package dev.axion.forge;

import dev.axion.forge.net.JoueurSimule;
import dev.axion.net.AssemblyState;
import java.util.Arrays;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.joml.Vector3f;

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
     * ADR-151 D8, R-630 : la charnière de {@code test/charniere_fragile} cède à un newton, sous le poids
     * de {@code b}, dès le premier pas. Le serveur retient le joint rompu ; son état réseau porte {@code b}
     * en pose relative au corps racine, et le bit rompu ; un client simulé les reçoit par les vrais
     * snapshots.
     */
    @GameTest(template = "test/plancher", timeoutTicks = 200)
    public static void uneCharniereRompueArriveAuClientAvecLeSecondCorps(GameTestHelper helper) {
        ObservateursDeTest.declarer(helper, new Vec3(4.0, DESSUS_DALLE, 4.0));
        helper.setBlock(BlockPos.containing(4.0, DESSUS_DALLE, 4.0), Blocks.STONE);
        AxionEntity assembly = AssembliesDeTest.poser(
                helper, "axion:test/charniere_fragile", new Vec3(4.5, DESSUS_DALLE + 1.0, 4.5),
                new float[] {0f, 0f, 0f, 1f});
        JoueurSimule joueur = JoueurSimule.rejoindre(
                helper.getLevel(), "charniere", helper.absoluteVec(new Vec3(4.0, DESSUS_DALLE, 6.0)));
        helper.onEachTick(joueur::tick);
        helper.succeedWhen(() -> {
            AssemblyState.JointBits serveur = assembly.jointBits();
            helper.assertTrue(serveur.count() == 1 && serveur.flags(0) == AssemblyState.JointBits.BROKEN,
                    "le joint rompu, retenu au serveur : " + serveur);
            AssemblyState etat = assembly.netState();
            helper.assertTrue(etat != null && etat.bodies().size() == 1, "l'état réseau porte le second corps");
            // Où b se pose après sa chute — à plat ou sur le flanc — tient à un équilibre instable que la place
            // du test dans le lot fait basculer (sa position absolue change les arrondis). L'oracle est donc la
            // pose que le serveur tient : la pose relative du réseau est celle de b dans le repère de la
            // racine, au quantum près, et b est tombé sous elle.
            AxionEntity.BodyPose b = assembly.secondaryBodies().get(0);
            Vector3f attendu = assembly.bodyRotation().conjugate().transform(new Vector3f(
                    (float) (b.x() - assembly.getX()), (float) (b.y() - assembly.getY()), (float) (b.z() - assembly.getZ())));
            double[] ecart = etat.bodies().get(0).offsetBlocks();
            double quantum = 1.0 / 1024;
            helper.assertTrue(Math.abs(ecart[0] - attendu.x) <= quantum && Math.abs(ecart[1] - attendu.y) <= quantum
                            && Math.abs(ecart[2] - attendu.z) <= quantum,
                    "pose relative " + Arrays.toString(ecart) + " pour " + attendu + " dans le repère de la racine");
            helper.assertTrue(attendu.y < -0.4 && Math.abs(attendu.x) > 1.5,
                    "b, détaché, tombé sur la dalle à côté de la racine : " + attendu);
            helper.assertTrue(etat.joints().equals(serveur), "l'état réseau porte le bit rompu : " + etat.joints());
            List<JoueurSimule.Recu> recus =
                    joueur.recus().stream().filter(recu -> recu.handle() == assembly.getId()).toList();
            helper.assertTrue(!recus.isEmpty(), "le client simulé n'a rien reçu");
            AssemblyState dernier = recus.get(recus.size() - 1).state();
            helper.assertTrue(
                    dernier.joints().equals(serveur) && dernier.bodies().equals(etat.bodies()),
                    "le client tient le bit rompu et la pose relative du serveur : " + dernier.joints() + ", "
                            + dernier.bodies());
            joueur.quitter();
        });
    }

    /**
     * ADR-151 D8 : un corps racine statique n'a pas d'état dans {@code SIM_OUT}. L'entité garde sa pose, et
     * le second corps part relatif à elle : pendu à la charnière d'axe z de {@code test/charniere_fixe},
     * limitée à un demi-radian, {@code b} pivote autour du gond, à un bloc de la racine, jusqu'à la butée.
     */
    @GameTest(template = "test/plancher", timeoutTicks = 200)
    public static void sousUneRacineStatiqueLeSecondCorpsPartRelatifALEntite(GameTestHelper helper) {
        ObservateursDeTest.declarer(helper, new Vec3(4.0, DESSUS_DALLE, 4.0));
        AxionEntity assembly = AssembliesDeTest.poser(
                helper, "axion:test/charniere_fixe", new Vec3(4.5, DESSUS_DALLE + 3.0, 4.5),
                new float[] {0f, 0f, 0f, 1f});
        double depart = assembly.getY();
        helper.succeedWhen(() -> {
            AssemblyState etat = assembly.netState();
            helper.assertTrue(etat != null && etat.bodies().size() == 1, "l'état réseau porte le second corps");
            helper.assertTrue(assembly.getY() == depart, "la racine statique ne bouge pas : y = " + assembly.getY());
            float[] q = etat.bodies().get(0).quaternion();
            double angle = 2 * Math.atan2(Math.abs(q[2]), Math.abs(q[3]));
            helper.assertTrue(
                    Math.abs(q[0]) < 0.02 && Math.abs(q[1]) < 0.02 && Math.abs(angle - 0.5) < 0.05,
                    "b à la butée, un demi-radian autour de z : " + Arrays.toString(q));
            double[] ecart = etat.bodies().get(0).offsetBlocks();
            helper.assertTrue(
                    Math.abs(ecart[0] - (1 + Math.cos(0.5))) < 0.05 && Math.abs(ecart[1] + Math.sin(0.5)) < 0.05
                            && Math.abs(ecart[2]) < 0.05,
                    "b tourné autour du gond, en (1 + cos ½, −sin ½, 0) : " + Arrays.toString(ecart));
            // La racine statique est rapportée : l'emprise de l'entité est l'union de ses corps, b compris,
            // qui pend sous elle.
            AABB emprise = assembly.getBoundingBox();
            helper.assertTrue(emprise.minY < assembly.getY() - 0.4,
                    "l'emprise descend avec b : minY " + emprise.minY + ", racine " + assembly.getY());
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
