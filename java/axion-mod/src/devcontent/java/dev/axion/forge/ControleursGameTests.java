package dev.axion.forge;

import com.mojang.authlib.GameProfile;
import dev.axion.api.AxionApi;
import dev.axion.api.assembly.Assembly;
import dev.axion.api.assembly.Modifier;
import dev.axion.api.assembly.Parameters;
import dev.axion.api.vehicle.DriverInput;
import dev.axion.api.vehicle.VehicleControl;
import dev.axion.api.vehicle.VehicleView;
import dev.axion.entity.AssemblyParameters;
import dev.axion.entity.ControllerRunner;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * ADR-167 D9 : les contrôleurs de véhicule en jeu — nommés par une fiche ou attachés par l'API, appelés à chaque tick
 * sur l'état du début du tick, leurs sorties appliquées, leur coût mesuré, désactivés quand ils fautent ; et un
 * véhicule conduit par un mod. Les contrôleurs viennent de {@link ControleursDeTest}.
 */
@GameTestHolder("axion")
@PrefixGameTestTemplate(false)
public final class ControleursGameTests {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    /** Le dessus de la dalle des gabarits {@code plancher}, en coordonnées du test. */
    private static final double DESSUS_DALLE = 2.0;

    private static final float[] IDENTITE = {0f, 0f, 0f, 1f};

    private ControleursGameTests() {}

    private static Assembly api(AxionEntity entite) {
        return AxionApi.get().assemblies().byEntity(entite).orElseThrow();
    }

    private static VehicleControl vehicule(AxionEntity entite) {
        return api(entite).vehicle().orElseThrow(() -> new AssertionError("vehicle() vide pour un véhicule"));
    }

    private static ServerPlayer figurant(GameTestHelper helper, String nom) {
        UUID uuid = UUID.nameUUIDFromBytes(("axion-controleur:" + nom).getBytes(StandardCharsets.UTF_8));
        ServerPlayer joueur = FakePlayerFactory.get(helper.getLevel(), new GameProfile(uuid, nom));
        joueur.stopRiding();
        joueur.getPersistentData().remove(AxionEntity.REMEMBERED_SEAT);
        return joueur;
    }

