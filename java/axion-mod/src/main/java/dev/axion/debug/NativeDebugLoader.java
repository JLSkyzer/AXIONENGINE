package dev.axion.debug;

import dev.axion.bridge.BufferKinds;
import dev.axion.bridge.NativeBridge;
import java.nio.ByteBuffer;

/**
 * Demande au natif la géométrie des overlays allumés et la lit dans {@code DEBUG} (C-67,
 * ADR-121).
 *
 * <p>Protocole d'IF-02 : appeler {@code axion_debug_fill}, acquérir {@code DEBUG}, vérifier
 * le schéma et la taille, lire, relâcher. Le tampon est ré-acquis à chaque appel et
 * toujours relâché (R-270, R-322).
 *
 * <p><strong>Thread client seulement</strong> : il est le seul utilisateur de {@code DEBUG},
 * si bien qu'aucun verrou Java n'est nécessaire — contrairement aux tampons d'asset, que
 * compilation et chargement se partagent.
 */
public final class NativeDebugLoader {

    /** Premier schéma de {@code DEBUG} portant cette disposition (ADR-121). */
    private static final int GEOMETRY_SINCE_SCHEMA = 1;

    /** Position de la génération dans l'en-tête d'un tampon (IF-02). */
    private static final int GENERATION_OFFSET = 8;

    /** Position de la version de schéma dans l'en-tête d'un tampon (IF-02, R-271). */
    private static final int SCHEMA_OFFSET = 12;

    private final long context;
    private final long[] sizeOut = new long[1];

    /**
     * Ce qu'une demande a produit.
     *
     * @param code {@link NativeBridge#OK}, ou le code d'erreur de l'ANNEXE A.1
     * @param geometry la géométrie, vide en cas d'échec
     */
    public record Fetched(int code, DebugGeometry geometry) {

        /** {@return vrai si la géométrie a été lue} */
        public boolean ok() {
            return code == NativeBridge.OK;
        }

        static Fetched failed(int code) {
            return new Fetched(code, DebugGeometry.empty());
        }
    }

    /**
     * Crée un chargeur adossé à un contexte natif.
     *
     * @param context jeton de contexte
     */
    public NativeDebugLoader(long context) {
        this.context = context;
    }

    /**
     * Demande et lit la géométrie de debug.
     *
     * @param overlayMask overlays demandés, au rang du §31.4
     * @param dimension dimension dont les corps sont tracés
     * @param cameraX position monde de la caméra
     * @param cameraY position monde de la caméra
     * @param cameraZ position monde de la caméra
     * @param maxSegments budget de segments
     * @return la géométrie, ou le code de l'échec
     */
    public Fetched fetch(
            long overlayMask,
            long dimension,
            double cameraX,
            double cameraY,
            double cameraZ,
            int maxSegments) {
        int code = NativeBridge.fillDebug(
                context, overlayMask, dimension, cameraX, cameraY, cameraZ, maxSegments, sizeOut);
        if (code != NativeBridge.OK) {
            return Fetched.failed(code);
        }
        ByteBuffer out = NativeBridge.acquire(context, BufferKinds.DEBUG, 0);
        if (out == null) {
            return Fetched.failed(NativeBridge.E_INVALID_BUFFER);
        }
        int generation = out.getInt(GENERATION_OFFSET);
        try {
            long size = sizeOut[0];
            if (out.getInt(SCHEMA_OFFSET) < GEOMETRY_SINCE_SCHEMA
                    || size < 0
                    || size > out.capacity() - BufferKinds.HEADER_BYTES) {
                return Fetched.failed(NativeBridge.E_INVALID_BUFFER);
            }
            byte[] bytes = new byte[(int) size];
            out.position(BufferKinds.HEADER_BYTES);
            out.get(bytes);
            return new Fetched(NativeBridge.OK, DebugGeometry.parse(bytes));
        } catch (IllegalArgumentException malformed) {
            return Fetched.failed(NativeBridge.E_INVALID_BUFFER);
        } finally {
            NativeBridge.release(context, BufferKinds.DEBUG, generation);
        }
    }
}
