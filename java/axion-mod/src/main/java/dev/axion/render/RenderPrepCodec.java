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

    /** En-tête de frame de {@code RENDER_OUT} : {@code frame_id}, retenus, groupes, drapeaux. */
    public static final int OUT_HEADER_BYTES = 16;

    /** Un retenu : index du candidat, {@code id}, LOD, distance. */
    public static final int OUT_RECORD_BYTES = 16;

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
            int candidateCount) {
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
        out.putInt(width).putInt(height).putFloat(maxDistance).putInt(lodBias).putInt(quality).putInt(candidateCount);
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
     * {@return l'index du candidat du retenu de rang {@code rank}}
     *
     * @param renderOut {@code RENDER_OUT}, little-endian
     * @param payload position de la charge utile, après l'en-tête du tampon
     * @param rank rang du retenu
     */
    public static int retainedIndex(ByteBuffer renderOut, int payload, int rank) {
        return renderOut.getInt(payload + OUT_HEADER_BYTES + rank * OUT_RECORD_BYTES);
    }
}
