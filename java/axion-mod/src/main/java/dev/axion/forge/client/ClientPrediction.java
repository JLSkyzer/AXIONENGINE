package dev.axion.forge.client;

import com.mojang.logging.LogUtils;
import dev.axion.AxionMod;
import dev.axion.asset.DefinitionCompiler;
import dev.axion.config.AxionConfig;
import dev.axion.definition.Definition;
import dev.axion.forge.AxionEntity;
import dev.axion.forge.RuntimeAccess;
import dev.axion.forge.net.AxionNetwork;
import dev.axion.lifecycle.AxionRuntime;
import dev.axion.net.CorrectionMessage;
import dev.axion.net.Prediction;
import dev.axion.net.ServerHandshake;
import dev.axion.physics.BodyState;
import dev.axion.physics.NativeSimulation;
import dev.axion.physics.PredictedWorld;
import dev.axion.physics.SimCommandStream;
import dev.axion.seat.VehicleInput;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.Connection;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Quaternionf;
import org.slf4j.Logger;

/**
 * Le client qui prédit le véhicule qu'il conduit (ADR-168, tranche 6c) : son monde prédit, sa boucle, et ce qui les
 * permet ou les coupe (D8).
 *
 * <ul>
 *   <li>Le monde prédit s'ouvre à la première correction reçue pour le véhicule dont le joueur tient le siège
 *       {@code driver}, et en reprend l'état ; il se ferme quand le joueur en sort, ou que la prédiction est
 *       coupée.</li>
 *   <li>Chaque entrée envoyée s'y applique aussitôt ({@link Prediction#advance}) ; chaque correction se réconcilie
 *       ({@link Prediction#reconcile}).</li>
 *   <li>Il ne s'ouvre pas sur une connexion en mémoire (un contexte natif par processus, R-450 : le client y partage
 *       celui du serveur intégré), sans {@code net.prediction}, quand le serveur ne la permet pas, quand la simulation
 *       du client n'est pas celle que le serveur annonce, ou au-delà de {@code net.prediction_max_ping}.</li>
 * </ul>
 *
 * <p>Le journal dit chaque changement : monde ouvert, fermé, prédiction coupée et sa cause, rétablie.
 *
 * <p>Thread du client seul : les corrections reçues sur Netty y sont repassées.
 */
