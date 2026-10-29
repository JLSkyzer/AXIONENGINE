package dev.axion.render;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Les octets du cycle de rendu IF-05 (ADR-154 D3), côté Java : {@code AxionCameraDesc}, les
 * candidats de {@code RENDER_IN}, les retenus de {@code RENDER_OUT}. Tout en little-endian (R-271).
 *
 * <p>Logique pure, sans natif : la disposition est celle d'{@code ax-render-prep} et de
 * {@code ax-ffi/src/render.rs}, que les tests des deux côtés confrontent.
 */
public final class RenderPrepCodec {

    /** Taille de {@code AxionCameraDesc}, en octets. */
    public static final int CAMERA_BYTES = 112;

    /** Taille d'un candidat, en octets. */
    public static final int CANDIDATE_BYTES = 48;

    /**
     * En-tête de frame de {@code RENDER_IN} (schéma 2), avant les candidats : le tick de rendu en
     * {@code double} — l'horloge du serveur interpolée (ADR-155) —, puis huit octets réservés.
     */
    public static final int IN_FRAME_HEADER_BYTES = 16;

    /**
     * En-tête de frame de {@code RENDER_OUT} (schéma 4) : {@code frame_id}, retenus, meshes, drapeaux,
     * matrices, puis les retenus skinnés laissés au repos par {@code render.max_skinned_instances}
     * (R-791).
     */
    public static final int OUT_HEADER_BYTES = 24;

    /**
     * Un retenu (schéma 4, décision de Killian) : index du candidat, {@code id}, niveau de détail,
     * distance, premier mesh et nombre de meshes dans la table qui suit les retenus, première matrice
     * ({@link #NO_MATRICES} au repos), première matrice de sa palette d'os ({@link #NO_PALETTE} sans).
     */
    public static final int OUT_RECORD_BYTES = 32;

    /** Première matrice d'un retenu qui n'en porte pas : il se dessine au repos. */
    public static final int NO_MATRICES = -1;

    /**
     * Première matrice de palette d'un retenu qui n'en a pas : sans skin, sans animation, ou au-delà de
     * {@code render.max_skinned_instances} (R-791).
     */
    public static final int NO_PALETTE = -1;

    /** Flottants d'une matrice {@code mat4x3} de la table de {@code RENDER_OUT}. */
    public static final int MATRIX_FLOATS = 12;

    /**
     * Drapeau d'un candidat : toujours retenu, sans test — une assembly dont l'emprise au repos ne
     * borne pas ce qu'elle dessine, des corps articulés écartés de leur pose de repos.
     */
    public static final int NO_CULL = 1;

    private RenderPrepCodec() {}

    /**
     * {@return un {@code AxionCameraDesc}}
     *
     * @param projection projection, colonne par colonne (convention d'OpenGL et de JOML)
     * @param viewRotation rotation de la vue {@code (x, y, z, w)} : elle porte un vecteur relatif à
     *     la caméra dans le repère de la vue
     * @param width largeur de la fenêtre, en pixels
     * @param height hauteur de la fenêtre, en pixels
     * @param maxDistance {@code render.max_distance}, en blocs
     * @param lodBias {@code render.lod_bias}
     * @param quality niveau de qualité
     * @param candidateCount nombre de candidats écrits dans {@code RENDER_IN}
     * @param maxInstances {@code budgets.max_visible_instances} : retenus au plus ; 0, sans plafond
     * @throws IllegalArgumentException si la projection n'a pas 16 valeurs ou la rotation 4
     */
    public static byte[] camera(
            float[] projection,
            float[] viewRotation,
            int width,
            int height,
            float maxDistance,
            int lodBias,
            int quality,
            int candidateCount,
            int maxInstances) {
        if (projection.length != 16 || viewRotation.length != 4) {
            throw new IllegalArgumentException("projection de 16 valeurs et rotation de 4 attendues");
        }
        ByteBuffer out = ByteBuffer.allocate(CAMERA_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : projection) {
            out.putFloat(value);
        }
        for (float value : viewRotation) {
            out.putFloat(value);
        }
        out.putInt(width).putInt(height).putFloat(maxDistance).putInt(lodBias).putInt(quality).putInt(candidateCount)
                .putInt(maxInstances);
        return out.array();
    }

