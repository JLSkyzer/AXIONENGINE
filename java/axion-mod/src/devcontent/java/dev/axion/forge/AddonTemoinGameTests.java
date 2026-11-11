package dev.axion.forge;

import dev.axion.content.RegisteredContent;
import dev.axion.definition.Definition;
import dev.axion.lifecycle.AxionRuntime;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * L'addon témoin en jeu (CAP-49, ADR-162 tranche 5) : un mod séparé, compilé contre {@code axion-api} seul
 * ({@code java/addon-temoin}), déclare une caisse en JSON et une autre en Java ; AXION les reçoit comme le contenu de
 * n'importe quel addon. Contenu de développement, jamais empaqueté (R-1790).
 */
@GameTestHolder("axion")
@PrefixGameTestTemplate(false)
public final class AddonTemoinGameTests {

    /** Le dessus de la dalle du gabarit {@code plancher}, en coordonnées du test. */
    private static final double DESSUS_DALLE = 2.0;

    private static final String CAISSE_JSON = "example_addon:crate";
    private static final String CAISSE_JAVA = "example_addon:heavy_crate";

    private AddonTemoinGameTests() {}

    /**
     * Les deux definitions du témoin sont chargées, avec la masse que chacune déclare ; le témoin est un propriétaire
     * de contenu, donc un addon que le serveur exige de ses clients (ADR-165 D1) ; et sa caisse, posée au-dessus de la
     * dalle, prend un corps, tombe et repose.
     */
    @GameTest(template = "test/plancher", timeoutTicks = 200)
    public static void lesCaissesDuTemoinSontChargeesEtTombent(GameTestHelper helper) {
        AxionRuntime runtime = RuntimeAccess.get();
        helper.assertTrue(runtime != null && runtime.outcome() != null, "AXION n'est pas chargé");
        Definition json = runtime.definitions().get(CAISSE_JSON).orElse(null);
        Definition java = runtime.definitions().get(CAISSE_JAVA).orElse(null);
        helper.assertTrue(json != null, CAISSE_JSON + " absente : le témoin n'a pas été enregistré");
        helper.assertTrue(java != null, CAISSE_JAVA + " absente : la definition du builder n'est pas entrée");
        helper.assertTrue(masse(json) == 40.0, CAISSE_JSON + " : masse " + masse(json) + " au lieu de 40");
        helper.assertTrue(masse(java) == 400.0, CAISSE_JAVA + " : masse " + masse(java) + " au lieu de 400");
        helper.assertTrue(RegisteredContent.owners().containsKey("example_addon"),
                "le témoin manque aux addons exigés : " + RegisteredContent.owners());

        AxionEntity caisse = AssembliesDeTest.poser(
                helper, CAISSE_JSON, new Vec3(4.5, DESSUS_DALLE + 3.0, 4.5), new float[] {0f, 0f, 0f, 1f});
        double sol = helper.absoluteVec(new Vec3(0, DESSUS_DALLE, 0)).y;
        helper.succeedWhen(() -> helper.assertTrue(Math.abs(caisse.getY() - sol) < 0.05,
                "la caisse est à y = " + caisse.getY() + ", la dalle à " + sol));
    }

    private static double masse(Definition definition) {
        return definition.root().getAsJsonObject("physics").get("mass").getAsDouble();
    }
}
