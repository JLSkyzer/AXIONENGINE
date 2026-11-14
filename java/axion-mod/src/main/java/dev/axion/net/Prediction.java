package dev.axion.net;

import dev.axion.seat.VehicleInput;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Iterator;
import java.util.function.LongSupplier;

/**
 * La boucle du client qui prédit le véhicule qu'il conduit (ADR-168 D3, D5, R-1682) : chaque entrée envoyée
 * s'applique aussitôt au monde prédit et se garde, avec l'état qu'elle a produit, dans un historique de
 * {@value #HISTORY} ; chaque correction du serveur se compare à l'état prédit pour la séquence qu'elle acquitte.
 *
 * <ul>
 *   <li>Assez proche ({@link Tolerance}) : la prédiction était juste, rien ne bouge.</li>
 *   <li>Différent : le monde prédit reprend l'état du serveur et rejoue, un pas par entrée, celles envoyées
 *       après.</li>
 *   <li>Un rejeu s'arrête avant le pas qui dépasserait son budget, jugé sur le coût moyen de ses propres pas : l'état
 *       du serveur est accepté tel quel (R-1682). Les états prédits gardés deviennent caducs, et la correction
 *       suivante rejoue.</li>
 * </ul>
 *
 * <p>La latence se mesure par l'acquittement : la correction rend la séquence de la dernière entrée appliquée, dont le
 * client connaît l'heure d'envoi (D2).
 *
 * <p>Logique pure, sans Forge ni natif : le monde prédit est derrière {@link World}. Non synchronisée : le thread du
 * client seul s'en sert.
 */
public final class Prediction {

    /** Entrées gardées pour le rejeu (R-1682). */
    public static final int HISTORY = 64;

    /** Poids d'une mesure neuve dans la moyenne de la latence et du coût d'un pas. */
    static final double SMOOTHING = 0.125;

    /** Le monde prédit, vu de la boucle. */
    public interface World {

        /**
         * Fait un pas, l'entrée appliquée.
         *
         * @param sequence la séquence de l'entrée : le tick prédit du pas
         * @param input l'entrée du pas
         * @return l'état complet exporté après le pas ; {@code null} si le natif n'en a pas rendu
         */
        byte[] step(long sequence, VehicleInput input);

        /**
         * Reprend l'état du serveur, avant le pas suivant.
         *
         * @param sequence la séquence que la correction acquitte, -1 sans entrée appliquée : ce qui accompagne l'état
         *     physique — les variables prédites — y revient aussi
         * @param state le bloc d'état exporté par le serveur
         */
        void restore(long sequence, byte[] state);

        /**
         * {@return vrai si ce qui accompagne l'état physique prédit pour cette séquence — les variables prédites (ADR-168
         * D6) — vaut ce que la correction en cours dit du serveur} Une correction dont la physique est dans la tolérance,
         * mais pas les variables, se rejoue.
         *
         * @param sequence la séquence acquittée
         */
        default boolean agrees(long sequence) {
            return true;
        }
    }

    /**
     * Ce qui rend un état prédit assez proche de celui du serveur pour ne pas rejouer (D5). Deux mondes natifs distincts
     * ne calculent pas au bit près : leurs origines flottantes et l'état de contact non restauré les séparent.
     */
    @FunctionalInterface
    public interface Tolerance {

        /** L'égalité au bit près. */
        Tolerance EXACT = Arrays::equals;

        /**
         * @param predicted l'état prédit pour la séquence acquittée
         * @param server l'état du serveur
         * @return vrai s'ils sont assez proches pour ne pas rejouer
         */
        boolean close(byte[] predicted, byte[] server);
    }

    /** Ce qu'une correction a fait. */
    public enum Outcome {
        /** L'état prédit pour la séquence acquittée était celui du serveur, à la tolérance près. */
        MATCH,
        /** L'état du serveur repris, les entrées postérieures rejouées. */
        REPLAYED,
        /** L'état du serveur repris sans rejeu : le rejeu dépassait son budget (R-1682). */
        ACCEPTED,
        /** Une correction plus ancienne que la dernière traitée : ignorée. */
        STALE
    }

    /**
     * Le bilan d'une correction.
     *
     * @param outcome ce qu'elle a fait
     * @param replayed les pas rejoués
     * @param nanos la durée de la réconciliation
     */
    public record Reconciliation(Outcome outcome, int replayed, long nanos) {}

    /** Une entrée envoyée, appliquée au monde prédit, et l'état qu'elle y a produit ({@code null} : caduc). */
    private static final class Entry {
        final long sequence;
        final VehicleInput input;
        final long sentNanos;
        byte[] predicted;

        Entry(long sequence, VehicleInput input, long sentNanos, byte[] predicted) {
            this.sequence = sequence;
            this.input = input;
            this.sentNanos = sentNanos;
            this.predicted = predicted;
        }
    }