    /**
     * Écrit un candidat à la position absolue {@code offset} d'un tampon little-endian.
     *
     * @param buffer {@code RENDER_IN}
     * @param offset position du candidat, en-tête du tampon compris
     * @param id identifiant stable de l'assembly tant qu'elle est rendue
     * @param assetIndex index du handle natif de son asset
     * @param assetGeneration génération de ce handle
     * @param flags drapeaux, voir {@link #NO_CULL}
     * @param x position relative à la caméra
     * @param y idem
     * @param z idem
     * @param scale échelle uniforme
     * @param rotation orientation {@code (x, y, z, w)}
     */
    public static void putCandidate(
            ByteBuffer buffer,
            int offset,
            int id,
            int assetIndex,
            int assetGeneration,
            int flags,
            float x,
            float y,
            float z,
            float scale,
            float[] rotation) {
        buffer.putInt(offset, id)
                .putInt(offset + 4, assetIndex)
                .putInt(offset + 8, assetGeneration)
                .putInt(offset + 12, flags)
                .putFloat(offset + 16, x)
                .putFloat(offset + 20, y)
                .putFloat(offset + 24, z)
                .putFloat(offset + 28, scale);
        for (int i = 0; i < 4; i++) {
            buffer.putFloat(offset + 32 + i * 4, rotation[i]);
        }
    }

    /**
     * Écrit le tick de rendu en tête de {@code RENDER_IN} (schéma 2).
     *
     * @param buffer {@code RENDER_IN}
     * @param payload position de la charge utile, après l'en-tête du tampon
     * @param tick tick du serveur interpolé, fraction comprise ; 0 sans horloge
     */
    public static void putRenderTick(ByteBuffer buffer, int payload, double tick) {
        buffer.putDouble(payload, tick).putLong(payload + 8, 0L);
    }

    /**
     * {@return la matrice {@code mat4x3} de chaque dessin du retenu de rang {@code rank}, dans l'ordre de la
     * liste au repos, ou {@code null} s'il se dessine au repos}
     *
     * @param renderOut {@code RENDER_OUT}, little-endian
     * @param payload position de la charge utile
     * @param retained nombre de retenus de la frame
     * @param rank rang du retenu
     */
    public static float[][] retainedMatrices(ByteBuffer renderOut, int payload, int retained, int rank) {
        int record = payload + OUT_HEADER_BYTES + rank * OUT_RECORD_BYTES;
        int first = renderOut.getInt(record + 24);
        if (first == NO_MATRICES) {
            return null;
        }
        int count = renderOut.getInt(record + 20);
        int meshTotal = renderOut.getInt(payload + 8);
        int table = payload + OUT_HEADER_BYTES + retained * OUT_RECORD_BYTES + meshTotal * 4;
        float[][] matrices = new float[count][MATRIX_FLOATS];
        for (int draw = 0; draw < count; draw++) {
            int at = table + (first + draw) * MATRIX_FLOATS * 4;
            for (int i = 0; i < MATRIX_FLOATS; i++) {
                matrices[draw][i] = renderOut.getFloat(at + i * 4);
            }
        }
        return matrices;
    }

    /**
     * {@return l'index du candidat du retenu de rang {@code rank}}
     *
     * @param renderOut {@code RENDER_OUT}, little-endian
     * @param payload position de la charge utile, après l'en-tête du tampon
     * @param rank rang du retenu
     */
    public static int retainedIndex(ByteBuffer renderOut, int payload, int rank) {
        return renderOut.getInt(payload + OUT_HEADER_BYTES + rank * OUT_RECORD_BYTES);
    }

    /**
     * {@return la première matrice de la palette d'os du retenu de rang {@code rank}, dans la table des
     * matrices, ou {@link #NO_PALETTE}}
     *
     * @param renderOut {@code RENDER_OUT}, little-endian
     * @param payload position de la charge utile
     * @param rank rang du retenu
     */
    public static int retainedPalette(ByteBuffer renderOut, int payload, int rank) {
        return renderOut.getInt(payload + OUT_HEADER_BYTES + rank * OUT_RECORD_BYTES + 28);
    }

