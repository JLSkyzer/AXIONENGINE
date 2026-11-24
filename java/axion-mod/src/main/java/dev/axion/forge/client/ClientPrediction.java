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
import dev.axion.net.AssemblyState;
import dev.axion.net.CorrectionMessage;
import dev.axion.net.CorrectionRate;
import dev.axion.net.EnsembleState;
import dev.axion.net.Interpolation;
import dev.axion.net.PredictedStates;
import dev.axion.net.PredictedTrack;
import dev.axion.net.Prediction;
import dev.axion.net.ServerHandshake;
import dev.axion.physics.AssemblyStateReport;
import dev.axion.physics.AssemblyStateView;
import dev.axion.physics.BodyState;
import dev.axion.physics.NativeSimulation;
import dev.axion.physics.PredictedWorld;
import dev.axion.physics.SimCommandStream;
import dev.axion.seat.VehicleInput;
import java.util.List;
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
    /** Les autres assemblies et les entités vanilla proches, en proxies (ADR-168, précision 6c-C2). */
    private static ClientProxies proxies;
    private static AxionEntity vehicle;
    private static int rootNode;
    /** Le dernier pas du monde prédit. */
    private static PredictedWorld.Step lastStep;
    /** L'état du corps racine au dernier pas qui l'a rapporté : un corps endormi ne l'est plus. */
    private static BodyState lastRoot;
    /** Vrai pendant le pas d'une entrée neuve : il porte les tuiles ; un pas rejoué, non. */
    private static boolean liveStep;
    /** La piste prédite du véhicule, que son entité affiche (ADR-168 D2). */
    private static PredictedTrack track;
    /**
     * Les membres de l'ensemble du véhicule prédits avec lui (ADR-171 D7) : leur piste dessinée et le node racine de leur
     * corps racine, par identifiant réseau.
     */
    private static final java.util.Map<Integer, Member> members = new java.util.TreeMap<>();
    /** Les assemblies exclues des proxies : les membres, et ceux qui viennent de quitter l'ensemble, un pas de plus. */
    private static final java.util.Set<Integer> notProxies = new java.util.HashSet<>();
    private static final java.util.Set<Integer> leaving = new java.util.HashSet<>();
    /** Les attaches posées dans le monde prédit, par identifiant au serveur. */
    private static final java.util.Set<Integer> attached = new java.util.HashSet<>();

    /** Ce que les corrections ont porté de l'ensemble, pour les essais. */
    private static long ensembleCorrections;
    private static long ensembleMulti;
    private static long ensembleBytes;
    private static long ensembleMaxBytes;
    private static long ensembleMaxMembers;

    /** Un membre prédit : son entité, sa piste, le node racine de son corps racine. */
    private record Member(AxionEntity entity, PredictedTrack track, int rootNode) {}
    /** Ce que les pistes fermées ont téléporté et lissé de plus grand, depuis la connexion. */
    private static long trackSnaps;
    private static double trackLargestCorrection;
    /** Les variables prédites du véhicule (ADR-168 D6) ; {@code null} s'il n'en déclare pas. */
    private static dev.axion.entity.PredictedVariables variables;
    /** Le siège d'où part l'entrée du conducteur, -1 avant la première. */
    private static int drivingSeat = -1;
    /** Les derniers paramètres envoyés au monde prédit (ADR-168 D6) ; {@code null} avant le premier envoi. */
    private static float[] sentFields;
    private static float[] sentGears;
    /** Les contrôleurs du véhicule prédit (ADR-168 D6, CAP-40). */
    private static dev.axion.entity.PredictedControl control;
    /** Le bloc d'état de la fin du tick précédent : celui du dernier pas, ou celui du serveur juste restauré. */
    private static byte[] stateBefore;
    /** La collecte du tick précédent, quand il a été un pas prédit ; {@code null} juste après une restauration. */
    private static dev.axion.physics.CollectResult resultBefore;
    /** La compression de repos de chaque roue, relevée sur les pas prédits ; {@code null} avant le premier. */
    private static float[] settled;
    /** Les derniers couples de roue envoyés au monde prédit ; {@code null} : aucun, ou tous nuls. */
    private static float[] sentTorques;
    /** Vrai après une restauration : le natif tient les couples du serveur, les prochains partent quoi qu'ils vaillent. */
    private static boolean torquesUnknown;
    /**
     * La cadence des entrées au serveur, lue dans les corrections : ses ticks qui ont avancé sans entrée neuve, et les
     * entrées qu'il a passées d'un coup. Le monde prédit fait un pas par entrée ; chacun de ces écarts le décale d'un
     * tick.
     */
    private static long lastCorrectionTick = -1;
    private static long lastCorrectionSequence = -1;
    private static long starvedTicks;
    private static long skippedInputs;
    /**
     * La cadence que le client tient au serveur (ADR-168 D3, révisé le 2026-11-14) ; {@code null} sans véhicule prédit.
     * Ses ajustements comptent depuis la connexion.
     */
    private static dev.axion.net.InputPacing pacing;
    private static long pacingAdded;
    private static long pacingWithheld;
    /** La dernière entrée partie, et sa séquence : la prochaine se dit tranquille si elle lui est égale. */
    private static VehicleInput lastSent;
    private static long lastSentSequence = -1;
    /** L'heure du dernier tick qui a envoyé, et les envois encore à marquer en retard après un à-coup du client. */
    private static long lastSendNanos;
    private static int lateSends;
    /** La profondeur de la file au serveur, lue dans la dernière correction ; -1 sans. */
    private static int lastQueued = -1;
    /**
     * Un tick du client parti plus d'un tick et demi après le précédent : le client a tardé — une image lente — et
     * Minecraft rattrape les ticks manqués d'un coup.
     */
    private static final long LATE_GAP_NANOS = 75_000_000L;
    /** Le début de la cause d'une coupure pour latence (D8) : son retour se juge sous une marge. */
    private static final String PING_REFUSAL = "latence de ";
    /** D8 : le taux de corrections qui démentent la prédiction, sur une fenêtre glissante ; un par connexion. */
    private static CorrectionRate correctionRate = new CorrectionRate();
    /** Une correction acceptée sans rejeu depuis le dernier pas : le pas suivant reprend la piste (R-1682, 6e). */
    private static boolean acceptedSinceStep;
    /** Les corrections acceptées sans rejeu depuis la connexion : la durée de leur réconciliation, les pas en attente. */
    private static final java.util.List<long[]> acceptances = new java.util.ArrayList<>();
    /** Vrai quand une restauration n'a pu recoudre un joint : le monde prédit se recrée à la correction suivante. */
    private static boolean irreconcilable;
    /** La dernière cause journalisée d'une prédiction coupée ; vide si elle ne l'est pas. */
    private static String refusal = "";
    /** L'état du véhicule de la dernière correction réconciliée. */
    private static byte[] lastServerState;
    /** Qui regarde chaque comparaison de la boucle ; les essais y mesurent l'écart. */
    private static java.util.function.BiConsumer<byte[], byte[]> observer = (predicted, server) -> {};

    /** Le monde prédit vu de la boucle : l'observateur à chaque pas, les tuiles au pas d'une entrée neuve. */
    private static final Prediction.World PORT = new Prediction.World() {
        @Override
        public byte[] step(long sequence, VehicleInput input) {
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
                ClientLevel level = Minecraft.getInstance().level;
                if (level != null) {
                    around.merge(proxies.select(level, vehicle, predictedSpeed(),
                            runtime.outcome().config().getFloat("net.prediction_proxy_radius"), notProxies));
                }
            }
            // ADR-168 D6 : les commandes du siège basculent les variables prédites au pas même, avant la physique,
            // dans l'ordre du serveur (ses entrées, puis ses variables).
            if (variables != null && drivingSeat >= 0) {
                dev.axion.definition.Seating seating = vehicle.declaredSeating();
                if (seating != null && drivingSeat < seating.seats().size()) {
                    variables.step(sequence, drivingSeat, seating.seats().get(drivingSeat).bindings(), input.flags());
                }
            }
            // ADR-168 D6 : les contrôleurs, sur l'état du début de ce tick, avant les paramètres — leurs écritures de
            // variables et leurs modificateurs en font partie, comme au serveur.
            around.merge(control(input));
            // ADR-168 D6 : les paramètres que le serveur composerait avec les variables de ce pas — un mode basculé
            // règle le couple au pas même. L'état restauré ne les porte pas : le natif garde les derniers envoyés.
            around.merge(parameters());
            // Les proxies au tick du serveur où s'applique l'entrée de ce pas, rejeu compris.
            long stepTick = loop.serverTickOf(sequence);
            if (stepTick >= 0) {
                around.merge(proxies.poses(stepTick, loop.lastServerTick()));
            }
            // ADR-176 D1 : le contact est la variable prédite que le démarreur lit ; le natif prédit le reçoit comme le serveur.
            int ignitionVariable = vehicle.ignitionVariable();
            VehicleInput stepped = variables != null && ignitionVariable >= 0
                    && ignitionVariable < variables.values().length
                    ? input.withIgnition(variables.values()[ignitionVariable] != 0.0)
                    : input;
            lastStep = world.step(stepped, around);
            for (BodyState state : lastStep.result().bodies()) {
                if (state.handleIndex() == world.handle() && state.handleGeneration() == PredictedWorld.GENERATION
                        && state.node() == rootNode) {
                    lastRoot = state;
                }
            }
            // ADR-168 D2 : la pose de ce pas, au tick de son entrée ; un pas rejoué réécrit le sien.
            AssemblyState pose = PredictedStates.of(lastStep.result(), world.handle(), PredictedWorld.GENERATION,
                    rootNode, input, variables == null ? vehicle.receivedVariables()
                            : AssemblyState.VariableValues.of(variables.values()), vehicle.energySpec());
            boolean afterAcceptance = liveStep && acceptedSinceStep;
            double now = vehicle.localMillis(System.nanoTime());
            if (pose != null) {
                advance(track, sequence, pose, now, afterAcceptance);
            }
            // ADR-171 D7 : chaque membre de l'ensemble a sa piste, au même tick que le véhicule.
            for (java.util.Map.Entry<Integer, Member> entry : members.entrySet()) {
                Member member = entry.getValue();
                AssemblyState memberPose = PredictedStates.of(lastStep.result(), entry.getKey(),
                        PredictedWorld.GENERATION, member.rootNode(), VehicleInput.NONE,
                        member.entity().receivedVariables());
                if (memberPose != null) {
                    advance(member.track(), sequence, memberPose, now, afterAcceptance);
                }
            }
            if (afterAcceptance) {
                acceptedSinceStep = false;
            }
            if (lastStep.state() != null) {
                stateBefore = lastStep.state().state();
                float[] rest = dev.axion.entity.PredictedControl.settled(stateBefore, lastStep.result(), world.handle(),
                        PredictedWorld.GENERATION);
                if (rest != null) {
                    settled = rest;
                }
            }
            resultBefore = lastStep.result();
            // Un joint rompu ici, intact au serveur, ne se recoud pas : le monde prédit se recrée (6a, IRRECONCILABLE).
            if (lastStep.state() != null && lastStep.state().status() == AssemblyStateReport.IRRECONCILABLE) {
                irreconcilable = true;
            }
            if (lastStep.state() == null) {
                return null;
            }
            // L'état de l'ensemble : le véhicule, puis chaque membre dans l'ordre de la correction (ADR-171 D7).
            List<EnsembleState.Member> ensemble = new java.util.ArrayList<>();
            ensemble.add(new EnsembleState.Member(world.handle(), lastStep.state().state()));
            for (Integer member : members.keySet()) {
                AssemblyStateReport report = lastStep.members().get(member);
                if (report != null) {
                    ensemble.add(new EnsembleState.Member(member, report.state()));
                }
            }
            return EnsembleState.encode(ensemble);
        }

        @Override
        public void restore(long sequence, byte[] ensembleState) {
            List<EnsembleState.Member> ensemble;
            try {
                ensemble = EnsembleState.decode(ensembleState);
            } catch (dev.axion.net.MalformedPacketException malformed) {
                return;
            }
            if (ensemble.isEmpty()) {
                return;
            }
            byte[] state = ensemble.get(0).state();
            world.restore(state);
            for (EnsembleState.Member member : ensemble.subList(1, ensemble.size())) {
                if (members.containsKey(member.entity())) {
                    world.restore(member.entity(), member.state());
                }
            }
            // Le premier pas rejoué voit l'état du serveur ; le natif reprend aussi ses couples de roue.
            stateBefore = state.clone();
            resultBefore = null;
            torquesUnknown = true;
            if (variables != null) {
                variables.restore(sequence);
            }
        }

        @Override
        public boolean agrees(long sequence) {
            return variables == null || variables.agrees(sequence);
        }
    };

    private ClientPrediction() {}

    /** Branche la réception des corrections : appelé à la préparation du client. */
    static void install() {
        AxionNetwork.installClientCorrections(ClientPrediction::onCorrection);
    }

    /** Sur le thread de Netty : la correction passe au thread du client. */
    private static void onCorrection(Connection connection, CorrectionMessage correction) {
        // Précision 6e : datée à sa réception, sur ce fil ; le thread du client peut être figé.
        long received = System.nanoTime();
        Minecraft.getInstance().execute(() -> {
            try {
                correct(connection, correction, received);
            } catch (RuntimeException | LinkageError failure) {
                LOGGER.error("AXION : prédiction : correction non traitée — {}", failure.toString());
                close();
            }
        });
    }

    private static void correct(Connection connection, CorrectionMessage correction, long received) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null) {
            return;
        }
        Prediction prediction = loop(connection);
        // D8 : la latence se mesure avant tout, même prédiction coupée : c'est elle qui dit quand la rétablir.
        prediction.acknowledged(correction.serverTick(), correction.appliedSequence(), received);
        AxionEntity assembly = level.getEntity(correction.entity()) instanceof AxionEntity found ? found : null;
        if (assembly == null || player.getVehicle() != assembly || !assembly.drivenBy(player)) {
            close();
            return;
        }
        String why = refusal(connection, prediction);
        report(why);
        if (!why.isEmpty()) {
            correctionRate.whileCut();
            close();
            return;
        }
        if (vehicle != assembly && !open(assembly, level)) {
            return;
        }
        lastServerState = correction.states().get(0).bytes();
        followEnsemble(level, correction);
        List<EnsembleState.Member> serverEnsemble = new java.util.ArrayList<>();
        long bytes = 0;
        for (CorrectionMessage.State state : correction.states()) {
            serverEnsemble.add(new EnsembleState.Member(state.entity(), state.bytes()));
            bytes += state.bytes().length;
        }
        ensembleCorrections++;
        ensembleBytes += bytes;
        ensembleMaxBytes = Math.max(ensembleMaxBytes, bytes);
        ensembleMaxMembers = Math.max(ensembleMaxMembers, members.size());
        if (correction.states().size() > 1) {
            ensembleMulti++;
        }
        lastQueued = correction.queued();
        if (pacing != null) {
            pacing.onCorrection(correction.appliedSequence(), correction.queued());
        }
        if (lastCorrectionTick >= 0 && correction.appliedSequence() >= 0 && lastCorrectionSequence >= 0) {
            long ticks = correction.serverTick() - lastCorrectionTick;
            long inputs = correction.appliedSequence() - lastCorrectionSequence;
            if (ticks > inputs) {
                starvedTicks += ticks - inputs;
            } else if (inputs > ticks) {
                skippedInputs += inputs - ticks;
            }
        }
        lastCorrectionTick = correction.serverTick();
        lastCorrectionSequence = correction.appliedSequence();
        if (variables != null) {
            variables.correction(correction.variables().values(), correction.variables().mask());
        }
        long divergedBefore = prediction.replays() + prediction.accepted();
        long reconciledBefore = divergedBefore + prediction.matches();
        Prediction.Reconciliation done =
                prediction.reconcile(PORT, correction.serverTick(), correction.appliedSequence(),
                        EnsembleState.encode(serverEnsemble));
        // R-1682, précision 6e : acceptée sans rejeu, le monde prédit repart en retard ; son pas suivant se lisse.
        if (done.outcome() == Prediction.Outcome.ACCEPTED) {
            acceptedSinceStep = true;
            if (acceptances.size() < 32) {
                acceptances.add(new long[] {done.nanos(), prediction.pending()});
            }
        }
        if (prediction.replays() + prediction.accepted() + prediction.matches() > reconciledBefore) {
            correctionRate.record(prediction.replays() + prediction.accepted() > divergedBefore);
        }
        recreateIfIrreconcilable();
    }

    /** Un joint que la restauration n'a pu recoudre : le monde prédit se ferme, et se rouvre à la correction suivante. */
    private static void recreateIfIrreconcilable() {
        if (irreconcilable) {
            LOGGER.info("AXION : prédiction : l'état du serveur ne se restaure pas sur le monde prédit (joint rompu ici,"
                    + " intact au serveur) : monde prédit recréé");
            close();
        }
    }

    /**
     * Les entrées d'un tick partent (ADR-168 D3) : une d'ordinaire. Pour le véhicule prédit, le client tient la
     * profondeur de la file de son siège au serveur ({@link dev.axion.net.InputPacing}) : il en envoie deux, et fait
     * deux pas prédits, quand la file s'est trouvée vide ; aucune, et aucun pas, quand elle a tenu une entrée de trop.
     * Le saut d'un tick que cela fait à l'écran se résorbe comme une correction.
     *
     * @param connection la connexion
     * @param assembly le véhicule
     * @param seat le siège d'où elles partent
     * @param input l'entrée du tick
     */
    static void sendInputs(Connection connection, AxionEntity assembly, int seat, VehicleInput input) {
        boolean paced = pacing != null && world != null && vehicle == assembly;
        int count = 1;
        Interpolation.Pose shown = null;
        if (paced) {
            count = pacing.inputsThisTick(lastSentSequence + 1, input.equals(lastSent));
            if (count != 1 && track != null) {
                shown = track.lastShown(assembly.localMillis(System.nanoTime()));
                if (count > 1) {
                    pacingAdded++;
                } else {
                    pacingWithheld++;
                }
            }
        }
        lastSent = input;
        long now = System.nanoTime();
        if (lastSendNanos != 0 && now - lastSendNanos > LATE_GAP_NANOS) {
            // Ce tick, et ceux que Minecraft rattrape juste après, partent en retard.
            lateSends = (int) Math.min(64, Math.round((now - lastSendNanos) / (dev.axion.net.ServerClock.TICK_MILLIS * 1e6)));
        }
        lastSendNanos = now;
        for (int i = 0; i < count; i++) {
            long sequence = AxionNetwork.sendInput(connection, assembly.getId(), seat, input, paced);
            if (sequence >= 0) {
                lastSentSequence = sequence;
                if (lateSends > 0 && pacing != null) {
                    pacing.sentLate(sequence);
                }
            }
            onInputSent(connection, assembly, seat, input, sequence);
        }
        if (lateSends > 0) {
            lateSends--;
        }
        if (shown != null && track != null) {
            track.blendFrom(shown, 0.0, assembly.localMillis(System.nanoTime()));
        }
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
        drivingSeat = seat;
        try {
            prediction.advance(PORT, sequence, assembly.sanitizedInput(seat, input));
            recreateIfIrreconcilable();
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
        correctionRate = new CorrectionRate();
        lastSent = null;
        lastSentSequence = -1;
        lastSendNanos = 0;
        lateSends = 0;
        lastQueued = -1;
        pacingAdded = 0;
        pacingWithheld = 0;
        trackSnaps = 0;
        trackLargestCorrection = 0;
        acceptances.clear();
    }

    /** Une colonne se charge chez le client : les tuiles qui la couvrent se recoupent. */
    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (tiles != null && event.getLevel() == Minecraft.getInstance().level) {
            tiles.chunkLoaded(event.getChunk().getPos().x, event.getChunk().getPos().z);
        }
    }

    /**
     * Pose qui regarde chaque comparaison d'une correction à la prédiction, pour la boucle courante et les suivantes.
     *
     * @param watcher appelé avec l'état prédit pour la séquence acquittée ({@code null} sans) et celui du serveur
     */
    public static void observe(java.util.function.BiConsumer<byte[], byte[]> watcher) {
        observer = watcher;
        if (loop != null) {
            loop.observe(watcher);
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

    /** {@return le nombre de proxies posés : assemblies, puis entités vanilla ; zéros sans monde prédit} */
    public static int[] proxyCounts() {
        return proxies == null ? new int[2] : new int[] {proxies.assemblyCount(), proxies.entityCount()};
    }

    /** {@return le véhicule prédit, {@code null} sans} */
    public static AxionEntity vehicle() {
        return vehicle;
    }

    /**
     * {@return ce que les corrections ont porté de l'ensemble (ADR-171 D7), pour les essais : corrections reçues,
     * corrections à plusieurs états, octets d'états au total et au plus dans une correction, membres prédits au plus}
     */
    public static long[] ensembleStats() {
        return new long[] {ensembleCorrections, ensembleMulti, ensembleBytes, ensembleMaxBytes, ensembleMaxMembers};
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
            AxionConfig config = runtime.outcome().config();
            double position = config.getFloat("net.prediction_tolerance_position_m");
            double rotation = config.getFloat("net.prediction_tolerance_rotation_rad");
            double velocity = config.getFloat("net.prediction_tolerance_velocity_ms");
            double energy = config.getFloat("net.prediction_tolerance_energy");
            double wear = config.getFloat("net.prediction_tolerance_wear");
            // D5 : deux mondes natifs ne calculent pas au bit près ; un bloc d'état illisible ne vaut jamais l'autre.
            // ADR-171 D7 : l'état comparé est celui de l'ensemble, membre à membre ; l'observateur des essais voit le
            // véhicule seul, comme avant.
            loop = new Prediction(System::nanoTime, config.getInt("net.prediction_replay_budget_ns"),
                    EnsembleState.tolerance((predicted, server) -> {
                        AssemblyStateView.Errors errors = AssemblyStateView.errors(predicted, server);
                        return errors != null && errors.within(position, rotation, velocity, energy, wear);
                    }));
            loop.observe((predicted, server) -> observer.accept(vehicleOf(predicted), vehicleOf(server)));
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
        // D8, précision 6e : coupée par sa latence, la prédiction ne se rouvre que sous une part du seuil — sans
        // marge, elle se coupait et se rouvrait au seuil même.
        boolean cutByPing = refusal.startsWith(PING_REFUSAL);
        double limit = cutByPing ? maxPing * config.getFloat("net.prediction_reenable_ratio") : maxPing;
        if (prediction.latencyMillis() > limit) {
            return String.format(java.util.Locale.ROOT, PING_REFUSAL + "%.0f ms, au-delà de %s (%.0f ms)",
                    prediction.latencyMillis(), cutByPing
                            ? "net.prediction_reenable_ratio × net.prediction_max_ping" : "net.prediction_max_ping",
                    limit);
        }
        double maxCorrections = config.getFloat("net.prediction_max_corrections");
        if (correctionRate.cut(maxCorrections)) {
            return String.format(java.util.Locale.ROOT, "%.0f %% des %d dernières corrections ont démenti la"
                    + " prédiction, au-delà de net.prediction_max_corrections (%.0f %%) ; elle se retente dans %d"
                    + " corrections", correctionRate.cutRate() * 100, CorrectionRate.WINDOW, maxCorrections * 100,
                    CorrectionRate.WINDOW);
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

    /** Une piste avance d'un pas : le pas d'une entrée neuve, le premier après une acceptation, ou un pas rejoué. */
    private static void advance(PredictedTrack track, long sequence, AssemblyState pose, double now,
            boolean afterAcceptance) {
        if (afterAcceptance) {
            track.advanceAfterAcceptance(sequence, pose, now);
        } else if (liveStep) {
            track.advance(sequence, pose, now);
        } else {
            track.replay(sequence, pose, now);
        }
    }

    /** {@return l'état du véhicule dans l'état d'un ensemble ; l'état tel quel s'il est illisible} */
    private static byte[] vehicleOf(byte[] ensembleState) {
        if (ensembleState == null) {
            return null;
        }
        try {
            List<EnsembleState.Member> ensemble = EnsembleState.decode(ensembleState);
            return ensemble.isEmpty() ? ensembleState : ensemble.get(0).state();
        } catch (dev.axion.net.MalformedPacketException malformed) {
            return ensembleState;
        }
    }

    /**
     * Le monde prédit suit l'ensemble que porte la correction (ADR-171 D7) : un membre qui y entre est exclu des proxies,
     * puis créé dynamique au pas où son proxy est parti ; un membre qui en sort est retiré, et reste exclu des proxies un
     * pas encore. Les attaches entre ses assemblies y sont posées avec les réglages que le serveur a répliqués (Q8), et
     * défaites quand elles ne le sont plus.
     */
    private static void followEnsemble(ClientLevel level, CorrectionMessage correction) {
        notProxies.removeAll(leaving);
        leaving.clear();
        java.util.Set<Integer> wanted = new java.util.TreeSet<>();
        for (CorrectionMessage.State state : correction.states().subList(1, correction.states().size())) {
            wanted.add(state.entity());
        }
        for (Integer gone : new java.util.ArrayList<>(members.keySet())) {
            if (!wanted.contains(gone)) {
                world.removeMember(gone);
                members.remove(gone).entity().predictWith(null);
                leaving.add(gone);
            }
        }
        AxionRuntime runtime = RuntimeAccess.get();
        for (int id : wanted) {
            notProxies.add(id);
            if (members.containsKey(id) || proxies.holds(id)
                    || !(level.getEntity(id) instanceof AxionEntity entity)) {
                continue;
            }
            Definition definition = runtime.definitions().get(entity.definitionId()).orElse(null);
            DefinitionCompiler.Summary summary = definition == null ? null
                    : runtime.compiledDefinition(definition.id().toString()).orElse(null);
            if (summary == null) {
                continue;
            }
            Quaternionf rotation = entity.orientation();
            world.addMember(id, new double[] {entity.getX(), entity.getY(), entity.getZ()},
                    new float[] {rotation.x(), rotation.y(), rotation.z(), rotation.w()}, definition.hash());
            PredictedTrack memberTrack = new PredictedTrack(Interpolation.of(runtime.outcome().config()));
            members.put(id, new Member(entity, memberTrack, summary.rootNode()));
            entity.predictWith(memberTrack);
        }
        // Les attaches entre le véhicule et ses membres prédits, et celles d'un membre au monde.
        java.util.Set<Integer> present = new java.util.HashSet<>(members.keySet());
        present.add(vehicle.getId());
        java.util.Set<Integer> still = new java.util.HashSet<>();
        for (int id : present) {
            AxionEntity from = id == vehicle.getId() ? vehicle : members.get(id).entity();
            for (AssemblyState.Link link : from.receivedLinks().links()) {
                if (!link.anchored() && !present.contains(link.other()) || link.setting().id() == 0) {
                    continue;
                }
                still.add(link.setting().id());
                if (attached.contains(link.setting().id())) {
                    continue;
                }
                SimCommandStream.AttachRequest request = request(from, link, level);
                if (request != null) {
                    world.attach(request);
                    attached.add(link.setting().id());
                }
            }
        }
        for (Integer gone : new java.util.ArrayList<>(attached)) {
            if (!still.contains(gone)) {
                world.detach(gone);
                attached.remove(gone);
            }
        }
    }

    /** {@return la commande qui pose dans le monde prédit l'attache répliquée {@code link} ; {@code null} sans repère} */
    private static SimCommandStream.AttachRequest request(AxionEntity from, AssemblyState.Link link, ClientLevel level) {
        List<dev.axion.definition.SocketTable.Spec> sockets = from.sockets().sockets();
        if (link.socket() >= sockets.size()) {
            return null;
        }
        float[] aFrame = from.socketFrame(sockets.get(link.socket()).node());
        if (aFrame == null) {
            return null;
        }
        int bIndex = 0;
        int bGeneration = 0;
        int bNode = 0;
        float[] bFrame = new float[7];
        double[] anchor = new double[3];
        if (link.anchored()) {
            anchor = new double[] {link.anchorX(), link.anchorY(), link.anchorZ()};
        } else {
            if (!(level.getEntity(link.other()) instanceof AxionEntity other)
                    || link.otherSocket() >= other.sockets().sockets().size()) {
                return null;
            }
            float[] frame = other.socketFrame(other.sockets().sockets().get(link.otherSocket()).node());
            if (frame == null) {
                return null;
            }
            bIndex = other.getId();
            bGeneration = PredictedWorld.GENERATION;
            bNode = (int) frame[0];
            bFrame = java.util.Arrays.copyOfRange(frame, 1, 8);
        }
        AssemblyState.Setting setting = link.setting();
        return new SimCommandStream.AttachRequest(setting.id(), link.kind(), from.getId(), PredictedWorld.GENERATION,
                (int) aFrame[0], java.util.Arrays.copyOfRange(aFrame, 1, 8), bIndex, bGeneration, bNode, bFrame,
                anchor, setting.maxForce(), setting.maxTorque(), setting.cone(), setting.length(),
                setting.minLength(), setting.maxLength(), setting.speed());
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
        proxies = new ClientProxies(runtime, tiles.dimension());
        Quaternionf rotation = assembly.orientation();
        world = PredictedWorld.open(new NativeSimulation(runtime.outcome().context()),
                runtime.takePhysicsMaterialsIfChanged(), assembly.getId(), tiles.dimension(),
                new double[] {assembly.getX(), assembly.getY(), assembly.getZ()},
                new float[] {rotation.x(), rotation.y(), rotation.z(), rotation.w()}, definition.hash());
        vehicle = assembly;
        rootNode = summary.rootNode();
        lastStep = null;
        lastRoot = null;
        irreconcilable = false;
        track = new PredictedTrack(Interpolation.of(runtime.outcome().config()));
        pacing = new dev.axion.net.InputPacing();
        variables = predictedVariables(assembly);
        sentFields = null;
        sentGears = null;
        stateBefore = null;
        resultBefore = null;
        settled = null;
        sentTorques = null;
        torquesUnknown = false;
        AxionConfig config = runtime.outcome().config();
        control = new dev.axion.entity.PredictedControl(new dev.axion.entity.ControllerRunner.Limits(
                config.getInt("vehicle.controller_budget_ns"), (int) config.getInt("vehicle.controller_strikes")),
                System::nanoTime);
        if (variables != null) {
            // Le garde du serveur (ADR-167 D4) : une bascule qui sortirait un paramètre de ses bornes est refusée.
            variables.variables().guard((index, next) -> {
                dev.axion.entity.AssemblyParameters table = assembly.predictedParameters(variables.values());
                return table == null ? null : table.refusal(index, next);
            });
        }
        assembly.predictWith(track);
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
        ClientProxies releasedProxies = proxies;
        world = null;
        tiles = null;
        proxies = null;
        if (vehicle != null) {
            vehicle.predictWith(null);
        }
        if (vehicle != null) {
            vehicle.predictView(null);
        }
        for (Member member : members.values()) {
            member.entity().predictWith(null);
        }
        members.clear();
        notProxies.clear();
        leaving.clear();
        attached.clear();
        vehicle = null;
        acceptedSinceStep = false;
        if (track != null) {
            trackSnaps += track.snaps();
            trackLargestCorrection = Math.max(trackLargestCorrection, track.largestCorrection());
        }
        track = null;
        pacing = null;
        variables = null;
        control = null;
        stateBefore = null;
        resultBefore = null;
        settled = null;
        sentTorques = null;
        irreconcilable = false;
        lastStep = null;
        lastRoot = null;
        if (loop != null) {
            loop.reset();
        }
        try {
            closing.close(released.releaseAll().merge(releasedProxies.releaseAll()));
        } catch (RuntimeException | LinkageError failure) {
            LOGGER.error("AXION : prédiction : monde prédit mal fermé — {}", failure.toString());
        }
        LOGGER.info("AXION : prédiction : monde prédit fermé");
    }

    /**
     * {@return les variables prédites d'un véhicule, parties de celles que son dernier snapshot porte ; {@code null} s'il
     * n'en déclare pas}
     */
    private static dev.axion.entity.PredictedVariables predictedVariables(AxionEntity assembly) {
        dev.axion.definition.Seating seating = assembly.declaredSeating();
        if (seating == null || seating.variables().size() == 0) {
            return null;
        }
        AssemblyState.VariableValues received = assembly.receivedVariables();
        double[] values = new double[seating.variables().size()];
        long mask = 0L;
        if (received.count() == values.length) {
            for (int i = 0; i < values.length; i++) {
                values[i] = received.value(i);
            }
            mask = received.mask();
        }
        return new dev.axion.entity.PredictedVariables(seating.variables(), values, mask);
    }

    /** {@return les variables du tick : prédites, ou reçues pour un véhicule qui n'en prédit pas} */
    private static double[] tickVariables() {
        if (variables != null) {
            return variables.values();
        }
        AssemblyState.VariableValues received = vehicle.receivedVariables();
        double[] values = new double[received.count()];
        for (int i = 0; i < values.length; i++) {
            values[i] = received.value(i);
        }
        return values;
    }

    /**
     * Les contrôleurs du véhicule prédit, pour un tick (ADR-168 D6, ADR-167 D9) : sur l'état du début du tick, après
     * l'entrée ; leurs écritures vont aux variables prédites, leurs modificateurs aux paramètres prédits, leurs couples
     * au monde prédit. La vue est aussi celle que {@code VehicleControl.state()} rend au client.
     *
     * @param input l'entrée du tick
     * @return l'envoi des couples de roue, s'ils ont changé ; vide sinon
     */
    /**
     * La trame des sources lues par un contrôleur prédit (ADR-173 D1) : l'état du tick prédit — roues, régime, vitesse,
     * entrée, variables —, ce que le client sait du reste du véhicule — charge et drapeaux dessinés, sièges, attaches —
     * et son propre niveau au point du véhicule. Un écart avec le serveur est une fausse prédiction ordinaire.
     */
    private static dev.axion.render.ProceduralTracks.Frame predictedFrame(dev.axion.entity.VehicleSnapshot view,
            double[] values, VehicleInput input) {
        List<dev.axion.net.Interpolation.WheelPose> wheels = new java.util.ArrayList<>(view.wheels().size());
        // ADR-176 D4 : l'usure du pneu est celle que le serveur a envoyée, comme la charge et les drapeaux du moteur.
        java.util.List<dev.axion.net.Interpolation.WheelPose> shownWheels = vehicle.renderPose(1f).wheels();
        int rank = 0;
        for (dev.axion.api.vehicle.WheelView wheel : view.wheels()) {
            float wear = rank < shownWheels.size() ? shownWheels.get(rank).wear() : 0f;
            rank++;
            wheels.add(new dev.axion.net.Interpolation.WheelPose((float) wheel.compression(), (float) wheel.spin(),
                    (float) wheel.steer(), wheel.grounded(), wear));
        }
        dev.axion.net.Interpolation.VehiclePose shown = vehicle.renderPose(1f).vehicle();
        dev.axion.net.Interpolation.VehiclePose pose = new dev.axion.net.Interpolation.VehiclePose((float) view.rpm(),
                shown.load(), view.gear(), shown.flags(), input.throttle(), input.brake(), input.steer(),
                input.handbrake(), (float) Math.abs(view.speed()), (float) view.speed(), shown.fuelRatio(),
                shown.battery());
        float[] floats = new float[values.length];
        // ADR-174 D2 : une variable par siège ne se prédit pas ; elle se lit telle que le serveur l'a envoyée.
        dev.axion.net.AssemblyState.VariableValues received = vehicle.receivedVariables();
        float[][] seats = new float[values.length][];
        for (int i = 0; i < values.length; i++) {
            if (received.perSeat(i)) {
                seats[i] = received.seats()[i];
            } else {
                floats[i] = (float) values[i];
            }
        }
        long mask = values.length == 64 ? -1L : (1L << values.length) - 1L;
        net.minecraft.world.level.Level level = vehicle.level();
        net.minecraft.core.BlockPos at = net.minecraft.core.BlockPos.containing(vehicle.getX(), vehicle.getY(),
                vehicle.getZ());
        long tick = level.getGameTime();
        return new dev.axion.render.ProceduralTracks.Frame(wheels, vehicle.wheelSpin().angles(), pose,
                new dev.axion.net.AssemblyState.VariableValues(values.length, mask, floats, seats),
                vehicle.seatsOccupied(), tick * dev.axion.net.ServerClock.TICK_MILLIS / 1000.0,
                dev.axion.render.ProceduralTracks.worldTime(level.getDayTime()),
                level.getBrightness(net.minecraft.world.level.LightLayer.SKY, at), level.getRainLevel(1f),
                vehicle.socketReadings(), localSeat(vehicle), vehicle.sensorState());
    }

    /** {@return le siège qu'occupe le joueur local dans cette assembly, -1 sans} Ce que lit {@code var.<nom>}. */
    static int localSeat(AxionEntity assembly) {
        net.minecraft.world.entity.player.Player local = net.minecraft.client.Minecraft.getInstance().player;
        return local == null ? -1 : assembly.seatOf(local);
    }

    private static SimCommandStream control(VehicleInput input) {
        SimCommandStream out = new SimCommandStream();
        double[] values = tickVariables();
        dev.axion.entity.AssemblyParameters table = vehicle.predictedParameters(values);
        if (table == null) {
            return out;
        }
        List<dev.axion.definition.VehicleExtensions.Controller> attached = vehicle.runnableControllers();
        if (attached.isEmpty() && sentTorques == null && !torquesUnknown) {
            table.control(List.of());
            return out;
        }
        // Après un pas prédit, sa collecte, comme au serveur ; après une restauration, le bloc du serveur.
        dev.axion.entity.VehicleSnapshot view = resultBefore != null
                ? dev.axion.entity.PredictedControl.view(resultBefore, world.handle(), PredictedWorld.GENERATION,
                        rootNode, vehicle.wheelNodes(), variableNames(), values, table, input)
                : stateBefore == null ? null
                        : dev.axion.entity.PredictedControl.view(stateBefore, settled, vehicle.wheelNodes(),
                                variableNames(), values, table, input);
        if (view == null) {
            return out;
        }
        dev.axion.entity.VehicleSnapshot seen = view;
        view = view.withSources(vehicle.sources(() -> predictedFrame(seen, values, input)));
        vehicle.predictView(view);
        dev.axion.entity.ControllerRunner.Outcome outcome = control.run(attached, view);
        if (variables != null) {
            for (dev.axion.entity.ControllerRunner.Write write : outcome.writes()) {
                int index = variables.variables().declared().indexOf(write.variable());
                if (index >= 0) {
                    try {
                        variables.variables().set(index, write.value(), "contrôleur " + write.controller());
                    } catch (IllegalArgumentException refused) {
                        // Le serveur l'écarte de même, et le dit : rien à journaliser ici.
                    }
                }
            }
            table = vehicle.predictedParameters(variables.values());
        }
        table.control(outcome.modifiers());
        float[] torques = outcome.torques();
        boolean zero = true;
        for (float torque : torques) {
            zero &= torque == 0f;
        }
        boolean silent = sentTorques == null && zero && !torquesUnknown;
        if (!silent && (sentTorques == null || torquesUnknown || !java.util.Arrays.equals(sentTorques, torques))) {
            out.setWheelTorques(world.handle(), PredictedWorld.GENERATION, torques);
        }
        sentTorques = zero ? null : torques;
        torquesUnknown = false;
        return out;
    }

    /** {@return les noms des variables du véhicule prédit, dans l'ordre de sa definition} */
    private static List<String> variableNames() {
        dev.axion.definition.Seating seating = vehicle.declaredSeating();
        return seating == null ? List.of() : seating.variables().declared().stream()
                .map(dev.axion.definition.Variables.Variable::name).toList();
    }

    /**
     * {@return l'envoi au monde prédit des paramètres de ce pas, s'ils ont changé depuis le dernier ; vide sinon} Composés
     * sur les variables prédites, ou sur celles reçues pour un véhicule qui n'en prédit pas.
     */
    private static SimCommandStream parameters() {
        SimCommandStream out = new SimCommandStream();
        double[] values = tickVariables();
        dev.axion.entity.AssemblyParameters table = vehicle.predictedParameters(values);
        if (table == null) {
            return out;
        }
        float[] fields = table.fields(SimCommandStream.PARAMETER_FIELDS);
        float[] gears = table.gearTable();
        if (!java.util.Arrays.equals(fields, sentFields) || !java.util.Arrays.equals(gears, sentGears)) {
            out.setParameters(world.handle(), PredictedWorld.GENERATION, fields, gears);
            sentFields = fields;
            sentGears = gears;
        }
        return out;
    }

    /** {@return les paramètres envoyés au monde prédit en dernier, copie ; {@code null} avant} Les essais les lisent. */
    public static float[] sentParameters() {
        return sentFields == null ? null : sentFields.clone();
    }

    /** {@return la compression de repos de chaque roue du véhicule prédit, relevée ; {@code null} avant} */
    public static float[] settled() {
        return settled == null ? null : settled.clone();
    }

    /**
     * {@return la cadence des entrées au serveur depuis la connexion : ses ticks avancés sans entrée neuve, puis les
     * entrées qu'il a passées d'un coup}
     */
    public static long[] inputCadence() {
        return new long[] {starvedTicks, skippedInputs};
    }

    /** {@return la profondeur de la file au serveur, lue dans la dernière correction ; -1 sans} */
    public static int lastQueued() {
        return lastQueued;
    }

    /**
     * {@return les ajustements de la cadence depuis la connexion (ADR-168 D3, révisé le 2026-11-14) : les ticks à deux
     * entrées, puis ceux à aucune}
     */
    public static long[] pacingAdjustments() {
        return new long[] {pacingAdded, pacingWithheld};
    }

    /**
     * {@return les 32 premières corrections acceptées sans rejeu depuis la connexion (R-1682) : la durée de leur
     * réconciliation en nanosecondes, puis les pas en attente} Une copie.
     */
    public static java.util.List<long[]> acceptances() {
        return java.util.List.copyOf(acceptances);
    }

    /**
     * {@return ce que la piste prédite a fait de ses écarts depuis la connexion (D5) : les téléportations au-delà de
     * {@code net.snap_threshold}, puis le plus grand écart lissé, en blocs}
     */
    public static double[] trackCorrections() {
        long snaps = trackSnaps + (track == null ? 0 : track.snaps());
        double largest = Math.max(trackLargestCorrection, track == null ? 0 : track.largestCorrection());
        return new double[] {snaps, largest};
    }

    /** {@return les variables prédites du véhicule prédit, {@code null} sans} */
    public static dev.axion.entity.PredictedVariables predictedVariables() {
        return variables;
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
