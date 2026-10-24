package dev.axion.forge;

import dev.axion.forge.net.JoueurSimule;
import dev.axion.net.AssemblyState;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * ADR-151 D10 : les joints de bout en bout, sur le contenu que génère {@code tools/assets/joints_essai.py} —
 * ce qui traverse la frontière et le réseau ; leurs règles fines sont prouvées au banc de physique
 * ({@code ax-physics}).
 */
@GameTestHolder("axion")
@PrefixGameTestTemplate(false)
public final class JointsGameTests {

    /** Le dessus de la dalle du gabarit {@code plancher}, en coordonnées du test. */
    private static final double DESSUS_DALLE = 2.0;

    private static final float[] DROIT = {0f, 0f, 0f, 1f};

    private JointsGameTests() {}

    /** L'angle d'une rotation relative autour de y, en radians. */
    private static double autourDeY(float[] q) {
        return 2 * Math.atan2(q[1], q[3]);
    }

    /**
     * T-330 : lâché à l'horizontale, le pendule descend sous son pivot, et sa masse reste à un bloc et demi de
     * lui — les axes bloqués de la charnière tiennent, la frontière transcrit le joint.
     */
    @GameTest(template = "test/plancher", timeoutTicks = 200)
    public static void t330_lePenduleDescendEtGardeSonBras(GameTestHelper helper) {
        ObservateursDeTest.declarer(helper, new Vec3(4.0, DESSUS_DALLE, 4.0));
        AxionEntity pendule = AssembliesDeTest.poser(
                helper, "axion:test/joints/pendule", new Vec3(2.5, DESSUS_DALLE, 4.0), DROIT);
        helper.succeedWhen(() -> {
            List<AxionEntity.BodyPose> corps = pendule.secondaryBodies();
            helper.assertTrue(corps.size() == 1, "la masse est un corps");
            Vec3 pivot = pendule.position().add(0.0, 3.0, 0.0);
            Vec3 masse = new Vec3(corps.get(0).x(), corps.get(0).y(), corps.get(0).z());
            helper.assertTrue(masse.y < pivot.y - 1.2, "la masse descend sous le pivot : " + (masse.y - pivot.y));
            double bras = masse.distanceTo(pivot);
            helper.assertTrue(Math.abs(bras - 1.5) < 0.05, "le bras garde un bloc et demi : " + bras);
        });
    }

    /** T-331 : le servo de la porte l'ouvre à 1,2 rad, dans sa plage. */
    @GameTest(template = "test/plancher", timeoutTicks = 200)
    public static void t331_LeServoOuvreLaPorte(GameTestHelper helper) {
        ObservateursDeTest.declarer(helper, new Vec3(4.0, DESSUS_DALLE, 4.0));
        AxionEntity porte = AssembliesDeTest.poser(
                helper, "axion:test/joints/porte", new Vec3(3.5, DESSUS_DALLE + 0.05, 4.0), DROIT);
        helper.succeedWhen(() -> {
            AssemblyState etat = porte.netState();
            helper.assertTrue(etat != null && etat.bodies().size() == 1, "le battant est un corps");
            double angle = autourDeY(etat.bodies().get(0).quaternion());
            helper.assertTrue(Math.abs(angle - 1.2) < 0.05, "ouverte à 1,2 rad : " + angle);
        });
    }

    /**
     * T-333 : pendue sous son ancre, la chaîne finit par s'endormir entière — ses six maillons, à leur pose de
     * repos.
     */
    @GameTest(template = "test/plancher", timeoutTicks = 600)
    public static void t333_LaChaineSEndortEntiere(GameTestHelper helper) {
        ObservateursDeTest.declarer(helper, new Vec3(4.0, DESSUS_DALLE, 4.0));
        AxionEntity chaine = AssembliesDeTest.poser(
                helper, "axion:test/joints/chaine", new Vec3(4.0, DESSUS_DALLE, 4.0), DROIT);
        helper.succeedWhen(() -> {
            AssemblyState etat = chaine.netState();
            helper.assertTrue(etat != null && etat.bodies().size() == 6, "six maillons");
            helper.assertTrue(etat.sleeping(), "endormie");
            double bas = etat.bodies().stream().mapToDouble(corps -> corps.offsetBlocks()[1]).min().orElseThrow();
            helper.assertTrue(Math.abs(bas - (5.6 - 0.45 * 5)) < 0.1, "le dernier maillon pend à sa place : " + bas);
        });
    }

