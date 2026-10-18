package dev.axion.net;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * L'émetteur des snapshots d'un joueur (21.5, ADR-150 D6) : quelles assemblies partent à ce tick, complètes
 * ou en delta, sous le plafond de débit du joueur.
 *
 * <ul>
 *   <li>Base d'un delta : la dernière entrée envoyée dans un snapshot que le client a acquitté, à moins de
 *       {@link #WINDOW} snapshots de distance (R-1661) ; à défaut, ou sur demande du client, une entrée
 *       complète.</li>
 *   <li>Fréquences (R-1662) : {@link Frequency#NORMAL} à {@code net.snapshot_rate_hz}, {@link Frequency#LOW}
 *       à 2 Hz ; une assembly endormie et inchangée n'envoie rien (R-1663).</li>
 *   <li>Plafond (R-1664) : un seau de {@code net.max_bytes_per_second_per_player}, rempli à chaque tick,
 *       d'au plus une seconde de débit. Ce qui n'y tient pas reste dû, passe en tête au tick suivant, et se
 *       compte : une entrée est retardée, jamais omise en silence (T-418).</li>
 * </ul>
 *
 * <p>Une instance par joueur, utilisée depuis le thread du serveur seulement.
 */
public final class SnapshotSender {

    /** Fréquence d'émission d'une assembly (R-1662). */
    public enum Frequency {
        /** 20 Hz : pilotée par un joueur proche. Le pilotage vient en M5. */
        HIGH,
        /** {@code net.snapshot_rate_hz}. */
        NORMAL,
        /** 2 Hz : endormie. */
        LOW;

        /**
         * La fréquence d'une assembly en M4 : endormie, {@link #LOW} ; sinon {@link #NORMAL}. La distance qui
         * rendra une assembly « lointaine » viendra avec les niveaux du chantier LOD ; {@link #HIGH} avec le
         * pilotage, en M5.
         *
         * @param state état de l'assembly
         * @return sa fréquence
         */
        public static Frequency of(AssemblyState state) {
            return state.sleeping() ? LOW : NORMAL;
        }
    }

    /**
     * Une assembly que le joueur suit, à ce tick.
     *
     * @param handle identifiant réseau de son entité
     * @param state son état
     * @param frequency sa fréquence
     * @param distance sa distance au joueur, en blocs : à ancienneté égale, la plus proche passe d'abord
     */
    public record Candidate(int handle, AssemblyState state, Frequency frequency, double distance) {}

    /** Fenêtre des bases d'un delta, en snapshots (R-1661). */
    static final int WINDOW = 32;

    /** Ticks du serveur par seconde. */
    static final int TICKS_PER_SECOND = 20;

    /** Fréquence de {@link Frequency#LOW}, en hertz. */
    static final int LOW_RATE_HZ = 2;

    /**
     * Fréquence {@link Frequency#NORMAL}. Les échéances se comptent en ticks multipliés par elle : chaque
     * période y est entière, et une fréquence qui ne divise pas 20 se tient exactement, sans dérive.
     */
    private final int rateHz;
    private final double bytesPerTick;
    private final double burst;
    private final Map<Integer, Track> tracks = new HashMap<>();
    private final Queue<String> journal = new ConcurrentLinkedQueue<>();
    private double tokens;
    private int sequence;
    private int acked;
    private long delayedEntries;
    private long sentBytes;
    private long sentSnapshots;
    private boolean degraded;

    /**
     * @param snapshotRateHz {@code net.snapshot_rate_hz}, la fréquence {@link Frequency#NORMAL}
     * @param maxBytesPerSecond {@code net.max_bytes_per_second_per_player}
     */
    public SnapshotSender(int snapshotRateHz, long maxBytesPerSecond) {
        if (snapshotRateHz < 1 || snapshotRateHz > TICKS_PER_SECOND) {
            throw new IllegalArgumentException("fréquence " + snapshotRateHz + " Hz hors de [1, 20]");
        }
        if (maxBytesPerSecond < 1) {
            throw new IllegalArgumentException("plafond de débit " + maxBytesPerSecond);
        }
        this.rateHz = snapshotRateHz;
        this.bytesPerTick = (double) maxBytesPerSecond / TICKS_PER_SECOND;
        this.burst = maxBytesPerSecond;
        this.tokens = burst;
    }

    /** Ce que l'émetteur sait d'une assembly. */
    private static final class Track {
        /** Entrées envoyées, de la plus ancienne à la plus récente. */
        final ArrayDeque<Sent> history = new ArrayDeque<>();
        AssemblyState lastSent;
        /** Échéance, en ticks multipliés par la fréquence {@link Frequency#NORMAL}. */
        long dueAt;
        long waitingSince = -1;
        boolean needFull;

        /** {@return la base d'un delta pour le snapshot {@code next}, ou {@code null}} */
        Sent baseline(int acked, int next) {
            for (Iterator<Sent> it = history.descendingIterator(); it.hasNext(); ) {
                Sent sent = it.next();
                if (sent.sequence() <= acked) {
                    return next - sent.sequence() <= WINDOW ? sent : null;
                }
            }
            return null;
        }

    }

    private record Sent(int sequence, AssemblyState state) {}

    private record Due(Candidate candidate, Track track) {}

    /**
     * Bâtit le snapshot de ce tick.
     *
     * @param serverTick tick du serveur
     * @param candidates assemblies que le joueur suit
     * @return le message, ou {@code null} si rien n'est à envoyer
     */
    public byte[] tick(long serverTick, List<Candidate> candidates) {
        tokens = Math.min(burst, tokens + bytesPerTick);
        List<Due> due = new ArrayList<>();
        for (Candidate candidate : candidates) {
            Track track = tracks.computeIfAbsent(candidate.handle(), handle -> new Track());
            boolean known = track.lastSent != null && !track.needFull;
            if (known && candidate.state().sleeping() && candidate.state().equals(track.lastSent)) {
                continue;
            }
            if (known && serverTick * rateHz < track.dueAt) {
                continue;
            }
            due.add(new Due(candidate, track));
        }
        if (due.isEmpty()) {
            noteDelays(0);
            return null;
        }
        // Le plus anciennement retardé d'abord, puis la fréquence la plus haute, puis le plus proche.
        due.sort(Comparator.<Due>comparingLong(d -> d.track().waitingSince < 0 ? Long.MAX_VALUE : d.track().waitingSince)
                .thenComparing(d -> d.candidate().frequency())
                .thenComparingDouble(d -> d.candidate().distance())
                .thenComparingInt(d -> d.candidate().handle()));

        int next = sequence + 1;
        List<SnapshotMessage.Entry> entries = new ArrayList<>();
        List<Due> sent = new ArrayList<>();
        double used = SnapshotMessage.headerSize(next, serverTick, Math.min(due.size(), SnapshotMessage.MAX_ENTRIES));
        for (Due d : due) {
            if (entries.size() == SnapshotMessage.MAX_ENTRIES) {
                break;
            }
            SnapshotMessage.Entry entry = entryFor(d, next);
            int size = SnapshotMessage.encodedSize(entry);
            // La première entrée part dès qu'il reste du débit, quitte à creuser le seau : une entrée plus
            // grosse qu'un tick de débit ne doit pas rester bloquée.
            boolean fits = used + size <= tokens || (entries.isEmpty() && tokens > 0);
            if (!fits) {
                break;
            }
            entries.add(entry);
            sent.add(d);
            used += size;
        }
        int delayed = due.size() - sent.size();
        for (int index = sent.size(); index < due.size(); index++) {
            Track track = due.get(index).track();
            if (track.waitingSince < 0) {
                track.waitingSince = serverTick;
            }
        }
        delayedEntries += delayed;
        noteDelays(delayed);
        if (entries.isEmpty()) {
            return null;
        }

        byte[] message = new SnapshotMessage(next, serverTick, SnapshotMessage.DESCRIPTOR_VERSION, entries).encode();
        tokens -= message.length;
        sequence = next;
        sentBytes += message.length;
        sentSnapshots++;
        for (Due d : sent) {
            Track track = d.track();
            AssemblyState state = d.candidate().state();
            long period = period(d.candidate().frequency());
            // L'échéance avance d'une période depuis la précédente ; la première part du premier envoi, et
            // un retard ne s'accumule pas en dette.
            long now = serverTick * rateHz;
            boolean onTime = track.lastSent != null && track.dueAt + period > now;
            track.dueAt = onTime ? track.dueAt + period : now + period;
            track.history.addLast(new Sent(next, state));
            while (track.history.size() > WINDOW) {
                track.history.removeFirst();
            }
            track.lastSent = state;
            track.needFull = false;
            track.waitingSince = -1;
        }
        return message;
    }

    private SnapshotMessage.Entry entryFor(Due due, int next) {
        Candidate candidate = due.candidate();
        Sent base = due.track().needFull ? null : due.track().baseline(acked, next);
        return base == null
                ? SnapshotMessage.Entry.full(candidate.handle(), candidate.state())
                : SnapshotMessage.Entry.delta(candidate.handle(), base.sequence(), base.state(), candidate.state());
    }

    /** {@return la période d'une fréquence, en ticks multipliés par la fréquence {@link Frequency#NORMAL}} */
    private long period(Frequency frequency) {
        return switch (frequency) {
            case HIGH -> rateHz;
            case NORMAL -> TICKS_PER_SECOND;
            case LOW -> (long) rateHz * (TICKS_PER_SECOND / LOW_RATE_HZ);
        };
    }

    /** Journalise l'entrée dans la dégradation, puis la sortie : une ligne à chaque bascule. */
    private void noteDelays(int delayed) {
        if (delayed > 0 && !degraded) {
            degraded = true;
            journal.add("débit plafonné : " + delayed + " entrée(s) de snapshot retardée(s) à ce tick, envoyées"
                    + " par ancienneté aux ticks suivants (R-1664)");
        } else if (delayed == 0 && degraded) {
            degraded = false;
            journal.add("débit revenu sous le plafond ; " + delayedEntries + " entrée(s) retardée(s) en tout");
        }
    }

    /**
     * Prend en compte l'acquittement du client.
     *
     * @param ack acquittement reçu
     */
    public void onAck(AckMessage ack) {
        if (ack.lastSequence() > sequence) {
            journal.add(new MalformedPacketException("acquittement du snapshot " + ack.lastSequence()
                    + ", le dernier envoyé est " + sequence).report("acquittement"));
            return;
        }
        acked = Math.max(acked, ack.lastSequence());
        for (int handle : ack.needFull()) {
            Track track = tracks.get(handle);
            if (track != null) {
                track.needFull = true;
            }
        }
    }

    /**
     * Oublie une assembly que le joueur ne suit plus : si elle revient, elle repart d'une entrée complète.
     *
     * @param handle identifiant réseau de son entité
     */
    public void forget(int handle) {
        tracks.remove(handle);
    }

    /** {@return la séquence du dernier snapshot envoyé} */
    public int sequence() {
        return sequence;
    }

    /** {@return la séquence du dernier snapshot acquitté} */
    public int acked() {
        return acked;
    }

    /** {@return le nombre d'entrées retardées par le plafond, depuis la création} */
    public long delayedEntries() {
        return delayedEntries;
    }

    /** {@return les octets de snapshot envoyés, depuis la création} */
    public long sentBytes() {
        return sentBytes;
    }

    /** {@return le nombre de snapshots envoyés, depuis la création} */
    public long sentSnapshots() {
        return sentSnapshots;
    }

    /**
     * @param handle identifiant réseau d'une entité
     * @return le nombre d'entrées envoyées qu'il garde pour elle, bornée par la fenêtre
     */
    int historySize(int handle) {
        Track track = tracks.get(handle);
        return track == null ? 0 : track.history.size();
    }

    /** {@return le nombre d'assemblies suivies} */
    public int tracked() {
        return tracks.size();
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