    private final ArrayDeque<Entry> history = new ArrayDeque<>(HISTORY);
    /** Les heures d'envoi des entrées récentes, prédites ou non : la latence se mesure même sans monde prédit. */
    private final ArrayDeque<long[]> sent = new ArrayDeque<>(HISTORY);
    private final LongSupplier clock;
    private final long replayBudgetNanos;
    private final Tolerance tolerance;
    /** Qui regarde chaque comparaison : l'état prédit pour la séquence acquittée, puis celui du serveur. */
    private java.util.function.BiConsumer<byte[], byte[]> observer = (predicted, server) -> {};
    private double latencyMillis = -1;
    private double stepNanos = -1;
    private long lastServerTick = -1;
    /** Le tick du serveur de la dernière latence mesurée : une correction ne compte qu'une fois. */
    private long lastMeasuredTick = -1;
    private long lastAppliedSequence = -1;
    private long matches;
    private long replays;
    private long accepted;

    /**
     * @param clock l'horloge, en nanosecondes
     * @param replayBudgetNanos {@code net.prediction_replay_budget_ns} : la durée qu'un rejeu ne dépasse pas
     */
    public Prediction(LongSupplier clock, long replayBudgetNanos) {
        this(clock, replayBudgetNanos, Tolerance.EXACT);
    }

    /**
     * @param clock l'horloge, en nanosecondes
     * @param replayBudgetNanos {@code net.prediction_replay_budget_ns} : la durée qu'un rejeu ne dépasse pas
     * @param tolerance ce qui est assez proche pour ne pas rejouer
     */
    public Prediction(LongSupplier clock, long replayBudgetNanos, Tolerance tolerance) {
        if (replayBudgetNanos <= 0) {
            throw new IllegalArgumentException("budget de rejeu " + replayBudgetNanos);
        }
        this.clock = clock;
        this.replayBudgetNanos = replayBudgetNanos;
        this.tolerance = tolerance;
    }

    /**
     * Pose qui regarde chaque comparaison d'une correction à la prédiction : les essais y mesurent l'écart.
     *
     * @param observer appelé avec l'état prédit pour la séquence acquittée ({@code null} sans) et celui du serveur
     */
    public void observe(java.util.function.BiConsumer<byte[], byte[]> observer) {
        this.observer = observer;
    }

    /**
     * Une entrée vient de partir : son heure compte pour la latence, prédite ou non.
     *
     * @param sequence sa séquence, non signée
     * @param sentNanos son heure d'envoi
     */
    public void sent(long sequence, long sentNanos) {
        sent.addLast(new long[] {sequence, sentNanos});
        while (sent.size() > HISTORY) {
            sent.removeFirst();
        }
    }

    /**
     * Applique au monde prédit l'entrée qui vient de partir, et la garde (D3).
     *
     * @param world le monde prédit
     * @param sequence sa séquence, non signée, celle de {@link #sent}
     * @param input l'entrée, bornée comme le serveur la borne
     */
    public void advance(World world, long sequence, VehicleInput input) {
        long start = clock.getAsLong();
        byte[] predicted = world.step(sequence, input);
        long sentNanos = sentAt(sequence);
        history.addLast(new Entry(sequence, input, sentNanos < 0 ? start : sentNanos, predicted));
        while (history.size() > HISTORY) {
            history.removeFirst();
        }
    }

    /**
     * Traite une correction du serveur (D5).
     *
     * @param world le monde prédit
     * @param serverTick son tick
     * @param appliedSequence la séquence acquittée, -1 sans entrée appliquée
     * @param serverState l'état du véhicule calculé par le serveur
     * @return ce qu'elle a fait
     */
    public Reconciliation reconcile(World world, long serverTick, long appliedSequence, byte[] serverState) {
        long start = clock.getAsLong();
        if (serverTick <= lastServerTick) {
            return new Reconciliation(Outcome.STALE, 0, 0);
        }
        lastServerTick = serverTick;
        lastAppliedSequence = appliedSequence;
        acknowledged(serverTick, appliedSequence);
        // Les entrées que le serveur a appliquées sortent de l'historique ; celle qu'il acquitte se compare.
        Entry acknowledged = null;
        for (Iterator<Entry> it = history.iterator(); it.hasNext(); ) {
            Entry entry = it.next();
            if (appliedSequence < 0 || entry.sequence > appliedSequence) {
                break;
            }
            if (entry.sequence == appliedSequence) {
                acknowledged = entry;
            }
            it.remove();
        }
        observer.accept(acknowledged == null ? null : acknowledged.predicted, serverState);
        if (acknowledged != null && acknowledged.predicted != null
                && tolerance.close(acknowledged.predicted, serverState) && world.agrees(appliedSequence)) {
            matches++;
            return new Reconciliation(Outcome.MATCH, 0, clock.getAsLong() - start);
        }
        world.restore(appliedSequence, serverState);
        // Le coût d'un pas se juge sur ce rejeu-ci, pas sur une moyenne passée : le premier pas d'un monde prédit,
        // tuiles découpées et code froid, en coûte cent fois plus que les suivants (170 ms contre 1,2 ms, mesuré en
        // jeu le 2026-11-13), et une estimation qu'il gonfle refuserait des rejeux qui tiennent.
        int replayed = 0;
        long stepping = 0;
        for (Entry entry : history) {
            long elapsed = clock.getAsLong() - start;
            if (elapsed > replayBudgetNanos || (replayed > 0 && elapsed + stepping / replayed > replayBudgetNanos)) {
                // Le pas suivant ne tiendrait pas : le monde reprend l'état du serveur, rien du rejeu ne compte.
                if (replayed > 0) {
                    world.restore(appliedSequence, serverState);
                }
                return acceptWithoutReplay(start);
            }
            long before = clock.getAsLong();
            entry.predicted = world.step(entry.sequence, entry.input);
            long cost = clock.getAsLong() - before;
            stepping += cost;
            stepNanos = stepNanos < 0 ? cost : stepNanos + SMOOTHING * (cost - stepNanos);
            replayed++;
        }
        replays++;
        return new Reconciliation(Outcome.REPLAYED, replayed, clock.getAsLong() - start);
    }

