package dev.axion.render;

import dev.axion.bridge.BufferKinds;
import dev.axion.bridge.NativeBridge;
import java.nio.ByteBuffer;

/**
 * Le culling d'une frame par le natif (IF-05, ADR-154) : écrire les candidats dans
 * {@code RENDER_IN}, préparer, lire les retenus dans {@code RENDER_OUT}, rendre la frame.
 *
 * <p>Les tampons sont ré-acquis à chaque frame et toujours relâchés (R-270, R-322), et la frame
 * est rendue dès ses retenus lus (R-301) : rien du natif ne survit à l'appel. Sur le fil de rendu
 * seul. La préparation ne prend pas le verrou que tient le pas physique (ADR-154 D2) : en solo,
 * elle n'attend pas le serveur intégré.
 */
public final class NativeCulling {

    /** Écrit les candidats d'une frame. */
    @FunctionalInterface
    public interface Candidates {

        /**
         * Écrit le candidat de rang {@code rank} à {@code offset}, par
         * {@link RenderPrepCodec#putCandidate}.
         *
         * @param buffer {@code RENDER_IN}
         * @param offset position du candidat, en-tête du tampon compris
         * @param rank rang du candidat
         */
        void write(ByteBuffer buffer, int offset, int rank);
    }

    /**
     * Ce qu'une frame a donné.
     *
     * @param code {@link NativeBridge#OK}, ou le code de l'échec : rien n'est alors retenu
     * @param retained rangs des candidats retenus, dans l'ordre des candidats
     * @param levels niveau de détail de chaque retenu
     * @param meshes pour chaque retenu, le mesh de chacun de ses dessins, dans l'ordre de la liste au
     *     repos (ADR-154, tranche 2b)
     * @param flags drapeaux du résultat, voir {@link NativeBridge#RENDER_DEGRADED}
     * @param triangles triangles estimés des retenus
     */
    public record Outcome(int code, int[] retained, int[] levels, int[][] meshes, int flags, long triangles) {

        static Outcome failed(int code) {
            return new Outcome(code, new int[0], new int[0], new int[0][], 0, 0);
        }
    }

    /** Position de la génération dans l'en-tête d'un tampon (IF-02). */
    private static final int GENERATION_OFFSET = 8;

    private final long context;
    private final long[] result = new long[NativeBridge.RENDER_PREPARE_SLOTS];

    /**
     * Crée un culling adossé à un contexte natif.
     *
     * @param context jeton de contexte
     */
    public NativeCulling(long context) {
        this.context = context;
    }

    /**
     * Culle une frame.
     *
     * @param camera {@code AxionCameraDesc}, par {@link RenderPrepCodec#camera}, qui annonce
     *     {@code count} candidats
     * @param count nombre de candidats
     * @param candidates qui écrit chacun d'eux
     * @param budgetNs budget de la préparation, {@code budgets.render_prep_ns}
     * @return les retenus, ou le code de l'échec
     */
    public Outcome cull(byte[] camera, int count, Candidates candidates, long budgetNs) {
        if (count == 0) {
            return new Outcome(NativeBridge.OK, new int[0], new int[0], new int[0][], 0, 0);
        }
        long bytes = (long) count * RenderPrepCodec.CANDIDATE_BYTES;
        ByteBuffer in = NativeBridge.acquire(context, BufferKinds.RENDER_IN, bytes);
        if (in == null) {
            return Outcome.failed(NativeBridge.E_INVALID_BUFFER);
        }
        int inGeneration = in.getInt(GENERATION_OFFSET);
        try {
            for (int rank = 0; rank < count; rank++) {
                candidates.write(in, BufferKinds.HEADER_BYTES + rank * RenderPrepCodec.CANDIDATE_BYTES, rank);
            }
            int code = NativeBridge.prepareRender(context, camera, 0f, budgetNs, result);
            if (code != NativeBridge.OK) {
                return Outcome.failed(code);
            }
            int frameId = (int) result[NativeBridge.RENDER_FRAME_ID];
            try {
                return read();
            } finally {
                NativeBridge.releaseRender(context, frameId);
            }
        } finally {
            NativeBridge.release(context, BufferKinds.RENDER_IN, inGeneration);
        }
    }

    /** Lit les retenus de la frame préparée dans {@code RENDER_OUT}, puis relâche le tampon. */
    private Outcome read() {
        ByteBuffer out = NativeBridge.acquire(context, BufferKinds.RENDER_OUT, 0);
        if (out == null) {
            return Outcome.failed(NativeBridge.E_INVALID_BUFFER);
        }
        try {
            int count = (int) result[NativeBridge.RENDER_INSTANCE_COUNT];
            int[] retained = new int[count];
            int[] levels = new int[count];
            int[][] meshes = new int[count][];
            for (int rank = 0; rank < count; rank++) {
                retained[rank] = RenderPrepCodec.retainedIndex(out, BufferKinds.HEADER_BYTES, rank);
                levels[rank] = RenderPrepCodec.retainedLevel(out, BufferKinds.HEADER_BYTES, rank);
                meshes[rank] = RenderPrepCodec.retainedMeshes(out, BufferKinds.HEADER_BYTES, count, rank);
            }
            return new Outcome(NativeBridge.OK, retained, levels, meshes, (int) result[NativeBridge.RENDER_FLAGS],
                    result[NativeBridge.RENDER_TRIANGLE_ESTIMATE]);
        } finally {
            NativeBridge.release(context, BufferKinds.RENDER_OUT, out.getInt(GENERATION_OFFSET));
        }
    }
}
