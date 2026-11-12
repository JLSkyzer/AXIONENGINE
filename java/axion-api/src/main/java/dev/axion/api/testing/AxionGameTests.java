package dev.axion.api.testing;

import dev.axion.api.AxionApi;
import dev.axion.api.Experimental;
import dev.axion.api.assembly.Assembly;
import dev.axion.api.content.ContentQuery;
import dev.axion.api.content.DefinitionRef;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/**
 * Les aides aux GameTests d'un addon (ADR-166 D3) : un addon teste son contenu dans un vrai serveur de GameTests, par
 * l'API publique seule.
 *
 * <pre>{@code
 * @GameTestHolder("example_addon")
 * @PrefixGameTestTemplate(false)
 * public final class ExampleAddonGameTests {
 *     @GameTest(template = AxionGameTests.DALLE)
 *     public static void crateFallsAndRests(GameTestHelper helper) {
 *         AxionGameTests.assertLoaded(helper, "example_addon:crate");
 *         Assembly crate = AxionGameTests.spawn(helper, "example_addon:crate", new Vec3(4.5, 5, 4.5));
 *         AxionGameTests.succeedWhenAtRest(helper, crate);
 *     }
 * }
 * }</pre>
 *
 * <p>Thread : celui du serveur, où Minecraft appelle les GameTests. Coût : celui d'un GameTest. Échec : chaque méthode
 * fait échouer le test par {@link GameTestHelper#fail(String)}, avec un message qui dit pourquoi.
 */
@Experimental
public final class AxionGameTests {

    /**
     * Le gabarit d'une dalle de 9 × 9 blocs sur laquelle poser ce qu'on teste :
     * {@code @GameTest(template = AxionGameTests.DALLE)}, la classe de tests portant
     * {@code @PrefixGameTestTemplate(false)}. Forge cherche le gabarit dans l'espace de noms du mod qui porte le test :
     * le modèle d'addon fournit la structure, {@code data/<modId>/structures/dalle.nbt}.
     */
    public static final String DALLE = "dalle";

    /** Le dessus de la dalle, en hauteur relative au gabarit : ce qui repose dessus est à cette hauteur. */
    public static final double DESSUS_DALLE = 2.0;

    /** Les ticks pendant lesquels une assembly ne doit plus bouger pour être tenue au repos. */
    static final int TICKS_DE_REPOS = 10;

    /** Le déplacement sous lequel une assembly est tenue immobile d'un tick à l'autre, en blocs. */
    static final double IMMOBILE = 1.0e-3;

    private AxionGameTests() {
        throw new AssertionError("classe utilitaire, non instanciable");
    }

    /**
     * Fait échouer le test si la definition n'est pas chargée, avec la cause de son refus.
     *
     * @param helper le GameTest
     * @param definitionId identifiant, {@code <modId>:<chemin>}
     */
    public static void assertLoaded(GameTestHelper helper, String definitionId) {
        ContentQuery content = AxionApi.get().content();
        if (!content.isLoaded(definitionId)) {
            helper.fail(content.refusal(definitionId)
                    .map(refusal -> definitionId + " refusée par AXION : " + refusal)
                    .orElse(definitionId + " absente : aucun mod ne l'enregistre (vérifier le modId et le chemin)"));
        }
    }

    /**
     * {@return une assembly de cette definition, apparue à une position relative au gabarit du test}
     *
     * @param helper le GameTest
     * @param definitionId identifiant, {@code <modId>:<chemin>}
     * @param relative position relative au gabarit, en blocs ; la dalle de {@link #DALLE} a son dessus à
     *     {@link #DESSUS_DALLE}
     */
    public static Assembly spawn(GameTestHelper helper, String definitionId, Vec3 relative) {
        ResourceLocation id = ResourceLocation.tryParse(definitionId);
        if (id == null) {
            helper.fail(definitionId + " n'est pas un identifiant <modId>:<chemin>");
        }
        assertLoaded(helper, definitionId);
        return AxionApi.get().assemblies().spawn(
                helper.getLevel(), new DefinitionRef(id), helper.absoluteVec(relative), new Quaternionf());
    }

    /**
     * Fait réussir le test quand l'assembly ne bouge plus : sa position stable pendant dix ticks. Tant qu'elle bouge,
     * le test attend, jusqu'à son délai.
     *
     * @param helper le GameTest
     * @param assembly l'assembly observée
     */
    public static void succeedWhenAtRest(GameTestHelper helper, Assembly assembly) {
        Vec3[] precedente = {null};
        int[] immobile = {0};
        helper.succeedWhen(() -> {
            if (!assembly.isValid()) {
                helper.fail(assembly.definition().id() + " a disparu du monde avant de se poser");
            }
            Vec3 position = assembly.transform().position();
            immobile[0] = precedente[0] != null && position.distanceTo(precedente[0]) < IMMOBILE ? immobile[0] + 1 : 0;
            precedente[0] = position;
            if (immobile[0] < TICKS_DE_REPOS) {
                helper.fail(assembly.definition().id() + " bouge encore, en " + position);
            }
        });
    }

    /**
     * Fait réussir le test quand l'assembly s'est éloignée d'au moins cette distance de sa position à l'appel.
     *
     * @param helper le GameTest
     * @param assembly l'assembly observée
     * @param distance distance minimale, en blocs
     */
    public static void succeedWhenMoved(GameTestHelper helper, Assembly assembly, double distance) {
        Vec3 depart = assembly.transform().position();
        helper.succeedWhen(() -> {
            if (!assembly.isValid()) {
                helper.fail(assembly.definition().id() + " a disparu du monde");
            }
            double parcourue = assembly.transform().position().distanceTo(depart);
            if (parcourue < distance) {
                helper.fail(assembly.definition().id() + " ne s'est déplacée que de " + parcourue + " bloc(s), "
                        + distance + " attendu(s)");
            }
        });
    }
}
