package dev.axion.forge;

import dev.axion.api.ApiVersion;
import dev.axion.api.AxionApi;
import dev.axion.api.assembly.Assembly;
import dev.axion.api.content.DefinitionRef;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.joml.Quaternionf;

/**
 * L'API publique jouée en jeu (ADR-162 D1, tranche 1d) : le fournisseur que {@code ServiceLoader} trouve, et le
 * service des assemblies tel qu'un mod tiers l'appelle — créer, retrouver par l'entité et dans un rayon, lire sa
 * definition et sa transformation, écrire ses données, la retirer ; une definition inconnue refusée.
 */
@GameTestHolder("axion")
@PrefixGameTestTemplate(false)
public final class ApiPubliqueGameTests {

    /** Hauteur du dessus de la dalle du gabarit {@code test/plancher}, en blocs. */
    private static final double DESSUS_DALLE = 2.0;

    private ApiPubliqueGameTests() {}

    @GameTest(template = "test/plancher", timeoutTicks = 60)
    public static void lApiCreeRetrouveEtRetireUneAssembly(GameTestHelper helper) {
        AxionApi api = AxionApi.getIfReady().orElse(null);
        helper.assertTrue(api != null, "AxionApi.getIfReady() vide : aucun fournisseur trouvé par ServiceLoader");
        helper.assertTrue(api == AxionApi.get(), "AxionApi.get() ne rend pas le fournisseur mémorisé");
        helper.assertTrue(ApiVersion.CURRENT.equals(api.version()), "version " + api.version());

        Vec3 position = helper.absoluteVec(new Vec3(4.5, DESSUS_DALLE + 2.0, 4.5));
        Quaternionf quart = new Quaternionf().rotateY((float) (Math.PI / 2));
        Assembly assembly = api.assemblies().spawn(helper.getLevel(),
                new DefinitionRef(new ResourceLocation("axion", "test_cube")), position, quart);

        helper.assertTrue(assembly.isValid(), "l'assembly créée n'est pas valide");
        helper.assertTrue(assembly.entity() instanceof AxionEntity, "l'entité n'est pas une AxionEntity");
        helper.assertTrue(assembly.definition().id().toString().equals("axion:test_cube"),
                "definition " + assembly.definition());
        helper.assertTrue(assembly.id().equals(assembly.entity().getUUID()), "l'identifiant n'est pas celui de l'entité");
        helper.assertTrue(assembly.transform().position().distanceTo(position) < 1e-6,
                "position " + assembly.transform().position() + ", attendue " + position);
        helper.assertTrue(assembly.transform().rotation().equals(quart, 1e-5f),
                "orientation " + assembly.transform().rotation() + ", attendue " + quart);

        helper.assertTrue(api.assemblies().byEntity(assembly.entity())
                        .map(found -> found.id().equals(assembly.id())).orElse(false),
                "byEntity ne retrouve pas l'assembly");
        helper.assertTrue(api.assemblies().byEntity(helper.getLevel().getRandomPlayer() == null
                        ? new net.minecraft.world.entity.item.ItemEntity(helper.getLevel(), 0, 0, 0,
                                net.minecraft.world.item.ItemStack.EMPTY)
                        : helper.getLevel().getRandomPlayer()).isEmpty(),
                "byEntity rend une assembly pour une entité qui n'en est pas une");
        Collection<Assembly> proches = api.assemblies().inRange(helper.getLevel(), position, 1.0);
        helper.assertTrue(proches.stream().anyMatch(a -> a.id().equals(assembly.id())),
                "inRange ne trouve pas l'assembly à sa position");
        helper.assertTrue(api.assemblies().inRange(helper.getLevel(), position.add(0, 50, 0), 1.0).stream()
                        .noneMatch(a -> a.id().equals(assembly.id())),
                "inRange trouve l'assembly à 50 blocs de sa position");

        byte[] donnees = "atelier".getBytes(StandardCharsets.UTF_8);
        helper.assertTrue(assembly.customData().put("atelier_test", donnees), "customData refuse l'écriture");
        helper.assertTrue(assembly.customData().get("atelier_test").map(d -> Arrays.equals(d, donnees)).orElse(false),
                "customData ne rend pas ce qui a été écrit");

        boolean refusee = false;
        try {
            api.assemblies().spawn(helper.getLevel(),
                    new DefinitionRef(new ResourceLocation("axion", "test/inexistante_api")), position, quart);
        } catch (IllegalArgumentException attendu) {
            refusee = true;
        }
        helper.assertTrue(refusee, "une definition inconnue n'est pas refusée par IllegalArgumentException");

        assembly.remove();
        helper.assertTrue(!assembly.isValid(), "l'assembly retirée est encore valide");
        assembly.remove();
        helper.succeed();
    }
}
