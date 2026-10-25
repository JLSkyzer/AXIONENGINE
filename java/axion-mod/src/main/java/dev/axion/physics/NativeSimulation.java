package dev.axion.physics;

import dev.axion.bridge.BufferKinds;
import dev.axion.bridge.NativeBridge;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Pilote du cycle de simulation (IF-03) côté Java, adossé au runtime natif.
 *
 * <p>Analogue de {@code NativeAssetCompiler} pour la physique : il traduit un
 * tick en {@code submit} → {@code collect} → lecture de {@code SIM_OUT}/{@code
 * EVENTS}, sans jamais conserver de vue d'un tampon (R-270) — chaque tampon est
 * ré-acquis puis relâché, et le bilan d'allocations reste équilibré à chaque
 * tick (R-322). À appeler sur le <strong>thread autoritatif</strong> (INV-03).
 *
 * <p>Chaque {@code collect} fait déposer par le natif ses charges dans
 * {@code SIM_OUT} et {@code EVENTS} : on les acquiert et relâche donc à chaque
 * tick, y compris à zéro état, pour ne rien laisser vivant. Les relâcher plutôt
 * que les garder réalloue un petit tampon par tick — négligeable tant qu'aucun
 * corps n'existe (C-32) ; l'optimisation « garder et réutiliser » viendra avec
 * une mesure, si elle se justifie.
 */
public final class NativeSimulation {

    /** Durée du dernier {@code submit} natif, en nanosecondes. */
    private long lastSubmitNanos;

    /** Position de la génération dans l'en-tête d'un tampon (IF-02). */
    private static final int GENERATION_OFFSET = 8;

    /** Position de la version de schéma dans l'en-tête d'un tampon (IF-02, R-271). */
    private static final int SCHEMA_OFFSET = 12;

    /**
     * Premier schéma de {@code SIM_OUT} où les emprises suivent les états (ADR-120). Fixe :
     * un schéma ultérieur n'ajoute qu'en fin, et garde ce préfixe.
     */
    private static final int BOUNDS_SINCE_SCHEMA = 1;

    /** Premier schéma de {@code SIM_OUT} où la section des joints suit les emprises (ADR-151 D7). */
    private static final int JOINTS_SINCE_SCHEMA = 2;

    private final long context;

    /** Réutilisé à chaque {@code collect} : aucune allocation par tick. */
    private final long[] collectScratch = new long[NativeBridge.SIM_COLLECT_SLOTS];

    /**
     * Crée un pilote adossé à un contexte natif.
     *
     * @param context jeton de contexte
     */
    public NativeSimulation(long context) {
        this.context = context;
    }

    /**
     * Déroule un tick complet : soumet les commandes puis récolte.
     *
     * <p>Si {@code submit} échoue, le cycle ouvert est refermé par {@code cancel}
     * et le résultat porte le code d'échec.
     *
     * @param tick numéro de tick du serveur autoritatif
     * @param commands commandes à appliquer (peut être vide)
     * @param deadlineNs délai du collect en nanosecondes, {@code 0} pour sans limite
     * @return le résultat du collect
     */
    public CollectResult tick(long tick, SimCommandStream commands, long deadlineNs) {
        int submitCode = submit(tick, commands);
        if (submitCode != NativeBridge.OK) {
            cancel();
            return CollectResult.failed(submitCode);
        }
        return collect(deadlineNs);
    }

    /**
     * Écrit les commandes dans {@code SIM_IN} et les soumet (IF-03).
     *
     * <p>Sans commande, aucun tampon n'est touché : {@code submit} ouvre le cycle
     * et le natif avancera la simulation au {@code collect}. Avec commandes, le
     * tampon est acquis, la charge écrite après l'en-tête (que le natif a
     * pré-rempli), puis relâché aussitôt — le natif a copié la tranche.
     *
     * @param tick numéro de tick
     * @param commands commandes à appliquer
     * @return le code de {@code submit}
     */
    public int submit(long tick, SimCommandStream commands) {
        int count = commands.count();
        if (count == 0) {
            long start = System.nanoTime();
            int code = NativeBridge.submit(context, tick, 0, 0);
            lastSubmitNanos = System.nanoTime() - start;
            return code;
        }

        byte[] stream = commands.toBytes();
        ByteBuffer buffer = NativeBridge.acquire(context, BufferKinds.SIM_IN, stream.length);
        if (buffer == null) {
            return NativeBridge.E_INVALID_BUFFER;
        }
        int generation = buffer.getInt(GENERATION_OFFSET);
        buffer.position(BufferKinds.HEADER_BYTES);
        if (buffer.remaining() < stream.length) {
            NativeBridge.release(context, BufferKinds.SIM_IN, generation);
            return NativeBridge.E_INVALID_BUFFER;
        }
        buffer.put(stream);

        long start = System.nanoTime();
        int code = NativeBridge.submit(context, tick, count, 0);
        lastSubmitNanos = System.nanoTime() - start;
        NativeBridge.release(context, BufferKinds.SIM_IN, generation);
        return code;
    }

    /**
     * {@return la durée du dernier {@code submit} natif, en nanosecondes} Le natif y applique les
     * commandes du tick, créations d'assemblies comprises (R-1730, ADR-153 D4).
     */
    public long lastSubmitNanos() {
        return lastSubmitNanos;
    }

