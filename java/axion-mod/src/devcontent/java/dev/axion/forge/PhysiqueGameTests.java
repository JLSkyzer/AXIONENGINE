package dev.axion.forge;

import dev.axion.entity.AssemblySpawns;
import dev.axion.lifecycle.AxionRuntime;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * Acceptance physique de M3 (PARTIE 37.2), jouée dans le jeu : GameTests de Minecraft 1.20.1
 * (§29.5). Contenu de développement, jamais empaqueté (R-1790) ; dans le paquet du pont Forge pour
 * lier une assembly comme {@code /axion spawn} le fait.
 */
@GameTestHolder("axion")
@PrefixGameTestTemplate(false)
public final class PhysiqueGameTests {

    /** Pesanteur par défaut de {@code physics.gravity} (R-611), en m/s². */
    private static final double G = 9.81;

    /** Durée d'un tick, en secondes. */
    private static final double TICK = 1.0 / 20.0;

    /**
     * Le dessus de la dalle du gabarit {@code plancher}, en coordonnées du test : le bloc de
     * structure occupe la couche y = 0, et la première couche du gabarit est posée en y = 1.
     */
    private static final double DESSUS_DALLE = 2.0;

    private PhysiqueGameTests() {}

    /**
     * Le livrable de M3 : un objet tombe, puis repose sur le sol. La chute est libre à 1 % près —
     * le cube de test n'a pas de profil aérodynamique, rien ne le freine —, mesurée au 40ᵉ pas :
     * l'intégration d'Euler semi-implicite, en avance d'un sous-pas sur ½·g·t², s'en écarte alors de
     * 0,8 %. Le cube se pose ensuite sur la dalle, sans jamais la traverser.
     */
    @GameTest(template = "plancher", timeoutTicks = 200)
    public static void unObjetTombeEnChuteLibrePuisReposeSurLeSol(GameTestHelper helper) {
        ObservateursDeTest.declarer(helper, new Vec3(4.0, DESSUS_DALLE, 4.0));
        AxionEntity cube = poser(helper, "axion:test_cube", new Vec3(4.5, DESSUS_DALLE + 21.0, 4.5));
        double depart = cube.getY();
        double sol = helper.absoluteVec(new Vec3(0, DESSUS_DALLE, 0)).y;
        int[] pas = {0};
        boolean[] chuteMesuree = {false};
        double[] precedente = {depart};
        int[] immobile = {0};

        // Le cycle de simulation suit les tests dans le tick : chaque relevé lit l'état du pas
        // précédent, un pas par tick.
        helper.onEachTick(() -> {
            double y = cube.getY();
            if (pas[0] > 0 || y < depart - 1e-6) {
                pas[0]++;
            }
            if (pas[0] == 40) {
                double t = pas[0] * TICK;
                double attendue = 0.5 * G * t * t;
                double mesuree = depart - y;
                helper.assertTrue(
                        Math.abs(mesuree - attendue) <= 0.01 * attendue,
                        "chute de " + mesuree + " m au 40e pas, " + attendue + " attendus à 1 % près");
                chuteMesuree[0] = true;
            }
            helper.assertTrue(y > sol - 0.5, "le cube a traversé le sol : y = " + y + ", dalle à " + sol);
            immobile[0] = Math.abs(y - precedente[0]) < 1e-4 ? immobile[0] + 1 : 0;
            precedente[0] = y;
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(chuteMesuree[0], "chute pas encore mesurée");
            helper.assertTrue(immobile[0] >= 20, "le cube bouge encore");
            helper.assertTrue(
                    Math.abs(cube.getY() - sol) < 0.05,
                    "le cube repose à y = " + cube.getY() + ", la dalle est à " + sol);
        });
    }

    /**
     * Crée une assembly comme {@code /axion spawn} : definition vérifiée, entité liée, puis ajoutée
     * au monde ; son corps naît au cycle de simulation suivant.
     *
     * @param helper le test
     * @param definitionId definition de l'assembly
     * @param relative position dans le gabarit, en blocs
     * @return l'entité ajoutée
     */
    private static AxionEntity poser(GameTestHelper helper, String definitionId, Vec3 relative) {
        AxionRuntime runtime = RuntimeAccess.get();
        if (runtime == null || runtime.outcome() == null) {
            throw new GameTestAssertException("AXION n'est pas chargé : bibliothèque native absente ?");
        }
        AssemblySpawns.Decision decision = AssemblySpawns.check(
                runtime.definitions(),
                definitionId,
                1,
                runtime.outcome().config().getInt("limits.max_spawn_per_command"));
        if (!decision.accepted()) {
            throw new GameTestAssertException("definition " + definitionId + " refusée : " + decision.refusal());
        }
        ServerLevel level = helper.getLevel();
        AxionEntity assembly = AxionEntities.ASSEMBLY.get().create(level);
        if (assembly == null) {
            throw new GameTestAssertException("l'entité d'assembly n'a pas pu être construite");
        }
        Vec3 position = helper.absoluteVec(relative);
        assembly.moveTo(position.x, position.y, position.z, 0f, 0f);
        assembly.bind(decision.definition());
        helper.assertTrue(level.addFreshEntity(assembly), "l'assembly n'a pas pu entrer dans le monde");
        return assembly;
    }
}
