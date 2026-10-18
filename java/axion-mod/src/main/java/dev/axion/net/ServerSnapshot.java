package dev.axion.net;

import dev.axion.config.AxionConfig;
import java.util.ArrayList;
import java.util.List;

/**
 * Ce que le serveur envoie à chaque connexion (ADR-150 D4, D5) : son handshake, puis son registre, bâtis
 * une fois par rechargement des données, sur le thread du serveur.
 *
 * <p>Immuable : Forge rassemble les paquets de connexion sur un thread de Netty, à l'arrivée de chaque
 * client, et peut lire l'instantané pendant qu'un rechargement en bâtit un autre.
 */
public final class ServerSnapshot {

    /** Séquence du handshake, un message d'une seule trame. */
    static final int HANDSHAKE_SEQUENCE = 0;

    /** Séquence du registre envoyé pendant la connexion. */
    static final int LOGIN_REGISTRY_SEQUENCE = 1;

    private final ServerHandshake handshake;
    private final RegistryContent registry;
    private final byte[] handshakeFrame;
    private final byte[] registryMessage;
    private final List<String> notes;

    private ServerSnapshot(
            ServerHandshake handshake, RegistryContent registry, byte[] registryMessage, List<String> notes) {
        this.handshake = handshake;
        this.registry = registry;
        this.handshakeFrame = Fragments.split(handshake.encode(), HANDSHAKE_SEQUENCE).get(0);
        this.registryMessage = registryMessage;
        this.notes = List.copyOf(notes);
    }

    /**
     * Bâtit l'instantané d'un rechargement.
     *
     * <p>Un registre qui dépasse les bornes du protocole n'est pas envoyé, et le dit : les clients
     * distants se connectent sans definition et dessinent la boîte de repli, plutôt que de recevoir un
     * registre qu'ils refuseraient en entier.
     *
     * @param config configuration commune du serveur
     * @param registry registre à envoyer
     * @param excluded fichiers que l'admission a écartés, chacun avec sa raison
     * @param det empreinte déterministe du serveur
     * @param compression compression des charges utiles réseau
     * @return l'instantané
     */
    public static ServerSnapshot build(
            AxionConfig config,
            RegistryContent registry,
            List<String> excluded,
            DetProfile det,
            NetCompression compression) {
        List<String> notes = new ArrayList<>(excluded);
        byte[] message;
        try {
            message = RegistryMessage.encode(registry, compression);
        } catch (IllegalArgumentException | IllegalStateException refused) {
            message = null;
            notes.add("registre réseau non construit : " + refused.getMessage()
                    + " ; les clients distants n'en recevront aucun");
        }
        return new ServerSnapshot(ServerHandshake.of(config, registry, det), registry, message, notes);
    }

    /** {@return le handshake annoncé} */
    public ServerHandshake handshake() {
        return handshake;
    }

    /** {@return le registre que le serveur tient} */
    public RegistryContent registry() {
        return registry;
    }

    /** {@return vrai si le registre a pu être construit pour le réseau} */
    public boolean hasRegistry() {
        return registryMessage != null;
    }

    /**
     * Les trames des paquets de connexion : le handshake toujours, le registre seulement pour une
     * connexion distante. Une connexion locale — le joueur d'un monde solo, l'hôte d'un monde ouvert en
     * réseau local — partage le runtime du serveur intégré (ADR-118) : il tient déjà le registre.
     *
     * @param local vrai pour une connexion en mémoire
     * @return les trames, dans l'ordre d'envoi
     */
    public List<byte[]> loginFrames(boolean local) {
        List<byte[]> frames = new ArrayList<>();
        frames.add(handshakeFrame.clone());
        if (!local) {
            frames.addAll(registryFrames(LOGIN_REGISTRY_SEQUENCE));
        }
        return frames;
    }

    /**
     * @param sequence numéro du message chez l'émetteur, positif ou nul
     * @return les trames du registre, vide s'il n'a pas pu être construit
     */
    public List<byte[]> registryFrames(int sequence) {
        return registryMessage == null ? List.of() : Fragments.split(registryMessage, sequence);
    }

    /** {@return ce que la construction a écarté ou refusé, à journaliser} */
    public List<String> notes() {
        return notes;
    }

    /** {@return une ligne de journal qui décrit l'instantané} */
    public String describe() {
        String sent = registryMessage == null
                ? "non envoyé"
                : registryMessage.length + " octets compressés en " + registryFrames(0).size() + " trame(s)";
        return "registre réseau : "
                + registry.files(RegistryContent.Family.PHYSICS_MATERIALS).size() + " matériau(x) physique(s), "
                + registry.files(RegistryContent.Family.COLLISION_GROUPS).size() + " groupe(s) de collision, "
                + registry.files(RegistryContent.Family.DEFINITIONS).size() + " definition(s) ; " + sent;
    }
}
