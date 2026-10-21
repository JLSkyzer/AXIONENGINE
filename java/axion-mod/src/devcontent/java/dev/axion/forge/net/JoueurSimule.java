package dev.axion.forge.net;

import com.mojang.authlib.GameProfile;
import dev.axion.definition.DefinitionRegistry;
import dev.axion.forge.RuntimeAccess;
import dev.axion.lifecycle.AxionRuntime;
import dev.axion.net.AssemblyState;
import dev.axion.net.ClientSession;
import dev.axion.net.Interpolation;
import dev.axion.net.NetProtocol;
import dev.axion.net.PlayerSync;
import dev.axion.net.RemoteRegistry;
import dev.axion.net.RenderClock;
import dev.axion.net.ServerPeer;
import dev.axion.net.ServerSnapshot;
import dev.axion.net.SnapshotSender;
import dev.axion.net.SnapshotReceiver;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.AttributeKey;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkDirection;
import org.apache.commons.lang3.tuple.Pair;

/**
 * Un joueur simulé du harnais réseau (ADR-150 D8) : un vrai {@code ServerPlayer} sur une connexion Netty
 * embarquée, et le client qu'il serait — le vrai décodeur, le vrai tampon d'interpolation.
 *
 * <p>Le serveur le traite en joueur distant : Minecraft le suit, Forge publie ses événements, et le canal
 * d'AXION lui envoie ses trames, qui sortent de l'{@link EmbeddedChannel} sous la forme des paquets que le
 * codec de Minecraft aurait écrits. Ses acquittements rentrent par le même canal, sur le thread du serveur,
 * comme Forge les y traiterait. La phase de login de Forge n'existe pas sur une connexion embarquée : la
 * connexion AXION — handshake, registre, réponses — est rejouée sur la connexion avant l'entrée en jeu.
 *
 * <p>Le temps du client simulé est celui des ticks du serveur, 50 ms chacun : le serveur de GameTests ne
 * tient pas le temps réel, il enchaîne ses ticks, et un client en temps réel face à un serveur à 20 ticks par
 * seconde voit justement passer 50 ms par tick.
 *
 * <p>Le joueur factice de Minecraft ({@code makeMockServerPlayerInLevel}) laisse le canal nul, et Forge
 * lève alors dès l'entrée en jeu : d'où cette connexion-ci. Contenu de développement, jamais empaqueté
 * (R-1790). Sur le thread du serveur seulement : la file de la connexion embarquée n'est pas synchronisée.
 */
public final class JoueurSimule {

    /**
     * Un état reçu par le client simulé.
     *
     * @param handle identifiant réseau de l'entité
     * @param serverTick tick du serveur qui l'a produit
     * @param state état reconstitué
     */
    public record Recu(int handle, long serverTick, AssemblyState state) {}

    private static final ResourceLocation CANAL =
            new ResourceLocation(NetProtocol.CHANNEL_NAMESPACE, NetProtocol.CHANNEL_PATH);

    /** La marque de Forge qu'un client moddé porte ; les contrôles de Forge la lisent sur le canal. */
    private static final AttributeKey<String> VERSION_FML = AttributeKey.valueOf("fml:netversion");

    /** Durée d'un tick du serveur, en nanosecondes. */
    private static final long TICK_NANOS = 50_000_000L;

    private final AxionRuntime runtime;
    private final MinecraftServer serveur;
    private final EmbeddedChannel canal;
    private final ClientSession session;
    private final RenderClock horloge;
    private final Map<Integer, Interpolation> interpolations = new HashMap<>();
    private final List<Recu> recus = new ArrayList<>();
    private final List<DefinitionRegistry> registres = new ArrayList<>();
    private final List<String> journal = new ArrayList<>();
    private ServerPlayer joueur;
    private long arrivee;
    private long octetsRecus;
    private int tramesRecues;
    private int acquittements;
    private byte[] dernierAcquittement;

    private JoueurSimule(AxionRuntime runtime, MinecraftServer serveur, EmbeddedChannel canal, ServerSnapshot instantane) {
        this.runtime = runtime;
        this.serveur = serveur;
        this.canal = canal;
        this.horloge = new RenderClock(instantane.handshake().interpolationDelayMs());
        this.session = new ClientSession(runtime.netCompression(), runtime.detProfile(), registre -> {
            // Les definitions du client simulé sont les siennes : le runtime est celui du serveur.
            RemoteRegistry.Loaded relu = RemoteRegistry.load(registre);
            registres.add(relu.definitions());
            return relu.journal();
        }, this::appliquer);
    }

