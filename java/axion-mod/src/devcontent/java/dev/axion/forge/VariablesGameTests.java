package dev.axion.forge;

import dev.axion.api.AxionApi;
import dev.axion.api.assembly.Variables;
import dev.axion.entity.AssemblyVariables;
import dev.axion.seat.VehicleInput;
import java.util.List;
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
 * ADR-163 D2 : les variables d'une assembly, posées par les commandes liées de ses sièges — de vraies entrées, sur
 * le chemin du réseau —, par une interaction et par l'API, puis sauvegardées et relues. La definition
 * {@code axion:test/siege_variables} lie quatre commandes au conducteur, une au passager, et pose les phares d'un
 * clic sur la portière.
 */
@GameTestHolder("axion")
@PrefixGameTestTemplate(false)
public final class VariablesGameTests {

    private static final String DEFINITION = "axion:test/siege_variables";

    /** Le dessus de la dalle du gabarit {@code plancher}, en coordonnées du test. */
    private static final double DESSUS_DALLE = 2.0;

    private static final int GYROPHARE = 0;
    private static final int KLAXON = 1;
    private static final int PHARES = 2;
    private static final int BIP = 3;

    private VariablesGameTests() {}

    private static AxionEntity poser(GameTestHelper helper) {
        ObservateursDeTest.declarer(helper, new Vec3(4.0, DESSUS_DALLE, 4.0));
        return AssembliesDeTest.poser(helper, DEFINITION, new Vec3(4.5, DESSUS_DALLE, 4.5), new float[] {0f, 0f, 0f, 1f});
    }

    /** Une entrée où seules les commandes nommées sont tenues. */
    private static VehicleInput commandes(int... tenues) {
        int flags = 0;
        for (int commande : tenues) {
            flags |= 1 << (commande - 1);
        }
        return new VehicleInput(0f, 0f, 0f, 0f, 0f, (byte) 0, flags);
    }

    private static void envoyer(GameTestHelper helper, AxionEntity assembly, ServerPlayer joueur, int siege,
            VehicleInput entree) {
        String faute = assembly.receiveInput(joueur, siege, entree);
        helper.assertTrue(faute == null, "entrée refusée : " + faute);
    }

    private static void vaut(GameTestHelper helper, AxionEntity assembly, int variable, double attendue, String quoi) {
        double valeur = assembly.variables().get(variable);
        helper.assertTrue(valeur == attendue, quoi + " : " + valeur + ", " + attendue + " attendu");
    }

