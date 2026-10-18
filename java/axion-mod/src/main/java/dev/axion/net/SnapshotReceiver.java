package dev.axion.net;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Le récepteur des snapshots, chez le client (21.5, ADR-150 D6) : il reconstitue l'état de chaque assembly
 * depuis les entiers reçus, à l'identique de ce que le serveur a quantifié, et prépare l'acquittement.
 *
 * <p>Pour chaque assembly, il garde les états des {@link SnapshotSender#WINDOW} derniers snapshots qui la
 * portaient : une base plus ancienne, le serveur ne la nomme pas. Un delta dont il ne tient pas la base
 * n'est pas appliqué à une autre : l'entrée complète est redemandée au prochain acquittement. Rien de ce
 * qu'il reçoit ne lève (R-1650).
 */
public final class SnapshotReceiver {

    /**
     * L'état reconstitué d'une assembly, à appliquer à son entité.
     *
     * @param handle identifiant réseau de son entité
     * @param state son état
     * @param serverTick tick du serveur dont il est l'état
     */
    public record Applied(int handle, AssemblyState state, long serverTick) {}

    private record Held(int sequence, AssemblyState state) {}

    private final Map<Integer, ArrayDeque<Held>> held = new HashMap<>();
    private final Set<Integer> needFull = new LinkedHashSet<>();
    private final Queue<String> journal = new ConcurrentLinkedQueue<>();
    private int lastSequence;

    /**
     * Lit un snapshot.
     *
     * @param message message reçu, genre compris
     * @return les états reconstitués, à appliquer ; vide si le snapshot est refusé
     */
    public synchronized List<Applied> onSnapshot(byte[] message) {
        SnapshotMessage snapshot;
        try {
            snapshot = SnapshotMessage.decode(message);
            if (snapshot.sequence() <= lastSequence) {
                throw new MalformedPacketException("snapshot " + snapshot.sequence() + " après le "
                        + lastSequence);
            }
        } catch (MalformedPacketException error) {
            journal.add(error.report("snapshot"));
            return List.of();
        }
        lastSequence = snapshot.sequence();
        List<Applied> applied = new ArrayList<>(snapshot.entries().size());
        for (SnapshotMessage.Entry entry : snapshot.entries()) {
            AssemblyState state;
            if (entry.full()) {
                state = entry.resolve(null);
            } else {
                AssemblyState base = base(entry.handle(), entry.base());
                if (base == null) {
                    if (needFull.add(entry.handle())) {
                        journal.add("delta de l'entité " + entry.handle() + " sur le snapshot " + entry.base()
                                + ", que ce client ne tient pas : entrée complète redemandée");
                    }
                    continue;
                }
                state = entry.resolve(base);
            }
            needFull.remove(entry.handle());
            ArrayDeque<Held> history = held.computeIfAbsent(entry.handle(), handle -> new ArrayDeque<>());
            history.addLast(new Held(snapshot.sequence(), state));
            while (history.size() > SnapshotSender.WINDOW) {
                history.removeFirst();
            }
            applied.add(new Applied(entry.handle(), state, snapshot.serverTick()));
        }
        return applied;
    }

    private AssemblyState base(int handle, int sequence) {
        ArrayDeque<Held> history = held.get(handle);
        if (history == null) {
            return null;
        }
        for (Iterator<Held> it = history.descendingIterator(); it.hasNext(); ) {
            Held entry = it.next();
            if (entry.sequence() == sequence) {
                return entry.state();
            }
            if (entry.sequence() < sequence) {
                return null;
            }
        }
        return null;
    }

    /**
     * {@return l'acquittement à envoyer : le dernier snapshot reçu, et au plus
     * {@link AckMessage#MAX_FULL_REQUESTS} entités à renvoyer en entier} Les entités redemandées le restent
     * jusqu'à ce qu'une entrée complète arrive ; une demande qui ne tient pas dans cet acquittement part
     * avec le suivant.
     */
    public synchronized AckMessage ack() {
        List<Integer> requests = new ArrayList<>();
        for (int handle : needFull) {
            if (requests.size() == AckMessage.MAX_FULL_REQUESTS) {
                break;
            }
            requests.add(handle);
        }
        return new AckMessage(lastSequence, requests);
    }

    /**
     * Oublie une assembly dont l'entité a quitté ce client.
     *
     * @param handle identifiant réseau de son entité
     */
    public synchronized void forget(int handle) {
        held.remove(handle);
        needFull.remove(handle);
    }

    /**
     * @param handle identifiant réseau d'une entité
     * @return le nombre d'états qu'il garde pour elle, borné par la fenêtre
     */
    synchronized int historySize(int handle) {
        ArrayDeque<Held> history = held.get(handle);
        return history == null ? 0 : history.size();
    }

    /** {@return la séquence du dernier snapshot reçu} */
    public synchronized int lastSequence() {
        return lastSequence;
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
