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

    private final SnapshotSender sender;
    private final Set<Integer> tracked = new LinkedHashSet<>();
    private final Fragments.Reassembler acks = new Fragments.Reassembler();
    private final List<String> journal = new ArrayList<>();

    /**
     * @param sender émetteur des snapshots de ce joueur
     */
    public PlayerSync(SnapshotSender sender) {
        this.sender = sender;
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
     * Bâtit les trames du snapshot de ce tick.
     *
     * @param serverTick tick du serveur
     * @param source état et distance des assemblies suivies
     * @param sequence numéro du message pour sa fragmentation, positif ou nul
     * @return les trames à envoyer, dans l'ordre ; vide si rien n'est dû
     */
    public List<byte[]> tick(long serverTick, Source source, int sequence) {
        List<SnapshotSender.Candidate> candidates = new ArrayList<>(tracked.size());
        for (int handle : tracked) {
            SnapshotSender.Candidate candidate = source.candidate(handle);
            if (candidate != null) {
                candidates.add(candidate);
            }
        }
        byte[] message = sender.tick(serverTick, candidates);
        return message == null ? List.of() : Fragments.split(message, sequence);
    }

    /**
     * Lit une trame d'acquittement du client. Ne lève jamais (R-1650) : une trame fautive est ignorée et
     * journalisée.
     *
     * @param frame trame reçue, ou {@code null} si le canal l'a écartée pour sa taille
     */
    public void onAckFrame(byte[] frame) {
        try {
            if (frame == null) {
                throw new MalformedPacketException("trame de plus de " + NetProtocol.MAX_FRAME_BYTES + " octets");
            }
            byte[] message = acks.accept(frame);
            if (message != null) {
                sender.onAck(AckMessage.decode(message));
            }
        } catch (MalformedPacketException error) {
            journal.add(error.report("acquittement"));
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
