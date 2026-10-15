package dev.axion.forge;

import dev.axion.asset.AssetEntry;
import dev.axion.asset.AssetRegistry;
import dev.axion.lifecycle.AxionRuntime;
import java.util.List;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * Les fichiers voisins d'une source, sous le vrai gestionnaire de ressources (ADR-139, R-830).
 *
 * <p>{@code test/voisins/triangle.gltf} range ses positions et ses indices dans deux buffers
 * externes, le second désigné par {@code ./indices.bin}. Sans eux, la compilation échoue avec le
 * code que la CLI donne à un buffer introuvable ; elle n'aboutit que si l'énumération de Forge les
 * a gardés, si le registre les a passés au compilateur et si le dialogue « demande puis renvoi » a
 * traversé la JNI. {@code axion-cli validate} compile la même source depuis le disque.
 */
@GameTestHolder("axion")
@PrefixGameTestTemplate(false)
public final class VoisinsGameTests {

    private static final String TRIANGLE = "axion:axion/models/test/voisins/triangle.gltf";

    private VoisinsGameTests() {}

    @GameTest(template = "test/plancher")
    public static void unGltfABuffersExternesSeCompileAvecSesVoisins(GameTestHelper helper) {
        AxionRuntime runtime = RuntimeAccess.get();
        helper.assertTrue(runtime != null && runtime.assets() != null, "registre d'assets absent");
        AssetRegistry assets = runtime.assets();
        AssetEntry triangle = assets.entry(TRIANGLE);
        helper.assertTrue(triangle != null, TRIANGLE + " non découvert");
        helper.assertTrue(
                triangle.state().isUsable(),
                TRIANGLE + " " + triangle.state() + " — " + assets.diagnostics());
        // Le nom d'un voisin est relatif au répertoire de la source, sous sa forme normale :
        // celle que le natif demande, `./indices.bin` compris.
        List<String> voisins = List.copyOf(triangle.siblings().keySet());
        helper.assertTrue(
                voisins.equals(List.of("indices.bin", "positions.bin")), "voisins " + voisins);
        helper.assertTrue(
                triangle.siblings().values().stream().allMatch(octets -> octets != null),
                "un voisin n'a pas été lu");
        helper.succeed();
    }
}