    /**
     * Toggle, cycle, hold et press par les entrées du conducteur ; une commande qu'aucun siège ne lie n'est pas
     * tenue ; le passager pose les phares par sa propre commande ; une commande tenue se relâche quand les entrées
     * se taisent, et quand le siège est quitté.
     */
    @GameTest(template = "test/plancher", timeoutTicks = 300)
    public static void lesCommandesLieesPosentLesVariables(GameTestHelper helper) {
        AxionEntity assembly = poser(helper);
        ServerPlayer conducteur = SiegesGameTests.joueur(helper, "conducteur_variables");
        ServerPlayer passager = SiegesGameTests.joueur(helper, "passager_variables");
        long silence = AxionEntities.inputTimeoutMillis() / 50 + 3;
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(assembly.hasPhysicalBounds(), "l'assembly est simulée"))
                .thenExecute(() -> {
                    helper.assertTrue(assembly.sit(conducteur, 0), "assis au volant");
                    helper.assertTrue(assembly.sit(passager, 1), "assis au siège passager");
                    helper.assertTrue(assembly.variables() != null, "variables créées");
                    // toggle : au front montant.
                    envoyer(helper, assembly, conducteur, 0, commandes(1));
                    vaut(helper, assembly, GYROPHARE, 1, "gyrophare allumé au front");
                    envoyer(helper, assembly, conducteur, 0, commandes(1));
                    vaut(helper, assembly, GYROPHARE, 1, "tenu, toujours allumé");
                    envoyer(helper, assembly, conducteur, 0, commandes());
                    envoyer(helper, assembly, conducteur, 0, commandes(1));
                    vaut(helper, assembly, GYROPHARE, 0, "second front : éteint");
                    envoyer(helper, assembly, conducteur, 0, commandes());
                    // cycle.
                    envoyer(helper, assembly, conducteur, 0, commandes(3));
                    vaut(helper, assembly, PHARES, 1, "phares, premier cran");
                    envoyer(helper, assembly, conducteur, 0, commandes());
                    envoyer(helper, assembly, conducteur, 0, commandes(3));
                    vaut(helper, assembly, PHARES, 2, "phares, second cran");
                    envoyer(helper, assembly, conducteur, 0, commandes());
                    // Une commande qu'aucune liaison du siège ne nomme n'est pas tenue.
                    envoyer(helper, assembly, conducteur, 0, commandes(8));
                    helper.assertTrue(assembly.seatInput(0).flags() == 0, "commande 8 non liée : réduite");
                    // press : un tick.
                    envoyer(helper, assembly, conducteur, 0, commandes(4));
                    vaut(helper, assembly, BIP, 1, "bip posé à l'entrée");
                    // hold, du passager : sa liaison pose 2, relâchée 0.
                    envoyer(helper, assembly, passager, 1, commandes(3));
                    vaut(helper, assembly, PHARES, 2, "phares tenus par le passager");
                    envoyer(helper, assembly, passager, 1, commandes());
                    vaut(helper, assembly, PHARES, 0, "relâchés par le passager");
                    // hold du conducteur, puis ses entrées se taisent.
                    envoyer(helper, assembly, conducteur, 0, commandes(2));
                    vaut(helper, assembly, KLAXON, 1, "klaxon tenu");
                })
                .thenIdle(2)
                .thenExecute(() -> vaut(helper, assembly, BIP, 0, "bip revenu après son tick"))
                .thenIdle((int) silence)
                .thenExecute(() -> {
                    vaut(helper, assembly, KLAXON, 0, "entrées tues : klaxon relâché");
                    helper.assertTrue(assembly.variables().writer(KLAXON).contains("entrées tues"),
                            assembly.variables().writer(KLAXON));
                    envoyer(helper, assembly, conducteur, 0, commandes(2));
                    vaut(helper, assembly, KLAXON, 1, "klaxon de nouveau tenu");
                    conducteur.stopRiding();
                })
                .thenIdle(2)
                .thenExecute(() -> {
                    vaut(helper, assembly, KLAXON, 0, "siège quitté : klaxon relâché");
                    helper.assertTrue(assembly.variables().writer(KLAXON).contains("quitté"),
                            assembly.variables().writer(KLAXON));
                    passager.stopRiding();
                })
                .thenSucceed();
    }

    /**
     * L'interaction de la portière pose les phares ; l'API lit et pose une variable dans ses bornes, et refuse,
     * en la nommant, une valeur hors bornes ou une variable inconnue ; {@code /axion variables} montre l'écrivain.
     */
    @GameTest(template = "test/plancher", timeoutTicks = 200)
    public static void interactionEtApiPosentLesVariables(GameTestHelper helper) {
        AxionEntity assembly = poser(helper);
        ServerPlayer joueur = SiegesGameTests.joueur(helper, "cliqueur_variables");
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(assembly.hasPhysicalBounds(), "l'assembly est simulée"))
                .thenIdle(20)
                .thenExecute(() -> {
                    helper.assertTrue(SiegesGameTests.cliquer(assembly, joueur, 2f, 0.5f, 0f) == InteractionResult.CONSUME,
                            "le clic sur la portière a son effet");
                    vaut(helper, assembly, PHARES, 2, "phares posés par l'interaction");
                    helper.assertTrue(assembly.variables().writer(PHARES).startsWith("interaction"),
                            assembly.variables().writer(PHARES));

                    Variables variables = AxionApi.get().assemblies().byEntity(assembly).orElseThrow().variables();
                    helper.assertTrue(variables.names().equals(List.of("gyrophare", "klaxon", "phares", "bip", "compteur")),
                            "noms : " + variables.names());
                    helper.assertTrue(variables.get("phares") == 2.0, "l'API lit la valeur");
                    variables.set("compteur", 42.5);
                    helper.assertTrue(assembly.variables().get(4) == 42.5, "l'API pose une variable que nulle entrée n'écrit");
                    variables.setBoolean("gyrophare", true);
                    helper.assertTrue(variables.getBoolean("gyrophare"), "booléen posé");
                    IllegalArgumentException horsBornes = null;
                    try {
                        variables.set("compteur", 101);
                    } catch (IllegalArgumentException refus) {
                        horsBornes = refus;
                    }
                    helper.assertTrue(horsBornes != null && horsBornes.getMessage().contains("compteur"),
                            "hors bornes, refusée et nommée : " + horsBornes);
                    helper.assertTrue(assembly.variables().get(4) == 42.5, "jamais tronquée");
                    helper.assertTrue(!variables.has("inconnue"), "variable inconnue");
                    boolean inconnue = false;
                    try {
                        variables.get("inconnue");
                    } catch (IllegalArgumentException refus) {
                        inconnue = refus.getMessage().contains("inconnue");
                    }
                    helper.assertTrue(inconnue, "une variable inconnue se nomme");

                    List<String> lignes = AxionCommands.variableLines(assembly.definitionId(), assembly.variables());
                    helper.assertTrue(lignes.size() == 6, "en-tête et cinq variables : " + lignes);
                    helper.assertTrue(lignes.get(3).contains("phares = 2") && lignes.get(3).contains("interaction"),
                            lignes.get(3));
                    helper.assertTrue(lignes.get(5).contains("compteur = 42.5") && lignes.get(5).contains("API"),
                            lignes.get(5));
                })
                .thenSucceed();
    }

    /**
     * Les variables {@code persist} se sauvegardent dans {@code axion:vars}, les autres non ; relues, elles reprennent
     * leur valeur, que la sauvegarde a posée.
     */
    @GameTest(template = "test/plancher", timeoutTicks = 200)
    public static void lesVariablesGardeesSeRelisent(GameTestHelper helper) {
        AxionEntity assembly = poser(helper);
        AxionEntity[] relue = new AxionEntity[1];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(assembly.hasPhysicalBounds(), "l'assembly est simulée"))
                .thenExecute(() -> {
                    AssemblyVariables variables = assembly.variables();
                    variables.set(GYROPHARE, 1, "test");
                    variables.set(PHARES, 2, "test");
                    variables.set(KLAXON, 1, "test");
                    CompoundTag sauvegarde = new CompoundTag();
                    helper.assertTrue(assembly.save(sauvegarde), "sauvegarde refusée");
                    CompoundTag gardees = sauvegarde.getCompound(AssemblyTags.VARIABLES);
                    helper.assertTrue(gardees.getAllKeys().equals(java.util.Set.of("gyrophare", "phares")),
                            "seules les variables persist : " + gardees);
                    assembly.discard();
                    Entity entite = EntityType.loadEntityRecursive(sauvegarde, helper.getLevel(), e -> e);
                    helper.assertTrue(entite instanceof AxionEntity, "la sauvegarde ne rend pas une assembly");
                    helper.assertTrue(helper.getLevel().addFreshEntity(entite), "l'assembly relue n'entre pas");
                    relue[0] = (AxionEntity) entite;
                })
                .thenWaitUntil(() -> helper.assertTrue(relue[0].variables() != null, "variables relues"))
                .thenExecute(() -> {
                    vaut(helper, relue[0], GYROPHARE, 1, "gyrophare relu");
                    vaut(helper, relue[0], PHARES, 2, "phares relus");
                    vaut(helper, relue[0], KLAXON, 0, "klaxon non gardé : défaut");
                    helper.assertTrue(AssemblyVariables.SAVE.equals(relue[0].variables().writer(PHARES)),
                            relue[0].variables().writer(PHARES));
                })
                .thenSucceed();
    }

    /**
     * ADR-163 D2 : les snapshots portent à chaque joueur les variables qu'il doit recevoir — toutes celles de
     * {@code replicate: all}, celles de {@code driver} au seul conducteur, jamais celles de {@code none}.
     */
    @GameTest(template = "test/plancher", timeoutTicks = 200)
    public static void chaqueJoueurRecoitSesVariables(GameTestHelper helper) {
        AxionEntity assembly = poser(helper);
        ServerPlayer conducteur = SiegesGameTests.joueur(helper, "conducteur_replique");
        ServerPlayer passager = SiegesGameTests.joueur(helper, "passager_replique");
        int compteur = 4;
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(assembly.netState() != null, "état réseau calculé"))
                .thenExecute(() -> {
                    helper.assertTrue(assembly.sit(conducteur, 0), "assis au volant");
                    helper.assertTrue(assembly.sit(passager, 1), "assis au siège passager");
                    assembly.variables().set(GYROPHARE, 1, "test");
                    assembly.variables().set(BIP, 1, "test");
                    assembly.variables().set(compteur, 7, "test");
                })
                .thenIdle(1)
                .thenExecute(() -> {
                    dev.axion.net.AssemblyState.VariableValues auConducteur =
                            assembly.netStateFor(conducteur).variables();
                    dev.axion.net.AssemblyState.VariableValues auPassager = assembly.netStateFor(passager).variables();
                    helper.assertTrue(auConducteur.count() == 5, "cinq variables déclarées : " + auConducteur);
                    helper.assertTrue(auConducteur.has(GYROPHARE) && auConducteur.value(GYROPHARE) == 1f,
                            "gyrophare au conducteur : " + auConducteur);
                    helper.assertTrue(auPassager.has(GYROPHARE) && auPassager.value(GYROPHARE) == 1f,
                            "gyrophare au passager : " + auPassager);
                    helper.assertTrue(auConducteur.has(BIP), "bip (driver) au conducteur");
                    helper.assertTrue(!auPassager.has(BIP), "bip (driver) pas au passager");
                    helper.assertTrue(!auConducteur.has(compteur) && !auPassager.has(compteur), "compteur (none) à personne");
                    conducteur.stopRiding();
                    passager.stopRiding();
                })
                .thenSucceed();
    }
}
