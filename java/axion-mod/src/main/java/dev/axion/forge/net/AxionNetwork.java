package dev.axion.forge.net;

import com.mojang.logging.LogUtils;
import dev.axion.forge.RuntimeAccess;
import dev.axion.lifecycle.AxionRuntime;
import dev.axion.config.AxionConfig;
import dev.axion.forge.AxionEntity;
import dev.axion.net.AssemblyState;
import dev.axion.net.ClientSession;
import dev.axion.net.DetProfile;
import dev.axion.net.PlayerSync;
import dev.axion.net.RegistryAck;
import dev.axion.net.ServerHandshake;
import dev.axion.net.ServerPeer;
import dev.axion.net.ServerSnapshot;
import dev.axion.net.SnapshotSender;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.HandshakeHandler;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;

/**
 * La colle Forge du handshake et du registre (ADR-150 D4, D5) : elle passe les trames entre Forge et les
 * sessions de {@code dev.axion.net}, qui décident de tout.
 *
 * <p>Chaque connexion porte sa session dans un attribut de son canal Netty : côté client la
 * {@link ClientSession}, côté serveur le {@link ServerPeer}. Aucun gestionnaire ne lève (R-400, R-1650) :
 * ce qui échoue se journalise, et le client répond toujours au paquet de connexion reçu, sans quoi le
 * serveur ne le laisserait pas entrer.
 */
public final class AxionNetwork {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final AttributeKey<ClientSession> CLIENT_SESSION = AttributeKey.valueOf("axion_client_session");

    private static final AttributeKey<ServerPeer> SERVER_PEER = AttributeKey.valueOf("axion_server_peer");

    /** Séquence des messages fragmentés envoyés en jeu ; la phase de connexion emploie 0 et 1. */
    private static final AtomicInteger SEQUENCE = new AtomicInteger(2);

    /** La synchronisation de chaque joueur, en jeu (ADR-150 D2, D6) : thread du serveur seulement. */
    private static final Map<UUID, PlayerSync> SYNCS = new ConcurrentHashMap<>();

    /**
     * Ce que le client fait des états reçus : posé par le code du client à son démarrage. Un serveur
     * dédié n'a pas de session client, et ce code commun ne nomme aucune classe du client.
     */
    private static volatile ClientSession.SnapshotSink clientSnapshots = states -> {
        throw new IllegalStateException("aucun puits de snapshots installé chez ce client");
    };

    private AxionNetwork() {
        throw new AssertionError("non instanciable");
    }

    /**
     * Les paquets de connexion d'un client qui arrive : le handshake, puis le registre s'il est distant.
     * Forge les rassemble sur un thread de Netty, dès l'arrivée du client.
     *
     * @param local vrai pour une connexion en mémoire : un monde solo, ou l'hôte d'un monde ouvert
     * @return les paquets, dans l'ordre d'envoi
     */
    static List<Pair<String, ChannelFrames.LoginToClient>> loginPackets(boolean local) {
        AxionRuntime runtime = RuntimeAccess.get();
        ServerSnapshot snapshot = runtime == null ? null : runtime.netSnapshot();
        if (snapshot == null) {
            LOGGER.warn("AXION : aucun instantané réseau (runtime natif inactif ?) : le client entre sans"
                    + " handshake AXION");
            return List.of();
        }
        List<byte[]> frames = snapshot.loginFrames(local);
        List<Pair<String, ChannelFrames.LoginToClient>> packets = new ArrayList<>(frames.size());
        for (int index = 0; index < frames.size(); index++) {
            String context = index == 0 ? "axion:handshake" : "axion:registry/" + index;
            packets.add(Pair.of(context, new ChannelFrames.LoginToClient(frames.get(index))));
        }
        return packets;
    }

    /** Chez le client : un paquet de connexion, sur le thread de Netty. */
    static void onLoginToClient(ChannelFrames.LoginToClient message, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        byte[] reply = new byte[0];
        try {
            ClientSession session = clientSession(context.getNetworkManager());
            reply = session.onLoginFrame(message.frame());
            journal("client", session.drainJournal());
        } catch (RuntimeException | LinkageError failure) {
            LOGGER.error("AXION : client : paquet de connexion non traité — {}", failure.toString());
        }
        // Toujours une réponse, au numéro de transaction du paquet reçu.
        AxionChannel.CHANNEL.reply(new ChannelFrames.LoginToServer(reply), context);
        context.setPacketHandled(true);
    }

    /**
     * Chez le serveur : la réponse d'un client, sur le thread de Netty. {@link HandshakeHandler#indexFirst}
     * l'a déjà comptée comme reçue.
     */
    static void onLoginToServer(
            HandshakeHandler handler, ChannelFrames.LoginToServer message, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.setPacketHandled(true);
        try {
            serverPeer(context.getNetworkManager()).onLoginReply(message.frame());
        } catch (RuntimeException | LinkageError failure) {
            LOGGER.error("AXION : réponse de connexion non traitée — {}", failure.toString());
        }
    }

