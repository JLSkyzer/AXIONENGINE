package dev.axion.forge;

import com.mojang.authlib.GameProfile;
import dev.axion.forge.net.JoueurSimule;
import dev.axion.net.AssemblyState;
import dev.axion.net.Fragments;
import dev.axion.net.InputMessage;
import dev.axion.seat.VehicleInput;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * ADR-159, tranche 2 : un véhicule sur de vrais blocs (T-320, T-326, D3) ; tranche 3 : il se conduit par les
 * entrées de son conducteur.
 *
 * <p>La definition {@code test/vehicule} a pour origine le contact des roues au repos (§6.5) : posé,
 * ressorts comprimés de leur charge, son origine est au niveau du sol. Son avant est son -Z.
 */
@GameTestHolder("axion")
@PrefixGameTestTemplate(false)
public final class VehiculesGameTests {

    /** Le dessus de la dalle du gabarit {@code plancher}, en coordonnées du test. */
    private static final double DESSUS_DALLE = 2.0;

    private static final float[] IDENTITE = {0f, 0f, 0f, 1f};

    private static final Vec3 CENTRE = new Vec3(4.5, DESSUS_DALLE, 4.5);

    private VehiculesGameTests() {}

    private static AxionEntity poser(GameTestHelper helper, double hauteur) {
        ObservateursDeTest.declarer(helper, new Vec3(4.0, DESSUS_DALLE, 4.0));
        return AssembliesDeTest.poser(helper, "axion:test/vehicule", CENTRE.add(0, hauteur, 0), IDENTITE);
    }

    private static double hauteur(GameTestHelper helper, AxionEntity assembly) {
        return helper.relativeVec(assembly.position()).y - DESSUS_DALLE;
    }

    /** T-320 : sur de vrais blocs, il repose à sa compression d'équilibre, sans s'enfoncer ni dériver. */
    @GameTest(template = "test/plancher", timeoutTicks = 200)
    public static void t320ReposeSurSesRoues(GameTestHelper helper) {
        AxionEntity assembly = poser(helper, 0.1);
        helper.startSequence()
                .thenIdle(120)
                .thenExecute(() -> {
                    double h = hauteur(helper, assembly);
                    helper.assertTrue(Math.abs(h) < 0.03, "hauteur " + h + " : le modèle doit être posé au sol");
                    Vec3 ici = helper.relativeVec(assembly.position());
                    helper.assertTrue(Math.abs(ici.x - CENTRE.x) < 0.02 && Math.abs(ici.z - CENTRE.z) < 0.02,
                            "dérive : " + ici);
                })
                .thenSucceed();
    }

    /**
     * ADR-159 D9 : au repos, la compression publiée de chaque roue est nulle : la roue est où le modèle la
     * dessine.
     */
    @GameTest(template = "test/plancher", timeoutTicks = 200)
    public static void auReposLaCompressionPublieeEstNulle(GameTestHelper helper) {
        AxionEntity assembly = poser(helper, 0.1);
        helper.startSequence()
                .thenIdle(120)
                .thenExecute(() -> {
                    AssemblyState.Wheels roues = assembly.netState().wheels();
                    helper.assertTrue(roues.count() == 4, "roues publiées : " + roues.count());
                    for (int roue = 0; roue < roues.count(); roue++) {
                        helper.assertTrue(Math.abs(roues.compression(roue)) < 0.08,
                                "compression publiée de la roue " + roue + " : " + roues.compression(roue));
                        helper.assertTrue(roues.onGround(roue), "roue " + roue + " en l'air");
                    }
                })
                .thenSucceed();
    }

    /** T-326 : une chute de dix blocs finit posée sur ses roues, sans rebond ni vitesse folle. */
    @GameTest(template = "test/plancher", timeoutTicks = 400)
    public static void t326ChuteDeDixBlocs(GameTestHelper helper) {
        AxionEntity assembly = poser(helper, 10.0);
        double[] plusBasEnsuite = {Double.MAX_VALUE};
        boolean[] touche = {false};
        helper.onEachTick(() -> {
            double h = hauteur(helper, assembly);
            helper.assertTrue(Double.isFinite(h) && h <= 10.5, "hauteur " + h);
            if (h < 1.0) {
                touche[0] = true;
            }
            if (touche[0]) {
                plusBasEnsuite[0] = Math.min(plusBasEnsuite[0], h);
                helper.assertTrue(h < 3.0, "rebond à " + h);
            }
        });
        helper.startSequence()
                .thenIdle(260)
                .thenExecute(() -> {
                    double h = hauteur(helper, assembly);
                    helper.assertTrue(touche[0], "jamais arrivé au sol");
                    helper.assertTrue(Math.abs(h) < 0.05, "fin à " + h);
                    helper.assertTrue(plusBasEnsuite[0] > -0.6, "écrasé à " + plusBasEnsuite[0]);
                })
                .thenSucceed();
    }