    private Reconciliation acceptWithoutReplay(long start) {
        for (Entry entry : history) {
            entry.predicted = null;
        }
        accepted++;
        return new Reconciliation(Outcome.ACCEPTED, 0, clock.getAsLong() - start);
    }

    private long sentAt(long sequence) {
        for (Iterator<long[]> it = sent.descendingIterator(); it.hasNext(); ) {
            long[] entry = it.next();
            if (entry[0] == sequence) {
                return entry[1];
            }
        }
        return -1;
    }

    /**
     * Une correction acquitte une entrée : la latence se mesure, que le monde prédit tourne ou non (D8 : une
     * prédiction coupée pour sa latence se rétablit quand elle redescend, et seule cette mesure le dit). Une fois par
     * tick du serveur.
     *
     * @param serverTick le tick de la correction
     * @param appliedSequence la séquence acquittée, -1 sans entrée appliquée
     */
    public void acknowledged(long serverTick, long appliedSequence) {
        if (serverTick <= lastMeasuredTick) {
            return;
        }
        lastMeasuredTick = serverTick;
        measureLatency(appliedSequence, clock.getAsLong());
    }

    private void measureLatency(long appliedSequence, long now) {
        if (appliedSequence < 0) {
            return;
        }
        long sentNanos = sentAt(appliedSequence);
        if (sentNanos < 0) {
            return;
        }
        double sample = (now - sentNanos) / 1e6;
        latencyMillis = latencyMillis < 0 ? sample : latencyMillis + SMOOTHING * (sample - latencyMillis);
    }

    /**
     * Oublie l'historique et les états prédits : le monde prédit est recréé, ou la prédiction coupée. La latence et
     * les heures d'envoi restent : elles valent pour la connexion.
     */
    public void reset() {
        history.clear();
        lastServerTick = -1;
        lastAppliedSequence = -1;
    }

    /**
     * {@return la latence aller-retour lissée, en millisecondes ; -1 avant la première mesure} De l'envoi d'une entrée
     * à la réception de la correction qui l'acquitte, attente dans la file du serveur comprise.
     */
    public double latencyMillis() {
        return latencyMillis;
    }

    /**
     * {@return le coût lissé d'un pas rejoué, en nanosecondes ; -1 avant le premier} Les pas en direct n'y comptent
     * pas : ils paient aussi les tuiles et les matériaux, qu'un rejeu ne touche pas.
     */
    public double stepNanos() {
        return stepNanos;
    }

    /** {@return les entrées gardées, appliquées mais pas encore acquittées} */
    public int pending() {
        return history.size();
    }

    /**
     * {@return le tick du serveur où s'applique l'entrée de cette séquence, estimé par la dernière correction : son tick
     * plus l'écart des séquences, une entrée par tick (D3) ; -1 avant toute correction qui acquitte une entrée}
     *
     * @param sequence la séquence d'une entrée
     */
    public long serverTickOf(long sequence) {
        return lastServerTick < 0 || lastAppliedSequence < 0 ? -1 : lastServerTick + (sequence - lastAppliedSequence);
    }

    /** {@return le tick du serveur de la dernière correction traitée, -1 avant} */
    public long lastServerTick() {
        return lastServerTick;
    }

    /** {@return les corrections égales à la prédiction, depuis la création} */
    public long matches() {
        return matches;
    }

    /** {@return les corrections rejouées, depuis la création} */
    public long replays() {
        return replays;
    }

    /** {@return les corrections acceptées sans rejeu, faute de budget, depuis la création} */
    public long accepted() {
        return accepted;
    }
}
