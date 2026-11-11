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
}
