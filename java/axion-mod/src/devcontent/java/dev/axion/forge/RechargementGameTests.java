package dev.axion.forge;

import dev.axion.content.RegisteredContent;
import dev.axion.api.content.ContentKind;
import dev.axion.asset.AssetSource;
import dev.axion.lifecycle.AxionRuntime;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * Un rechargement des données atteint les assemblies qui apparaissent après lui. Contenu de
 * développement, jamais empaqueté (R-1790).
 */
@GameTestHolder("axion")
@PrefixGameTestTemplate(false)
public final class RechargementGameTests {

    /** Le dessus de la dalle du gabarit {@code plancher}, en coordonnées du test. */
    private static final double DESSUS_DALLE = 2.0;

    /** Une definition que seul le rechargement du test apporte. */
    private static final String AJOUTEE = "axion:test/ajoutee_au_rechargement";

    private static final String CHEMIN = "axion:axion/definitions/test/ajoutee_au_rechargement.json";

    /** Le cube de test, sous un autre nom. */
    private static final byte[] DEFINITION =
            "{\"schema\": 1, \"asset\": \"axion:models/test/cube.gltf\", \"kind\": \"rigid_object\"}"
                    .getBytes(StandardCharsets.UTF_8);

    private RechargementGameTests() {}

    /**
     * Les definitions rechargées après le démarrage du serveur — un {@code /reload} — sont celles
     * que lit la boucle de l'entité : une assembly d'une definition que seul le rechargement apporte
     * prend un corps, tombe et repose sur la dalle. Lue dans le registre du démarrage, la definition
     * manquerait, et l'assembly resterait immobile, sans corps.
     */
    // Un lot à lui : RegistreReseauGameTests recharge aussi le registre des definitions, au même tick de
    // départ ; ce cube, créé par la file des chargements à la fin du tick (R-1730), y perdrait la sienne.
    @GameTest(template = "test/plancher", batch = "rechargement_definitions", timeoutTicks = 200)
    public static void uneDefinitionAjouteeParUnRechargementPrendCorps(GameTestHelper helper) {
        AxionRuntime runtime = RuntimeAccess.get();
        helper.assertTrue(runtime != null && runtime.outcome() != null, "AXION n'est pas chargé");
        AssetSource courantes = RegisteredContent.source(ContentKind.DEFINITION);
        AssetSource avecUneDePlus = new AssetSource() {
            @Override
            public List<String> list() {
                List<String> paths = new ArrayList<>(courantes.list());
                paths.add(CHEMIN);
                return paths;
            }

            @Override
            public byte[] read(String path) throws IOException {
                return CHEMIN.equals(path) ? DEFINITION.clone() : courantes.read(path);
            }
        };
        runtime.onDefinitionReload(avecUneDePlus);
        helper.assertTrue(runtime.definitions().get(AJOUTEE).isPresent(), AJOUTEE + " refusée au rechargement");

        AxionEntity cube = AssembliesDeTest.poser(
                helper, AJOUTEE, new Vec3(4.5, DESSUS_DALLE + 3.0, 4.5), new float[] {0f, 0f, 0f, 1f});
        double sol = helper.absoluteVec(new Vec3(0, DESSUS_DALLE, 0)).y;
        helper.succeedWhen(() -> {
            helper.assertTrue(
                    Math.abs(cube.getY() - sol) < 0.05,
                    "l'assembly est à y = " + cube.getY() + ", la dalle à " + sol
                            + " : sans corps, la boucle de l'entité lit des definitions d'avant le rechargement");
            // Les autres tests retrouvent les definitions enregistrées, sans celle du test.
            runtime.onDefinitionReload(courantes);
        });
    }

    /**
     * ADR-175 D4 : une assembly vivante garde la definition dont son corps est né. Un rechargement qui recompile la
     * même definition en fait de nouveaux objets — document, sièges, réglage — ; l'assembly, elle, lit toujours les
     * siens : Java ne mêle plus la definition d'avant et celle d'après.
     */
    @GameTest(template = "test/plancher", batch = "rechargement_definitions", timeoutTicks = 300)
    public static void adr175_UneInstanceGardeSaDefinitionApresUnRechargement(GameTestHelper helper) {
        AxionRuntime runtime = RuntimeAccess.get();
        helper.assertTrue(runtime != null && runtime.outcome() != null, "AXION n'est pas chargé");
        AssetSource courantes = RegisteredContent.source(ContentKind.DEFINITION);
        AxionEntity assembly = AssembliesDeTest.poser(
                helper, "axion:test/siege", new Vec3(4.5, DESSUS_DALLE, 4.5), new float[] {0f, 0f, 0f, 1f});
        dev.axion.definition.HeldDefinition[] avant = {null};
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(assembly.hasPhysicalBounds() && assembly.held() != null,
                        "l'assembly est simulée, sa definition tenue"))
                .thenExecute(() -> {
                    avant[0] = assembly.held();
                    runtime.onDefinitionReload(courantes);
                })
                .thenWaitUntil(() -> helper.assertTrue(AxionEntities.held("axion:test/siege").isPresent(),
                        "la definition rechargée est recompilée"))
                .thenExecute(() -> {
                    dev.axion.definition.HeldDefinition apres = AxionEntities.held("axion:test/siege").orElseThrow();
                    helper.assertTrue(apres.seating() != avant[0].seating() && apres.definition() != avant[0].definition()
                            && apres.summary() != avant[0].summary(),
                            "le rechargement a bien produit d'autres objets");
                    helper.assertTrue(assembly.held() == avant[0], "l'assembly tient toujours sa definition d'avant");
                    helper.assertTrue(assembly.declaredSeating() == avant[0].seating(),
                            "ses sièges sont ceux d'avant, non ceux du rechargement");
                })
                .thenSucceed();
    }
}
