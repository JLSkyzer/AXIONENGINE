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
     * Remet au GPU la table des matrices d'une frame — matrices des dessins animés, puis palettes d'os —,
     * avant que {@code RENDER_OUT} ne soit relâché : Java ne la copie pas (R-1460).
     */
    @FunctionalInterface
    public interface MatrixTable {

        /**
         * @param renderOut {@code RENDER_OUT}, little-endian
         * @param offset position du premier {@code mat4x3}, en-tête du tampon compris
         * @param bytes taille de la table, en octets
         */
        void upload(ByteBuffer renderOut, int offset, int bytes);
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
     * @param palettes pour chaque retenu, la première matrice de sa palette d'os, ou
     *     {@link RenderPrepCodec#NO_PALETTE} (ADR-155, schéma 4)
     * @param skinnedCapped retenus skinnés laissés au repos par {@code render.max_skinned_instances} (R-791)
     * @param distances distance de chaque retenu à la caméra, en blocs
     * @param bones pour chaque retenu, sa palette d'os lue pour le CPU — douze flottants par os —, ou
     *     {@code null} : sans palette, ou quand la table part au GPU (backend natif)
     */
    public record Outcome(
            int code, int[] retained, int[] levels, int[][] meshes, float[][][] matrices, int flags, long triangles,
            int[] palettes, int skinnedCapped, float[] distances, float[][] bones) {

        static Outcome failed(int code) {
            return new Outcome(code, new int[0], new int[0], new int[0][], new float[0][][], 0, 0, new int[0], 0,
                    new float[0], new float[0][]);
        }

        /** {@return les retenus de la frame qui portent une palette d'os} */
        public int skinned() {
            int skinned = 0;
            for (int palette : palettes) {
                if (palette != RenderPrepCodec.NO_PALETTE) {
                    skinned++;
                }
            }
            return skinned;
        }
    }

    /** {@return vrai si un retenu porte une palette} */
    private static boolean skinned(int[] palettes) {
        for (int palette : palettes) {
            if (palette != RenderPrepCodec.NO_PALETTE) {
                return true;
            }
        }
        return false;
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
     * @param renderTick tick de rendu de l'image (ADR-155)
     * @param table qui reçoit la table des matrices quand un retenu porte une palette, ou {@code null} : aucun
     *     skinning sur le GPU (backend vanilla), et les palettes sont lues pour le CPU (R-742)
     * @return les retenus, ou le code de l'échec
     */
    public Outcome cull(
            byte[] camera, int count, Candidates candidates, long budgetNs, double renderTick, MatrixTable table) {
        if (count == 0) {
            return new Outcome(NativeBridge.OK, new int[0], new int[0], new int[0][], new float[0][][], 0, 0,
                    new int[0], 0, new float[0], new float[0][]);
        }
        long bytes = RenderPrepCodec.IN_FRAME_HEADER_BYTES + (long) count * RenderPrepCodec.CANDIDATE_BYTES;
        ByteBuffer in = NativeBridge.acquire(context, BufferKinds.RENDER_IN, bytes);
        if (in == null) {
            return Outcome.failed(NativeBridge.E_INVALID_BUFFER);
        }
        int inGeneration = in.getInt(GENERATION_OFFSET);
        try {
            RenderPrepCodec.putRenderTick(in, BufferKinds.HEADER_BYTES, renderTick);
            int first = BufferKinds.HEADER_BYTES + RenderPrepCodec.IN_FRAME_HEADER_BYTES;
            for (int rank = 0; rank < count; rank++) {
                candidates.write(in, first + rank * RenderPrepCodec.CANDIDATE_BYTES, rank);
            }
            int code = NativeBridge.prepareRender(context, camera, 0f, budgetNs, result);
            if (code != NativeBridge.OK) {
                return Outcome.failed(code);
            }
            int frameId = (int) result[NativeBridge.RENDER_FRAME_ID];
            try {
                return read(table);
            } finally {
                NativeBridge.releaseRender(context, frameId);
            }
        } finally {
            NativeBridge.release(context, BufferKinds.RENDER_IN, inGeneration);
        }
    }

    /**
     * Pousse des commandes d'animation au contexte de rendu (ADR-155, ABI 10).
     *
     * @param commands les commandes, écrites par {@link AnimationCommands}
     * @return le nombre de commandes appliquées, ou un code d'erreur négatif
     */
    public int animate(byte[] commands) {
        return NativeBridge.animateRender(context, commands);
    }

    /**
     * Lit les retenus de la frame préparée dans {@code RENDER_OUT}, remet sa table des matrices au GPU si
     * un retenu porte une palette — ou, sans GPU qui la reçoive, en copie les palettes pour le CPU (R-742) —,
     * puis relâche le tampon.
     */
    private Outcome read(MatrixTable table) {
        ByteBuffer out = NativeBridge.acquire(context, BufferKinds.RENDER_OUT, 0);
        if (out == null) {
            return Outcome.failed(NativeBridge.E_INVALID_BUFFER);
        }
        try {
            int count = (int) result[NativeBridge.RENDER_INSTANCE_COUNT];
            int[] retained = new int[count];
            int[] levels = new int[count];
            int[][] meshes = new int[count][];
            float[][][] matrices = new float[count][][];
            int[] palettes = new int[count];
            float[] distances = new float[count];
            for (int rank = 0; rank < count; rank++) {
                retained[rank] = RenderPrepCodec.retainedIndex(out, BufferKinds.HEADER_BYTES, rank);
                levels[rank] = RenderPrepCodec.retainedLevel(out, BufferKinds.HEADER_BYTES, rank);
                meshes[rank] = RenderPrepCodec.retainedMeshes(out, BufferKinds.HEADER_BYTES, count, rank);
                matrices[rank] = RenderPrepCodec.retainedMatrices(out, BufferKinds.HEADER_BYTES, count, rank);
                palettes[rank] = RenderPrepCodec.retainedPalette(out, BufferKinds.HEADER_BYTES, rank);
                distances[rank] = RenderPrepCodec.retainedDistance(out, BufferKinds.HEADER_BYTES, rank);
            }
            float[][] bones = new float[count][];
            if (table == null && skinned(palettes)) {
                bones = RenderPrepCodec.retainedPalettes(out, BufferKinds.HEADER_BYTES, count);
            }
            if (table != null && skinned(palettes)) {
                int payload = BufferKinds.HEADER_BYTES;
                int matrixTotal = out.getInt(payload + 16);
                int meshTotal = out.getInt(payload + 8);
                int offset = payload + RenderPrepCodec.OUT_HEADER_BYTES + count * RenderPrepCodec.OUT_RECORD_BYTES
                        + meshTotal * Integer.BYTES;
                table.upload(out, offset, matrixTotal * RenderPrepCodec.MATRIX_FLOATS * Float.BYTES);
            }
            return new Outcome(NativeBridge.OK, retained, levels, meshes, matrices,
                    (int) result[NativeBridge.RENDER_FLAGS], result[NativeBridge.RENDER_TRIANGLE_ESTIMATE],
                    palettes, RenderPrepCodec.skinnedCapped(out, BufferKinds.HEADER_BYTES), distances, bones);
        } finally {
            NativeBridge.release(context, BufferKinds.RENDER_OUT, out.getInt(GENERATION_OFFSET));
        }
    }
}