    /**
     * T-334 : la porte ouverte, un désalignement d'un demi-bloc la grippe tout à fait, puis un désalignement
     * nul la rend ; chaque état arrive au client par les snapshots.
     */
    @GameTest(template = "test/plancher", timeoutTicks = 300)
    public static void t334_LeGrippageArriveAuClient(GameTestHelper helper) {
        ObservateursDeTest.declarer(helper, new Vec3(4.0, DESSUS_DALLE, 4.0));
        AxionEntity porte = AssembliesDeTest.poser(
                helper, "axion:test/joints/porte", new Vec3(3.5, DESSUS_DALLE + 0.05, 4.0), DROIT);
        JoueurSimule joueur = JoueurSimule.rejoindre(
                helper.getLevel(), "grippage", helper.absoluteVec(new Vec3(4.0, DESSUS_DALLE, 6.0)));
        int[] etape = {0};
        helper.onEachTick(() -> {
            joueur.tick();
            AssemblyState etat = porte.netState();
            if (etat == null || etat.bodies().isEmpty()) {
                return;
            }
            List<JoueurSimule.Recu> recus =
                    joueur.recus().stream().filter(recu -> recu.handle() == porte.getId()).toList();
            int auClient = recus.isEmpty() || recus.get(recus.size() - 1).state().joints().count() != 1
                    ? -1
                    : recus.get(recus.size() - 1).state().joints().flags(0);
            int auServeur = porte.jointBits().count() == 1 ? porte.jointBits().flags(0) : -1;
            if (etape[0] == 0 && Math.abs(autourDeY(etat.bodies().get(0).quaternion()) - 1.2) < 0.05) {
                GrippagesDeTest.gripper(helper, porte, 0, 0.5f);
                etape[0] = 1;
            } else if (etape[0] == 1 && auServeur == AssemblyState.JointBits.JAMMED
                    && auClient == AssemblyState.JointBits.JAMMED) {
                GrippagesDeTest.gripper(helper, porte, 0, 0.0f);
                etape[0] = 2;
            } else if (etape[0] == 2 && auServeur == 0 && auClient == 0) {
                etape[0] = 3;
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(etape[0] == 3, "grippée puis rendue, au serveur et au client : étape " + etape[0]);
            joueur.quitter();
        });
    }

    /**
     * La grille de neuf plaques soudées tombe et touche la dalle d'un seul tenant : chaque plaque garde sa place
     * par rapport à la première.
     */
    @GameTest(template = "test/plancher", timeoutTicks = 300)
    public static void laGrilleTombeDUnSeulTenant(GameTestHelper helper) {
        ObservateursDeTest.declarer(helper, new Vec3(4.0, DESSUS_DALLE, 4.0));
        AxionEntity grille = AssembliesDeTest.poser(
                helper, "axion:test/joints/grille", new Vec3(2.5, DESSUS_DALLE + 3.0, 2.5), DROIT);
        double depart = grille.getY();
        helper.succeedWhen(() -> {
            AssemblyState etat = grille.netState();
            helper.assertTrue(etat != null && etat.bodies().size() == 8, "huit plaques autour de la première");
            helper.assertTrue(grille.getY() < depart - 2.5, "tombée sur la dalle : " + (grille.getY() - depart));
            helper.assertTrue(etat.sleeping(), "posée");
            Set<String> places = new HashSet<>();
            for (AssemblyState.Body plaque : etat.bodies()) {
                double[] ecart = plaque.offsetBlocks();
                long i = Math.round(ecart[0] / 0.5);
                long j = Math.round(ecart[2] / 0.5);
                helper.assertTrue(
                        Math.abs(ecart[0] - 0.5 * i) < 0.02 && Math.abs(ecart[1]) < 0.02 && Math.abs(ecart[2] - 0.5 * j) < 0.02,
                        "une plaque hors de la grille : " + Arrays.toString(ecart));
                places.add(i + "," + j);
            }
            helper.assertTrue(places.size() == 8, "chaque plaque à sa place : " + places);
        });
    }
}
