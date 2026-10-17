package dev.axion.forge;

import dev.axion.asset.AssetEntry;
import dev.axion.asset.AssetRegistry;
import dev.axion.lifecycle.AxionRuntime;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * Les avertissements d'une compilation atteignent le journal du jeu (ADR-148, amendement A1
 * d'ADR-137).
 *
 * <p>{@code test/avertissement/triangle.gltf} cite dans {@code extensionsUsed} une extension que le
 * moteur ne prend pas en charge : R-530 l'ignore avec un avertissement. Le serveur de GameTests part
 * d'un cache d'assets vide : l'asset est compilé au démarrage, une fois, et sa ligne entre une fois
 * dans le journal des assets, que la couche Forge écrit dans {@code latest.log} — la CI l'y compte.
 * {@code axion-cli validate} affiche le même avertissement (R-830).
 */
@GameTestHolder("axion")
@PrefixGameTestTemplate(false)
public final class JournalDesAssetsGameTests {

    private static final String TRIANGLE = "axion:axion/models/test/avertissement/triangle.gltf";

    private static final String LIGNE =
            TRIANGLE + " — avertissement : extension ignorée : KHR_materials_variants";

    private JournalDesAssetsGameTests() {}

    @GameTest(template = "test/plancher")
    public static void unAvertissementDeCompilationEntreUneFoisAuJournal(GameTestHelper helper) {
        AxionRuntime runtime = RuntimeAccess.get();
        helper.assertTrue(runtime != null && runtime.assets() != null, "registre d'assets absent");
        AssetRegistry assets = runtime.assets();
        AssetEntry triangle = assets.entry(TRIANGLE);
        helper.assertTrue(triangle != null, TRIANGLE + " non découvert");
        helper.assertTrue(
                triangle.state().isUsable(),
                TRIANGLE + " " + triangle.state() + " — un avertissement ne refuse pas l'asset");
        long fois = assets.diagnostics().stream().filter(LIGNE::equals).count();
        helper.assertTrue(
                fois == 1,
                "avertissement consigné " + fois + " fois (état " + triangle.state()
                        + ", repris du cache s'il est CACHED) : " + assets.diagnostics());
        helper.succeed();
    }
}
