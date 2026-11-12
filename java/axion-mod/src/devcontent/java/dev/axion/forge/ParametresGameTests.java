package dev.axion.forge;

import com.mojang.authlib.GameProfile;
import dev.axion.api.AxionApi;
import dev.axion.api.assembly.Modifier;
import dev.axion.api.assembly.Parameters;
import dev.axion.entity.AssemblyParameters;
import dev.axion.net.AssemblyState;
import dev.axion.net.MalformedPacketException;
import dev.axion.net.SnapshotMessage;
import dev.axion.seat.VehicleInput;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.commands.CommandSource;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * ADR-167, sous-tranche 5a, sur un vrai serveur : un paramètre réglé par l'API agit sur le véhicule simulé, les
 * modificateurs posés se sauvegardent et se relisent, partent aux joueurs datés du tick où ils prennent effet, et
 * {@code /axion params} pose, refuse et retire. Contenu de développement, jamais empaqueté (R-1790).
 */
@GameTestHolder("axion")
@PrefixGameTestTemplate(false)
public final class ParametresGameTests {

    /** Le dessus de la dalle des gabarits {@code plancher}, en coordonnées du test. */
    private static final double DESSUS_DALLE = 2.0;

    private static final float[] IDENTITE = {0f, 0f, 0f, 1f};

    private static final String VEHICULE = "axion:test/vehicule";

    private ParametresGameTests() {}

    /** Un joueur factice de Forge, assis au volant : il est tické par son véhicule. */
    private static ServerPlayer figurant(GameTestHelper helper, String nom) {
        UUID uuid = UUID.nameUUIDFromBytes(("axion-parametres:" + nom).getBytes(StandardCharsets.UTF_8));
        ServerPlayer joueur = FakePlayerFactory.get(helper.getLevel(), new GameProfile(uuid, nom));
        joueur.stopRiding();
        joueur.getPersistentData().remove(AxionEntity.REMEMBERED_SEAT);
        return joueur;
    }