    /**
     * R-1082, D9, Q6 : une fiche sans {@code powertrain} nomme ses contrôleurs ; leur couple fait rouler le véhicule
     * vers son avant, leur état vit dans une variable, et le paramètre du mod existe là, réglé pour le tick par le
     * contrôleur — l'état qu'ils lisent l'a sans leurs sorties. Sans siège {@code driver}, un mod le conduit : sans
     * conducteur, son frein de stationnement le tiendrait.
     */
    @GameTest(template = "test/plancher_16", timeoutTicks = 300)
    public static void uneFicheNommeSesControleurs(GameTestHelper helper) {
        Vec3 depart = new Vec3(8.0, DESSUS_DALLE, 12.5);
        ObservateursDeTest.declarer(helper, depart);
        AxionEntity entite = AssembliesDeTest.poser(helper, "axion:test/vehicule_controle", depart, IDENTITE);
        Vec3[] repere = new Vec3[1];
        double[] compte = new double[1];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(entite.hasPhysicalBounds(), "le véhicule est simulé"))
                .thenExecute(() -> {
                    VehicleControl vehicule = vehicule(entite);
                    helper.assertTrue(vehicule.controllers().equals(
                            List.of("axion:test/propulsion", "axion:test/compteur")), "ceux de la fiche, dans son"
                                    + " ordre : " + vehicule.controllers());
                    Parameters parametres = api(entite).parameters();
                    helper.assertTrue(parametres.has(ControleursDeTest.GAIN), "Q6 : le paramètre du mod existe");
                    vehicule.drive(DriverInput.NONE);
                    repere[0] = entite.position();
                    compte[0] = api(entite).variables().get("compteur");
                })
                .thenIdle(40)
                .thenExecute(() -> {
                    Vec3 parcouru = entite.position().subtract(repere[0]);
                    helper.assertTrue(parcouru.z < -1.0, "R-1082 : le couple des roues le fait avancer vers son -Z : "
                            + parcouru);
                    double maintenant = api(entite).variables().get("compteur");
                    helper.assertTrue(maintenant - compte[0] >= 39 && maintenant - compte[0] <= 41,
                            "un appel par tick, son état dans la variable : " + compte[0] + " → " + maintenant);
                    Parameters parametres = api(entite).parameters();
                    helper.assertTrue(parametres.value(ControleursDeTest.GAIN) == 2.0,
                            "le gain réglé pour ce tick : " + parametres.value(ControleursDeTest.GAIN));
                    VehicleView etat = vehicule(entite).state();
                    helper.assertTrue(etat.parameter(ControleursDeTest.GAIN) == 1.0,
                            "l'état lu est composé sans les sorties des contrôleurs");
                    helper.assertTrue(etat.wheels().size() == 4 && etat.speed() > 0.5,
                            "quatre roues, une vitesse vers l'avant : " + etat.speed());
                    helper.assertTrue(etat.wheels().get(0).node().equals("roue_avant_gauche"), "dans l'ordre de la"
                            + " fiche");
                    helper.assertTrue(parametres.modifiers(ControleursDeTest.GAIN).stream().anyMatch(applied ->
                            applied.origin().equals(AssemblyParameters.CONTROLLER) && applied.active()
                                    && applied.modifier().cause().equals("axion:test/compteur")),
                            "la sortie se lit, au nom du contrôleur");
                    List<ControllerRunner.Cost> couts = AxionEntities.controllerCosts();
                    helper.assertTrue(couts.stream().anyMatch(cout -> cout.id().equals("axion:test/propulsion")
                            && cout.calls() >= 40 && !cout.disabled()), "R-1770 : son coût est mesuré : " + couts);
                    // La mesure en jeu, appels à froid compris, qui fixe avec le banc les défauts de [vehicle].
                    couts.forEach(cout -> LOGGER.info("AXION : coût mesuré du contrôleur {} : {} appels, moyenne {} ns,"
                            + " pire {} ns, dernier {} ns", cout.id(), cout.calls(), Math.round(cout.meanNanos()),
                            cout.worstNanos(), cout.lastNanos()));
                })
                .thenSucceed();
    }

    /**
     * D9, Q6 : l'API attache un contrôleur à une instance, les paramètres de son mod y apparaissent ; retiré, ils
     * disparaissent, et un modificateur posé dessus reste gardé inactif (CAP-38). Les refus se nomment.
     */
    @GameTest(template = "test/plancher", timeoutTicks = 200)
    public static void lApiAttacheEtRetire(GameTestHelper helper) {
        ObservateursDeTest.declarer(helper, new Vec3(4.0, DESSUS_DALLE, 4.0));
        AxionEntity entite = AssembliesDeTest.poser(helper, "axion:test/vehicule", new Vec3(4.5, DESSUS_DALLE, 4.5),
                IDENTITE);
        AxionEntity cube = AssembliesDeTest.poser(helper, "axion:test_cube", new Vec3(1.5, DESSUS_DALLE + 2, 1.5),
                IDENTITE);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(entite.hasPhysicalBounds(), "le véhicule est simulé"))
                .thenExecute(() -> {
                    helper.assertTrue(api(cube).vehicle().isEmpty(), "un cube n'est pas un véhicule");
                    VehicleControl vehicule = vehicule(entite);
                    Parameters parametres = api(entite).parameters();
                    helper.assertTrue(vehicule.controllers().isEmpty(), "sa fiche n'en nomme pas");
                    helper.assertTrue(!parametres.has(ControleursDeTest.GAIN), "sans contrôleur du mod, pas de gain");
                    vehicule.attach("axion:test/compteur");
                    helper.assertTrue(parametres.has(ControleursDeTest.GAIN), "Q6 : attaché, le gain existe");
                    parametres.modify(new Modifier("essai:reglage", ControleursDeTest.GAIN, Modifier.Operation.ADD,
                            3));
                    refus(helper, () -> vehicule.attach("axion:test/compteur"), "déjà attaché");
                    refus(helper, () -> vehicule.attach("inconnu:rien"), "aucun mod");
                })
                .thenIdle(2)
                .thenExecute(() -> {
                    Parameters parametres = api(entite).parameters();
                    helper.assertTrue(parametres.value(ControleursDeTest.GAIN) == 5.0,
                            "1 + 3 posé + 1 du contrôleur : " + parametres.value(ControleursDeTest.GAIN));
                    helper.assertTrue(vehicule(entite).detach("axion:test/compteur"), "retiré");
                    helper.assertTrue(!vehicule(entite).detach("axion:test/compteur"), "plus attaché");
                    helper.assertTrue(!parametres.has(ControleursDeTest.GAIN), "retiré, le gain disparaît");
                    helper.assertTrue(parametres.modifiers().stream().anyMatch(applied ->
                            applied.modifier().cause().equals("essai:reglage") && !applied.active()),
                            "CAP-38 : le modificateur posé reste, inactif : " + parametres.modifiers());
                })
                .thenSucceed();
    }

    private static void refus(GameTestHelper helper, Runnable appel, String attendu) {
        try {
            appel.run();
        } catch (IllegalArgumentException refuse) {
            helper.assertTrue(refuse.getMessage().contains(attendu), "refus nommé : " + refuse.getMessage());
            return;
        }
        helper.fail("refus attendu : " + attendu);
    }

    /**
     * D9 : une sortie qui ferait sortir un paramètre de ses bornes est écartée pour ce tick, le paramètre garde sa
     * valeur ; un contrôleur qui lève à chaque appel est désactivé pour la session ({@code E-1011}), et le véhicule
     * roule sans ses sorties.
     */
    @GameTest(template = "test/plancher", timeoutTicks = 200)
    public static void lesFautesDUnControleur(GameTestHelper helper) {
        ObservateursDeTest.declarer(helper, new Vec3(4.0, DESSUS_DALLE, 4.0));
        AxionEntity entite = AssembliesDeTest.poser(helper, "axion:test/vehicule", new Vec3(4.5, DESSUS_DALLE, 4.5),
                IDENTITE);
        double[] couple = new double[1];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(entite.hasPhysicalBounds(), "le véhicule est simulé"))
                .thenExecute(() -> {
                    couple[0] = api(entite).parameters().value(Parameters.ENGINE_TORQUE);
                    vehicule(entite).attach("axion:test/excessif");
                    vehicule(entite).attach("axion:test/casse");
                })
                .thenIdle(5)
                .thenExecute(() -> {
                    Parameters parametres = api(entite).parameters();
                    helper.assertTrue(parametres.value(Parameters.ENGINE_TORQUE) == couple[0],
                            "la sortie hors bornes est écartée : " + parametres.value(Parameters.ENGINE_TORQUE));
                    helper.assertTrue(parametres.modifiers(Parameters.ENGINE_TORQUE).stream().anyMatch(applied ->
                            applied.modifier().cause().equals("axion:test/excessif") && !applied.active()
                                    && applied.reason() != null), "écartée, avec sa raison");
                })
                .thenWaitUntil(() -> helper.assertTrue(AxionEntities.controllerCosts().stream().anyMatch(cout ->
                        cout.id().equals("axion:test/casse") && cout.disabled()
                                && cout.reason().contains("cassé exprès")), "E-1011 : désactivé au nombre de fautes"))
                .thenExecute(() -> helper.assertTrue(AxionEntities.controllerCosts().stream().noneMatch(cout ->
                        cout.id().equals("axion:test/excessif") && cout.disabled()),
                        "une sortie écartée n'est pas une faute"))
                .thenSucceed();
    }

    /**
     * D9, ADR-159 D11 : un mod conduit un véhicule que personne ne conduit ; un joueur au volant reprend la main, et un
     * mod qui voudrait conduire à sa place est refusé en le nommant.
     */
    @GameTest(template = "test/plancher_16", timeoutTicks = 300)
    public static void unModConduit(GameTestHelper helper) {
        Vec3 depart = new Vec3(8.0, DESSUS_DALLE, 12.5);
        ObservateursDeTest.declarer(helper, depart);
        AxionEntity entite = AssembliesDeTest.poser(helper, "axion:test/vehicule", depart, IDENTITE);
        ServerPlayer conducteur = figurant(helper, "pilote_mod");
        Vec3[] repere = new Vec3[1];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(entite.hasPhysicalBounds(), "le véhicule est simulé"))
                .thenIdle(20)
                .thenExecute(() -> {
                    vehicule(entite).drive(new DriverInput(1, 0, 0, 0, 0, 0));
                    repere[0] = entite.position();
                })
                .thenIdle(30)
                .thenExecute(() -> {
                    Vec3 parcouru = entite.position().subtract(repere[0]);
                    helper.assertTrue(parcouru.z < -1.0, "le mod le fait avancer vers son -Z : " + parcouru);
                    helper.assertTrue(vehicule(entite).state().input().throttle() == 1.0, "l'état lit son entrée");
                    helper.assertTrue(entite.reseat(conducteur, "conducteur"), "un joueur prend le volant");
                })
                .thenIdle(2)
                .thenExecute(() -> {
                    try {
                        vehicule(entite).drive(DriverInput.NONE);
                        helper.fail("un mod ne conduit pas à la place d'un joueur");
                    } catch (IllegalStateException refuse) {
                        helper.assertTrue(refuse.getMessage().contains("pilote_mod"), "le refus le nomme : "
                                + refuse.getMessage());
                    }
                    conducteur.stopRiding();
                    repere[0] = entite.position();
                })
                .thenIdle(80)
                .thenExecute(() -> {
                    Vec3 avant = entite.position();
                    helper.assertTrue(vehicule(entite).state().input().equals(DriverInput.NONE),
                            "le joueur sorti, l'entrée du mod n'est pas revenue");
                    repere[0] = avant;
                })
                .thenIdle(20)
                .thenExecute(() -> helper.assertTrue(entite.position().distanceTo(repere[0]) < 0.02,
                        "Q3 : sans conducteur, frein de stationnement serré, il est arrêté"))
                .thenSucceed();
    }
}