@Mod.EventBusSubscriber(modid = AxionMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class ClientPrediction {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** La boucle de la connexion : la latence et les heures d'envoi valent pour elle. {@code null} sans connexion. */
    private static Prediction loop;
    private static PredictedWorld world;
    private static ClientTiles tiles;
    private static AxionEntity vehicle;
    private static int rootNode;
    /** Le dernier pas du monde prédit. */
    private static PredictedWorld.Step lastStep;
    /** L'état du corps racine au dernier pas qui l'a rapporté : un corps endormi ne l'est plus. */
    private static BodyState lastRoot;
    /** Vrai pendant le pas d'une entrée neuve : il porte les tuiles ; un pas rejoué, non. */
    private static boolean liveStep;
    /** La dernière cause journalisée d'une prédiction coupée ; vide si elle ne l'est pas. */
    private static String refusal = "";
    /** L'état du véhicule de la dernière correction réconciliée. */
    private static byte[] lastServerState;

    /** Le monde prédit vu de la boucle : l'observateur à chaque pas, les tuiles au pas d'une entrée neuve. */
    private static final Prediction.World PORT = new Prediction.World() {
        @Override
        public byte[] step(VehicleInput input) {
            double[] at = predictedPosition();
            SimCommandStream around = new SimCommandStream()
                    .setObservers(world.dimension(), new double[][] {at});
            if (liveStep) {
                AxionRuntime runtime = RuntimeAccess.get();
                dev.axion.definition.PhysicsMaterialTable.Encoded materials = runtime.takePhysicsMaterialsIfChanged();
                if (materials != null) {
                    world.materials(materials);
                }
                around.merge(tiles.tick(vehicle, at[0], at[1], at[2], predictedSpeed()));
            }
            lastStep = world.step(input, around);
            for (BodyState state : lastStep.result().bodies()) {
                if (state.handleIndex() == world.handle() && state.handleGeneration() == PredictedWorld.GENERATION
                        && state.node() == rootNode) {
                    lastRoot = state;
                }
            }
            return lastStep.state() == null ? null : lastStep.state().state();
        }

        @Override
        public void restore(byte[] state) {
            world.restore(state);
        }
    };

    private ClientPrediction() {}

    /** Branche la réception des corrections : appelé à la préparation du client. */
    static void install() {
        AxionNetwork.installClientCorrections(ClientPrediction::onCorrection);
    }

    /** Sur le thread de Netty : la correction passe au thread du client. */
    private static void onCorrection(Connection connection, CorrectionMessage correction) {
        Minecraft.getInstance().execute(() -> {
            try {
                correct(connection, correction);
            } catch (RuntimeException | LinkageError failure) {
                LOGGER.error("AXION : prédiction : correction non traitée — {}", failure.toString());
                close();
            }
        });
    }

    private static void correct(Connection connection, CorrectionMessage correction) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null) {
            return;
        }
        Prediction prediction = loop(connection);
        AxionEntity assembly = level.getEntity(correction.entity()) instanceof AxionEntity found ? found : null;
        if (assembly == null || player.getVehicle() != assembly || !assembly.drivenBy(player)) {
            close();
            return;
        }
        String why = refusal(connection, prediction);
        report(why);
        if (!why.isEmpty()) {
            close();
            return;
        }
        if (vehicle != assembly && !open(assembly, level)) {
            return;
        }
        lastServerState = correction.states().get(0).bytes();
        prediction.reconcile(PORT, correction.serverTick(), correction.appliedSequence(), lastServerState);
    }

    /**
     * Une entrée vient de partir (ADR-168 D3) : son heure compte pour la latence ; si le véhicule est prédit, elle s'y
     * applique aussitôt, bornée comme le serveur la borne.
     *
     * @param connection la connexion
     * @param assembly le véhicule
     * @param seat le siège d'où elle part
     * @param input l'entrée envoyée
     * @param sequence sa séquence, non signée
     */
    static void onInputSent(Connection connection, AxionEntity assembly, int seat, VehicleInput input, long sequence) {
        if (sequence < 0) {
            return;
        }
        Prediction prediction = loop(connection);
        prediction.sent(sequence, System.nanoTime());
        if (world == null || vehicle != assembly) {
            return;
        }
        String why = refusal(connection, prediction);
        report(why);
        if (!why.isEmpty()) {
            close();
            return;
        }
        liveStep = true;
        try {
            prediction.advance(PORT, sequence, assembly.sanitizedInput(seat, input));
        } catch (RuntimeException | LinkageError failure) {
            LOGGER.error("AXION : prédiction : pas non fait — {}", failure.toString());
            close();
        } finally {
            liveStep = false;
        }
    }

    /**
     * Chaque tick du client : un joueur qui ne conduit plus le véhicule prédit le ferme.
     *
     * @param player le joueur local
     */
    static void tick(LocalPlayer player) {
        if (vehicle != null && (vehicle.isRemoved() || player.getVehicle() != vehicle || !vehicle.drivenBy(player))) {
            close();
        }
    }

    /** La déconnexion : le monde prédit se ferme, la boucle de la connexion s'oublie. */
    static void reset() {
        close();
        loop = null;
        refusal = "";
    }

    /** Une colonne se charge chez le client : les tuiles qui la couvrent se recoupent. */
    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (tiles != null && event.getLevel() == Minecraft.getInstance().level) {
            tiles.chunkLoaded(event.getChunk().getPos().x, event.getChunk().getPos().z);
        }
    }

    /** {@return la boucle de la connexion courante, {@code null} sans} Les essais y lisent latence et corrections. */
    public static Prediction loop() {
        return loop;
    }

    /** {@return vrai si un monde prédit est ouvert} */
    public static boolean predicting() {
        return world != null;
    }

    /** {@return le dernier pas du monde prédit, {@code null} sans} */
    public static PredictedWorld.Step lastStep() {
        return lastStep;
    }

    /** {@return le véhicule prédit, {@code null} sans} */
    public static AxionEntity vehicle() {
        return vehicle;
    }

    /** {@return l'état du véhicule de la dernière correction réconciliée, copie ; {@code null} sans} */
    public static byte[] lastServerState() {
        return lastServerState == null ? null : lastServerState.clone();
    }

    /** {@return le monde prédit ouvert, {@code null} sans} Les essais y mesurent un rejeu. */
    public static PredictedWorld world() {
        return world;
    }

    private static Prediction loop(Connection connection) {
        if (loop == null) {
            AxionRuntime runtime = RuntimeAccess.get();
            loop = new Prediction(System::nanoTime,
                    runtime.outcome().config().getInt("net.prediction_replay_budget_ns"));
        }
        return loop;
    }

    /** {@return pourquoi la prédiction est coupée (D8), vide si elle ne l'est pas} */
    private static String refusal(Connection connection, Prediction prediction) {
        AxionRuntime runtime = RuntimeAccess.get();
        if (runtime == null || !runtime.isOperational()) {
            return "runtime natif inactif";
        }
        if (connection.isMemoryConnection()) {
            return "connexion en mémoire : le client partage le natif du serveur intégré (R-450)";
        }
        AxionConfig config = runtime.outcome().config();
        if (!config.getBoolean("net.prediction")) {
            return "net.prediction est faux";
        }
        ServerHandshake server = AxionNetwork.clientServer(connection);
        if (server == null || !server.predictionEnabled()) {
            return "le serveur ne la permet pas (server.allow_client_prediction)";
        }
        if (server.fixedDt() != (float) config.getFloat("sim.fixed_dt")
                || server.maxSubsteps() != config.getInt("sim.max_substeps")
                || server.gravity() != (float) config.getFloat("physics.gravity")) {
            return "la simulation du client n'est pas celle du serveur (sim.fixed_dt " + server.fixedDt()
                    + ", sim.max_substeps " + server.maxSubsteps() + ", physics.gravity " + server.gravity() + ")";
        }
        long maxPing = config.getInt("net.prediction_max_ping");
        if (prediction.latencyMillis() > maxPing) {
            return String.format(java.util.Locale.ROOT, "latence de %.0f ms, au-delà de net.prediction_max_ping (%d ms)",
                    prediction.latencyMillis(), maxPing);
        }
        return "";
    }

    /** Dit au journal un changement de cause, une fois par changement (D8). */
    private static void report(String why) {
        if (why.equals(refusal)) {
            return;
        }
        if (why.isEmpty()) {
            LOGGER.info("AXION : prédiction rétablie");
        } else {
            LOGGER.info("AXION : prédiction coupée — {}", why);
        }
        refusal = why;
    }

    private static boolean open(AxionEntity assembly, ClientLevel level) {
        close();
        AxionRuntime runtime = RuntimeAccess.get();
        Definition definition = runtime.definitions().get(assembly.definitionId()).orElse(null);
        DefinitionCompiler.Summary summary = definition == null ? null
                : runtime.compiledDefinition(definition.id().toString()).orElse(null);
        if (summary == null) {
            return false;
        }
        runtime.markPhysicsMaterialsChanged();
        tiles = new ClientTiles(level, runtime);
        Quaternionf rotation = assembly.orientation();
        world = PredictedWorld.open(new NativeSimulation(runtime.outcome().context()),
                runtime.takePhysicsMaterialsIfChanged(), assembly.getId(), tiles.dimension(),
                new double[] {assembly.getX(), assembly.getY(), assembly.getZ()},
                new float[] {rotation.x(), rotation.y(), rotation.z(), rotation.w()}, definition.hash());
        vehicle = assembly;
        rootNode = summary.rootNode();
        lastStep = null;
        lastRoot = null;
        loop.reset();
        LOGGER.info("AXION : prédiction : monde prédit ouvert pour {} (entité {})", definition.id(), assembly.getId());
        return true;
    }

    private static void close() {
        if (world == null) {
            return;
        }
        PredictedWorld closing = world;
        ClientTiles released = tiles;
        world = null;
        tiles = null;
        vehicle = null;
        lastStep = null;
        lastRoot = null;
        if (loop != null) {
            loop.reset();
        }
        try {
            closing.close(released.releaseAll());
        } catch (RuntimeException | LinkageError failure) {
            LOGGER.error("AXION : prédiction : monde prédit mal fermé — {}", failure.toString());
        }
        LOGGER.info("AXION : prédiction : monde prédit fermé");
    }

    /** {@return l'état prédit du corps racine du véhicule, {@code null} avant le premier pas qui l'a rapporté} */
    public static BodyState predictedRoot() {
        return lastRoot;
    }

    /** {@return la position prédite du véhicule, celle de son entité avant le premier pas} */
    private static double[] predictedPosition() {
        return lastRoot == null ? new double[] {vehicle.getX(), vehicle.getY(), vehicle.getZ()} : lastRoot.position();
    }

    private static double predictedSpeed() {
        if (lastRoot == null) {
            return 0.0;
        }
        float[] v = lastRoot.linearVelocity();
        return Math.sqrt((double) v[0] * v[0] + (double) v[1] * v[1] + (double) v[2] * v[2]);
    }
}
