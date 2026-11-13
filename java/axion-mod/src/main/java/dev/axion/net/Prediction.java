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
 *   <li>Égal au bit près : la prédiction était juste, rien ne bouge.</li>
 *   <li>Différent : le monde prédit reprend l'état du serveur et rejoue, un pas par entrée, celles envoyées
 *       après.</li>
 *   <li>Un rejeu qui dépasserait son budget n'a pas lieu, ou s'interrompt : l'état du serveur est accepté tel quel
 *       (R-1682). Les états prédits gardés deviennent caducs, et la correction suivante rejoue.</li>
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
    private static final double SMOOTHING = 0.125;

    /** Le monde prédit, vu de la boucle. */
    public interface World {

        /**
         * Fait un pas, l'entrée appliquée.
         *
         * @param input l'entrée du pas
         * @return l'état complet exporté après le pas ; {@code null} si le natif n'en a pas rendu
         */
        byte[] step(VehicleInput input);

        /**
         * Reprend l'état du serveur, avant le pas suivant.
         *
         * @param state le bloc d'état exporté par le serveur
         */
        void restore(byte[] state);
    }

    /** Ce qu'une correction a fait. */
    public enum Outcome {
        /** L'état prédit pour la séquence acquittée était celui du serveur, au bit près. */
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
    private double latencyMillis = -1;
    private double stepNanos = -1;
    private long lastServerTick = -1;
    private long matches;
    private long replays;
    private long accepted;

    /**
     * @param clock l'horloge, en nanosecondes
     * @param replayBudgetNanos {@code net.prediction_replay_budget_ns} : la durée qu'un rejeu ne dépasse pas
     */
    public Prediction(LongSupplier clock, long replayBudgetNanos) {
        if (replayBudgetNanos <= 0) {
            throw new IllegalArgumentException("budget de rejeu " + replayBudgetNanos);
        }
        this.clock = clock;
        this.replayBudgetNanos = replayBudgetNanos;
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
        byte[] predicted = world.step(input);
        long cost = clock.getAsLong() - start;
        stepNanos = stepNanos < 0 ? cost : stepNanos + SMOOTHING * (cost - stepNanos);
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
        measureLatency(appliedSequence, start);
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
        if (acknowledged != null && acknowledged.predicted != null
                && Arrays.equals(acknowledged.predicted, serverState)) {
            matches++;
            return new Reconciliation(Outcome.MATCH, 0, clock.getAsLong() - start);
        }
        world.restore(serverState);
        int pending = history.size();
        if (pending > 0 && stepNanos >= 0 && stepNanos * pending > replayBudgetNanos) {
            return acceptWithoutReplay(start);
        }
        int replayed = 0;
        for (Entry entry : history) {
            entry.predicted = world.step(entry.input);
            replayed++;
            if (clock.getAsLong() - start > replayBudgetNanos && replayed < pending) {
                // Interrompu : le monde reprend l'état du serveur, et rien de ce qui a été rejoué ne compte.
                world.restore(serverState);
                return acceptWithoutReplay(start);
            }
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
    }

    /**
     * {@return la latence aller-retour lissée, en millisecondes ; -1 avant la première mesure} De l'envoi d'une entrée
     * à la réception de la correction qui l'acquitte, attente dans la file du serveur comprise.
     */
    public double latencyMillis() {
        return latencyMillis;
    }

    /** {@return le coût lissé d'un pas du monde prédit, en nanosecondes ; -1 avant le premier} */
    public double stepNanos() {
        return stepNanos;
    }

    /** {@return les entrées gardées, appliquées mais pas encore acquittées} */
    public int pending() {
        return history.size();
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
