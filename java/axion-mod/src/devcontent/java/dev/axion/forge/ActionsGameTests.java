package dev.axion.forge;

import dev.axion.definition.Seating;
import dev.axion.net.NodeStates;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * ADR-174, tranche 9a1 : les actions d'une interaction changent l'état des nodes — cachés, émission et teinte posées —,
 * dans leur ordre, au même tick ; l'état part aux joueurs avec le snapshot, et se sauvegarde par nom.
 */
@GameTestHolder("axion")
@PrefixGameTestTemplate(false)
public final class ActionsGameTests {

    private static final String DEFINITION = "axion:test/siege_actions";

    /** Le dessus de la dalle du gabarit {@code plancher}, en coordonnées du test. */
    private static final double DESSUS_DALLE = 2.0;

    private ActionsGameTests() {}

    @GameTest(template = "test/plancher", timeoutTicks = 200)
    public static void adr174_LesActionsChangentLEtatDesNodes(GameTestHelper helper) {
        ObservateursDeTest.declarer(helper, new Vec3(4.0, DESSUS_DALLE, 4.0));
        AxionEntity assembly = AssembliesDeTest.poser(helper, DEFINITION, new Vec3(4.5, DESSUS_DALLE, 4.5),
                new float[] {0f, 0f, 0f, 1f});
        ServerPlayer joueur = SiegesGameTests.joueur(helper, "cliqueur_actions");
        AxionEntity[] relue = new AxionEntity[1];
        NodeStates[] attendu = new NodeStates[1];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(assembly.hasPhysicalBounds(), "l'assembly est simulée"))
                .thenIdle(20)
                .thenExecute(() -> {
                    Seating seating = AxionEntities.seating(DEFINITION).orElseThrow();
                    Map<String, Integer> nodes = seating.actionNodes();
                    helper.assertTrue(nodes.keySet().equals(Set.of("caisse_vue", "siege_g")), "nodes : " + nodes);
                    helper.assertTrue(assembly.nodeStates().states().isEmpty(), "aucun node changé au départ");

                    helper.assertTrue(SiegesGameTests.cliquer(assembly, joueur, 2f, 0.5f, 0f) == InteractionResult.CONSUME,
                            "le clic sur la portière joue ses actions");
                    NodeStates etat = assembly.nodeStates();
                    NodeStates.State vue = etat.of(nodes.get("caisse_vue"));
                    helper.assertTrue(vue != null && vue.hidden() && Float.isNaN(vue.emissive()) && vue.color() == null,
                            "caisse_vue cachée : " + vue);
                    NodeStates.State siege = etat.of(nodes.get("siege_g"));
                    helper.assertTrue(siege != null && !siege.hidden() && siege.emissive() == 2.5f
                            && Arrays.equals(siege.color(), new float[] {1f, 0.25f, 0.25f}),
                            "siège : émission puis teinte, dans l'ordre : " + siege);
                    helper.assertTrue(assembly.netState().nodes().equals(etat), "le snapshot porte l'état des nodes");

                    // show_node lève le masque : le node revient à son état de départ, et sort de l'état.
                    assembly.runActions(seating.interactions().get(1).actions(), "test");
                    helper.assertTrue(assembly.nodeStates().of(nodes.get("caisse_vue")) == null,
                            "caisse_vue montrée : " + assembly.nodeStates());
                    assembly.runActions(seating.interactions().get(0).actions(), "test");
                    attendu[0] = assembly.nodeStates();

                    CompoundTag sauvegarde = new CompoundTag();
                    helper.assertTrue(assembly.save(sauvegarde), "sauvegarde refusée");
                    CompoundTag gardes = sauvegarde.getCompound(AssemblyTags.NODES);
                    helper.assertTrue(gardes.getAllKeys().equals(Set.of("caisse_vue", "siege_g")),
                            "l'état se garde par nom : " + gardes);
                    assembly.discard();
                    Entity entite = EntityType.loadEntityRecursive(sauvegarde, helper.getLevel(), e -> e);
                    helper.assertTrue(entite instanceof AxionEntity, "la sauvegarde ne rend pas une assembly");
                    helper.assertTrue(helper.getLevel().addFreshEntity(entite), "l'assembly relue n'entre pas");
                    relue[0] = (AxionEntity) entite;
                })
                .thenWaitUntil(() -> helper.assertTrue(!relue[0].nodeStates().states().isEmpty(), "état relu"))
                .thenExecute(() -> helper.assertTrue(relue[0].nodeStates().equals(attendu[0]),
                        "relu tel quel : " + relue[0].nodeStates() + ", attendu " + attendu[0]))
                .thenSucceed();
    }
}