    /**
     * Récolte le résultat d'un tick : avance la simulation, lit les états et les
     * événements déposés, et renvoie les compteurs (IF-03).
     *
     * @param deadlineNs délai en nanosecondes, {@code 0} pour sans limite
     * @return le résultat, en échec si le natif refuse le collect
     */
    public CollectResult collect(long deadlineNs) {
        int code = NativeBridge.collect(context, deadlineNs, collectScratch);
        if (code != NativeBridge.OK) {
            // Le collect a échoué avant de déposer quoi que ce soit : rien à lire
            // ni à relâcher.
            return CollectResult.failed(code);
        }

        int stateCount = (int) collectScratch[NativeBridge.SIM_STATE_COUNT];
        int eventCount = (int) collectScratch[NativeBridge.SIM_EVENT_COUNT];
        SimOut simOut = readSimOut(stateCount);
        List<PhysicsEvent> events = readEvents(eventCount);

        return new CollectResult(
                NativeBridge.OK,
                stateCount,
                eventCount,
                (int) collectScratch[NativeBridge.SIM_DEFORM_PAGE_COUNT],
                (int) collectScratch[NativeBridge.SIM_REFIT_COUNT],
                (int) collectScratch[NativeBridge.SIM_DETACH_COUNT],
                (int) collectScratch[NativeBridge.SIM_NET_BYTES],
                (int) collectScratch[NativeBridge.SIM_FLAGS],
                simOut.bodies(),
                simOut.bounds(),
                simOut.joints(),
                events);
    }

    /**
     * Annule le cycle de simulation courant (IF-03).
     *
     * @return {@link NativeBridge#OK}, ou un code d'erreur négatif
     */
    public int cancel() {
        return NativeBridge.cancel(context);
    }

    /**
     * Ce que {@code SIM_OUT} porte : les états, et s'il les annonce leurs emprises et les états
     * des joints.
     */
    private record SimOut(List<BodyState> bodies, List<BodyBounds> bounds, List<JointState> joints) {}

    /**
     * Lit les {@code state_count} états de {@code SIM_OUT}, depuis le schéma 1 leurs emprises
     * (ADR-120), depuis le schéma 2 les états des joints (ADR-151 D7), puis relâche (R-322).
     *
     * <p>Les emprises suivent les états, au même rang, et la section des joints les emprises.
     * Chacune n'est lue que si l'en-tête annonce un schéma qui la contient <strong>et</strong> si
     * le tampon la couvre : un natif antérieur dégrade en liste vide, il ne fait pas lire au-delà
     * de sa charge.
     */
    private SimOut readSimOut(int count) {
        ByteBuffer buffer = NativeBridge.acquire(context, BufferKinds.SIM_OUT, 0);
        if (buffer == null) {
            return new SimOut(List.of(), List.of(), List.of());
        }
        int generation = buffer.getInt(GENERATION_OFFSET);
        int schema = buffer.getInt(SCHEMA_OFFSET);
        List<BodyState> bodies = new ArrayList<>(count);
        List<BodyBounds> bounds = List.of();
        List<JointState> joints = List.of();
        long available = Math.max(0, buffer.capacity() - BufferKinds.HEADER_BYTES);
        if ((long) count * BodyState.BYTES <= available) {
            for (int i = 0; i < count; i++) {
                bodies.add(BodyState.decode(buffer, BufferKinds.HEADER_BYTES + i * BodyState.BYTES));
            }
            long withBounds = (long) count * (BodyState.BYTES + BodyBounds.BYTES);
            if (schema >= BOUNDS_SINCE_SCHEMA && withBounds <= available) {
                int boundsAt = BufferKinds.HEADER_BYTES + count * BodyState.BYTES;
                List<BodyBounds> read = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    read.add(BodyBounds.decode(buffer, boundsAt + i * BodyBounds.BYTES));
                }
                bounds = read;
                if (schema >= JOINTS_SINCE_SCHEMA) {
                    joints = JointState.decodeSection(
                            buffer, BufferKinds.HEADER_BYTES + count * (BodyState.BYTES + BodyBounds.BYTES));
                }
            }
        }
        NativeBridge.release(context, BufferKinds.SIM_OUT, generation);
        return new SimOut(bodies, bounds, joints);
    }

    /** Lit les {@code event_count} événements d'{@code EVENTS}, puis relâche (R-322). */
    private List<PhysicsEvent> readEvents(int count) {
        ByteBuffer buffer = NativeBridge.acquire(context, BufferKinds.EVENTS, 0);
        if (buffer == null) {
            return List.of();
        }
        int generation = buffer.getInt(GENERATION_OFFSET);
        List<PhysicsEvent> events = new ArrayList<>(count);
        int available = Math.max(0, buffer.capacity() - BufferKinds.HEADER_BYTES);
        if (count * PhysicsEvent.BYTES <= available) {
            for (int i = 0; i < count; i++) {
                events.add(PhysicsEvent.decode(buffer, BufferKinds.HEADER_BYTES + i * PhysicsEvent.BYTES));
            }
        }
        NativeBridge.release(context, BufferKinds.EVENTS, generation);
        return events;
    }
}
