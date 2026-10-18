package dev.axion.net;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * La connexion d'un client à un serveur AXION, vue du client (ADR-150 D4, D5) : le handshake reçu, le
 * mode de réplication qui en découle, le registre appliqué.
 *
 * <p>Une instance par connexion. Forge la nourrit depuis le thread de Netty de la connexion, pendant la
 * phase de connexion puis en jeu ; rien de ce qu'elle reçoit ne lève jusqu'au pipeline (R-1650).
 */
public final class ClientSession {

    /** Ce que le client fait d'un registre reçu, ses empreintes vérifiées. */
    @FunctionalInterface
    public interface RegistrySink {

        /**
         * @param registry registre du serveur
         * @return les lignes de journal de l'application
         */
        List<String> apply(RegistryContent registry);
    }

    private static final byte[] ACKNOWLEDGED = new byte[0];

    private final NetCompression compression;
    private final DetProfile self;
    private final RegistrySink sink;
    private final Fragments.Reassembler login = new Fragments.Reassembler();
    private final Fragments.Reassembler play = new Fragments.Reassembler();
    private final Queue<String> journal = new ConcurrentLinkedQueue<>();
    private volatile ServerHandshake server;
    private volatile ReplicationMode mode;

    /**
     * @param compression compression des charges utiles réseau, ou {@code null} si le runtime natif de
     *     ce client est inactif : le registre ne peut alors pas se lire
     * @param self empreinte déterministe de ce client
     * @param sink application d'un registre reçu
     */
    public ClientSession(NetCompression compression, DetProfile self, RegistrySink sink) {
        this.compression = compression;
        this.self = self;
        this.sink = sink;
    }

    /**
     * Traite une trame de la phase de connexion et rend la réponse due au serveur. Il y en a toujours
     * une : le serveur attend une réponse à chaque paquet de connexion avant de laisser entrer le
     * joueur, et une trame refusée n'en dispense pas.
     *
     * @param frame trame reçue, ou {@code null} si le canal l'a écartée pour sa taille
     * @return une trame à renvoyer, ou un tableau vide pour un simple acquittement
     */
    public synchronized byte[] onLoginFrame(byte[] frame) {
        byte[] message;
        try {
            message = login.accept(present(frame));
        } catch (MalformedPacketException error) {
            journal.add(error.report("connexion"));
            return ACKNOWLEDGED;
        }
        if (message == null) {
            return ACKNOWLEDGED;
        }
        int kind = message[0] & 0xFF;
        if (kind == MessageKinds.SERVER_HANDSHAKE) {
            readHandshake(message);
            // La réponse ne dépend pas de ce qui a été lu : l'empreinte de ce client est connue même
            // quand le handshake du serveur est refusé, et le serveur en a besoin pour choisir.
            return reply(new ClientHandshake(NetProtocol.PROTOCOL_VERSION, self).encode());
        }
        if (kind == MessageKinds.REGISTRY) {
            return reply(applyRegistry(message, "registre de la connexion").encode());
        }
        journal.add(new MalformedPacketException("genre " + kind + " inattendu en connexion")
                .report("connexion"));
        return ACKNOWLEDGED;
    }

    /**
     * Traite une trame reçue en jeu : en M4, le registre renvoyé après un rechargement des données du
     * serveur, ou parce que celui de la connexion ne correspondait plus.
     *
     * @param frame trame reçue, ou {@code null} si le canal l'a écartée pour sa taille
     */
    public synchronized void onPlayFrame(byte[] frame) {
        byte[] message;
        try {
            message = play.accept(present(frame));
        } catch (MalformedPacketException error) {
            journal.add(error.report("jeu"));
            return;
        }
        if (message == null) {
            return;
        }
        int kind = message[0] & 0xFF;
        if (kind == MessageKinds.REGISTRY) {
            applyRegistry(message, "registre en jeu");
        } else {
            journal.add(new MalformedPacketException("genre " + kind + " inattendu en jeu").report("jeu"));
        }
    }

    private static byte[] present(byte[] frame) throws MalformedPacketException {
        if (frame == null) {
            throw new MalformedPacketException("trame de plus de " + NetProtocol.MAX_FRAME_BYTES + " octets");
        }
        return frame;
    }

    private void readHandshake(byte[] message) {
        try {
            ServerHandshake received = ServerHandshake.decode(message);
            mode = ReplicationMode.negotiate(received.det(), self);
            server = received;
            journal.add("handshake du serveur reçu : protocole " + received.protocolVersion()
                    + ", mode de réplication " + mode + " (" + ReplicationMode.cause(received.det(), self) + ")");
        } catch (MalformedPacketException error) {
            journal.add(error.report("handshake du serveur"));
        }
    }

    /** {@return l'acquittement de ce que ce client tient après l'application} */
    private RegistryAck applyRegistry(byte[] message, String where) {
        if (compression == null) {
            journal.add(where + " ignoré : runtime natif inactif chez ce client");
            return RegistryAck.NONE;
        }
        RegistryContent registry;
        try {
            registry = RegistryMessage.decode(message, compression);
        } catch (MalformedPacketException error) {
            journal.add(error.report(where));
            return RegistryAck.NONE;
        }
        try {
            journal.addAll(sink.apply(registry));
        } catch (RuntimeException failure) {
            // Le registre est valide mais son application a échoué : le serveur le saura par
            // l'acquittement, et le renverra une fois en jeu.
            journal.add(where + " non appliqué : " + failure);
            return RegistryAck.NONE;
        }
        return RegistryAck.of(registry);
    }

    private static byte[] reply(byte[] message) {
        return Fragments.split(message, 0).get(0);
    }

    /** {@return le handshake du serveur, s'il est arrivé et lisible} */
    public Optional<ServerHandshake> server() {
        return Optional.ofNullable(server);
    }

    /** {@return le mode de réplication, tel que ce client le déduit des deux empreintes (T-820c)} */
    public Optional<ReplicationMode> mode() {
        return Optional.ofNullable(mode);
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
