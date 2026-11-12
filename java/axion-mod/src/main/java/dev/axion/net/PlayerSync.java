package dev.axion.net;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * La synchronisation des assemblies vers un joueur, en jeu (ADR-150 D2, D6) : les assemblies qu'il suit,
 * l'émetteur de ses snapshots, et la lecture de ses acquittements.
 *
 * <p>L'ensemble suivi est celui du suivi d'entités de Minecraft (D2) : une assembly y entre quand le
 * client a reçu son apparition, et en sort à sa disparition. Une instance par joueur, utilisée depuis le
 * thread du serveur seulement : les acquittements reçus sur Netty y sont repassés.
 *
 * <p>Limitation C→S (R-1690) : les trames du client se comptent par seconde du serveur, vingt ticks.
 * Au-delà de {@code net.max_packets_per_second}, une trame est ignorée et comptée, et la seconde qui en a
 * ignoré se dit au journal en une ligne. Après {@link #ABUSE_SECONDS} secondes de suite, le client est
 * abusif : son serveur le déconnecte. Un client honnête n'envoie qu'un acquittement par snapshot, vingt par
 * seconde au plus ; une seconde isolée au-delà — un client qui rattrape son retard d'un coup — ne coupe rien.
 */
public final class PlayerSync {

    /** D'où viennent l'état et la distance d'une assembly suivie, à ce tick. */
    @FunctionalInterface
    public interface Source {

        /**
         * @param handle identifiant réseau de son entité
         * @return ce qu'il y a à envoyer d'elle, ou {@code null} : pas encore d'état simulé, ou hors du
         *     rayon de synchronisation ({@code NONE}, R-1662)
         */
        SnapshotSender.Candidate candidate(int handle);
    }

    /**
     * Secondes consécutives au-delà de {@code net.max_packets_per_second} au bout desquelles le client est abusif
     * (R-1690).
     */
    public static final int ABUSE_SECONDS = 5;

    private final SnapshotSender sender;
    private final int maxPacketsPerSecond;
    private int packetsThisSecond;
    private int limitedThisSecond;
    private int ticksThisSecond;
    private int abusiveSeconds;
    private long limitedSinceTick;
    private long lastTickLimited;
    private final Set<Integer> tracked = new LinkedHashSet<>();
    private final Fragments.Reassembler acks = new Fragments.Reassembler();

    /** Plus grande séquence d'entrées reçue, non signée ; -1 avant la première. */
    private long lastInputSequence = -1;

    private final List<InputMessage> inputs = new ArrayList<>();

    /** Causes d'entrées refusées déjà journalisées pour ce joueur. */
    private final java.util.Set<String> reportedInputs = new java.util.HashSet<>();
    private final List<String> journal = new ArrayList<>();

    /**
     * Octets de snapshot et de correction envoyés à chacun des vingt derniers ticks, en anneau : la consommation du budget
     * « débit réseau par joueur » (25.2) est leur somme.
     */
    private final long[] lastSecond = new long[SnapshotSender.TICKS_PER_SECOND];
    private int slot;
    private long lastSecondBytes;
    private long lastTickBytes;
    private long lastTickDelayed;

    /** La correction de ce tick, en attente de son envoi (ADR-168 D4). */
    private byte[] correction;
    private long correctionBytes;
    private long correctionsSent;
    private long correctionsHeld;

    /**
     * Une synchronisation sans limitation des trames du client.
     *
     * @param sender émetteur des snapshots de ce joueur
     */
    public PlayerSync(SnapshotSender sender) {
        this(sender, Integer.MAX_VALUE);
    }

    /**
     * @param sender émetteur des snapshots de ce joueur
     * @param maxPacketsPerSecond {@code net.max_packets_per_second}
     */
    public PlayerSync(SnapshotSender sender, int maxPacketsPerSecond) {
        if (maxPacketsPerSecond < 1) {
            throw new IllegalArgumentException("limite de " + maxPacketsPerSecond + " paquet(s) par seconde");
        }
        this.sender = sender;
        this.maxPacketsPerSecond = maxPacketsPerSecond;
    }

    /**
     * Le joueur suit une assembly : son client a reçu l'apparition de son entité.
     *
     * @param handle identifiant réseau de l'entité
     */
    public void track(int handle) {
        tracked.add(handle);
    }

    /**
     * Le joueur ne suit plus une assembly : si elle revient, elle repart d'une entrée complète.
     *
     * @param handle identifiant réseau de l'entité
     */
    public void untrack(int handle) {
        tracked.remove(handle);
        sender.forget(handle);
    }

    /** {@return les assemblies que le joueur suit, copie} */
    public Set<Integer> tracked() {
        return Set.copyOf(tracked);
    }

    /**
     * Pose la correction du véhicule que ce joueur conduit, calculée à ce tick (ADR-168 D4) : elle part en fin de tick,
     * avant le snapshot ({@link #correctionFrames}).
     *
     * @param message le message {@code S2C_Correction}, genre compris
     */
    public void correct(byte[] message) {
        correction = message;
    }

    /** {@return vrai si une correction attend son envoi} */
    public boolean hasCorrection() {
        return correction != null;
    }

    /**
     * Les trames de la correction en attente, qui compte dans le débit du joueur (21.10). Sans débit, elle ne part pas :
     * la suivante, plus fraîche, la remplace.
     *
     * @param sequence numéro du message pour sa fragmentation, positif ou nul
     * @return ses trames ; vide si aucune n'attend, ou si le débit la retient
     */
    public List<byte[]> correctionFrames(int sequence) {
        byte[] message = correction;
        correction = null;
        if (message == null) {
            return List.of();
        }
        if (!sender.spend(message.length)) {
            correctionsHeld++;
            return List.of();
        }
        correctionsSent++;
        correctionBytes += message.length;
        return Fragments.split(message, sequence);
    }

    /** {@return les corrections parties depuis la connexion} */
    public long correctionsSent() {
        return correctionsSent;
    }

    /** {@return les corrections que le débit a retenues depuis la connexion} */
    public long correctionsHeld() {
        return correctionsHeld;
    }

    /**
     * Bâtit les trames du snapshot de ce tick.
     *
     * @param serverTick tick du serveur
     * @param source état et distance des assemblies suivies
     * @param sequence numéro du message pour sa fragmentation, positif ou nul
     * @return les trames à envoyer, dans l'ordre ; vide si rien n'est dû
     */
    public List<byte[]> tick(long serverTick, Source source, int sequence) {
        lastTickLimited = limitedSinceTick;
        limitedSinceTick = 0;
        if (++ticksThisSecond >= SnapshotSender.TICKS_PER_SECOND) {
            closeSecond();
        }
        List<SnapshotSender.Candidate> candidates = new ArrayList<>(tracked.size());
        for (int handle : tracked) {
            SnapshotSender.Candidate candidate = source.candidate(handle);
            if (candidate != null) {
                candidates.add(candidate);
            }
        }
        long delayedBefore = sender.delayedEntries();
        byte[] message = sender.tick(serverTick, candidates);
        lastTickDelayed = sender.delayedEntries() - delayedBefore;
        lastTickBytes = (message == null ? 0 : message.length) + correctionBytes;
        correctionBytes = 0;
        slot = (slot + 1) % lastSecond.length;
        lastSecondBytes += lastTickBytes - lastSecond[slot];
        lastSecond[slot] = lastTickBytes;
        return message == null ? List.of() : Fragments.split(message, sequence);
    }

    /** Clôt une seconde du serveur : celle qui a ignoré des trames se dit, et compte vers l'abus. */
    private void closeSecond() {
        if (limitedThisSecond > 0) {
            abusiveSeconds++;
            journal.add(new MalformedPacketException(limitedThisSecond + " trame(s) au-delà de " + maxPacketsPerSecond
                    + " par seconde (net.max_packets_per_second, R-1690)").report("limitation"));
        } else {
            abusiveSeconds = 0;
        }
        packetsThisSecond = 0;
        limitedThisSecond = 0;
        ticksThisSecond = 0;
    }

    /** {@return vrai si le client dépasse la limite depuis {@link #ABUSE_SECONDS} secondes : à déconnecter} */
    public boolean abusive() {
        return abusiveSeconds >= ABUSE_SECONDS;
    }

    /** {@return les trames du client ignorées par la limitation entre l'avant-dernier tick et le dernier} */
    public long lastTickLimited() {
        return lastTickLimited;
    }

    /** {@return les octets du snapshot et de la correction du dernier tick, en-têtes de fragments exclus} */
    public long lastTickBytes() {
        return lastTickBytes;
    }

    /**
     * {@return les octets de snapshot et de correction envoyés sur les vingt derniers ticks : la consommation du
     * budget {@code net.max_bytes_per_second_per_player}}
     */
    public long bytesLastSecond() {
        return lastSecondBytes;
    }

    /** {@return les entrées que le plafond a retardées au dernier tick} */
    public long lastTickDelayed() {
        return lastTickDelayed;
    }

    /**
     * Lit une trame du client : un acquittement, ou des entrées. Ne lève jamais (R-1650) : une trame fautive est ignorée et
     * journalisée.
     *
     * @param frame trame reçue, ou {@code null} si le canal l'a écartée pour sa taille
     */
    public void onClientFrame(byte[] frame) {
        if (++packetsThisSecond > maxPacketsPerSecond) {
            limitedThisSecond++;
            limitedSinceTick++;
            return;
        }
        try {
            if (frame == null) {
                throw new MalformedPacketException("trame de plus de " + NetProtocol.MAX_FRAME_BYTES + " octets");
            }
            byte[] message = acks.accept(frame);
            if (message == null) {
                return;
            }
            if (message.length > 0 && (message[0] & 0xFF) == MessageKinds.INPUT) {
                onInput(message);
            } else {
                sender.onAck(AckMessage.decode(message));
            }
        } catch (MalformedPacketException error) {
            journal.add(error.report("acquittement"));
        }
    }

    /** ADR-158 D4 : une séquence qui ne croît pas s'ignore ; les autres attendent le tick du serveur. */
    private void onInput(byte[] message) {
        InputMessage input;
        try {
            input = InputMessage.decode(message);
        } catch (MalformedPacketException error) {
            reportInput("message d'entrées mal formé");
            return;
        }
        long sequence = Integer.toUnsignedLong(input.sequence());
        if (sequence <= lastInputSequence) {
            reportInput("séquence d'entrées qui ne croît pas");
            return;
        }
        lastInputSequence = sequence;
        inputs.add(input);
    }

    /** {@return les entrées reçues depuis l'appel précédent, à appliquer dans cet ordre, puis les oublie} */
    public List<InputMessage> drainInputs() {
        List<InputMessage> drained = List.copyOf(inputs);
        inputs.clear();
        return drained;
    }

    /**
     * Journalise une entrée refusée, une fois par cause (R-710) : un client fautif enverrait sinon une ligne
     * par tick.
     *
     * @param cause ce qui ne va pas
     */
    public void reportInput(String cause) {
        if (reportedInputs.add(cause)) {
            journal.add(new MalformedPacketException(cause).report("entrées"));
        }
    }

    /** {@return l'émetteur de ce joueur, pour ses compteurs} */
    public SnapshotSender sender() {
        return sender;
    }

    /** {@return les lignes de journal accumulées depuis le dernier appel, émetteur compris} */
    public List<String> drainJournal() {
        List<String> lines = new ArrayList<>(journal);
        journal.clear();
        lines.addAll(sender.drainJournal());
        return lines;
    }
}
