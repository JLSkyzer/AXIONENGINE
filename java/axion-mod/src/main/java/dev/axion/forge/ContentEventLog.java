package dev.axion.forge;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Les derniers événements de contenu émis au serveur (ADR-174, axe Diagnostic), que {@code /axion events} montre : ce
 * qui a été émis, par qui, sur quelle assembly, à quel tick. Une file bornée, sur le thread autoritatif.
 */
final class ContentEventLog {

    /** Événements gardés, au plus. */
    static final int KEPT = 32;

    /**
     * Un événement émis.
     *
     * @param tick le tick du serveur
     * @param assembly l'assembly qui l'a émis
     * @param definition sa definition
     * @param event son nom
     * @param value sa valeur
     * @param writer qui l'a émis
     */
    record Entry(long tick, UUID assembly, String definition, String event, double value, String writer) {

        /** {@return la ligne qui le dit} */
        String line() {
            return String.format(Locale.ROOT, "  tick %d — %s (%s, %s) = %s, par %s", tick, event, definition,
                    assembly.toString().substring(0, 8), value == Math.rint(value) ? Long.toString((long) value)
                            : Double.toString(value), writer);
        }
    }

    private static final Deque<Entry> ENTRIES = new ArrayDeque<>();

    private ContentEventLog() {}

    /**
     * Garde un événement émis.
     *
     * @param entry l'événement
     */
    static synchronized void record(Entry entry) {
        if (ENTRIES.size() == KEPT) {
            ENTRIES.removeFirst();
        }
        ENTRIES.addLast(entry);
    }

    /**
     * {@return les derniers événements, du plus ancien au plus récent}
     *
     * @param assembly l'assembly dont on les veut, {@code null} pour toutes
     */
    static synchronized List<Entry> last(UUID assembly) {
        List<Entry> found = new ArrayList<>();
        for (Entry entry : ENTRIES) {
            if (assembly == null || entry.assembly().equals(assembly)) {
                found.add(entry);
            }
        }
        return found;
    }

    /** Oublie tout : le serveur s'arrête. */
    static synchronized void clear() {
        ENTRIES.clear();
    }
}
