package dev.axion.net;

import dev.axion.seat.VehicleInput;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Function;

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

    /** Ce que le client fait des états reçus par un snapshot : il les applique à ses entités. */
    @FunctionalInterface
    public interface SnapshotSink {

        /**
         * @param states états reconstitués, un par assembly
         */
        void apply(List<SnapshotReceiver.Applied> states);
    }

    private static final byte[] ACKNOWLEDGED = new byte[0];

    private final NetCompression compression;
    private final DetProfile self;
    private final RegistrySink sink;
    private final SnapshotSink snapshotSink;
    private final Function<String, String> installed;

    /** Vrai si un mod de ce client enregistre ce contrôleur de véhicule (ADR-167 Q7). */
    private final java.util.function.Predicate<String> registered;
    private final SnapshotReceiver snapshots = new SnapshotReceiver();
    private int nextInputSequence;

    private final Fragments.Reassembler login = new Fragments.Reassembler();
    private final Fragments.Reassembler play = new Fragments.Reassembler();
    private final Queue<String> journal = new ConcurrentLinkedQueue<>();
    private volatile ServerHandshake server;
    private volatile ReplicationMode mode;
    /** La dernière correction reçue du serveur (ADR-168 D4) : celle du véhicule que ce joueur conduit. */
    private volatile CorrectionMessage lastCorrection;
    private volatile long corrections;

    /**
     * @param compression compression des charges utiles réseau, ou {@code null} si le runtime natif de
     *     ce client est inactif : le registre ne peut alors pas se lire
     * @param self empreinte déterministe de ce client
     * @param sink application d'un registre reçu
     * @param snapshotSink application des états reçus par un snapshot
     * @param installed la version d'un mod installé chez ce client, ou {@code null} s'il ne l'est pas : la réponse
     *     au handshake en donne une par addon que le serveur exige (ADR-165 D2)
     */
    public ClientSession(NetCompression compression, DetProfile self, RegistrySink sink, SnapshotSink snapshotSink,
            Function<String, String> installed) {
        this(compression, self, sink, snapshotSink, installed,
                id -> dev.axion.definition.VehicleExtensions.current().controller(id).isPresent());
    }

    /**
     * @param compression compression des charges utiles réseau, ou {@code null} si le runtime natif est inactif
     * @param self empreinte déterministe de ce client
     * @param sink application d'un registre reçu
     * @param snapshotSink application des états reçus par un snapshot
     * @param installed la version d'un mod installé chez ce client, ou {@code null} s'il ne l'est pas
     * @param registered vrai si un mod de ce client enregistre ce contrôleur de véhicule : la réponse au handshake
     *     nomme ceux que le serveur annonce et qui manquent (ADR-167 Q7)
     */
    public ClientSession(NetCompression compression, DetProfile self, RegistrySink sink, SnapshotSink snapshotSink,
            Function<String, String> installed, java.util.function.Predicate<String> registered) {
        this.compression = compression;
        this.self = self;
        this.sink = sink;
        this.snapshotSink = snapshotSink;
        this.installed = installed;
        this.registered = registered;
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
            // L'empreinte de ce client est connue même quand le handshake du serveur est refusé, et le serveur
            // en a besoin pour choisir ; les versions d'addons, elles, répondent à la liste reçue.
            return reply(new ClientHandshake(NetProtocol.PROTOCOL_VERSION, self, versions(server),
                    missingControllers(server)).encode());
        }
        if (kind == MessageKinds.REGISTRY) {
            return reply(applyRegistry(message, "registre de la connexion").encode());
        }
        journal.add(new MalformedPacketException("genre " + kind + " inattendu en connexion")
                .report("connexion"));
        return ACKNOWLEDGED;
    }

    /**
     * Traite une trame reçue en jeu : un snapshot (21.5), ou le registre renvoyé après un rechargement
     * des données du serveur, ou parce que celui de la connexion ne correspondait plus.
     *
     * @param frame trame reçue, ou {@code null} si le canal l'a écartée pour sa taille
     * @return la trame de l'acquittement à renvoyer après un snapshot, {@code null} sinon
     */
    public synchronized byte[] onPlayFrame(byte[] frame) {
        byte[] message;
        try {
            message = play.accept(present(frame));
        } catch (MalformedPacketException error) {
            journal.add(error.report("jeu"));
            return null;
        }
        if (message == null) {
            return null;
        }
        int kind = message[0] & 0xFF;
        if (kind == MessageKinds.SNAPSHOT) {
            List<SnapshotReceiver.Applied> states = snapshots.onSnapshot(message);
            journal.addAll(snapshots.drainJournal());
            if (!states.isEmpty()) {
                try {
                    snapshotSink.apply(states);
                } catch (RuntimeException failure) {
                    journal.add("états du snapshot non appliqués : " + failure);
                }
            }
            // Acquitté même refusé : le serveur y lit aussi les entrées à renvoyer en entier.
            return reply(snapshots.ack().encode());
        }
        if (kind == MessageKinds.CORRECTION) {
            try {
                lastCorrection = CorrectionMessage.decode(message);
                corrections++;
            } catch (MalformedPacketException error) {
                journal.add(error.report("correction"));
            }
        } else if (kind == MessageKinds.REGISTRY) {
            applyRegistry(message, "registre en jeu");
        } else {
            journal.add(new MalformedPacketException("genre " + kind + " inattendu en jeu").report("jeu"));
        }
        return null;
    }

    /**
     * {@return la dernière correction reçue du serveur (ADR-168 D4), {@code null} avant la première} Elle porte l'état
     * du véhicule que ce joueur conduit, la séquence de sa dernière entrée appliquée, ses variables.
     */
    public CorrectionMessage lastCorrection() {
        return lastCorrection;
    }

    /** {@return les corrections reçues depuis la connexion} */
    public long corrections() {
        return corrections;
    }

    /**
     * Oublie une assembly dont l'entité a quitté ce client : si elle revient, le serveur la renverra en
     * entier.
     *
     * @param handle identifiant réseau de son entité
     */
    public void forgetEntity(int handle) {
        snapshots.forget(handle);
    }

    /**
     * {@return la trame des entrées de ce tick client (ADR-158 D4), sous la séquence suivante de la connexion}
     *
     * @param entity identifiant réseau de l'assembly
     * @param seat rang du siège
     * @param input les entrées
     */
    public synchronized byte[] inputFrame(int entity, int seat, VehicleInput input) {
        return reply(new InputMessage(nextInputSequence++, entity, seat, input).encode());
    }

    /** {@return la séquence des dernières entrées envoyées, non signée ; -1 avant les premières} */
    public synchronized long lastInputSequence() {
        return nextInputSequence == 0 ? -1 : Integer.toUnsignedLong(nextInputSequence - 1);
    }

    private static byte[] present(byte[] frame) throws MalformedPacketException {
        if (frame == null) {
            throw new MalformedPacketException("trame de plus de " + NetProtocol.MAX_FRAME_BYTES + " octets");
        }
        return frame;
    }

    /**
     * {@return la version que ce client a de chaque addon que le serveur exige, dans son ordre ; vide pour un addon
     * absent} Une version trop longue pour le message s'écrit {@code ?} : elle diffère de toute façon de celle du
     * serveur, qui tient dans la limite.
     */
    /** {@return les contrôleurs que le serveur annonce et qu'aucun mod de ce client n'enregistre, dans son ordre} */
    private List<String> missingControllers(ServerHandshake received) {
        if (received == null || registered == null) {
            return List.of();
        }
        return received.controllers().stream().filter(id -> !registered.test(id)).toList();
    }

    private List<String> versions(ServerHandshake received) {
        if (received == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>(received.requiredMods().size());
        for (RequiredMod mod : received.requiredMods()) {
            String version = installed.apply(mod.modId());
            if (version == null) {
                out.add("");
            } else if (version.isEmpty()
                    || version.getBytes(StandardCharsets.UTF_8).length > RequiredMod.MAX_VERSION_BYTES) {
                out.add("?");
            } else {
                out.add(version);
            }
        }
        return out;
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
