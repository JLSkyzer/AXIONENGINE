package dev.axion.seat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Les entrées d'un siège en attente de leur tick (ADR-168 D3) : le serveur en applique une par tick, dans l'ordre des
 * séquences, comme le client qui prédit les rejoue — une par tick.
 *
 * <ul>
 *   <li>Une séquence qui ne croît pas est refusée.</li>
 *   <li>Chaque tick prend la plus ancienne. Une file vide ne rend rien : le siège garde l'entrée qu'il applique,
 *       jusqu'à ce que son silence dure ({@code net.extrapolation_max_ms}).</li>
 *   <li>Une file qui déborde rattrape : avant de prendre, elle saute aux {@link #MAX_PENDING} plus récentes. Une
 *       entrée n'attend ainsi qu'un tick au plus. Celles qu'elle saute sont rendues, dans l'ordre, pour que les
 *       commandes tenues gardent leurs fronts (ADR-163 D2).</li>
 * </ul>
 *
 * <p>Logique pure, sans Forge. Non synchronisée : l'entité qui la tient ne la touche que sur son fil.
 *
 * @param <T> ce que porte une entrée
 */
public final class InputQueue<T> {

    /**
     * Entrées en attente au plus, au moment d'en prendre une : celle de ce tick, et une pour le suivant. La gigue
     * d'arrivée en amène deux dans un tick et aucune dans le suivant ; au-delà, la file a pris du retard.
     */
    public static final int MAX_PENDING = 2;

    /**
     * Ce qu'un tick applique.
     *
     * @param skipped les entrées sautées pour rattraper, de la plus ancienne à la plus récente ; leurs fronts comptent,
     *     pas leurs axes
     * @param sequence la séquence de l'entrée appliquée
     * @param applied l'entrée appliquée
     * @param <T> ce que porte une entrée
     */
    public record Step<T>(List<T> skipped, long sequence, T applied) {

        /** Copie les entrées sautées. */
        public Step {
            skipped = List.copyOf(skipped);
        }
    }

    private record Pending<T>(long sequence, T item) {}

    private final ArrayDeque<Pending<T>> pending = new ArrayDeque<>();
    private long lastOffered = -1;
    private long lastApplied = -1;
    private long skippedTotal;
    private long starvedTotal;
    private final long[] depths = new long[DEPTHS];

    /** Profondeurs distinguées par {@link #depths()} ; la dernière compte aussi les plus profondes. */
    public static final int DEPTHS = 8;

    /**
     * Met une entrée en file.
     *
     * @param sequence sa séquence, positive ou nulle
     * @param item ce qu'elle porte
     * @return faux si la séquence ne croît pas : l'entrée est écartée
     * @throws IllegalArgumentException pour une séquence négative
     */
    public boolean offer(long sequence, T item) {
        if (sequence < 0) {
            throw new IllegalArgumentException("séquence négative : " + sequence);
        }
        if (sequence <= lastOffered) {
            return false;
        }
        lastOffered = sequence;
        pending.addLast(new Pending<>(sequence, item));
        return true;
    }

    /** {@return l'entrée de ce tick, après le rattrapage d'une file qui déborde ; {@code null} si la file est vide} */
    public Step<T> next() {
        if (lastOffered >= 0) {
            depths[Math.min(pending.size(), DEPTHS - 1)]++;
        }
        if (pending.isEmpty()) {
            if (lastOffered >= 0) {
                starvedTotal++;
            }
            return null;
        }
        List<T> skipped = new ArrayList<>();
        while (pending.size() > MAX_PENDING) {
            skipped.add(pending.removeFirst().item());
        }
        skippedTotal += skipped.size();
        Pending<T> taken = pending.removeFirst();
        lastApplied = taken.sequence();
        return new Step<>(skipped, taken.sequence(), taken.item());
    }

    /** {@return la séquence de la dernière entrée mise en file, -1 avant la première} */
    public long lastOffered() {
        return lastOffered;
    }

    /** {@return la séquence de la dernière entrée appliquée, -1 avant la première} */
    public long lastApplied() {
        return lastApplied;
    }

    /** {@return les entrées en attente} */
    public int size() {
        return pending.size();
    }

    /** {@return les entrées sautées pour rattraper, depuis la création de la file} */
    public long skippedTotal() {
        return skippedTotal;
    }

    /**
     * {@return les ticks passés sur une file vide depuis la première entrée : le siège y a gardé son entrée d'avant}
     */
    public long starvedTotal() {
        return starvedTotal;
    }

    /**
     * {@return combien de ticks, depuis la première entrée, ont trouvé la file à chaque profondeur avant de prendre :
     * l'indice est le nombre d'entrées en attente, le dernier compte aussi les plus profondes} Une copie.
     */
    public long[] depths() {
        return depths.clone();
    }
}
