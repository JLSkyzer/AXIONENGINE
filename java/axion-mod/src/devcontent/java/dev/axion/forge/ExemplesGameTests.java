package dev.axion.forge;

import dev.axion.api.AxionApi;
import dev.axion.api.assembly.Assembly;
import dev.axion.api.assembly.Parameters;
import dev.axion.api.testing.AxionGameTests;
import java.util.List;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * R-1790 : le contenu de démonstration d'AXION se charge entier, et ce qu'il démontre marche. Le serveur des GameTests
 * l'active ({@code axion.content.examples}) : sans eux, un exemple refusé à la compilation passait inaperçu — le buggy
 * l'a été, son capot devenu un corps sans collider. Contenu de développement, jamais empaqueté.
 */
@GameTestHolder("axion")
@PrefixGameTestTemplate(false)
public final class ExemplesGameTests {

    /** Le dessus de la dalle des gabarits {@code plancher}, en coordonnées du test. */
    private static final double DESSUS_DALLE = 2.0;

    private static final List<String> EXEMPLES = List.of("axion:example/barrier", "axion:example/buggy",
            "axion:example/crate", "axion:example/flag", "axion:example/winch_rope");

    private ExemplesGameTests() {}

    /** Chaque definition d'exemple est chargée et compilée ; un refus se nomme, avec sa cause. */
    @GameTest(template = "test/plancher")
    public static void lesExemplesSeChargent(GameTestHelper helper) {
        for (String id : EXEMPLES) {
            AxionGameTests.assertLoaded(helper, id);
        }
        helper.succeed();
    }

    /**
     * ADR-167 D4 : le mode sport du buggy, une variable qui règle trois paramètres — couple +30 %, limiteur +500 tr/min,
     * adhérence +20 % — et les rend à leur base quand on le coupe.
     */
    @GameTest(template = "test/plancher", timeoutTicks = 200)
    public static void leModeSportDuBuggy(GameTestHelper helper) {
        ObservateursDeTest.declarer(helper, new Vec3(4.0, DESSUS_DALLE, 4.0));
        AxionEntity entite = AssembliesDeTest.poser(helper, "axion:example/buggy", new Vec3(4.5, DESSUS_DALLE, 4.5),
                new float[] {0f, 0f, 0f, 1f});
        Assembly buggy = AxionApi.get().assemblies().byEntity(entite).orElseThrow();
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(buggy.parameters().has(Parameters.ENGINE_TORQUE),
                        "le buggy est compilé"))
                .thenExecute(() -> {
                    Parameters parametres = buggy.parameters();
                    double couple = parametres.base(Parameters.ENGINE_TORQUE);
                    helper.assertTrue(parametres.value(Parameters.ENGINE_TORQUE) == couple, "mode sport coupé");
                    buggy.variables().set("sport", 1);
                    helper.assertTrue(Math.abs(parametres.value(Parameters.ENGINE_TORQUE) - couple * 1.3) < 1e-9,
                            "couple : " + parametres.value(Parameters.ENGINE_TORQUE));
                    helper.assertTrue(parametres.value(Parameters.ENGINE_MAX_RPM)
                            == parametres.base(Parameters.ENGINE_MAX_RPM) + 500, "limiteur");
                    helper.assertTrue(parametres.value(Parameters.TIRES_GRIP) == 1.2,
                            "adhérence : " + parametres.value(Parameters.TIRES_GRIP));
                    buggy.variables().set("sport", 0);
                    helper.assertTrue(parametres.value(Parameters.ENGINE_TORQUE) == couple, "coupé : la base");
                })
                .thenSucceed();
    }
}
