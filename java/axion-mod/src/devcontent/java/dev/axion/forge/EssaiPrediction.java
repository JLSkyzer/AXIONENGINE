package dev.axion.forge;

import dev.axion.AxionMod;
import dev.axion.forge.client.ClientPrediction;
import dev.axion.net.Prediction;
import dev.axion.physics.BodyState;
import dev.axion.physics.PredictedWorld;
import dev.axion.physics.SimCommandStream;
import dev.axion.seat.VehicleInput;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ADR-168, l'essai à deux processus de la prédiction (décision de Killian du 2026-11-13) : un vrai serveur dédié, un
 * vrai client qui conduit par ses touches, une latence injectée par un {@link RelaisLatence}. Inerte sans ses
 * propriétés ; le contenu de développement ne part jamais dans le mod livré.
 *
 * <ul>
 *   <li>Le serveur ({@code runServer}), sous {@code AXION_ESSAI_PREDICTION=1}, pose un sol de pierre en altitude, y
 *       amène le premier joueur, pose le véhicule de test et l'assoit au volant. Il s'arrête quand ce joueur part, ou
 *       au bout de {@link #DUREE_SERVEUR_S} secondes.</li>
 *   <li>Le client ({@code runEssaiClientPrediction}), sous {@code -Daxion.essai.prediction=<délai d'un sens, ms>},
 *       se connecte par le relais. Assis et prédit, il attend, accélère, braque, relâche, en notant à chaque tick la
 *       pose prédite et la pose interpolée. Puis il mesure au banc le rejeu de 64 ticks (ADR-168 D10), écrit son
 *       rapport ({@code rapport-prediction.txt}, {@code trajectoire-prediction.csv}) et s'arrête.</li>
 * </ul>
 */
public final class EssaiPrediction {

    private static final Logger LOGGER = LoggerFactory.getLogger("AXION essai");

    /** Vrai pour le serveur de l'essai. */
    private static final boolean SERVEUR = "1".equals(System.getenv("AXION_ESSAI_PREDICTION"));

    /** Le délai d'un sens du relais, en millisecondes, pour le client de l'essai ; -1 hors de l'essai. */
    private static final long DELAI_MS = Long.getLong("axion.essai.prediction", -1L);

    /**
     * Vrai pour la connexion seule : le client se connecte, dit en combien de tentatives, et s'arrête dès qu'il est en
     * jeu — l'échantillon du défaut ouvert de la connexion (ADR-168, précision 6c-B), en une minute et demie.
     */
    private static final boolean CONNEXION_SEULE = Boolean.getBoolean("axion.essai.connexion_seule");

    /** Le temps laissé à l'essai, au-delà duquel le serveur s'arrête. */
    static final int DUREE_SERVEUR_S = 300;

    /** L'adresse du serveur de {@code runServer}. */
    private static final String HOTE = "127.0.0.1";
    private static final int PORT = 25599;

    /** Le port du relais, chez le client. */
    private static final int PORT_RELAIS = 25600;

    /** Le sol : seize blocs de large, cent soixante-seize de long vers -Z, à cette hauteur. */
    private static final int SOL_Y = 200;
    private static final int SOL_LONGUEUR = 176;

    /** Le véhicule de l'essai : il avance vers son -Z, de l'extrémité du sol. */
    private static final String VEHICULE = System.getenv().getOrDefault("AXION_ESSAI_VEHICULE", "axion:test/vehicule");

    /**
     * La variable que le client bascule par sa commande liée, au tick {@link Client#BASCULE} (ADR-168 D6, 6d-2) : le
     * rapport dit au bout de combien de ticks la prédiction l'a vue, puis le serveur. Vide : aucune bascule.
     */
    private static final String BASCULER = System.getProperty("axion.essai.basculer", "");

    /** Le contrôleur que le serveur attache au véhicule de l'essai (ADR-168 D6, 6d-4) ; vide : aucun. */
    private static final String CONTROLEUR = System.getenv().getOrDefault("AXION_ESSAI_CONTROLEUR", "");

    /** Les rapports passés avant d'accélérer : la première d'une boîte manuelle, qui part au neutre. */
    private static final int RAPPORTS = Integer.getInteger("axion.essai.rapports", 0);

    /** La gigue du relais : de 0 à tant de millisecondes de plus par morceau, au hasard (ADR-168, 6e). */
    private static final long GIGUE_MS = Long.getLong("axion.essai.gigue", 0L);

    /** Les secondes où le conducteur reste assis après la conduite : la cadence des entrées sur la durée (6e). */
    private static final int PROLONGER_S = Integer.getInteger("axion.essai.prolonger", 0);

    /**
     * Vrai pour filmer l'essai (recette de {@code D:\BDC}, {@link Tournage}) : le véhicule prédit vu de derrière, à 60
     * images par seconde, les compteurs de la prédiction au bandeau ; {@code tools/film/encoder.py} assemble le MP4.
     */
    private static final boolean FILM = Boolean.getBoolean("axion.essai.film");

    /** La distance de l'obstacle devant le véhicule, en blocs : atteint en virant, vers le tick 127. */
    private static final double OBSTACLE = 23.0;

    /** Le premier handle des proxies du banc : loin de tout id d'entité de l'essai. */
    private static final int PROXY_BANC = 1_000_000;

    /** Rejeux mesurés au banc, après dix d'échauffement. */
    private static final int REJEUX = 100;

    private EssaiPrediction() {
        throw new AssertionError("classe utilitaire, non instanciable");
    }

    /** Le serveur : le sol, le véhicule, le conducteur ; puis l'arrêt. */
    @Mod.EventBusSubscriber(modid = AxionMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
    public static final class Serveur {

        private static long debut;
        private static ServerPlayer conducteur;
        private static AxionEntity vehicule;
        private static AxionEntity obstacle;
        private static boolean assis;

        private Serveur() {}

        /**
         * Au début de chaque tick du serveur, avant celui des connexions où partent les paquets de la poignée de main :
         * les capteurs se branchent sur toute connexion neuve (défaut ouvert de la connexion, ADR-168 précision 6c-B).
         * Son nom est à elle (voir {@link Client#conduire}).
         */
        @SubscribeEvent
        public static void capteursServeur(TickEvent.ServerTickEvent event) {
            if (!SERVEUR || event.phase != TickEvent.Phase.START || event.getServer().getConnection() == null) {
                return;
            }
            List<net.minecraft.network.Connection> connexions = event.getServer().getConnection().getConnections();
            synchronized (connexions) {
                for (net.minecraft.network.Connection connexion : connexions) {
                    dev.axion.forge.net.CapteurReseau.brancher(connexion, "serveur");
                    dev.axion.forge.net.CapteurReseau.sonder(connexion);
                }
            }
        }

        /** Chaque tick du serveur ; son nom est à elle (voir {@link Client#conduire}). */
        @SubscribeEvent
        public static void preparer(TickEvent.ServerTickEvent event) {
            if (!SERVEUR || event.phase != TickEvent.Phase.END) {
                return;
            }
            MinecraftServer serveur = event.getServer();
            long maintenant = System.nanoTime();
            if (debut == 0) {
                debut = maintenant;
            }
            if (maintenant - debut > DUREE_SERVEUR_S * 1_000_000_000L
                    || (conducteur != null && conducteur.hasDisconnected())) {
                LOGGER.info("[essai] fin du serveur d'essai de la prédiction");
                serveur.halt(false);
                return;
            }
            ServerLevel monde = serveur.overworld();
            if (conducteur == null) {
                if (serveur.getPlayerList().getPlayers().isEmpty()) {
                    return;
                }
                conducteur = serveur.getPlayerList().getPlayers().get(0);
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < SOL_LONGUEUR; z++) {
                        monde.setBlockAndUpdate(new BlockPos(x, SOL_Y, z), Blocks.STONE.defaultBlockState());
                    }
                }
                conducteur.teleportTo(monde, 8.0, SOL_Y + 1.0, SOL_LONGUEUR - 4.0, 180f, 0f);
                try {
                    vehicule = AssembliesDeTest.poser(monde, VEHICULE,
                            new Vec3(8.5, SOL_Y + 1.0, SOL_LONGUEUR - 12.0), new float[] {0f, 0f, 0f, 1f});
                } catch (IllegalStateException refus) {
                    LOGGER.error("[essai] ÉCHEC : véhicule non posé — {}", refus.getMessage());
                    serveur.halt(false);
                    return;
                }
                // ADR-168, précision 6c-C2 : un second véhicule garé sur la trajectoire, que le premier heurte en
                // virant ; au client, il est un proxy du monde prédit.
                try {
                    obstacle = AssembliesDeTest.poser(monde, VEHICULE, new Vec3(8.5, SOL_Y + 1.0,
                            SOL_LONGUEUR - 12.0 - OBSTACLE),
                            new float[] {0f, 0f, 0f, 1f});
                } catch (IllegalStateException refus) {
                    LOGGER.error("[essai] ÉCHEC : obstacle non posé — {}", refus.getMessage());
                }
                if (!CONTROLEUR.isEmpty()) {
                    vehicule.attachController(CONTROLEUR);
                    LOGGER.info("[essai] contrôleur {} attaché au véhicule", CONTROLEUR);
                }
                LOGGER.info("[essai] sol posé, véhicule {} posé pour {}, obstacle à {} blocs", VEHICULE,
                        conducteur.getGameProfile().getName(), OBSTACLE);
                return;
            }
            // Les véhicules d'un essai précédent, que le journal latéral garde et rend (22.5.2), quittent la piste : seuls
            // ceux de cet essai y roulent. Leurs colonnes se chargent quand le joueur arrive : le ménage dure tout
            // l'essai.
            for (AxionEntity ancien : monde.getEntitiesOfClass(AxionEntity.class,
                    new net.minecraft.world.phys.AABB(-32, SOL_Y - 32, -32, 48, SOL_Y + 32, SOL_LONGUEUR + 32),
                    ancien -> ancien != vehicule && ancien != obstacle)) {
                LOGGER.info("[essai] véhicule d'un essai précédent retiré de la piste : {}", ancien.getUUID());
                ancien.discard();
            }
            if (!assis && vehicule.hasPhysicalBounds() && vehicule.reseat(conducteur, "conducteur")) {
                assis = true;
                LOGGER.info("[essai] {} est au volant", conducteur.getGameProfile().getName());
            }
            if (assis && serveur.getTickCount() % 20 == 0) {
                long[] corrections = dev.axion.forge.net.EssaiReseau.corrections(conducteur.getUUID());
                int siege = vehicule.seatOf(conducteur);
                LOGGER.info(String.format(Locale.ROOT, "[essai] serveur : véhicule à (%.2f, %.2f), séquence appliquée %d,"
                        + " accélérateur %.2f, corrections parties %d (retenues %d)", vehicule.getX(), vehicule.getZ(),
                        vehicule.driverSequence(), siege < 0 ? -1f : vehicule.seatInput(siege).throttle(),
                        corrections[0], corrections[1]));
                long[] cadence = siege < 0 ? null : vehicule.seatCadence(siege);
                if (cadence != null) {
                    // ADR-168 D3, 6e : la cadence des entrées, relevée dans la file même ; la profondeur trouvée à
                    // chaque tick, de 0 à 7 et plus.
                    LOGGER.info("[essai] serveur : cadence : {} tick(s) sur une file vide, {} entrée(s) sautée(s),"
                            + " profondeurs {}", cadence[0], cadence[1],
                            java.util.Arrays.toString(java.util.Arrays.copyOfRange(cadence, 2, cadence.length)));
                }
            }
        }
    }

    /** Le client : la conduite, les relevés, le banc des rejeux, le rapport, l'arrêt. */
    @Mod.EventBusSubscriber(modid = AxionMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
    public static final class Client {

        /** Les phases de la conduite, en ticks du client. */
        private static final int ATTENTE = 40;

        /** Le tick où la commande liée à {@link EssaiPrediction#BASCULER} s'enfonce, un tick, en pleine accélération. */
        static final int BASCULE = 60;
        private static final int ACCELERE = 80;
        private static final int BRAQUE = 30;
        private static final int RELACHE = 40;

        /** Ticks laissés, une fois en jeu, pour se retrouver au volant ; au-delà, l'essai échoue et s'arrête. */
        private static final int VOLANT_MAX = 1200;

        /** Connexions tentées avant d'abandonner, quand elles expirent (défaut ouvert, voir {@link #deconnexion}). */
        private static final int TENTATIVES_MAX = 3;

        private static RelaisLatence relais;
        private static Tournage tournage;
        private static int tentatives;
        private static boolean annonce;
        private static boolean connecte;
        private static int enJeu;
        private static int tick = -1;
        private static boolean fini;
        private static final List<String> trajectoire = new ArrayList<>();
        /** L'écart de chaque correction à la prédiction (position m, rotation rad, vitesse m/s), et les sans-prédiction. */
        private static final List<double[]> ecarts = new ArrayList<>();
        private static int sansPrediction;
        /**
         * À chaque image prédite : l'heure en nanosecondes, le tick de rendu (ticks du client + fraction de l'image), la
         * pose dessinée du véhicule (x, z), et le dernier pas prédit.
         */
        private static final List<double[]> images = new ArrayList<>();
        /** Le plus de proxies posés en un tick : assemblies, entités vanilla. */
        private static int proxiesAssemblies;
        private static int proxiesEntites;
        /** Le tick où la variable basculée a changé dans la prédiction, puis dans ce que le serveur a envoyé ; -1 avant. */
        private static int basculePredite = -1;
        private static int basculeRecue = -1;
        private static double avantPredite = Double.NaN;
        private static double avantRecue = Double.NaN;
        /** Le tick où les paramètres envoyés au monde prédit ont changé après la bascule, et les rejeux d'avant elle. */
        private static int basculeParametres = -1;
        private static float[] parametresAvant;
        private static long rejeuxAvant = -1;

        /**
         * Enfonce, au tick {@link #BASCULE} et pendant un tick, la commande que le siège lie à {@link #BASCULER}, puis
         * guette le changement de la variable : prédite d'abord, reçue ensuite.
         */
        private static void basculer(AxionEntity assembly) {
            if (BASCULER.isEmpty() || assembly.declaredSeating() == null) {
                return;
            }
            dev.axion.definition.Seating seating = assembly.declaredSeating();
            int variable = seating.variables().indexOf(BASCULER);
            int seat = assembly.seatOf(Minecraft.getInstance().player);
            if (variable < 0 || seat < 0) {
                return;
            }
            int commande = -1;
            for (dev.axion.definition.Seating.Binding binding : seating.seats().get(seat).bindings()) {
                if (binding.variable() == variable) {
                    commande = binding.command();
                }
            }
            if (commande > 0 && commande <= dev.axion.forge.client.VehicleKeys.COMMANDS.size()) {
                dev.axion.forge.client.VehicleKeys.COMMANDS.get(commande - 1).setDown(tick == BASCULE);
            }
            dev.axion.entity.PredictedVariables predites = ClientPrediction.predictedVariables();
            double predite = predites == null ? Double.NaN : predites.values()[variable];
            double recue = assembly.receivedVariables().value(variable);
            float[] parametres = ClientPrediction.sentParameters();
            if (tick == BASCULE - 1) {
                avantPredite = predite;
                avantRecue = recue;
                parametresAvant = parametres;
                Prediction boucle = ClientPrediction.loop();
                rejeuxAvant = boucle == null ? -1 : boucle.replays();
            }
            if (tick >= BASCULE && basculeParametres < 0 && parametres != null
                    && !java.util.Arrays.equals(parametres, parametresAvant)) {
                basculeParametres = tick;
            }
            if (tick >= BASCULE && basculePredite < 0 && Double.compare(predite, avantPredite) != 0) {
                basculePredite = tick;
            }
            if (tick >= BASCULE && basculeRecue < 0 && Double.compare(recue, avantRecue) != 0) {
                basculeRecue = tick;
            }
        }

        /** À chaque comparaison, le plus grand écart de couple posé par les contrôleurs entre prédit et serveur. */
        private static final List<Double> ecartsCouple = new ArrayList<>();
        private static double coupleServeurMax;

        /** Le bloc du serveur de la correction précédente : l'état du début du tick de la suivante. */
        private static byte[] serveurPrecedent;
        /** Écarts entre le régulateur rejoué au client sur le bloc du serveur et le couple que le serveur a posé. */
        private static final List<Double> ecartsDecodage = new ArrayList<>();

        /**
         * ADR-168 D6 : le décodage du bloc, seul. Le régulateur ne lit que le bloc — vitesse, régime, rapport, roues — :
         * rejoué au client sur le bloc du serveur du tick précédent, il doit rendre au bit près le couple que le serveur a
         * posé. La comparaison se fait au moment où la correction suivante arrive.
         */
        private static void rejouerSurLeServeur(dev.axion.physics.AssemblyStateView.Vehicle serveur) {
            byte[] precedent = serveurPrecedent;
            serveurPrecedent = ClientPrediction.lastServerState();
            AxionEntity vehicule = ClientPrediction.vehicle();
            if (precedent == null || vehicule == null) {
                return;
            }
            dev.axion.entity.VehicleSnapshot vue = dev.axion.entity.PredictedControl.view(precedent,
                    ClientPrediction.settled(), vehicule.wheelNodes(), List.of(), new double[0],
                    vehicule.predictedParameters(new double[0]), VehicleInput.NONE);
            if (vue == null) {
                return;
            }
            float[] rejoue = new dev.axion.entity.PredictedControl(
                    new dev.axion.entity.ControllerRunner.Limits(1_000_000_000L, 3), System::nanoTime)
                    .run(List.of(new dev.axion.definition.VehicleExtensions.Controller("axion:test/regulateur", "axion",
                            ControleursDeTest.REGULATEUR)), vue).torques();
            double pire = 0;
            for (int r = 0; r < Math.min(rejoue.length, serveur.wheels().size()); r++) {
                pire = Math.max(pire, Math.abs(rejoue[r] - serveur.wheels().get(r).torque()));
            }
            ecartsDecodage.add(pire);
        }

        /** Ce que {@code VehicleControl.state()} a rendu au client, au tick 100. */
        private static String vueAuClient = "état non lu au client : aucune vue prédite";

        /** Les ticks du client depuis le début de l'essai. */
        private static long ticksClient;

        static {
            if (DELAI_MS >= 0) {
                ClientPrediction.observe((predit, serveur) -> {
                    if (predit == null) {
                        sansPrediction++;
                        return;
                    }
                    dev.axion.physics.AssemblyStateView.Vehicle vp = dev.axion.physics.AssemblyStateView.vehicle(predit);
                    dev.axion.physics.AssemblyStateView.Vehicle vs = dev.axion.physics.AssemblyStateView.vehicle(serveur);
                    if (vp != null && vs != null && vp.wheels().size() == vs.wheels().size()) {
                        double pire = 0;
                        for (int r = 0; r < vp.wheels().size(); r++) {
                            pire = Math.max(pire, Math.abs(vp.wheels().get(r).torque() - vs.wheels().get(r).torque()));
                        }
                        ecartsCouple.add(pire);
                        rejouerSurLeServeur(vs);
                        coupleServeurMax = Math.max(coupleServeurMax, vs.wheels().stream()
                                .mapToDouble(w -> Math.abs(w.torque())).max().orElse(0));
                    }
                    dev.axion.physics.AssemblyStateView.Errors e =
                            dev.axion.physics.AssemblyStateView.errors(predit, serveur);
                    if (e != null) {
                        ecarts.add(new double[] {e.position(), e.rotation(), e.velocity(), tick,
                                ClientPrediction.loop() == null ? -1 : ClientPrediction.loop().pending()});
                    }
                });
            }
        }

        private Client() {}

        /**
         * Chaque tick du client. Le nom de la méthode est à elle : EventBus 6 nomme le gestionnaire qu'il génère
         * d'après le paquet, le nom simple de la classe, la méthode et l'événement, et un {@code Client.tick} de
         * {@link EssaiAddonsExiges} y aurait pris sa place.
         */
        @SubscribeEvent
        public static void conduire(TickEvent.ClientTickEvent event) {
            if (DELAI_MS < 0 || fini || event.phase != TickEvent.Phase.START) {
                return;
            }
            ticksClient++;
            Minecraft minecraft = Minecraft.getInstance();
            if (!annonce) {
                annonce = true;
                LOGGER.info("[essai] client de la prédiction : {} ms par sens", DELAI_MS);
                if (FILM) {
                    Tournage.reglerLesOptions(minecraft);
                }
            }
            if (!connecte) {
                connecter(minecraft);
                return;
            }
            // Au volant, la conduite part : un véhicule endormi ne produit pas de correction, et seule l'entrée le
            // réveille. La trajectoire dit à chaque tick si la prédiction tourne.
            if (minecraft.player == null) {
                return;
            }
            if (CONNEXION_SEULE) {
                fini = true;
                LOGGER.info("[essai] connexion seule : en jeu en {} tentative(s)", tentatives);
                try {
                    Files.write(minecraft.gameDirectory.toPath().resolve("rapport-prediction.txt"),
                            List.of("connexion seule : en jeu en " + tentatives + " tentative(s)"), StandardCharsets.UTF_8);
                } catch (IOException faute) {
                    LOGGER.error("[essai] rapport non écrit : {}", faute.toString());
                }
                minecraft.level.disconnect();
                fermerRelais();
                minecraft.stop();
                return;
            }
            if (!(minecraft.player.getVehicle() instanceof AxionEntity assembly) || !assembly.drivenBy(minecraft.player)) {
                if (tick < 0 && ++enJeu > VOLANT_MAX) {
                    echec(minecraft, "jamais au volant en " + VOLANT_MAX / 20 + " s");
                }
                return;
            }
            tick++;
            if (FILM && tournage == null) {
                minecraft.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
                tournage = new Tournage(minecraft.gameDirectory.toPath().resolve("axion-film").resolve("images"),
                        "AXION ENGINE, C-33");
            }
            boolean avant = tick >= ATTENTE && tick < ATTENTE + ACCELERE + BRAQUE;
            boolean gauche = tick >= ATTENTE + ACCELERE && tick < ATTENTE + ACCELERE + BRAQUE;
            minecraft.options.keyUp.setDown(avant);
            minecraft.options.keyLeft.setDown(gauche);
            // Prolongé, l'essai freine après le virage : sans quoi le véhicule sort du sol, tombe, et sa culbute
            // brouille la mesure de la cadence.
            minecraft.options.keyDown.setDown(PROLONGER_S > 0 && tick >= ATTENTE + ACCELERE + BRAQUE);
            if (tick == ATTENTE - 2) {
                for (int i = 0; i < RAPPORTS; i++) {
                    net.minecraft.client.KeyMapping.click(dev.axion.forge.client.VehicleKeys.GEAR_UP.getKey());
                }
            }
            basculer(assembly);
            noter(assembly, avant, gauche);
            int[] proxies = ClientPrediction.proxyCounts();
            proxiesAssemblies = Math.max(proxiesAssemblies, proxies[0]);
            proxiesEntites = Math.max(proxiesEntites, proxies[1]);
            if (tick == 100 && assembly.predictedView() != null) {
                // ADR-168 D6 : VehicleControl.state() se lit au client pour le véhicule prédit.
                dev.axion.api.vehicle.VehicleView etat = assembly.predictedView();
                vueAuClient = String.format(Locale.ROOT, "état lu au client au tick 100 : %.2f m/s, %.0f tr/min, rapport"
                        + " %d, %d roue(s)", etat.speed(), etat.rpm(), etat.gear(), etat.wheels().size());
            }
            if (tick % 20 == 0) {
                LOGGER.info(String.format(Locale.ROOT, "[essai] client : tick %d, avant %b, forwardImpulse %.2f,"
                        + " corrections reçues %d, interpolation %b, prédit %b", tick, avant,
                        minecraft.player.input.forwardImpulse,
                        dev.axion.forge.net.EssaiReseau.correctionsRecues(minecraft.getConnection().getConnection()),
                        assembly.interpolating(), ClientPrediction.predicting()));
            }
            if (tick == ATTENTE + ACCELERE + BRAQUE + RELACHE + PROLONGER_S * 20) {
                fini = true;
                terminer(minecraft);
            }
        }

        /**
         * Chaque image, avant son rendu : les capteurs se branchent sur la connexion en cours, dès l'écran de connexion
         * (défaut ouvert de la connexion, ADR-168 précision 6c-B). Son nom est à elle (voir {@link #conduire}).
         */
        /** Chaque image rendue, pendant la conduite : une image du film, à 60 par seconde. */
        @SubscribeEvent
        public static void filmer(TickEvent.RenderTickEvent event) {
            if (!FILM || tournage == null || fini || event.phase != TickEvent.Phase.END
                    || !tournage.imageAttendue()) {
                return;
            }
            Prediction boucle = ClientPrediction.loop();
            long[] reglage = ClientPrediction.pacingAdjustments();
            String legende = String.format(Locale.ROOT, "Prédiction du véhicule piloté, %d ms par sens, gigue de 0 à %d ms",
                    DELAI_MS, GIGUE_MS);
            String valeurs = boucle == null ? "" : String.format(Locale.ROOT,
                    "latence %.0f ms   corrections égales %d, rejouées %d   cadence : %d tick(s) à deux entrées, %d à"
                            + " aucune", boucle.latencyMillis(), boucle.matches(), boucle.replays(), reglage[0],
                    reglage[1]);
            double secondes = (System.nanoTime() - tournage.debut()) / 1e9;
            tournage.capturer(Minecraft.getInstance().getMainRenderTarget(), legende, valeurs,
                    String.format(Locale.ROOT, "%.1f s", secondes));
        }

        /** Ferme le film, et écrit son bilan à côté des images, pour l'encodeur. */
        private static void fermerLeFilm(Minecraft minecraft, String motif) {
            if (tournage == null) {
                return;
            }
            String faute = tournage.fermer();
            faute = faute != null ? faute : motif;
            String bilan = String.format(Locale.ROOT, "{\"images\": %d, \"uniques\": %d, \"echec\": %s}%n",
                    tournage.images(), tournage.uniques(),
                    faute == null ? "null" : "\"" + faute.replace('"', '\'') + "\"");
            tournage = null;
            try {
                Files.writeString(minecraft.gameDirectory.toPath().resolve("axion-film").resolve("termine.json"), bilan,
                        StandardCharsets.UTF_8);
            } catch (IOException faute2) {
                LOGGER.error("[essai] bilan du film non écrit : {}", faute2.toString());
            }
        }

        @SubscribeEvent
        public static void capteursClient(TickEvent.RenderTickEvent event) {
            if (DELAI_MS < 0 || fini || event.phase != TickEvent.Phase.START) {
                return;
            }
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.screen instanceof ConnectScreen ecran) {
                for (java.lang.reflect.Field champ : ConnectScreen.class.getDeclaredFields()) {
                    if (champ.getType() == net.minecraft.network.Connection.class) {
                        try {
                            champ.setAccessible(true);
                            Object connexion = champ.get(ecran);
                            if (connexion != null) {
                                dev.axion.forge.net.CapteurReseau.brancher((net.minecraft.network.Connection) connexion,
                                        "client");
                                dev.axion.forge.net.CapteurReseau.sonder((net.minecraft.network.Connection) connexion);
                            }
                        } catch (ReflectiveOperationException faute) {
                            LOGGER.warn("[capteur] connexion de l'écran illisible : {}", faute.toString());
                        }
                    }
                }
            }
            if (minecraft.getConnection() != null) {
                dev.axion.forge.net.CapteurReseau.brancher(minecraft.getConnection().getConnection(), "client");
            }
        }

        /**
         * Chaque image, après son rendu : la pose dessinée du véhicule prédit, pour juger de sa fluidité image par
         * image (ADR-168, 6c-C). Son nom est à elle (voir {@link #conduire}).
         */
        @SubscribeEvent
        public static void image(TickEvent.RenderTickEvent event) {
            if (DELAI_MS < 0 || fini || event.phase != TickEvent.Phase.END || !ClientPrediction.predicting()) {
                return;
            }
            AxionEntity vehicule = ClientPrediction.vehicle();
            if (vehicule != null) {
                AxionEntity.RenderPose pose = vehicule.renderPose(event.renderTickTime);
                images.add(new double[] {System.nanoTime(), ticksClient + event.renderTickTime, pose.x(), pose.z(),
                    ClientPrediction.loop() == null ? -1 : ClientPrediction.loop().pending()});
            }
        }

        /**
         * Un écran de déconnexion — connexion refusée, serveur parti : l'essai échoue, le dit et s'arrête, plutôt que
         * d'attendre devant l'écran. Son nom est à elle (voir {@link #conduire}).
         */
        @SubscribeEvent
        public static void deconnexion(ScreenEvent.Opening event) {
            if (DELAI_MS < 0 || fini || !(event.getNewScreen() instanceof DisconnectedScreen ecran)) {
                return;
            }
            net.minecraft.network.chat.Component raison = EssaiAddonsExiges.Client.raison(ecran);
            String texte = ecran.getTitle().getString() + " — " + raison.getString().replace('\n', '|');
            // Le défaut ouvert de la connexion (ADR-168, précision 6c-B) : une fois sur deux environ, le client ne
            // décode rien de la poignée de main FML que le serveur dit avoir envoyée, et la connexion expire. Tant
            // qu'il n'est pas compris, l'essai se reconnecte, et son rapport dit combien de tentatives il a fallu.
            if (tick < 0 && tentatives < TENTATIVES_MAX && expiree(raison)) {
                LOGGER.warn("[essai] tentative de connexion {} expirée ({}), nouvelle tentative", tentatives, texte);
                fermerRelais();
                connecte = false;
                event.setNewScreen(new TitleScreen());
                return;
            }
            echec(Minecraft.getInstance(), "écran de déconnexion après " + tentatives + " tentative(s) : " + texte);
        }

        /** {@return vrai si la déconnexion est l'expiration de la connexion, quelle que soit la langue du client} */
        private static boolean expiree(net.minecraft.network.chat.Component raison) {
            return raison.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents cle
                    && "disconnect.timeout".equals(cle.getKey());
        }

        private static void fermerRelais() {
            if (relais != null) {
                try {
                    relais.close();
                } catch (IOException ignoree) {
                    // Le relais se ferme avec le processus.
                }
                relais = null;
            }
        }

        private static void echec(Minecraft minecraft, String raison) {
            fini = true;
            LOGGER.error("[essai] ÉCHEC de l'essai de la prédiction — {}", raison);
            try {
                Files.write(minecraft.gameDirectory.toPath().resolve("rapport-prediction.txt"),
                        List.of("ÉCHEC : " + raison), StandardCharsets.UTF_8);
            } catch (IOException faute) {
                LOGGER.error("[essai] rapport non écrit : {}", faute.toString());
            }
            fermerRelais();
            minecraft.stop();
        }

        private static void connecter(Minecraft minecraft) {
            if (minecraft.getOverlay() != null || !(minecraft.screen instanceof TitleScreen titre)) {
                return;
            }
            connecte = true;
            tentatives++;
            String adresse = HOTE + ":" + PORT;
            if (DELAI_MS > 0) {
                try {
                    relais = new RelaisLatence(PORT_RELAIS, HOTE, PORT, DELAI_MS, GIGUE_MS);
                    adresse = HOTE + ":" + PORT_RELAIS;
                } catch (IOException faute) {
                    LOGGER.error("[essai] relais de latence impossible : {}", faute.toString());
                }
            }
            LOGGER.info("[essai] connexion à {} ({} ms par sens)", adresse, DELAI_MS);
            ConnectScreen.startConnecting(titre, minecraft, ServerAddress.parseString(adresse),
                    new ServerData("essai-prediction", adresse, false), false);
        }

        /** Une ligne de la trajectoire : tick, touches, pose prédite, pose interpolée, compteurs de la boucle. */
        private static void noter(AxionEntity assembly, boolean avant, boolean gauche) {
            BodyState racine = ClientPrediction.predictedRoot();
            Prediction boucle = ClientPrediction.loop();
            if (boucle == null) {
                // Aucune entrée encore partie : la boucle de la connexion naît avec la première.
                return;
            }
            double[] predite = racine == null ? new double[] {Double.NaN, Double.NaN, Double.NaN} : racine.position();
            trajectoire.add(String.format(Locale.ROOT, "%d;%d;%d;%d;%.4f;%.4f;%.4f;%.4f;%.4f;%.4f;%.1f;%d;%d;%d;%d;%.0f;%d;%d;%d",
                    tick, avant ? 1 : 0, gauche ? 1 : 0, ClientPrediction.predicting() ? 1 : 0, predite[0], predite[1],
                    predite[2], assembly.getX(),
                    assembly.getY(), assembly.getZ(), boucle.latencyMillis(), boucle.pending(), boucle.matches(),
                    boucle.replays(), boucle.accepted(), boucle.stepNanos(), ClientPrediction.lastQueued(),
                    ClientPrediction.pacingAdjustments()[0], ClientPrediction.pacingAdjustments()[1]));
        }

        private static void terminer(Minecraft minecraft) {
            minecraft.options.keyUp.setDown(false);
            minecraft.options.keyLeft.setDown(false);
            fermerLeFilm(minecraft, null);
            minecraft.options.keyDown.setDown(false);
            List<String> rapport = new ArrayList<>();
            Prediction boucle = ClientPrediction.loop();
            rapport.add(String.format(Locale.ROOT, "délai injecté par sens : %d ms, gigue de 0 à %d ms, %d s"
                    + " prolongées", DELAI_MS, GIGUE_MS, PROLONGER_S));
            rapport.add("tentatives de connexion : " + tentatives);
            if (boucle == null) {
                rapport.add("aucune entrée partie : rien de prédit");
                boucle = new Prediction(System::nanoTime, 1);
            }
            rapport.add(String.format(Locale.ROOT, "latence mesurée par l'acquittement : %.1f ms",
                    boucle.latencyMillis()));
            rapport.add(String.format(Locale.ROOT, "corrections : %d égales, %d rejouées, %d acceptées sans rejeu",
                    boucle.matches(), boucle.replays(), boucle.accepted()));
            rapport.add(String.format(Locale.ROOT, "coût lissé d'un pas rejoué : %.0f ns", boucle.stepNanos()));
            rapport.add(repartition("écart de position à l'acquittement (m)", 0));
            rapport.add(repartition("écart de rotation à l'acquittement (rad)", 1));
            rapport.add(repartition("écart de vitesse à l'acquittement (m/s)", 2));
            rapport.add("corrections sans état prédit pour la séquence acquittée : " + sansPrediction);
            ecarts.stream().sorted((x, y) -> Double.compare(y[0], x[0])).limit(5).forEach(e -> rapport.add(
                    String.format(Locale.ROOT, "  pire écart de position : %.4f m (rotation %.4f rad, vitesse %.3f m/s)"
                            + " au tick %d, %d en attente", e[0], e[1], e[2], (int) e[3], (int) e[4])));
            rapport.add(fluidite());
            rapport.add(vueAuClient);
            long[] reglage = ClientPrediction.pacingAdjustments();
            rapport.add("cadence réglée par le client : " + reglage[0] + " tick(s) à deux entrées, " + reglage[1]
                    + " à aucune");
            long[] cadence = ClientPrediction.inputCadence();
            rapport.add("cadence des entrées au serveur : " + cadence[0] + " tick(s) avancé(s) sans entrée neuve, "
                    + cadence[1] + " entrée(s) passée(s) d'un coup");
            // Sans contrôleur au serveur, le régulateur rejoué n'a rien à quoi se comparer : la ligne mentirait.
            if (!ecartsDecodage.isEmpty() && coupleServeurMax > 0) {
                double[] d = ecartsDecodage.stream().mapToDouble(Double::doubleValue).sorted().toArray();
                long nuls = java.util.Arrays.stream(d).filter(x -> x == 0.0).count();
                rapport.add(String.format(Locale.ROOT, "régulateur rejoué au client sur le bloc du serveur du tick"
                        + " précédent, contre le couple posé par le serveur, %d comparaisons : %d au bit près, écart p50"
                        + " %.3e, p90 %.3e, max %.3e N·m", d.length, nuls, d[d.length / 2],
                        d[Math.min(d.length - 1, d.length * 9 / 10)], d[d.length - 1]));
            }
            if (!ecartsCouple.isEmpty()) {
                double[] c = ecartsCouple.stream().mapToDouble(Double::doubleValue).sorted().toArray();
                int n = c.length;
                rapport.add(String.format(Locale.ROOT, "couple posé par les contrôleurs, prédit contre serveur, %d"
                        + " comparaisons : écart p50 %.3e, p90 %.3e, max %.3e N·m (couple du serveur jusqu'à %.1f N·m)",
                        n, c[n / 2], c[Math.min(n - 1, n * 9 / 10)], c[n - 1], coupleServeurMax));
            }
            if (!BASCULER.isEmpty()) {
                rapport.add(String.format(Locale.ROOT, "variable %s basculée au tick %d : vue par la prédiction au tick"
                        + " %d, reçue du serveur au tick %d ; paramètres du monde prédit changés au tick %d ; %d"
                        + " correction(s) rejouée(s) depuis la bascule", BASCULER, BASCULE, basculePredite, basculeRecue,
                        basculeParametres, rejeuxAvant < 0 ? -1 : boucle.replays() - rejeuxAvant));
            }
            rapport.add("proxies posés au plus : " + proxiesAssemblies + " assembly(s), " + proxiesEntites
                    + " entité(s) vanilla");
            rapport.addAll(banc());
            Path dossier = minecraft.gameDirectory.toPath();
            try {
                Files.write(dossier.resolve("rapport-prediction.txt"), rapport, StandardCharsets.UTF_8);
                List<String> csv = new ArrayList<>();
                csv.add("tick;avant;gauche;predit;px;py;pz;ix;iy;iz;latence_ms;en_attente;egales;rejouees;acceptees;pas_lisse_ns;file;deux;aucune");
                csv.addAll(trajectoire);
                Files.write(dossier.resolve("trajectoire-prediction.csv"), csv, StandardCharsets.UTF_8);
                List<String> lignes = new ArrayList<>();
                lignes.add("nanos;tick_rendu;x;z;en_attente");
                for (double[] i : images) {
                    lignes.add(String.format(Locale.ROOT, "%.0f;%.5f;%.5f;%.5f;%.0f", i[0], i[1], i[2], i[3], i[4]));
                }
                Files.write(dossier.resolve("images-prediction.csv"), lignes, StandardCharsets.UTF_8);
            } catch (IOException faute) {
                LOGGER.error("[essai] rapport non écrit : {}", faute.toString());
            }
            rapport.forEach(ligne -> LOGGER.info("[essai] {}", ligne));
            if (minecraft.level != null) {
                minecraft.level.disconnect();
            }
            fermerRelais();
            minecraft.stop();
        }

        /**
         * {@return la fluidité du véhicule dessiné : d'une image à la suivante, la variation de sa vitesse horizontale}
         * Une pose qui avance régulièrement la garde petite ; un saut d'image la fait bondir.
         */
        private static String fluidite() {
            List<Double> variations = new ArrayList<>();
            double vitesse = Double.NaN;
            for (int i = 1; i < images.size(); i++) {
                double[] a = images.get(i - 1);
                double[] b = images.get(i);
                // Par tick de rendu, pas par seconde : la durée d'une image n'y entre pas, seul ce qu'elle montre.
                double dt = (b[1] - a[1]) * dev.axion.net.ServerClock.TICK_MILLIS / 1000.0;
                if (dt <= 1e-6) {
                    continue;
                }
                double v = Math.hypot(b[2] - a[2], b[3] - a[3]) / dt;
                if (!Double.isNaN(vitesse)) {
                    variations.add(Math.abs(v - vitesse));
                }
                vitesse = v;
            }
            if (variations.isEmpty()) {
                return "fluidité : aucune image prédite";
            }
            double[] d = variations.stream().mapToDouble(Double::doubleValue).sorted().toArray();
            int n = d.length;
            return String.format(Locale.ROOT, "fluidité, %d images : variation de vitesse d'une image à l'autre p50 %.3f,"
                    + " p90 %.3f, p99 %.3f, max %.3f m/s", n + 2, d[n / 2], d[Math.min(n - 1, n * 9 / 10)],
                    d[Math.min(n - 1, n * 99 / 100)], d[n - 1]);
        }

        /** {@return la répartition d'un écart sur les corrections comparées : p50, p90, p99, max} */
        private static String repartition(String nom, int rang) {
            double[] valeurs = ecarts.stream().mapToDouble(e -> e[rang]).sorted().toArray();
            if (valeurs.length == 0) {
                return nom + " : aucune comparaison";
            }
            int n = valeurs.length;
            return String.format(Locale.ROOT, "%s, %d comparaisons : p50 %.3e, p90 %.3e, p99 %.3e, max %.3e", nom, n,
                    valeurs[n / 2], valeurs[Math.min(n - 1, n * 9 / 10)], valeurs[Math.min(n - 1, n * 99 / 100)],
                    valeurs[n - 1]);
        }

        /**
         * ADR-168 D10 : le coût d'un rejeu de 64 ticks, au banc du vrai client — la restauration de l'état du serveur,
         * puis 64 pas du monde prédit, l'accélérateur à fond. Dix d'échauffement, puis {@link #REJEUX} mesurés.
         */
        private static List<String> banc() {
            PredictedWorld monde = ClientPrediction.world();
            byte[] etat = ClientPrediction.lastServerState();
            AxionEntity vehicule = ClientPrediction.vehicle();
            if (monde == null || etat == null || vehicule == null) {
                return List.of("banc du rejeu : aucun monde prédit, rien de mesuré");
            }
            BodyState racine = ClientPrediction.predictedRoot();
            double[] observateur = racine == null ? new double[] {8.0, SOL_Y + 1.0, 0.0} : racine.position();
            dev.axion.lifecycle.AxionRuntime runtime = RuntimeAccess.get();
            dev.axion.definition.Definition definition =
                    runtime.definitions().get(vehicule.definitionId()).orElse(null);
            dev.axion.asset.DefinitionCompiler.Summary resume = definition == null ? null
                    : runtime.compiledDefinition(definition.id().toString()).orElse(null);
            List<String> lignes = new ArrayList<>();
            double[] p50 = new double[4];
            int[] tailles = {0, 8, 16, 32};
            for (int rang = 0; rang < tailles.length; rang++) {
                int n = resume == null ? 0 : tailles[rang];
                // ADR-168, précision 6c-C2 : n proxies du véhicule de test, en couronne autour du véhicule prédit,
                // posés à chaque pas comme le client les pose.
                SimCommandStream creation = new SimCommandStream();
                List<double[]> places = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    double angle = 2 * Math.PI * i / n;
                    double r = 8 + (i % 4) * 4;
                    double[] place = {observateur[0] + r * Math.cos(angle), observateur[1], observateur[2]
                            + r * Math.sin(angle)};
                    places.add(place);
                    creation.createKinematicAssembly(PROXY_BANC + i, PredictedWorld.GENERATION, monde.dimension(), place,
                            new float[] {0f, 0f, 0f, 1f}, definition.hash());
                }
                VehicleInput plein = new VehicleInput(1f, 0f, 0f, 0f, 0f, (byte) 0, 0);
                monde.step(VehicleInput.NONE, creation.setObservers(monde.dimension(), new double[][] {observateur}));
                long[] durees = new long[REJEUX];
                for (int essai = -10; essai < REJEUX; essai++) {
                    long debut = System.nanoTime();
                    monde.restore(etat);
                    for (int pas = 0; pas < Prediction.HISTORY; pas++) {
                        SimCommandStream commandes = new SimCommandStream().setObservers(monde.dimension(),
                                new double[][] {observateur});
                        for (int i = 0; i < n; i++) {
                            commandes.setAssemblyPose(PROXY_BANC + i, PredictedWorld.GENERATION, List.of(
                                    new SimCommandStream.KinematicPose(resume.rootNode(), places.get(i),
                                            new float[] {0f, 0f, 0f, 1f})));
                        }
                        monde.step(plein, commandes);
                    }
                    if (essai >= 0) {
                        durees[essai] = System.nanoTime() - debut;
                    }
                }
                SimCommandStream retrait = new SimCommandStream();
                for (int i = 0; i < n; i++) {
                    retrait.removeAssembly(PROXY_BANC + i, PredictedWorld.GENERATION);
                }
                monde.step(VehicleInput.NONE, retrait);
                Arrays.sort(durees);
                p50[rang] = durees[REJEUX / 2] / 1e6;
                lignes.add(String.format(Locale.ROOT,
                        "banc du rejeu de %d ticks (restauration comprise), %d proxies, %d mesures : p50 %.3f ms,"
                                + " p90 %.3f ms, p99 %.3f ms, max %.3f ms",
                        Prediction.HISTORY, n, REJEUX, p50[rang], durees[REJEUX * 9 / 10] / 1e6,
                        durees[REJEUX * 99 / 100] / 1e6, durees[REJEUX - 1] / 1e6));
            }
            if (resume != null) {
                lignes.add(String.format(Locale.ROOT, "coût d'un proxy par pas, d'après les p50 : %.1f µs (8), %.1f µs"
                        + " (16), %.1f µs (32)", (p50[1] - p50[0]) * 1000 / 8 / Prediction.HISTORY,
                        (p50[2] - p50[0]) * 1000 / 16 / Prediction.HISTORY,
                        (p50[3] - p50[0]) * 1000 / 32 / Prediction.HISTORY));
            }
            return lignes;
        }
    }
}