    /**
     * ADR-159 D3 : un véhicule endormi ne touche pas le sol par son châssis ; les blocs qui disparaissent
     * sous ses roues le réveillent quand même, et il tombe.
     */
    /** Un joueur factice de Forge, hors de la liste des joueurs : assis, il est tické par son véhicule. */
    private static ServerPlayer figurant(GameTestHelper helper, String nom) {
        UUID uuid = UUID.nameUUIDFromBytes(("axion-vehicule:" + nom).getBytes(StandardCharsets.UTF_8));
        ServerPlayer joueur = FakePlayerFactory.get(helper.getLevel(), new GameProfile(uuid, nom));
        joueur.stopRiding();
        joueur.getPersistentData().remove(AxionEntity.REMEMBERED_SEAT);
        return joueur;
    }

    /**
     * ADR-159, tranche 3, par le vrai canal des entrées (C2S_Input, comme {@code SiegesGameTests}) : un passager qui
     * accélère n'a aucun effet, un conducteur assis sans rien commander laisse le véhicule libre ; au volant,
     * l'accélérateur le fait avancer vers son -Z ; les entrées arrêtées et le siège libéré, le frein de
     * stationnement l'arrête (D8, Q3).
     */
    @GameTest(template = "test/plancher_16", timeoutTicks = 500)
    public static void auVolantLAccelerateurLeFaitAvancerEtSansConducteurIlSArrete(GameTestHelper helper) {
        Vec3 depart = new Vec3(8.0, DESSUS_DALLE, 12.5);
        ObservateursDeTest.declarer(helper, depart);
        AxionEntity vehicule = AssembliesDeTest.poser(helper, "axion:test/vehicule", depart, IDENTITE);
        // Un nom à lui : un joueur simulé du même nom, dans un test voisin, aurait le même UUID, et Minecraft
        // déconnecterait l'un des deux.
        JoueurSimule simule = JoueurSimule.rejoindre(
                helper.getLevel(), "volant_vehicule", helper.absoluteVec(new Vec3(2.0, DESSUS_DALLE, 12.5)));
        ServerPlayer conducteur = figurant(helper, "conducteur");
        VehicleInput plein = new VehicleInput(1f, 0f, 0f, 0f, 0f, (byte) 0, 0);
        // L'entrée envoyée à chaque tick, et sa séquence ; aucune tant qu'elle est nulle.
        VehicleInput[] envoyee = {null};
        int[] sequence = {0};
        helper.onEachTick(() -> {
            simule.tick();
            int siege = vehicule.seatOf(simule.joueur());
            if (envoyee[0] != null && siege >= 0) {
                sequence[0]++;
                simule.envoyer(Fragments.split(
                        new InputMessage(sequence[0], vehicule.getId(), siege, envoyee[0]).encode(), 0).get(0));
            }
        });
        Vec3[] repere = new Vec3[1];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(vehicule.hasPhysicalBounds(), "le véhicule est simulé"))
                .thenIdle(30)
                .thenExecute(() -> {
                    helper.assertTrue(vehicule.reseat(conducteur, "conducteur"), "un conducteur, sans entrée");
                    helper.assertTrue(vehicule.reseat(simule.joueur(), "passager"), "le joueur, passager");
                    envoyee[0] = plein;
                    repere[0] = vehicule.position();
                })
                .thenIdle(30)
                .thenExecute(() -> {
                    helper.assertTrue(vehicule.seatInput(1).throttle() == 1f,
                            "l'accélérateur du passager est reçu : " + vehicule.seatInput(1));
                    double ecart = vehicule.position().distanceTo(repere[0]);
                    helper.assertTrue(ecart < 0.05, "D8 : un passager ne conduit pas ; déplacement " + ecart);
                    envoyee[0] = null;
                    conducteur.stopRiding();
                    simule.joueur().stopRiding();
                    helper.assertTrue(vehicule.reseat(simule.joueur(), "conducteur"), "le joueur, au volant");
                    envoyee[0] = plein;
                    repere[0] = vehicule.position();
                })
                .thenIdle(30)
                .thenExecute(() -> {
                    Vec3 parcouru = vehicule.position().subtract(repere[0]);
                    helper.assertTrue(parcouru.z < -1.0, "il avance vers son -Z : " + parcouru);
                    helper.assertTrue(Math.abs(parcouru.x) < 0.2 * -parcouru.z, "tout droit : " + parcouru);
                    envoyee[0] = null;
                    simule.joueur().stopRiding();
                    repere[0] = vehicule.position();
                })
                .thenIdle(60)
                .thenExecute(() -> {
                    double glisse = vehicule.position().distanceTo(repere[0]);
                    helper.assertTrue(glisse < 4.0, "freiné après " + glisse + " bloc(s)");
                    repere[0] = vehicule.position();
                })
                .thenIdle(20)
                .thenExecute(() -> {
                    double encore = vehicule.position().distanceTo(repere[0]);
                    helper.assertTrue(encore < 0.02, "Q3 : sans conducteur, il est arrêté ; encore " + encore);
                    simule.quitter();
                })
                .thenSucceed();
    }

    /**
     * ADR-159 D3 : les blocs retirés sous un véhicule endormi le font tomber — son châssis ne touche pas le sol, Rapier
     * ne le réveillerait pas.
     *
     * <p>« Un véhicule au repos dort » (D3), sans délai fixé : le test attend qu'il dorme, dans son délai, puis retire
     * le plancher au tick même. Toute tuile qui change à portée de ses roues le réveille, et la sienne est une section
     * 16³ que partagent les gabarits voisins du lot, où Minecraft pose une balise au chargement et à la fin de chaque
     * test : jugé à un tick fixe, il était parfois réveillé par un voisin, à quelques ticks de se rendormir.
     */
    @GameTest(template = "test/plancher", timeoutTicks = 400)
    public static void lesBlocsRetiresSousUnVehiculeEndormiLeFontTomber(GameTestHelper helper) {
        AxionEntity assembly = poser(helper, 0.1);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(assembly.netSleeping(), "il devrait dormir"))
                .thenExecute(() -> {
                    for (int x = 0; x < 9; x++) {
                        for (int z = 0; z < 9; z++) {
                            helper.setBlock(new BlockPos(x, 1, z), Blocks.AIR);
                        }
                    }
                })
                .thenWaitUntil(() -> helper.assertTrue(hauteur(helper, assembly) < -0.6,
                        "toujours suspendu : " + hauteur(helper, assembly)))
                .thenSucceed();
    }

    /**
     * ADR-176 D1 : un moteur à démarreur ne donne rien tant que son contact n'est pas mis ; le contact mis, le
     * démarreur tourne puis le moteur emporte le véhicule ; le contact coupé, il s'arrête. Le contact est la variable
     * que la definition nomme, écrite ici par l'API, comme la commande d'un siège l'écrirait.
     */
    @GameTest(template = "test/plancher", timeoutTicks = 500)
    public static void adr176_UnMoteurADemarreurAttendLeContactPuisTourneEtSArrete(GameTestHelper helper) {
        ObservateursDeTest.declarer(helper, new Vec3(4.0, DESSUS_DALLE, 4.0));
        AxionEntity vehicule = AssembliesDeTest.poser(helper, "axion:test/vehicule_demarreur",
                new Vec3(4.5, DESSUS_DALLE + 0.6, 4.5), new float[] {0f, 0f, 0f, 1f});
        Vec3[] depart = {null};
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(vehicule.hasPhysicalBounds() && vehicule.ignitionVariable() >= 0,
                        "le véhicule est simulé, son contact est une variable"))
                .thenIdle(20)
                .thenExecute(() -> {
                    depart[0] = vehicule.position();
                    vehicule.drive(new dev.axion.api.vehicle.DriverInput(1.0, 0, 0, 0, 0, 1));
                })
                .thenIdle(60)
                .thenExecute(() -> {
                    helper.assertTrue(vehicule.position().distanceTo(depart[0]) < 0.3,
                            "plein gaz, contact coupé : il n'avance pas (" + vehicule.position().distanceTo(depart[0]) + ")");
                    helper.assertTrue(vehicule.sources().applyAsDouble("engine.running") == 0.0, "le moteur est arrêté");
                    vehicule.variables().set(vehicule.ignitionVariable(), 1.0, "test");
                })
                .thenIdle(80)
                .thenExecute(() -> {
                    helper.assertTrue(vehicule.position().distanceTo(depart[0]) > 1.0,
                            "contact mis : il roule (" + vehicule.position().distanceTo(depart[0]) + ")");
                    helper.assertTrue(vehicule.sources().applyAsDouble("engine.running") == 1.0, "le moteur tourne");
                    vehicule.variables().set(vehicule.ignitionVariable(), 0.0, "test");
                })
                .thenWaitUntil(() -> helper.assertTrue(vehicule.sources().applyAsDouble("engine.running") == 0.0,
                        "contact coupé : le moteur s'arrête"))
                .thenSucceed();
    }

    /**
     * ADR-176 D3 : l'énergie d'une definition arrive au natif (section du véhicule de {@code DEFN}) : le réservoir est
     * plein à la création, la batterie aussi, et un moteur qui tourne brûle du carburant, que le rapport du natif
     * rend en litres.
     */
    @GameTest(template = "test/plancher", timeoutTicks = 400)
    public static void adr176_LeNatifConnaitLEnergieDeLaDefinitionEtLaBrule(GameTestHelper helper) {
        ObservateursDeTest.declarer(helper, new Vec3(4.0, DESSUS_DALLE, 4.0));
        AxionEntity vehicule = AssembliesDeTest.poser(helper, "axion:test/vehicule_reservoir",
                new Vec3(4.5, DESSUS_DALLE + 0.6, 4.5), new float[] {0f, 0f, 0f, 1f});
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(vehicule.hasPhysicalBounds() && vehicule.ignitionVariable() >= 0,
                        "le véhicule est simulé"))
                .thenExecute(() -> {
                    vehicule.variables().set(vehicule.ignitionVariable(), 1.0, "test");
                    vehicule.drive(new dev.axion.api.vehicle.DriverInput(0.5, 0, 0, 0, 0, 1));
                })
                .thenWaitUntil(() -> helper.assertTrue(vehicule.vehicleReport() != null
                        && vehicule.vehicleReport().fuel() > 0f && vehicule.vehicleReport().fuel() < 40f
                        && vehicule.vehicleReport().battery() > 0.5f && vehicule.vehicleReport().battery() <= 1f,
                        "le natif a brûlé du carburant sur un réservoir de 40 litres : " + vehicule.vehicleReport()))
                .thenSucceed();
    }

    /**
     * ADR-176 D2, D3 : un réservoir plein pèse sa masse à sec plus celle de son carburant — la cause {@code axion:energy}
     * du paramètre {@code mass}, d'origine « moteur », qui ne se sauvegarde pas — et cette masse est celle du corps natif.
     */
    @GameTest(template = "test/plancher", timeoutTicks = 200)
    public static void adr176_LeCarburantPeseSurLaMasseDeLAssembly(GameTestHelper helper) {
        ObservateursDeTest.declarer(helper, new Vec3(4.0, DESSUS_DALLE, 4.0));
        AxionEntity vehicule = AssembliesDeTest.poser(helper, "axion:test/vehicule_reservoir",
                new Vec3(4.5, DESSUS_DALLE + 0.6, 4.5), new float[] {0f, 0f, 0f, 1f});
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(vehicule.hasPhysicalBounds() && vehicule.parameters() != null,
                        "le véhicule est simulé"))
                .thenExecute(() -> {
                    dev.axion.entity.AssemblyParameters table = vehicule.parameters();
                    int masse = table.tuning().indexOf(dev.axion.api.assembly.Parameters.MASS);
                    helper.assertTrue(masse >= 0, "la definition déclare physics.mass");
                    helper.assertTrue(Math.abs(table.value(masse) - (340.0 + 30.0)) < 1e-6,
                            "340 kg à sec + 40 l × 0,75 : " + table.value(masse));
                    helper.assertTrue(table.applied().stream().anyMatch(a -> a.modifier().cause().equals("axion:energy")
                            && a.origin().equals(dev.axion.entity.AssemblyParameters.ENGINE) && a.active()),
                            "la cause du moteur se voit");
                    helper.assertTrue(table.modifiers().isEmpty(), "et ne se sauvegarde pas");
                    helper.assertTrue(vehicule.fuel() == 40.0 && vehicule.battery() == 1.0, "plein à la création");
                })
                .thenSucceed();
    }

    /**
     * ADR-176 D3 : le plein par l'API ({@code Assembly.energy().refuel}) et par l'action de contenu {@code refuel} : plein,
     * un réservoir n'accepte rien ; après avoir brûlé, il se remplit jusqu'à sa capacité, et le natif reçoit le plein
     * sans qu'on touche au véhicule.
     */
    @GameTest(template = "test/plancher", timeoutTicks = 500)
    public static void adr176_LeCarburantSeVerseParLAPiEtParUneAction(GameTestHelper helper) {
        ObservateursDeTest.declarer(helper, new Vec3(4.0, DESSUS_DALLE, 4.0));
        AxionEntity vehicule = AssembliesDeTest.poser(helper, "axion:test/vehicule_reservoir",
                new Vec3(4.5, DESSUS_DALLE + 0.6, 4.5), new float[] {0f, 0f, 0f, 1f});
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(vehicule.hasPhysicalBounds() && vehicule.ignitionVariable() >= 0,
                        "le véhicule est simulé"))
                .thenExecute(() -> {
                    helper.assertTrue(vehicule.refuel(10.0) == 0.0, "plein, il n'accepte rien");
                    helper.assertTrue(vehicule.fuel() == 40.0, "toujours 40 l");
                    vehicule.variables().set(vehicule.ignitionVariable(), 1.0, "test");
                    vehicule.drive(new dev.axion.api.vehicle.DriverInput(0.5, 0, 0, 0, 0, 1));
                })
                .thenWaitUntil(() -> helper.assertTrue(vehicule.fuel() < 39.9995, "il a brûlé : " + vehicule.fuel()))
                .thenExecute(() -> {
                    double manque = 40.0 - vehicule.fuel();
                    double entre = vehicule.refuel(100.0);
                    helper.assertTrue(Math.abs(entre - manque) < 1e-9, "il n'entre que ce qui manque : " + entre);
                    helper.assertTrue(vehicule.fuel() == 40.0, "plein : " + vehicule.fuel());
                    helper.assertTrue(vehicule.refuel(0.0) == 0.0, "zéro litre, zéro entré");
                })
                .thenWaitUntil(() -> helper.assertTrue(vehicule.vehicleReport() != null
                        && vehicule.vehicleReport().fuel() > 39.99f,
                        "le natif a reçu le plein : " + vehicule.vehicleReport()))
                .thenExecute(() -> {
                    // L'action de contenu verse comme l'API.
                    java.util.List<dev.axion.definition.Seating.NodeAction> plein = java.util.List.of(
                            dev.axion.definition.Seating.NodeAction.refuel(7.0));
                    double avant = vehicule.fuel();
                    helper.assertTrue(avant > 39.0, "il a un peu brûlé depuis : " + avant);
                    vehicule.runActions(plein, "test");
                    helper.assertTrue(vehicule.fuel() == 40.0, "le plein de l'action : " + vehicule.fuel());
                })
                .thenSucceed();
    }

    /** ADR-176 D3 : {@code /axion energy} voit l'énergie, verse sans dépasser la capacité, et pose la batterie. */
    @GameTest(template = "test/plancher", timeoutTicks = 200)
    public static void adr176_LaCommandeEnergyVoitVerseEtPose(GameTestHelper helper) {
        ObservateursDeTest.declarer(helper, new Vec3(4.0, DESSUS_DALLE, 4.0));
        AxionEntity vehicule = AssembliesDeTest.poser(helper, "axion:test/vehicule_reservoir",
                new Vec3(4.5, DESSUS_DALLE + 0.6, 4.5), new float[] {0f, 0f, 0f, 1f});
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(vehicule.hasPhysicalBounds() && vehicule.ignitionVariable() >= 0,
                        "le véhicule est simulé"))
                .thenExecute(() -> {
                    net.minecraft.server.MinecraftServer serveur = helper.getLevel().getServer();
                    String cible = "axion energy " + vehicule.getUUID();
                    String vu = String.join(" | ", ParametresGameTests.commande(serveur, cible));
                    helper.assertTrue(vu.contains("carburant : 40.000 / 40.000 l (100.0 %)")
                            && vu.contains("masse du carburant : 30.00 kg") && vu.contains("batterie : 1.000 / 1.000"),
                            "ce que la commande voit : " + vu);
                    String pose = String.join(" | ", ParametresGameTests.commande(serveur, cible + " battery 0.25"));
                    helper.assertTrue(vehicule.battery() == 0.25 && pose.contains("batterie : 0.250 / 1.000"),
                            "la batterie posée : " + vehicule.battery() + " ; " + pose);
                    String verse = String.join(" | ", ParametresGameTests.commande(serveur, cible + " refuel 100"));
                    helper.assertTrue(vehicule.fuel() == 40.0 && verse.contains("40.000 / 40.000 l"),
                            "jamais plus que la capacité : " + vehicule.fuel() + " ; " + verse);
                    vehicule.discard();
                })
                .thenSucceed();
    }

    /**
     * ADR-176 D4 : le profil de pneu d'une roue arrive au natif ({@code wear.rate} de {@code axion:tire/test/usable}) : plein
     * gaz de l'arrêt, les roues motrices patinent et s'usent, plus que les roues avant, qui roulent ; le rapport du natif
     * en porte l'usure.
     */
    @GameTest(template = "test/plancher", timeoutTicks = 400)
    public static void adr176_LePneuQuiPatineSUse(GameTestHelper helper) {
        ObservateursDeTest.declarer(helper, new Vec3(4.0, DESSUS_DALLE, 4.0));
        AxionEntity vehicule = AssembliesDeTest.poser(helper, "axion:test/vehicule_pneus",
                new Vec3(4.5, DESSUS_DALLE + 0.6, 4.5), new float[] {0f, 0f, 0f, 1f});
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(vehicule.hasPhysicalBounds() && vehicule.wheelNodes().size() == 4,
                        "le véhicule est simulé"))
                .thenExecute(() -> vehicule.drive(new dev.axion.api.vehicle.DriverInput(1.0, 0, 0, 0, 0, 1)))
                .thenWaitUntil(() -> helper.assertTrue(vehicule.wheelWear(2) > 0.001f && vehicule.wheelWear(3) > 0.001f,
                        "les roues motrices s'usent : " + vehicule.wheelWear(2) + ", " + vehicule.wheelWear(3)))
                .thenExecute(() -> {
                    helper.assertTrue(vehicule.wheelWear(2) > vehicule.wheelWear(0),
                            "plus que les roues avant, qui roulent : " + vehicule.wheelWear(2) + " contre "
                                    + vehicule.wheelWear(0));
                    helper.assertTrue(vehicule.wheelWear(2) <= 1f, "bornée à 1");
                    vehicule.drive(dev.axion.api.vehicle.DriverInput.NONE);
                })
                .thenSucceed();
    }

    /**
     * ADR-176 D4 : un pneu usé se change — par l'API ({@code setWear}), par l'action de contenu {@code set_wear} et par
     * {@code /axion wear} —, et le natif reçoit le changement sans que le véhicule soit réveillé.
     */
    @GameTest(template = "test/plancher", timeoutTicks = 600)
    public static void adr176_UnPneuUseSeChangeParLAPiLActionEtLaCommande(GameTestHelper helper) {
        ObservateursDeTest.declarer(helper, new Vec3(4.0, DESSUS_DALLE, 4.0));
        AxionEntity vehicule = AssembliesDeTest.poser(helper, "axion:test/vehicule_pneus",
                new Vec3(4.5, DESSUS_DALLE + 0.6, 4.5), new float[] {0f, 0f, 0f, 1f});
        dev.axion.api.assembly.Assembly api = dev.axion.api.AxionApi.get().assemblies().byEntity(vehicule).orElseThrow();
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(vehicule.hasPhysicalBounds() && vehicule.wheelNodes().size() == 4,
                        "le véhicule est simulé"))
                .thenExecute(() -> vehicule.drive(new dev.axion.api.vehicle.DriverInput(1.0, 0, 0, 0, 0, 1)))
                .thenWaitUntil(() -> helper.assertTrue(vehicule.wheelWear(2) > 0.05f,
                        "le pneu arrière gauche s'est usé : " + vehicule.wheelWear(2)))
                .thenExecute(() -> {
                    vehicule.drive(dev.axion.api.vehicle.DriverInput.NONE);
                    // 1. L'API : un pneu neuf.
                    api.vehicle().orElseThrow().setWear("roue_arriere_gauche", 0.0);
                    helper.assertTrue(vehicule.wheelWear(2) == 0f, "l'API a changé le pneu : " + vehicule.wheelWear(2));
                    helper.assertTrue(api.vehicle().orElseThrow().state().wheels().get(2).wear() == 0.0,
                            "et la vue de l'API le dit");
                    try {
                        api.vehicle().orElseThrow().setWear("roue_inconnue", 0.5);
                        helper.fail("une roue inconnue doit être refusée");
                    } catch (IllegalArgumentException attendu) {
                        // refusée, nommée
                    }
                    // 2. L'action de contenu, sur toutes les roues.
                    vehicule.runActions(java.util.List.of(dev.axion.definition.Seating.NodeAction.setWear(null, 0.5)),
                            "test");
                    for (int roue = 0; roue < 4; roue++) {
                        helper.assertTrue(vehicule.wheelWear(roue) == 0.5f, "l'action pose 0,5 sur la roue " + roue);
                    }
                })
                .thenIdle(5)
                .thenExecute(() -> {
                    // Le natif a reçu le changement : il repart des 0,5 — le véhicule roule encore sur son élan, l'usure a
                    // pu monter depuis, jamais redescendre ni revenir à l'usure d'avant.
                    helper.assertTrue(vehicule.wheelWear(2) >= 0.5f - 0.01f,
                            "le natif tient au moins les 0,5 : " + vehicule.wheelWear(2));
                    // 3. La commande d'administration.
                    var serveur = helper.getLevel().getServer();
                    serveur.getCommands().performPrefixedCommand(serveur.createCommandSourceStack(),
                            "axion wear " + vehicule.getUUID() + " reset");
                    helper.assertTrue(vehicule.wheelWear(0) == 0f && vehicule.wheelWear(3) == 0f,
                            "/axion wear reset : des pneus neufs : " + vehicule.wheelWear(0));
                    serveur.getCommands().performPrefixedCommand(serveur.createCommandSourceStack(),
                            "axion wear " + vehicule.getUUID() + " set roue_avant_droite 0.75");
                    helper.assertTrue(Math.abs(vehicule.wheelWear(1) - 0.75f) < 1e-6f,
                            "/axion wear set : " + vehicule.wheelWear(1));
                })
                .thenSucceed();
    }

    /**
     * ADR-176 D5 : le mouillé d'un véhicule suit la pluie qu'il reçoit, lue à sa position — ciel visible, biome non froid. À
     * l'air libre il se mouille en {@code physics.wet_rise_seconds} ; sous un toit, au même instant, il reste sec ; sans
     * pluie, il sèche.
     */
    @GameTest(template = "test/plancher", timeoutTicks = 600)
    public static void adr176_LaPluieMouilleUnVehiculeALAirLibreEtPasSousUnToit(GameTestHelper helper) {
        ObservateursDeTest.declarer(helper, new Vec3(4.0, DESSUS_DALLE, 4.0));
        AxionEntity dehors = AssembliesDeTest.poser(helper, "axion:test/vehicule_pneus",
                new Vec3(2.5, DESSUS_DALLE + 0.6, 4.5), IDENTITE);
        AxionEntity dessous = AssembliesDeTest.poser(helper, "axion:test/vehicule_pneus",
                new Vec3(7.5, DESSUS_DALLE + 0.6, 4.5), IDENTITE);
        // Un toit de pierre à trois blocs au-dessus de la dalle, sur le second seulement.
        for (int x = 5; x <= 9; x++) {
            for (int z = 2; z <= 7; z++) {
                helper.setBlock(new BlockPos(x, (int) DESSUS_DALLE + 2, z), Blocks.STONE);
            }
        }
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(dehors.hasPhysicalBounds() && dessous.hasPhysicalBounds(),
                        "les deux véhicules sont simulés"))
                .thenExecute(() -> {
                    helper.assertTrue(dehors.wetness() == 0.0 && dessous.wetness() == 0.0, "secs au départ");
                    helper.getLevel().setWeatherParameters(0, 12000, true, false);
                })
                .thenWaitUntil(() -> helper.assertTrue(dehors.wetness() >= 1.0,
                        "à l'air libre il se mouille : " + dehors.wetness()))
                .thenExecute(() -> {
                    helper.assertTrue(dessous.wetness() == 0.0, "sous un toit il reste sec : " + dessous.wetness());
                    // Plus de pluie : il sèche, en physics.wet_dry_seconds (30 s) ; deux demi-minutes le vident.
                    helper.getLevel().setWeatherParameters(12000, 0, false, false);
                })
                .thenWaitUntil(() -> helper.assertTrue(!helper.getLevel().isRaining(), "la pluie s'arrête"))
                .thenExecute(() -> {
                    double avant = dehors.wetness();
                    dehors.stepWetness(15.0, 2.0, 30.0);
                    helper.assertTrue(dehors.wetness() < avant, "il sèche : " + avant + " puis " + dehors.wetness());
                    dehors.stepWetness(15.0, 2.0, 30.0);
                    helper.assertTrue(dehors.wetness() == 0.0, "tout à fait sec : " + dehors.wetness());
                })
                .thenSucceed();
    }

    /**
     * ADR-176 D5, la preuve d'effet : sous la même pluie, le véhicule à l'air libre freine plus mal que son jumeau sous un toit.
     * Les deux prennent la même vitesse (le moteur ne patine pas) ; freinés ensemble, le mouillé garde plus de vitesse au bout
     * du même temps.
     */
    @GameTest(template = "test/plancher_16", timeoutTicks = 900)
    public static void adr176_SousLaPluieOnFreineMoinsBien(GameTestHelper helper) {
        ObservateursDeTest.declarer(helper, new Vec3(8.0, DESSUS_DALLE, 8.0));
        AxionEntity dehors = AssembliesDeTest.poser(helper, "axion:test/vehicule",
                new Vec3(3.5, DESSUS_DALLE + 0.6, 14.5), IDENTITE);
        AxionEntity dessous = AssembliesDeTest.poser(helper, "axion:test/vehicule",
                new Vec3(11.5, DESSUS_DALLE + 0.6, 14.5), IDENTITE);
        // Un toit de pierre à trois blocs au-dessus de la dalle, sur la moitié du second seulement.
        for (int x = 8; x <= 14; x++) {
            for (int z = 0; z <= 15; z++) {
                helper.setBlock(new BlockPos(x, (int) DESSUS_DALLE + 2, z), Blocks.STONE);
            }
        }
        dev.axion.api.vehicle.DriverInput frein = new dev.axion.api.vehicle.DriverInput(0, 1.0, 0, 0, 0, 0);
        double[] lancee = new double[2];
        // Chacun tient 5 m/s : mouillé, le moteur patine plus longtemps, il ne faut pas comparer deux vitesses de départ.
        boolean[] tient = {false};
        helper.onEachTick(() -> {
            if (tient[0]) {
                dehors.drive(new dev.axion.api.vehicle.DriverInput(vitesse(dehors) < 5.0 ? 1.0 : 0.0, 0, 0, 0, 0, 1));
                dessous.drive(new dev.axion.api.vehicle.DriverInput(vitesse(dessous) < 5.0 ? 1.0 : 0.0, 0, 0, 0, 0, 1));
            }
        });
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(dehors.hasPhysicalBounds() && dessous.hasPhysicalBounds(),
                        "les deux véhicules sont simulés"))
                .thenExecute(() -> helper.getLevel().setWeatherParameters(0, 12000, true, false))
                .thenWaitUntil(() -> helper.assertTrue(dehors.wetness() >= 1.0 && dessous.wetness() == 0.0,
                        "l'un mouillé, l'autre sec : " + dehors.wetness() + ", " + dessous.wetness()))
                .thenExecute(() -> tient[0] = true)
                .thenIdle(60)
                .thenExecute(() -> {
                    lancee[0] = vitesse(dehors);
                    lancee[1] = vitesse(dessous);
                    helper.assertTrue(Math.abs(lancee[0] - 5.0) < 0.7 && Math.abs(lancee[1] - 5.0) < 0.7,
                            "ils tiennent 5 m/s : " + lancee[0] + ", " + lancee[1]);
                    tient[0] = false;
                    dehors.drive(frein);
                    dessous.drive(frein);
                })
                .thenIdle(8)
                .thenExecute(() -> {
                    double mouille = vitesse(dehors);
                    double sec = vitesse(dessous);
                    helper.assertTrue(mouille > sec + 0.2,
                            "sous la pluie il garde plus de vitesse après le même freinage : " + mouille + " contre " + sec);
                    dehors.drive(dev.axion.api.vehicle.DriverInput.NONE);
                    dessous.drive(dev.axion.api.vehicle.DriverInput.NONE);
                    helper.getLevel().setWeatherParameters(12000, 0, false, false);
                })
                .thenSucceed();
    }

    private static double vitesse(AxionEntity vehicule) {
        return dev.axion.api.AxionApi.get().assemblies().byEntity(vehicule).orElseThrow().vehicle().orElseThrow()
                .state().speed();
    }
}
