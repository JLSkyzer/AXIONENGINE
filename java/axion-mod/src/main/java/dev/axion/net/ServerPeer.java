package dev.axion.net;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Ce que le serveur sait d'un client, d'après ses réponses aux paquets de connexion (ADR-150 D4).
 *
 * <p>Une instance par connexion. Forge la nourrit depuis le thread de Netty de la connexion ; le serveur
 * la lit depuis le sien à l'entrée en jeu, et la commande de statut aussi : ce qu'elle a appris se publie
 * par des champs volatils.
 */
public final class ServerPeer {

    private final DetProfile server;
    private final Fragments.Reassembler replies = new Fragments.Reassembler();
    private final Queue<String> journal = new ConcurrentLinkedQueue<>();
    private volatile ClientHandshake client;
    private volatile ReplicationMode mode;
    private volatile String cause;
    private volatile RegistryAck registry;

    /**
     * @param server empreinte déterministe annoncée par le serveur à ce client
     */
    public ServerPeer(DetProfile server) {
        this.server = server;
    }

    /**
     * Lit la réponse d'un client à un paquet de connexion. Ne lève jamais (R-1650) : une réponse
     * fautive est ignorée et journalisée.
     *
     * @param reply réponse reçue : une trame ; vide pour le simple acquittement d'un fragment ;
     *     {@code null} si le canal l'a écartée pour sa taille
     */
    public synchronized void onLoginReply(byte[] reply) {
        if (reply != null && reply.length == 0) {
            return;
        }
        try {
            if (reply == null) {
                throw new MalformedPacketException(
                        "trame de plus de " + NetProtocol.MAX_FRAME_BYTES + " octets");
            }
            byte[] message = replies.accept(reply);
            if (message == null) {
                return;
            }
            int kind = message[0] & 0xFF;
            if (kind == MessageKinds.CLIENT_HANDSHAKE) {
                ClientHandshake handshake = ClientHandshake.decode(message);
                cause = ReplicationMode.cause(server, handshake.det());
                mode = ReplicationMode.negotiate(server, handshake.det());
                client = handshake;
            } else if (kind == MessageKinds.REGISTRY_ACK) {
                registry = RegistryAck.decode(message);
            } else {
                throw new MalformedPacketException("genre " + kind + " inattendu en réponse de connexion");
            }
        } catch (MalformedPacketException error) {
            journal.add(error.report("réponse de connexion"));
        }
    }

    /** {@return la réponse du client au handshake, si elle est arrivée et lisible} */
    public Optional<ClientHandshake> client() {
        return Optional.ofNullable(client);
    }

    /** {@return le mode de réplication négocié, dès la réponse au handshake (T-820c)} */
    public Optional<ReplicationMode> mode() {
        return Optional.ofNullable(mode);
    }

    /** {@return ce que le client a dit tenir après le registre de la connexion} */
    public Optional<RegistryAck> registry() {
        return Optional.ofNullable(registry);
    }

    /**
     * Dit s'il faut envoyer le registre en jeu à ce client : un client distant qui n'a rien acquitté,
     * ou acquitté un autre registre que le registre courant — un rechargement a eu lieu pendant sa
     * connexion, ou il n'a pas pu appliquer celui qu'il a reçu.
     *
     * @param current instantané courant du serveur
     * @param local vrai pour une connexion en mémoire, qui partage le registre du serveur
     * @return vrai s'il faut envoyer le registre
     */
    public boolean needsRegistry(ServerSnapshot current, boolean local) {
        RegistryAck held = registry;
        return !local && current.hasRegistry() && (held == null || !held.matches(current.registry()));
    }

    /**
     * {@return le mode de réplication et sa cause, ou pourquoi il n'est pas connu} 5.12bis veut le mode
     * journalisé une fois par session, avec sa cause, et affiché par {@code /axion status}.
     */
    public String describe() {
        ReplicationMode negotiated = mode;
        return negotiated == null ? "aucune réponse au handshake" : negotiated + " (" + cause + ")";
    }

    /** {@return les lignes de journal accumulées depuis le dernier appel} */
    public List<String> drainJournal() {
        List<String> lines = new ArrayList<>();
        for (String line; (line = journal.poll()) != null; ) {
            lines.add(line);
        }
        return lines;
    }
}