    /**
     * Fait entrer un joueur simulé, sur le thread du serveur.
     *
     * @param level monde d'accueil
     * @param nom nom du joueur ; son identifiant en découle, comme sur un serveur hors ligne
     * @param position position dans le monde, en blocs
     * @return le joueur, entré en jeu
     * @throws IllegalStateException si AXION n'est pas chargé, ou n'a pas d'instantané réseau
     */
    public static JoueurSimule rejoindre(ServerLevel level, String nom, Vec3 position) {
        return rejoindre(level, nom, position, 0);
    }

    /**
     * Fait entrer un joueur simulé dont le débit est plafonné à part, sur le thread du serveur : sa
     * synchronisation est posée avant son entrée, à la place de celle de la configuration (T-418).
     *
     * @param level monde d'accueil
     * @param nom nom du joueur
     * @param position position dans le monde, en blocs
     * @param plafond son plafond de débit, en octets par seconde ; {@code 0} : celui de la configuration
     * @return le joueur, entré en jeu
     * @throws IllegalStateException si AXION n'est pas chargé, ou n'a pas d'instantané réseau
     */
    public static JoueurSimule rejoindre(ServerLevel level, String nom, Vec3 position, long plafond) {
        AxionRuntime runtime = RuntimeAccess.get();
        ServerSnapshot instantane = runtime == null ? null : runtime.netSnapshot();
        if (instantane == null) {
            throw new IllegalStateException("aucun instantané réseau : AXION n'est pas chargé");
        }
        Connection connexion = new Connection(PacketFlow.SERVERBOUND);
        EmbeddedChannel canal = new EmbeddedChannel(connexion);
        canal.attr(VERSION_FML).set("FML3");
        JoueurSimule simule = new JoueurSimule(runtime, level.getServer(), canal, instantane);
        // Les paquets de connexion que Forge enverrait à ce client distant, et ses réponses.
        ServerPeer pair = AxionNetwork.serverPeer(connexion);
        for (Pair<String, ChannelFrames.LoginToClient> paquet : AxionNetwork.loginPackets(false)) {
            pair.onLoginReply(simule.session.onLoginFrame(paquet.getRight().frame()));
        }
        simule.journal.addAll(simule.session.drainJournal());
        UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + nom).getBytes(StandardCharsets.UTF_8));
        if (plafond > 0) {
            int frequence = (int) runtime.outcome().config().getInt("net.snapshot_rate_hz");
            int limite = (int) runtime.outcome().config().getInt("net.max_packets_per_second");
            AxionNetwork.installSync(uuid, new PlayerSync(new SnapshotSender(frequence, plafond), limite));
        }
        ServerPlayer joueur = new ServerPlayer(level.getServer(), level, new GameProfile(uuid, nom));
        joueur.moveTo(position.x, position.y, position.z, 0f, 0f);
        level.getServer().getPlayerList().placeNewPlayer(connexion, joueur);
        simule.joueur = joueur;
        simule.tick();
        return simule;
    }

    /**
     * Un tick du client simulé, sur le thread du serveur : ce que le serveur lui a envoyé depuis le précédent
     * est lu comme le client le lirait, et ses acquittements repartent par le canal.
     */
    public void tick() {
        long maintenant = maintenant();
        horloge.onFrame(maintenant, false);
        arrivee = maintenant;
        Object message;
        while ((message = canal.readOutbound()) != null) {
            lire(message);
        }
    }

    private void lire(Object message) {
        if (message instanceof BundlePacket<?> groupe) {
            for (Packet<?> paquet : groupe.subPackets()) {
                lire(paquet);
            }
        } else if (message instanceof ClientboundRemoveEntitiesPacket retrait) {
            // L'entité quitte le monde du client : ce que le récepteur gardait d'elle s'oublie.
            retrait.getEntityIds().forEach(handle -> {
                session.forgetEntity(handle);
                interpolations.remove(handle);
            });
        } else if (message instanceof ClientboundCustomPayloadPacket charge && CANAL.equals(charge.getIdentifier())) {
            FriendlyByteBuf donnees = charge.getData();
            try {
                int discriminant = donnees.readUnsignedByte();
                if (discriminant != AxionChannel.PLAY_TO_CLIENT) {
                    journal.add("discriminant inattendu en jeu : " + discriminant);
                    return;
                }
                byte[] trame = ChannelFrames.PlayToClient.decode(donnees).frame();
                tramesRecues++;
                octetsRecus += trame == null ? 0 : trame.length;
                byte[] acquittement = session.onPlayFrame(trame);
                journal.addAll(session.drainJournal());
                if (acquittement != null) {
                    dernierAcquittement = acquittement;
                    canal.writeInbound(AxionChannel.CHANNEL.toVanillaPacket(
                            new ChannelFrames.PlayToServer(acquittement), NetworkDirection.PLAY_TO_SERVER));
                    acquittements++;
                }
            } finally {
                donnees.release();
            }
        }
    }

    private void appliquer(List<SnapshotReceiver.Applied> etats) {
        // Un snapshot porte un seul tick : ses états le partagent.
        horloge.onSnapshot(etats.get(0).serverTick(), arrivee);
        double rendu = horloge.renderMillis(arrivee);
        double local = horloge.localMillis(arrivee);
        for (SnapshotReceiver.Applied etat : etats) {
            interpolations.computeIfAbsent(etat.handle(), handle -> Interpolation.of(runtime.outcome().config()))
                    .push(etat.serverTick(), etat.state(), rendu, local);
            recus.add(new Recu(etat.handle(), etat.serverTick(), etat.state()));
        }
    }

    /**
     * {@return la pose qu'afficherait le client simulé pour une assembly, maintenant, ou {@code null} s'il n'en
     * a rien reçu}
     *
     * @param handle identifiant réseau de son entité
     */
    public Interpolation.Pose pose(int handle) {
        Interpolation interpolation = interpolations.get(handle);
        if (interpolation == null) {
            return null;
        }
        long maintenant = maintenant();
        return interpolation.sample(horloge.renderMillis(maintenant), horloge.localMillis(maintenant));
    }

    /** {@return l'heure du client simulé, en nanosecondes : 50 ms par tick du serveur} */
    private long maintenant() {
        return serveur.getTickCount() * TICK_NANOS;
    }

    /**
     * Fait quitter le joueur comme un client qui se déconnecte : Minecraft le retire, et Forge publie sa
     * sortie.
     */
    public void quitter() {
        joueur.connection.disconnect(Component.literal("fin du joueur simulé"));
        if (joueur.getServer().getPlayerList().getPlayer(joueur.getUUID()) == joueur) {
            // Le canal n'a pas fermé la connexion : Minecraft le retire comme à sa fermeture.
            joueur.getServer().getPlayerList().remove(joueur);
        }
        canal.finishAndReleaseAll();
    }

    /**
     * Envoie une trame au serveur comme le ferait un client, par le vrai canal : un acquittement, ou n'importe
     * quels octets — un client fautif ou hostile (R-1650).
     *
     * @param trame octets de la trame
     */
    public void envoyer(byte[] trame) {
        canal.writeInbound(AxionChannel.CHANNEL.toVanillaPacket(
                new ChannelFrames.PlayToServer(trame), NetworkDirection.PLAY_TO_SERVER));
    }

    /** {@return vrai si la connexion embarquée est encore ouverte} */
    public boolean connecte() {
        return canal.isOpen();
    }

    /** {@return le joueur, côté serveur} */
    public ServerPlayer joueur() {
        return joueur;
    }

    /** {@return sa synchronisation, côté serveur : ce qui lui a été envoyé et acquitté ; {@code null} hors du jeu} */
    public PlayerSync synchronisation() {
        return AxionNetwork.syncOf(joueur.getUUID());
    }

    /** {@return les états reçus, dans l'ordre de leur arrivée} */
    public List<Recu> recus() {
        return recus;
    }

    /**
     * {@return les états reçus depuis le dernier appel, retirés de l'historique} Une campagne longue les lit au fil
     * de l'eau, en mémoire bornée.
     */
    public List<Recu> drainRecus() {
        List<Recu> lus = new ArrayList<>(recus);
        recus.clear();
        return lus;
    }

    /** {@return les lignes du journal du client depuis le dernier appel, retirées} */
    public List<String> drainJournal() {
        List<String> lues = new ArrayList<>(journal);
        journal.clear();
        return lues;
    }

    /** {@return les registres que le client a appliqués : celui de la connexion, puis ceux renvoyés en jeu} */
    public List<DefinitionRegistry> registres() {
        return registres;
    }

    /** {@return le journal de la session du client simulé, à vider par l'appelant} */
    public List<String> journal() {
        return journal;
    }

    /** {@return les octets des trames AXION reçues} */
    public long octetsRecus() {
        return octetsRecus;
    }

    /** {@return le nombre de trames AXION reçues} */
    public int tramesRecues() {
        return tramesRecues;
    }

    /** {@return la trame du dernier acquittement envoyé, ou {@code null} : un client qui la renverrait en boucle} */
    public byte[] dernierAcquittement() {
        return dernierAcquittement;
    }

    /** {@return le nombre d'acquittements renvoyés au serveur : un par snapshot reçu} */
    public int acquittements() {
        return acquittements;
    }
}