    /** Chez le client : un message en jeu, sur le thread de Netty ; un snapshot est acquitté aussitôt. */
    static void onPlayToClient(ChannelFrames.PlayToClient message, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.setPacketHandled(true);
        try {
            ClientSession session = clientSession(context.getNetworkManager());
            byte[] ack = session.onPlayFrame(message.frame());
            journal("client", session.drainJournal());
            if (ack != null) {
                AxionChannel.CHANNEL.sendTo(new ChannelFrames.PlayToServer(ack), context.getNetworkManager(),
                        NetworkDirection.PLAY_TO_SERVER);
            }
        } catch (RuntimeException | LinkageError failure) {
            LOGGER.error("AXION : client : message en jeu non traité — {}", failure.toString());
        }
    }

    /** Chez le serveur : un acquittement, lu sur Netty et repassé au thread du serveur. */
    static void onPlayToServer(ChannelFrames.PlayToServer message, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.setPacketHandled(true);
        ServerPlayer player = context.getSender();
        if (player == null) {
            return;
        }
        context.enqueueWork(() -> {
            PlayerSync sync = SYNCS.get(player.getUUID());
            if (sync != null) {
                sync.onAckFrame(message.frame());
            }
        });
    }

    /**
     * Pose ce que le client fait des états reçus. Appelé par le code du client, à son démarrage.
     *
     * @param sink application des états aux entités du client
     */
    public static void installClientSnapshots(ClientSession.SnapshotSink sink) {
        clientSnapshots = sink;
    }

    /**
     * Chez le client : une entité a quitté le monde du client ; si elle revient, le serveur la renverra en
     * entier.
     *
     * @param connection connexion du client
     * @param entityId identifiant réseau de l'entité
     */
    public static void forgetClientEntity(Connection connection, int entityId) {
        ClientSession session = connection.channel().attr(CLIENT_SESSION).get();
        if (session != null) {
            session.forgetEntity(entityId);
        }
    }

    /**
     * Chez le client : le handshake du serveur de cette connexion.
     *
     * @param connection connexion du client
     * @return le handshake, ou {@code null} s'il n'est pas arrivé
     */
    public static ServerHandshake clientServer(Connection connection) {
        ClientSession session = connection.channel().attr(CLIENT_SESSION).get();
        return session == null ? null : session.server().orElse(null);
    }

    /**
     * Un joueur commence à suivre une entité : son client a reçu l'apparition (Forge publie l'événement
     * après l'avoir envoyée).
     *
     * @param player joueur
     * @param target entité suivie
     */
    public static void onStartTracking(ServerPlayer player, Entity target) {
        if (target instanceof AxionEntity) {
            PlayerSync sync = sync(player);
            if (sync != null) {
                sync.track(target.getId());
            }
        }
    }

    /**
     * Un joueur cesse de suivre une entité.
     *
     * @param player joueur
     * @param target entité
     */
    public static void onStopTracking(ServerPlayer player, Entity target) {
        PlayerSync sync = SYNCS.get(player.getUUID());
        if (sync != null && target instanceof AxionEntity) {
            sync.untrack(target.getId());
        }
    }