    /**
     * D6 : deux véhicules identiques, pied au plancher ; l'un a le couple de son moteur réduit de moitié par l'API, au
     * cours de la course. Il parcourt nettement moins. (Le doubler ne prouverait rien : ce petit véhicule patine déjà à
     * son couple d'origine.)
     */
    @GameTest(template = "test/plancher_16", timeoutTicks = 400)
    public static void unCoupleRegleParLApiAgitSurLeVehicule(GameTestHelper helper) {
        Vec3 gauche = new Vec3(4.0, DESSUS_DALLE, 13.0);
        Vec3 droite = new Vec3(12.0, DESSUS_DALLE, 13.0);
        ObservateursDeTest.declarer(helper, new Vec3(8.0, DESSUS_DALLE, 8.0));
        AxionEntity temoin = AssembliesDeTest.poser(helper, VEHICULE, gauche, IDENTITE);
        AxionEntity regle = AssembliesDeTest.poser(helper, VEHICULE, droite, IDENTITE);
        ServerPlayer[] conducteurs = {figurant(helper, "temoin"), figurant(helper, "regle")};
        VehicleInput plein = new VehicleInput(1f, 0f, 0f, 0f, 0f, (byte) 0, 0);
        boolean[] gaz = {false};
        helper.onEachTick(() -> {
            if (gaz[0]) {
                temoin.receiveInput(conducteurs[0], temoin.seatOf(conducteurs[0]), plein);
                regle.receiveInput(conducteurs[1], regle.seatOf(conducteurs[1]), plein);
            }
        });
        Vec3[] depart = new Vec3[2];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(temoin.hasPhysicalBounds() && regle.hasPhysicalBounds()
                        && temoin.parameters() != null && regle.parameters() != null, "les véhicules sont simulés"))
                .thenIdle(20)
                .thenExecute(() -> {
                    Parameters parametres = AxionApi.get().assemblies().byEntity(regle).orElseThrow().parameters();
                    helper.assertTrue(parametres.base(Parameters.ENGINE_TORQUE) == 60.0,
                            "le pic de la courbe de test/vehicule : " + parametres.base(Parameters.ENGINE_TORQUE));
                    parametres.modify(new Modifier("essai:bride", Parameters.ENGINE_TORQUE,
                            Modifier.Operation.MULTIPLY_TOTAL, -0.5));
                    helper.assertTrue(parametres.value(Parameters.ENGINE_TORQUE) == 30.0,
                            "couple bridé : " + parametres.value(Parameters.ENGINE_TORQUE));
                    helper.assertTrue(temoin.reseat(conducteurs[0], "conducteur"), "le témoin a son conducteur");
                    helper.assertTrue(regle.reseat(conducteurs[1], "conducteur"), "le réglé a son conducteur");
                    gaz[0] = true;
                    depart[0] = temoin.position();
                    depart[1] = regle.position();
                })
                .thenIdle(50)
                .thenExecute(() -> {
                    gaz[0] = false;
                    double parcouruTemoin = depart[0].z - temoin.position().z;
                    double parcouruRegle = depart[1].z - regle.position().z;
                    helper.assertTrue(parcouruTemoin > 1.0, "le témoin avance vers son -Z : " + parcouruTemoin);
                    helper.assertTrue(parcouruRegle < 0.85 * parcouruTemoin, "le couple bridé avance moins : "
                            + parcouruRegle + " bloc(s) contre " + parcouruTemoin);
                    conducteurs[0].stopRiding();
                    conducteurs[1].stopRiding();
                })
                .thenSucceed();
    }

    /** D7 : les modificateurs posés se sauvegardent, se relisent dans leur ordre et avec leur valeur. */
    @GameTest(template = "test/plancher", timeoutTicks = 200)
    public static void lesModificateursPosesSeRelisent(GameTestHelper helper) {
        ObservateursDeTest.declarer(helper, new Vec3(4.0, DESSUS_DALLE, 4.0));
        AxionEntity assembly = AssembliesDeTest.poser(helper, VEHICULE, new Vec3(4.5, DESSUS_DALLE, 4.5), IDENTITE);
        AxionEntity[] relue = new AxionEntity[1];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(assembly.parameters() != null, "paramètres compilés"))
                .thenExecute(() -> {
                    AssemblyParameters parametres = assembly.parameters();
                    parametres.modify(new Modifier("essai:allegement", Parameters.MASS, Modifier.Operation.ADD, -40),
                            AssemblyParameters.API);
                    parametres.modify(new Modifier("essai:freins", Parameters.BRAKES_FRONT,
                            Modifier.Operation.MULTIPLY_BASE, 0.25), AssemblyParameters.COMMAND);
                    CompoundTag sauvegarde = new CompoundTag();
                    helper.assertTrue(assembly.save(sauvegarde), "sauvegarde refusée");
                    helper.assertTrue(sauvegarde.getInt(AssemblyTags.VERSION) == 5, "schéma 5 : "
                            + sauvegarde.getInt(AssemblyTags.VERSION));
                    helper.assertTrue(sauvegarde.getList(AssemblyTags.PARAMETERS, 10).size() == 2,
                            "deux modificateurs gardés : " + sauvegarde.get(AssemblyTags.PARAMETERS));
                    assembly.discard();
                    Entity entite = EntityType.loadEntityRecursive(sauvegarde, helper.getLevel(), e -> e);
                    helper.assertTrue(entite instanceof AxionEntity, "la sauvegarde ne rend pas une assembly");
                    helper.assertTrue(helper.getLevel().addFreshEntity(entite), "l'assembly relue n'entre pas");
                    relue[0] = (AxionEntity) entite;
                })
                .thenWaitUntil(() -> helper.assertTrue(relue[0].parameters() != null, "paramètres relus"))
                .thenExecute(() -> {
                    AssemblyParameters parametres = relue[0].parameters();
                    int masse = parametres.tuning().indexOf(Parameters.MASS);
                    int freins = parametres.tuning().indexOf(Parameters.BRAKES_FRONT);
                    helper.assertTrue(parametres.value(masse) == 300.0, "masse relue : " + parametres.value(masse));
                    helper.assertTrue(parametres.value(freins) == 750.0, "freins relus : " + parametres.value(freins));
                    List<Parameters.Applied> poses = parametres.applied();
                    helper.assertTrue(poses.size() == 2 && poses.get(0).modifier().cause().equals("essai:allegement")
                            && poses.get(0).origin().equals(AssemblyParameters.SAVE), "ordre et origine : " + poses);
                })
                .thenSucceed();
    }

    /**
     * D8 : l'état que les snapshots portent donne les modificateurs posés, datés du tick du pas qui les applique — celui
     * de la pose ou le suivant —, et le client qui rejoue ce qu'il en lit sur la definition compose les valeurs du
     * serveur, au bit près. Un retrait part aussi, daté.
     */
    @GameTest(template = "test/plancher", timeoutTicks = 200)
    public static void lesJoueursRecoiventLesModificateursDates(GameTestHelper helper) {
        ObservateursDeTest.declarer(helper, new Vec3(4.0, DESSUS_DALLE, 4.0));
        AxionEntity assembly = AssembliesDeTest.poser(helper, VEHICULE, new Vec3(4.5, DESSUS_DALLE, 4.5), IDENTITE);
        MinecraftServer serveur = helper.getLevel().getServer();
        long[] pose = new long[2];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(assembly.parameters() != null && assembly.netState() != null,
                        "l'assembly est simulée"))
                .thenExecute(() -> {
                    helper.assertTrue(assembly.netState().parameters().equals(AssemblyState.Modifiers.NONE),
                            "jamais réglée : rien ne part");
                    Parameters parametres = AxionApi.get().assemblies().byEntity(assembly).orElseThrow().parameters();
                    parametres.modify(new Modifier("essai:bride", Parameters.ENGINE_TORQUE,
                            Modifier.Operation.MULTIPLY_TOTAL, -0.3));
                    parametres.modify(new Modifier("essai:allegement", Parameters.MASS, Modifier.Operation.ADD,
                            -1.0 / 3.0));
                    pose[0] = serveur.getTickCount();
                })
                .thenWaitUntil(() -> helper.assertTrue(assembly.netState().parameters().posed().size() == 2,
                        "les modificateurs partent"))
                .thenExecute(() -> {
                    AssemblyState.Modifiers envoyes = assembly.netState().parameters();
                    helper.assertTrue(envoyes.tick() == pose[0] || envoyes.tick() == pose[0] + 1,
                            "daté du pas qui les applique : " + envoyes.tick() + " pour une pose au tick " + pose[0]);
                    AssemblyParameters table = assembly.parameters();
                    helper.assertTrue(envoyes.equals(table.posed(envoyes.tick())), "la liste posée : " + envoyes);
                    AssemblyParameters miroir = AssemblyParameters.mirror(table.tuning(), relu(assembly.netState()),
                            new double[0]);
                    for (int i = 0; i < table.tuning().size(); i++) {
                        helper.assertTrue(Double.doubleToRawLongBits(table.value(i))
                                        == Double.doubleToRawLongBits(miroir.value(i)),
                                table.tuning().get(i).name() + " : " + miroir.value(i) + " au client, " + table.value(i)
                                        + " au serveur");
                    }
                    helper.assertTrue(miroir.applied().equals(table.applied()), "mêmes actifs : " + miroir.applied());
                    AxionApi.get().assemblies().byEntity(assembly).orElseThrow().parameters().remove("essai:bride");
                    AxionApi.get().assemblies().byEntity(assembly).orElseThrow().parameters().remove("essai:allegement");
                    pose[1] = serveur.getTickCount();
                })
                .thenWaitUntil(() -> helper.assertTrue(assembly.netState().parameters().posed().isEmpty(),
                        "le retrait part"))
                .thenExecute(() -> {
                    long tick = assembly.netState().parameters().tick();
                    helper.assertTrue(tick == pose[1] || tick == pose[1] + 1, "retrait daté : " + tick);
                    helper.assertTrue(!assembly.netState().parameters().equals(AssemblyState.Modifiers.NONE),
                            "une liste vidée n'est pas une assembly vierge");
                })
                .thenSucceed();
    }

    /**
     * D4 : une variable règle un paramètre. La commande 1 du siège bascule {@code bride}, qui divise le couple par deux :
     * au serveur comme chez le client qui reçoit la variable. Une écriture qui sortirait le couple de sa plage
     * ({@code tuning.ranges}, 20 à 80 N·m) est refusée — par la touche comme par l'API —, et la variable garde sa
     * valeur.
     */
    @GameTest(template = "test/plancher", timeoutTicks = 200)
    public static void uneVariableRegleLeCouple(GameTestHelper helper) {
        ObservateursDeTest.declarer(helper, new Vec3(4.0, DESSUS_DALLE, 4.0));
        AxionEntity assembly = AssembliesDeTest.poser(helper, "axion:test/vehicule_bride",
                new Vec3(4.5, DESSUS_DALLE, 4.5), IDENTITE);
        ServerPlayer conducteur = figurant(helper, "bride");
        VehicleInput commande = new VehicleInput(0f, 0f, 0f, 0f, 0f, (byte) 0, 1);
        int[] couple = new int[1];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(assembly.parameters() != null && assembly.netState() != null
                        && assembly.seatingKnown(), "l'assembly est simulée"))
                .thenExecute(() -> {
                    helper.assertTrue(assembly.reseat(conducteur, "conducteur"), "le conducteur s'assoit");
                    AssemblyParameters table = assembly.parameters();
                    couple[0] = table.tuning().indexOf(Parameters.ENGINE_TORQUE);
                    helper.assertTrue(table.value(couple[0]) == 60.0, "à la base : " + table.value(couple[0]));
                    assembly.receiveInput(conducteur, assembly.seatOf(conducteur), commande);
                })
                // ADR-168 D3 : une entrée s'applique au tick qui suit sa réception, une par tick.
                .thenExecuteAfter(1, () -> {
                    helper.assertTrue(assembly.variables().get(0) == 1.0, "la commande 1 bascule la variable");
                    helper.assertTrue(assembly.parameters().value(couple[0]) == 30.0,
                            "couple bridé : " + assembly.parameters().value(couple[0]));
                    Parameters.Applied declare = assembly.parameters().applied().get(0);
                    helper.assertTrue(declare.origin().equals(AssemblyParameters.VARIABLE)
                            && declare.modifier().cause().equals("axion:test/vehicule_bride/bride"), "déclaré : " + declare);
                })
                .thenWaitUntil(() -> helper.assertTrue(assembly.netState().variables().has(0)
                        && assembly.netState().variables().value(0) == 1f, "la variable part aux joueurs"))
                .thenExecute(() -> {
                    // D8 : le client compose avec ce qu'il lit du fil, variables comprises.
                    AssemblyParameters miroir = AssemblyParameters.mirror(assembly.parameters().tuning(),
                            relu(assembly.netState()), variablesRelues(assembly.netState()));
                    helper.assertTrue(Double.doubleToRawLongBits(miroir.value(couple[0]))
                            == Double.doubleToRawLongBits(assembly.parameters().value(couple[0])),
                            "le client lit " + miroir.value(couple[0]));
                    int siege = assembly.seatOf(conducteur);
                    assembly.receiveInput(conducteur, siege, VehicleInput.NONE);
                    assembly.receiveInput(conducteur, siege, commande);
                })
                .thenExecuteAfter(2, () -> {
                    helper.assertTrue(assembly.variables().get(0) == 0.0, "second appui : débridé");
                    // 60 - 25 = 35 N·m ; bridé, 17,5 N·m sortirait de la plage [20, 80].
                    AxionApi.get().assemblies().byEntity(assembly).orElseThrow().parameters().modify(
                            new Modifier("essai:lest", Parameters.ENGINE_TORQUE, Modifier.Operation.ADD, -25));
                    int siege = assembly.seatOf(conducteur);
                    assembly.receiveInput(conducteur, siege, VehicleInput.NONE);
                    assembly.receiveInput(conducteur, siege, commande);
                })
                .thenExecuteAfter(2, () -> {
                    helper.assertTrue(assembly.variables().get(0) == 0.0, "la touche est refusée");
                    helper.assertTrue(assembly.parameters().value(couple[0]) == 35.0,
                            "couple inchangé : " + assembly.parameters().value(couple[0]));
                    try {
                        AxionApi.get().assemblies().byEntity(assembly).orElseThrow().variables().set("bride", 1);
                        helper.fail("l'API pose une variable qui sortirait le couple de sa plage");
                    } catch (IllegalArgumentException refuse) {
                        helper.assertTrue(refuse.getMessage().contains("engine.torque"), refuse.getMessage());
                    }
                    conducteur.stopRiding();
                })
                .thenSucceed();
    }

    /** {@return les variables qu'un joueur lit de cet état, après un aller-retour sur le fil} */
    private static double[] variablesRelues(AssemblyState etat) {
        try {
            byte[] fil = new SnapshotMessage(1, 1L, SnapshotMessage.DESCRIPTOR_VERSION,
                    List.of(SnapshotMessage.Entry.full(1, etat))).encode();
            AssemblyState.VariableValues lues = SnapshotMessage.decode(fil).entries().get(0).resolve(null).variables();
            double[] valeurs = new double[lues.count()];
            for (int i = 0; i < valeurs.length; i++) {
                valeurs[i] = lues.value(i);
            }
            return valeurs;
        } catch (MalformedPacketException refuse) {
            throw new IllegalStateException("le snapshot de l'assembly se relit mal : " + refuse.getMessage(), refuse);
        }
    }

    /** {@return les modificateurs qu'un joueur lit de cet état, après un aller-retour sur le fil} */
    private static AssemblyState.Modifiers relu(AssemblyState etat) {
        try {
            byte[] fil = new SnapshotMessage(1, 1L, SnapshotMessage.DESCRIPTOR_VERSION,
                    List.of(SnapshotMessage.Entry.full(1, etat))).encode();
            return SnapshotMessage.decode(fil).entries().get(0).resolve(null).parameters();
        } catch (MalformedPacketException refuse) {
            throw new IllegalStateException("le snapshot de l'assembly se relit mal : " + refuse.getMessage(), refuse);
        }
    }

    /**
     * D10 : {@code /axion params} pose un modificateur, refuse et nomme celui qui sortirait des bornes, montre l'état,
     * et retire une cause.
     */
    @GameTest(template = "test/plancher", timeoutTicks = 200)
    public static void laCommandePoseRefuseEtRetire(GameTestHelper helper) {
        ObservateursDeTest.declarer(helper, new Vec3(4.0, DESSUS_DALLE, 4.0));
        AxionEntity assembly = AssembliesDeTest.poser(helper, VEHICULE, new Vec3(4.5, DESSUS_DALLE, 4.5), IDENTITE);
        MinecraftServer serveur = helper.getLevel().getServer();
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(assembly.parameters() != null, "paramètres compilés"))
                .thenExecute(() -> {
                    String cible = "axion params " + assembly.getUUID() + " ";
                    List<String> pose = commande(serveur, cible + "set essai:reglage brakes.front add 150");
                    helper.assertTrue(pose.stream().anyMatch(ligne -> ligne.startsWith("  brakes.front = 750 N·m (base 600)")),
                            "pose : " + pose);
                    helper.assertTrue(pose.stream().anyMatch(ligne -> ligne.contains("essai:reglage add 150 (commande)")),
                            "origine : " + pose);
                    List<String> refus = commande(serveur, cible + "set essai:trop brakes.front add -1000");
                    helper.assertTrue(refus.size() == 1 && refus.get(0).startsWith("AXION : refusé — ")
                            && refus.get(0).contains("brakes.front"), "refus nommé : " + refus);
                    List<String> etat = commande(serveur, "axion params " + assembly.getUUID());
                    helper.assertTrue(etat.get(0).startsWith("AXION ENGINE — " + VEHICULE + " : "), "en-tête : " + etat);
                    List<String> retrait = commande(serveur, cible + "remove essai:reglage");
                    helper.assertTrue(retrait.stream().anyMatch(ligne -> ligne.startsWith("  brakes.front = 600 N·m")),
                            "retrait : " + retrait);
                })
                .thenSucceed();
    }

    /** {@return les réponses d'une commande tapée avec les droits du serveur} */
    private static List<String> commande(MinecraftServer serveur, String ligne) {
        List<String> sortie = new ArrayList<>();
        CommandSource recueil = new CommandSource() {
            @Override
            public void sendSystemMessage(Component message) {
                sortie.addAll(List.of(message.getString().split("\n")));
            }

            @Override
            public boolean acceptsSuccess() {
                return true;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }

            @Override
            public boolean shouldInformAdmins() {
                return false;
            }
        };
        serveur.getCommands().performPrefixedCommand(serveur.createCommandSourceStack().withSource(recueil), ligne);
        return sortie;
    }
}
