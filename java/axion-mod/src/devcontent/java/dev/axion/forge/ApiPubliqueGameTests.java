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

    /**
     * CAP-11 (23.2) : un mod pousse une assembly et lit sa vitesse. Au centre de masse, elle part sans tourner ; à
     * côté, elle tourne autour de la verticale, du côté et de la mesure que donne le bras de levier — même tournée d'un
     * quart de tour, ce qui prouve que le point du monde est bien ramené au corps. Les deux cubes sont en l'air, sans
     * frottement.
     */
    @GameTest(template = "test/plancher_16", timeoutTicks = 100)
    public static void cap11_UnModPousseUneAssemblyEtLitSaVitesse(GameTestHelper helper) {
        ObservateursDeTest.declarer(helper, new Vec3(8.0, DESSUS_DALLE, 8.0));
        AxionApi api = AxionApi.get();
        DefinitionRef cube = new DefinitionRef(new ResourceLocation("axion", "test_cube"));
        Assembly droit = api.assemblies().spawn(helper.getLevel(), cube,
                helper.absoluteVec(new Vec3(4.0, DESSUS_DALLE + 5.0, 8.0)), new Quaternionf());
        Assembly decale = api.assemblies().spawn(helper.getLevel(), cube,
                helper.absoluteVec(new Vec3(11.0, DESSUS_DALLE + 5.0, 8.0)),
                new Quaternionf().rotateY((float) (Math.PI / 2)));
        // Le côté du cube, lu à l'orientation identité.
        double[] cote = {0.0};
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(((AxionEntity) droit.entity()).hasPhysicalBounds()
                        && ((AxionEntity) decale.entity()).hasPhysicalBounds(), "les cubes sont simulés"))
                .thenExecute(() -> {
                    cote[0] = droit.entity().getBoundingBox().getXsize();
                    droit.applyImpulse(new Vec3(0, 0, 3000), null);
                    Vec3 point = decale.transform().position().add(0.5 * cote[0], 0, 0);
                    decale.applyImpulse(new Vec3(0, 0, 3000), point);
                })
                .thenIdle(3)
                .thenExecute(() -> {
                    Vec3 v = droit.linearVelocity();
                    helper.assertTrue(v.z > 0.05 && Math.abs(v.x) < 0.02, "le cube poussé au centre part droit : " + v);
                    helper.assertTrue(droit.angularVelocity().length() < 0.05,
                            "sans tourner : " + droit.angularVelocity());
                    Vec3 u = decale.linearVelocity();
                    Vec3 w = decale.angularVelocity();
                    helper.assertTrue(Math.abs(u.z - v.z) < 0.1 * v.z, "la même impulsion, la même vitesse : " + u);
                    // Cube plein : I = m c² / 6 ; bras c / 2 vers +X, impulsion vers +Z : ω = -3 v / c autour de Y.
                    double attendu = -3.0 * u.z / cote[0];
                    helper.assertTrue(Math.abs(w.y - attendu) < 0.2 * Math.abs(attendu),
                            "il tourne de " + w.y + " rad/s, attendu " + attendu + " (côté " + cote[0] + ")");
                    droit.remove();
                    decale.remove();
                })
                .thenSucceed();
    }

    /**
     * ADR-173 D1 (CAP-43) : au serveur, un mod lit les sources procédurales d'une assembly par leur nom
     * ({@code Assembly.source}), et un contrôleur de même ({@code VehicleView.source}) — le monde au point de
     * l'assembly, son véhicule, ses variables ; un nom inconnu lève.
     */
    @GameTest(template = "test/plancher", timeoutTicks = 200)
    public static void adr173_LesSourcesSeLisentAuServeur(GameTestHelper helper) {
        Assembly assembly = AxionApi.get().assemblies().spawn(helper.getLevel(),
                new DefinitionRef(new ResourceLocation("axion", "test/vehicule_controle")),
                helper.absoluteVec(new Vec3(4.5, DESSUS_DALLE + 0.6, 4.5)), new Quaternionf());
        AxionEntity entity = (AxionEntity) assembly.entity();
        helper.succeedWhen(() -> {
            dev.axion.entity.VehicleSnapshot vue = entity.vehicleSnapshot();
            helper.assertTrue(vue != null && entity.netState() != null, "le véhicule n'est pas encore simulé");
            net.minecraft.server.level.ServerLevel monde = helper.getLevel();
            float heure = dev.axion.render.ProceduralTracks.worldTime(monde.getDayTime());
            helper.assertTrue(Math.abs(assembly.source("world.time") - heure) < 1e-6,
                    "world.time " + assembly.source("world.time") + ", attendu " + heure);
            helper.assertTrue(Math.abs(assembly.source("world.rain") - monde.getRainLevel(1f)) < 1e-6,
                    "world.rain " + assembly.source("world.rain"));
            int ciel = monde.getBrightness(net.minecraft.world.level.LightLayer.SKY, entity.blockPosition());
            helper.assertTrue(assembly.source("world.sky_light") == ciel,
                    "world.sky_light " + assembly.source("world.sky_light") + ", attendu " + ciel);
            helper.assertTrue(assembly.source("var.compteur") == vue.variable("compteur"),
                    "var.compteur " + assembly.source("var.compteur") + ", attendu " + vue.variable("compteur"));
            helper.assertTrue(vue.source("world.time") == assembly.source("world.time"),
                    "le contrôleur et le mod lisent la même heure");
            helper.assertTrue(Math.abs(vue.source("engine.rpm") - vue.rpm()) < 5.0,
                    "engine.rpm " + vue.source("engine.rpm") + ", régime de la vue " + vue.rpm());
            boolean refusee = false;
            try {
                assembly.source("meteo.pluie");
            } catch (IllegalArgumentException attendu) {
                refusee = attendu.getMessage().contains("meteo.pluie");
            }
            helper.assertTrue(refusee, "un nom inconnu ne lève pas IllegalArgumentException en se nommant");
        });
    }
}