    /**
     * En fin de tick serveur, après l'application des états du natif : le snapshot de chaque joueur
     * (21.5). Une assembly hors de {@code net.sync_radius} n'est pas envoyée ({@code NONE}, R-1662).
     *
     * @param server serveur
     */
    public static void sendSnapshots(MinecraftServer server) {
        AxionRuntime runtime = RuntimeAccess.get();
        if (runtime == null || runtime.outcome() == null || SYNCS.isEmpty()) {
            return;
        }
        double syncRadius = runtime.outcome().config().getFloat("net.sync_radius");
        long tick = server.getTickCount();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            PlayerSync sync = SYNCS.get(player.getUUID());
            if (sync == null || player.connection == null) {
                continue;
            }
            try {
                List<byte[]> frames = sync.tick(tick, handle -> {
                    if (!(player.serverLevel().getEntity(handle) instanceof AxionEntity assembly)) {
                        return null;
                    }
                    AssemblyState state = assembly.netState();
                    double distance = player.distanceTo(assembly);
                    if (state == null || distance > syncRadius) {
                        return null;
                    }
                    return new SnapshotSender.Candidate(handle, state, SnapshotSender.Frequency.of(state), distance);
                }, SEQUENCE.getAndIncrement() & Integer.MAX_VALUE);
                for (byte[] frame : frames) {
                    AxionChannel.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                            new ChannelFrames.PlayToClient(frame));
                }
                journal(player.getGameProfile().getName(), sync.drainJournal());
            } catch (RuntimeException | LinkageError failure) {
                LOGGER.error("AXION : snapshot de {} non envoyé — {}", player.getGameProfile().getName(),
                        failure.toString());
            }
        }
    }

    /** {@return la synchronisation d'un joueur, créée à son premier besoin ; {@code null} sans runtime} */
    private static PlayerSync sync(ServerPlayer player) {
        AxionRuntime runtime = RuntimeAccess.get();
        if (runtime == null || runtime.outcome() == null) {
            return null;
        }
        // Le suivi commence pendant l'entrée du joueur, avant PlayerLoggedInEvent : d'où la création au
        // premier besoin.
        return SYNCS.computeIfAbsent(player.getUUID(), uuid -> {
            AxionConfig config = runtime.outcome().config();
            return new PlayerSync(new SnapshotSender((int) config.getInt("net.snapshot_rate_hz"),
                    config.getInt("net.max_bytes_per_second_per_player")));
        });
    }

    /**
     * Un joueur entre en jeu, sur le thread du serveur : son mode de réplication est journalisé une fois,
     * avec sa cause (5.12bis), et le registre lui est renvoyé s'il ne tient pas le registre courant.
     *
     * @param player joueur entré
     */
    public static void onPlayerLoggedIn(ServerPlayer player) {
        AxionRuntime runtime = RuntimeAccess.get();
        if (runtime == null || player.connection == null) {
            return;
        }
        try {
            String name = player.getGameProfile().getName();
            Connection connection = player.connection.connection;
            ServerPeer peer = connection.channel().attr(SERVER_PEER).get();
            ServerSnapshot snapshot = runtime.netSnapshot();
            if (peer == null) {
                if (snapshot != null) {
                    LOGGER.warn("AXION : {} est entré sans répondre au handshake AXION", name);
                }
                return;
            }
            journal(name, peer.drainJournal());
            runtime.onPeerJoined(player.getStringUUID(), peer);
            LOGGER.info("AXION : réplication avec {} : {}", name, peer.describe());
            if (snapshot != null && peer.needsRegistry(snapshot, connection.isMemoryConnection())) {
                sendRegistry(player, snapshot);
                LOGGER.info("AXION : registre renvoyé en jeu à {} : il ne tenait pas le registre courant", name);
            }
        } catch (RuntimeException | LinkageError failure) {
            LOGGER.error("AXION : entrée en jeu non traitée — {}", failure.toString());
        }
    }

    /**
     * Un joueur quitte le serveur.
     *
     * @param player joueur parti
     */
    public static void onPlayerLoggedOut(ServerPlayer player) {
        SYNCS.remove(player.getUUID());
        AxionRuntime runtime = RuntimeAccess.get();
        if (runtime != null) {
            runtime.onPeerLeft(player.getStringUUID());
        }
    }

    /** À l'arrêt du serveur : les synchronisations de ses joueurs s'oublient. */
    public static void onServerStopping() {
        SYNCS.clear();
    }

    /**
     * Après un rechargement des definitions, sur le thread du serveur : un registre qui a changé
     * repart vers chaque joueur distant (21.4, {@code S2C_Registry} « login / reload »).
     *
     * @param before instantané d'avant le rechargement, ou {@code null}
     * @param after instantané d'après, ou {@code null}
     */
    public static void onRegistryReloaded(ServerSnapshot before, ServerSnapshot after) {
        if (after == null || !after.hasRegistry()
                || (before != null && RegistryAck.of(before.registry()).matches(after.registry()))) {
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        try {
            int sent = 0;
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (player.connection != null && !player.connection.connection.isMemoryConnection()) {
                    sendRegistry(player, after);
                    sent++;
                }
            }
            if (sent > 0) {
                LOGGER.info("AXION : registre rechargé renvoyé à {} joueur(s) distant(s)", sent);
            }
        } catch (RuntimeException | LinkageError failure) {
            LOGGER.error("AXION : renvoi du registre rechargé non traité — {}", failure.toString());
        }
    }

    private static void sendRegistry(ServerPlayer player, ServerSnapshot snapshot) {
        int sequence = SEQUENCE.getAndIncrement() & Integer.MAX_VALUE;
        for (byte[] frame : snapshot.registryFrames(sequence)) {
            AxionChannel.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new ChannelFrames.PlayToClient(frame));
        }
    }

    private static ClientSession clientSession(Connection connection) {
        Attribute<ClientSession> attribute = connection.channel().attr(CLIENT_SESSION);
        ClientSession session = attribute.get();
        if (session != null) {
            return session;
        }
        AxionRuntime runtime = RuntimeAccess.get();
        ClientSession created = new ClientSession(runtime.netCompression(), runtime.detProfile(),
                runtime::applyRemoteRegistry, states -> clientSnapshots.apply(states));
        ClientSession raced = attribute.setIfAbsent(created);
        return raced == null ? created : raced;
    }

    private static ServerPeer serverPeer(Connection connection) {
        Attribute<ServerPeer> attribute = connection.channel().attr(SERVER_PEER);
        ServerPeer peer = attribute.get();
        if (peer != null) {
            return peer;
        }
        AxionRuntime runtime = RuntimeAccess.get();
        ServerSnapshot snapshot = runtime.netSnapshot();
        DetProfile det = snapshot != null ? snapshot.handshake().det() : runtime.detProfile();
        ServerPeer created = new ServerPeer(det);
        ServerPeer raced = attribute.setIfAbsent(created);
        return raced == null ? created : raced;
    }

    private static void journal(String who, List<String> lines) {
        for (String line : lines) {
            if (line.startsWith("E-")) {
                LOGGER.warn("AXION : {} : {}", who, line);
            } else {
                LOGGER.info("AXION : {} : {}", who, line);
            }
        }
    }
}
