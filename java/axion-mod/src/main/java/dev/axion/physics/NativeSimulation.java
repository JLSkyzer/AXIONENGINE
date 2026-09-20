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

    /** Position de la génération dans l'en-tête d'un tampon (IF-02). */
    private static final int GENERATION_OFFSET = 8;

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
            return NativeBridge.submit(context, tick, 0, 0);
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

        int code = NativeBridge.submit(context, tick, count, 0);
        NativeBridge.release(context, BufferKinds.SIM_IN, generation);
        return code;
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
        List<BodyState> bodies = readBodies(stateCount);
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
                bodies,
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

    /** Lit les {@code state_count} états de {@code SIM_OUT}, puis relâche (R-322). */
    private List<BodyState> readBodies(int count) {
        ByteBuffer buffer = NativeBridge.acquire(context, BufferKinds.SIM_OUT, 0);
        if (buffer == null) {
            return List.of();
        }
        int generation = buffer.getInt(GENERATION_OFFSET);
        List<BodyState> bodies = new ArrayList<>(count);
        int available = Math.max(0, buffer.capacity() - BufferKinds.HEADER_BYTES);
        if (count * BodyState.BYTES <= available) {
            for (int i = 0; i < count; i++) {
                bodies.add(BodyState.decode(buffer, BufferKinds.HEADER_BYTES + i * BodyState.BYTES));
            }
        }
        NativeBridge.release(context, BufferKinds.SIM_OUT, generation);
        return bodies;
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
