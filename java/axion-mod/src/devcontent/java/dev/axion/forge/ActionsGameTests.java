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
                    helper.assertTrue(nodes.keySet().equals(Set.of("caisse_vue", "siege_g", "porte_d_collideur")),
                            "nodes : " + nodes);
                    int remplacant = nodes.get("porte_d_collideur");
                    helper.assertTrue(assembly.nodeStates().equals(new NodeStates(java.util.List.of(
                            new NodeStates.State(remplacant, true, Float.NaN, null)))),
                            "au départ, seul le remplaçant d'un swap_mesh est caché : " + assembly.nodeStates());

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

                    // swap_mesh : l'original se cache, le remplaçant se montre ; sans « with », l'inverse.
                    assembly.runActions(seating.interactions().get(2).actions(), "test");
                    NodeStates.State echange = assembly.nodeStates().of(nodes.get("caisse_vue"));
                    helper.assertTrue(echange != null && echange.hidden() && echange.swapped() == remplacant,
                            "caisse_vue remplacée : " + echange);
                    helper.assertTrue(assembly.nodeStates().of(remplacant) == null, "le remplaçant se montre");
                    assembly.runActions(seating.interactions().get(3).actions(), "test");
                    helper.assertTrue(assembly.nodeStates().of(nodes.get("caisse_vue")) == null,
                            "l'original revient : " + assembly.nodeStates());
                    helper.assertTrue(assembly.nodeStates().of(remplacant) != null
                            && assembly.nodeStates().of(remplacant).hidden(), "le remplaçant retourne à l'ombre");

                    assembly.runActions(seating.interactions().get(0).actions(), "test");
                    assembly.runActions(seating.interactions().get(2).actions(), "test");
                    attendu[0] = assembly.nodeStates();

                    CompoundTag sauvegarde = new CompoundTag();
                    helper.assertTrue(assembly.save(sauvegarde), "sauvegarde refusée");
                    CompoundTag gardes = sauvegarde.getCompound(AssemblyTags.NODES);
                    helper.assertTrue(gardes.getAllKeys().equals(Set.of("caisse_vue", "siege_g", "porte_d_collideur")),
                            "l'état se garde par nom, le remplaçant montré compris : " + gardes);
                    helper.assertTrue("porte_d_collideur".equals(gardes.getCompound("caisse_vue").getString("swap")),
                            "le remplaçant se garde par nom : " + gardes);
                    assembly.discard();
                    Entity entite = EntityType.loadEntityRecursive(sauvegarde, helper.getLevel(), e -> e);
                    helper.assertTrue(entite instanceof AxionEntity, "la sauvegarde ne rend pas une assembly");
                    helper.assertTrue(helper.getLevel().addFreshEntity(entite), "l'assembly relue n'entre pas");
                    relue[0] = (AxionEntity) entite;
                })
                .thenWaitUntil(() -> helper.assertTrue(relue[0].nodeStates().of(
                        AxionEntities.seating(DEFINITION).orElseThrow().actionNodes().get("siege_g")) != null,
                        "état relu"))
                .thenExecute(() -> helper.assertTrue(relue[0].nodeStates().equals(attendu[0]),
                        "relu tel quel : " + relue[0].nodeStates() + ", attendu " + attendu[0]))
                .thenSucceed();
    }

    /**
     * ADR-174 D5 : un capteur déclaré voit entrer une autre assembly — son compte monte et part avec le snapshot —,
     * puis la voit sortir quand elle disparaît.
     */
    @GameTest(template = "test/plancher", timeoutTicks = 300)
    public static void adr174_UnCapteurCompteCeQuiYEntreEtEnSort(GameTestHelper helper) {
        ObservateursDeTest.declarer(helper, new Vec3(4.0, DESSUS_DALLE, 4.0));
        AxionEntity capteur = AssembliesDeTest.poser(helper, "axion:test/capteur", new Vec3(4.5, DESSUS_DALLE, 4.5),
                new float[] {0f, 0f, 0f, 1f});
        AxionEntity[] ballon = new AxionEntity[1];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(capteur.hasPhysicalBounds(), "le capteur est simulé"))
                .thenExecute(() -> {
                    helper.assertTrue(capteur.sensorCounts().length == 2 && capteur.sensorCounts()[0] == 0,
                            "deux capteurs, vides : " + java.util.Arrays.toString(capteur.sensorCounts()));
                    ballon[0] = AssembliesDeTest.poser(helper, "axion:test/ballon",
                            new Vec3(4.5, DESSUS_DALLE + 5, 4.5), new float[] {0f, 0f, 0f, 1f});
                })
                .thenWaitUntil(() -> helper.assertTrue(capteur.sensorCounts()[0] == 1,
                        "le ballon entre dans la porte : " + java.util.Arrays.toString(capteur.sensorCounts())))
                .thenExecute(() -> {
                    helper.assertTrue(capteur.netState().sensors().count(0) == 1, "le snapshot porte le compte");
                    ballon[0].discard();
                })
                .thenWaitUntil(() -> helper.assertTrue(capteur.sensorCounts()[0] == 0,
                        "disparu, il en sort : " + java.util.Arrays.toString(capteur.sensorCounts())))
                .thenSucceed();
    }

    /**
     * ADR-174 D2 : une variable par siège — l'interaction l'écrit au siège de qui clique, debout elle est refusée ; la
     * valeur de chaque siège part avec le snapshot et se sauvegarde par nom de siège.
     */
    @GameTest(template = "test/plancher", timeoutTicks = 200)
    public static void adr174_UneVariableParSiegeSEcritAuSiegeDeQuiClique(GameTestHelper helper) {
        ObservateursDeTest.declarer(helper, new Vec3(4.0, DESSUS_DALLE, 4.0));
        AxionEntity assembly = AssembliesDeTest.poser(helper, "axion:test/siege_radio",
                new Vec3(4.5, DESSUS_DALLE, 4.5), new float[] {0f, 0f, 0f, 1f});
        ServerPlayer joueur = SiegesGameTests.joueur(helper, "auditeur");
        AxionEntity[] relue = new AxionEntity[1];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(assembly.hasPhysicalBounds(), "l'assembly est simulée"))
                .thenIdle(20)
                .thenExecute(() -> {
                    dev.axion.entity.AssemblyVariables variables = assembly.variables();
                    int radio = variables.declared().indexOf("radio");
                    helper.assertTrue(variables.seats().equals(java.util.List.of("conducteur", "passager")),
                            "une case par siège : " + variables.seats());
                    helper.assertTrue(SiegesGameTests.cliquer(assembly, joueur, 2f, 0.5f, 0f) != InteractionResult.CONSUME,
                            "debout, il n'a pas de siège où l'écrire");
                    helper.assertTrue(variables.get(radio, 0) == 0 && variables.get(radio, 1) == 0, "rien n'a changé");
                    helper.assertTrue(assembly.sit(joueur, 1), "il s'assoit au siège passager");
                    helper.assertTrue(SiegesGameTests.cliquer(assembly, joueur, 2f, 0.5f, 0f) == InteractionResult.CONSUME,
                            "assis, l'interaction joue");
                    helper.assertTrue(variables.get(radio, 1) == 3 && variables.get(radio, 0) == 0,
                            "écrite à son siège seulement : " + java.util.Arrays.toString(variables.seatValues(radio)));
                    variables.set(radio, 0, 7, "test");
                })
                .thenIdle(2)
                .thenExecute(() -> {
                    int radio = assembly.variables().declared().indexOf("radio");
                    dev.axion.net.AssemblyState.VariableValues envoyees = assembly.netState().variables();
                    helper.assertTrue(envoyees.perSeat(radio) && envoyees.seatValue(radio, 0) == 7f
                            && envoyees.seatValue(radio, 1) == 3f, "le snapshot porte chaque siège : " + envoyees);
                    joueur.stopRiding();
                    CompoundTag sauvegarde = new CompoundTag();
                    helper.assertTrue(assembly.save(sauvegarde), "sauvegarde refusée");
                    CompoundTag gardee = sauvegarde.getCompound(AssemblyTags.VARIABLES).getCompound("radio");
                    helper.assertTrue(gardee.getDouble("conducteur") == 7 && gardee.getDouble("passager") == 3,
                            "gardée par nom de siège : " + gardee);
                    assembly.discard();
                    Entity entite = EntityType.loadEntityRecursive(sauvegarde, helper.getLevel(), e -> e);
                    helper.assertTrue(entite instanceof AxionEntity, "la sauvegarde ne rend pas une assembly");
                    helper.assertTrue(helper.getLevel().addFreshEntity(entite), "l'assembly relue n'entre pas");
                    relue[0] = (AxionEntity) entite;
                })
                .thenWaitUntil(() -> helper.assertTrue(relue[0].variables() != null, "variables relues"))
                .thenExecute(() -> {
                    int radio = relue[0].variables().declared().indexOf("radio");
                    helper.assertTrue(java.util.Arrays.equals(relue[0].variables().seatValues(radio),
                            new double[] {7, 3}), "relue telle quelle : "
                            + java.util.Arrays.toString(relue[0].variables().seatValues(radio)));
                })
                .thenSucceed();
    }

    /**
     * ADR-174 D4 : le {@code trigger} d'une animation, évalué au serveur — une interaction et l'événement qu'elle émet
     * jouent chacun leur clip ; une variable qui devient non nulle joue le sien, revenue à 0 elle l'arrête. Le masque de
     * l'animation (ADR-174 D4) suit la couche.
     */
    @GameTest(template = "test/plancher", timeoutTicks = 200)
    public static void adr174_LesDeclencheursJouentLeurClip(GameTestHelper helper) {
        ObservateursDeTest.declarer(helper, new Vec3(4.0, DESSUS_DALLE, 4.0));
        AxionEntity assembly = AssembliesDeTest.poser(helper, "axion:test/anime/glissiere_declenchee",
                new Vec3(4.5, DESSUS_DALLE, 4.5), new float[] {0f, 0f, 0f, 1f});
        ServerPlayer joueur = SiegesGameTests.joueur(helper, "declencheur");
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(assembly.hasPhysicalBounds(), "l'assembly est simulée"))
                .thenIdle(40)
                .thenExecute(() -> {
                    helper.assertTrue(assembly.animationTriggers().triggers().size() == 3,
                            "trois déclencheurs : " + assembly.animationTriggers());
                    helper.assertTrue(SiegesGameTests.cliquer(assembly, joueur, 0f, 0.5f, 0f) == InteractionResult.CONSUME,
                            "le socle émet son événement");
                    helper.assertTrue(assembly.animation().state().layer(2).playing(), "event:sonne joue la couche 2");
                    helper.assertTrue(assembly.animation().state().layer(3).playing(), "interact:socle joue la couche 3");
                    helper.assertTrue(!assembly.animation().state().layer(1).playing(), "la variable n'a pas bougé");
                    assembly.variables().set(assembly.variables().declared().indexOf("ouverte"), 1, "test");
                })
                .thenIdle(2)
                .thenExecute(() -> {
                    helper.assertTrue(assembly.animation().state().layer(1).playing(), "var:ouverte non nulle joue la couche 1");
                    // ADR-174 D4 : le masque de l'animation, résolu contre les nodes de l'asset, part avec la couche.
                    int coulisseau = AxionEntities.nodeNames("axion:test/anime/glissiere_declenchee").orElseThrow()
                            .indexOf("coulisseau");
                    helper.assertTrue(coulisseau >= 0 && assembly.animation().state().layer(1).mask()
                            .equals(java.util.List.of(coulisseau)), "le masque : " + assembly.animation().state().layer(1));
                    helper.assertTrue(assembly.netState().animation().layer(1).mask().equals(java.util.List.of(coulisseau)),
                            "le snapshot porte le masque");
                    helper.assertTrue(assembly.animation().state().layer(2).mask().isEmpty(),
                            "sans masque, la couche anime tous les nodes");
                    assembly.variables().set(assembly.variables().declared().indexOf("ouverte"), 0, "test");
                })
                .thenIdle(2)
                .thenExecute(() -> helper.assertTrue(!assembly.animation().state().layer(1).playing(),
                        "revenue à 0, elle l'arrête : " + assembly.animation().state().layer(1)))
                .thenSucceed();
    }
}