    /**
     * {@return la palette d'os de chaque retenu, {@link #MATRIX_FLOATS} flottants par os dans l'ordre du
     * squelette, ou {@code null} pour un retenu qui n'en porte pas} Le backend vanilla la lit pour skinner sur
     * le CPU (R-742). Une palette suit les matrices des dessins de son retenu : elle s'arrête où commencent
     * celles du retenu animé suivant, ou à la fin de la table.
     *
     * @param renderOut {@code RENDER_OUT}, little-endian
     * @param payload position de la charge utile
     * @param retained nombre de retenus de la frame
     */
    public static float[][] retainedPalettes(ByteBuffer renderOut, int payload, int retained) {
        float[][] palettes = new float[retained][];
        int meshTotal = renderOut.getInt(payload + 8);
        int table = payload + OUT_HEADER_BYTES + retained * OUT_RECORD_BYTES + meshTotal * 4;
        int end = renderOut.getInt(payload + 16);
        for (int rank = retained - 1; rank >= 0; rank--) {
            int record = payload + OUT_HEADER_BYTES + rank * OUT_RECORD_BYTES;
            int first = renderOut.getInt(record + 28);
            if (first != NO_PALETTE) {
                float[] palette = new float[(end - first) * MATRIX_FLOATS];
                for (int i = 0; i < palette.length; i++) {
                    palette[i] = renderOut.getFloat(table + (first * MATRIX_FLOATS + i) * 4);
                }
                palettes[rank] = palette;
            }
            int matrices = renderOut.getInt(record + 24);
            if (matrices != NO_MATRICES) {
                end = matrices;
            }
        }
        return palettes;
    }

    /**
     * {@return la distance du retenu de rang {@code rank} à la caméra, en blocs, celle sur laquelle le natif
     * a choisi ses retenus skinnés (R-791)}
     *
     * @param renderOut {@code RENDER_OUT}, little-endian
     * @param payload position de la charge utile
     * @param rank rang du retenu
     */
    public static float retainedDistance(ByteBuffer renderOut, int payload, int rank) {
        return renderOut.getFloat(payload + OUT_HEADER_BYTES + rank * OUT_RECORD_BYTES + 12);
    }

    /**
     * {@return les retenus skinnés de la frame laissés au repos par {@code render.max_skinned_instances}
     * (R-791)}
     *
     * @param renderOut {@code RENDER_OUT}, little-endian
     * @param payload position de la charge utile
     */
    public static int skinnedCapped(ByteBuffer renderOut, int payload) {
        return renderOut.getInt(payload + 20);
    }

    /**
     * {@return le niveau de détail du retenu de rang {@code rank}}
     *
     * @param renderOut {@code RENDER_OUT}, little-endian
     * @param payload position de la charge utile
     * @param rank rang du retenu
     */
    public static int retainedLevel(ByteBuffer renderOut, int payload, int rank) {
        return renderOut.getInt(payload + OUT_HEADER_BYTES + rank * OUT_RECORD_BYTES + 8);
    }

    /**
     * {@return le mesh de chaque dessin du retenu de rang {@code rank}, dans l'ordre de la liste au repos}
     *
     * @param renderOut {@code RENDER_OUT}, little-endian
     * @param payload position de la charge utile
     * @param retained nombre de retenus de la frame
     * @param rank rang du retenu
     */
    public static int[] retainedMeshes(ByteBuffer renderOut, int payload, int retained, int rank) {
        int record = payload + OUT_HEADER_BYTES + rank * OUT_RECORD_BYTES;
        int first = renderOut.getInt(record + 16);
        int count = renderOut.getInt(record + 20);
        int table = payload + OUT_HEADER_BYTES + retained * OUT_RECORD_BYTES;
        int[] meshes = new int[count];
        for (int i = 0; i < count; i++) {
            meshes[i] = renderOut.getInt(table + (first + i) * 4);
        }
        return meshes;
    }
}
