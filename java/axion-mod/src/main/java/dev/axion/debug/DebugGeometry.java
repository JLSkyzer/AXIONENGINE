package dev.axion.debug;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Géométrie de debug déposée par le natif dans {@code DEBUG} (C-67, ADR-121, schéma 1).
 *
 * <pre>
 * u32 body_count, u32 segment_count, u32 flags (TRUNCATED), u32 omitted_bodies
 * DebugBody[body_count]         24 octets : handle (index, génération), premier segment,
 *                               nombre de segments, drapeaux, réservé
 * DebugSegment[segment_count]   24 octets : extrémités a et b, en repère du corps
 * </pre>
 *
 * <p>Les segments sont en <strong>repère du corps</strong> : on les dessine à la pose
 * interpolée de l'entité, comme son maillage. Le natif garantit la cohérence avant de
 * déposer ; ce lecteur contrôle quand même la longueur annoncée et les plages — une donnée
 * venue de la frontière reste une donnée externe, et un écart doit se voir.
 *
 * <p>Les tableaux rendus par {@link Body#segments()} sont ceux de l'instance, sans copie :
 * ils ne doivent pas être modifiés.
 */
public final class DebugGeometry {

    /** Taille de l'en-tête de la charge utile. */
    public static final int HEADER_BYTES = 16;

    /** Taille d'un {@code DebugBody}. */
    public static final int BODY_BYTES = 24;

    /** Taille d'un {@code DebugSegment}. */
    public static final int SEGMENT_BYTES = 24;

    /** Drapeau d'en-tête : des corps ont été omis faute de budget. */
    public static final int TRUNCATED = 1;

    /** Drapeau de corps : statique. */
    public static final int BODY_STATIC = 1;

    /** Drapeau de corps : cinématique. */
    public static final int BODY_KINEMATIC = 1 << 1;

    /** Drapeau de corps : endormi. */
    public static final int BODY_SLEEPING = 1 << 2;

    /** Flottants par segment : {@code a.x, a.y, a.z, b.x, b.y, b.z}. */
    public static final int FLOATS_PER_SEGMENT = 6;

    /**
     * Un corps tracé.
     *
     * @param handleIndex index du handle de son assembly
     * @param handleGeneration génération du handle
     * @param flags drapeaux {@code BODY_*}
     * @param segments {@link #FLOATS_PER_SEGMENT} flottants par segment, en repère du corps
     */
    public record Body(int handleIndex, int handleGeneration, int flags, float[] segments) {

        /** {@return le nombre de segments} */
        public int segmentCount() {
            return segments.length / FLOATS_PER_SEGMENT;
        }

        /** {@return vrai si le corps porte ce drapeau} */
        public boolean has(int flag) {
            return (flags & flag) != 0;
        }
    }

    private static final DebugGeometry EMPTY = new DebugGeometry(List.of(), Map.of(), 0, 0);

    private final List<Body> bodies;
    private final Map<Long, Body> byHandle;
    private final int flags;
    private final int omittedBodies;

    private DebugGeometry(List<Body> bodies, Map<Long, Body> byHandle, int flags, int omittedBodies) {
        this.bodies = bodies;
        this.byHandle = byHandle;
        this.flags = flags;
        this.omittedBodies = omittedBodies;
    }

    /** {@return une géométrie sans corps} */
    public static DebugGeometry empty() {
        return EMPTY;
    }

    /**
     * Lit une charge utile de {@code DEBUG}.
     *
     * @param bytes charge déposée par {@code axion_debug_fill}
     * @return la géométrie
     * @throws IllegalArgumentException si la longueur contredit les dénombrements, ou si la
     *     plage de segments d'un corps sort du tableau
     */
    public static DebugGeometry parse(byte[] bytes) {
        if (bytes.length < HEADER_BYTES) {
            throw new IllegalArgumentException("charge de debug de " + bytes.length
                    + " octets : en-tête tronqué");
        }
        ByteBuffer in = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        long bodyCount = Integer.toUnsignedLong(in.getInt(0));
        long segmentCount = Integer.toUnsignedLong(in.getInt(4));
        int flags = in.getInt(8);
        int omitted = in.getInt(12);
        long expected = HEADER_BYTES + bodyCount * BODY_BYTES + segmentCount * SEGMENT_BYTES;
        if (expected != bytes.length) {
            throw new IllegalArgumentException("charge de debug de " + bytes.length
                    + " octets, " + expected + " annoncés par ses dénombrements");
        }

        int segmentsAt = HEADER_BYTES + (int) bodyCount * BODY_BYTES;
        List<Body> bodies = new ArrayList<>((int) bodyCount);
        Map<Long, Body> byHandle = new HashMap<>();
        for (int rank = 0; rank < bodyCount; rank++) {
            int at = HEADER_BYTES + rank * BODY_BYTES;
            int index = in.getInt(at);
            int generation = in.getInt(at + 4);
            long first = Integer.toUnsignedLong(in.getInt(at + 8));
            long count = Integer.toUnsignedLong(in.getInt(at + 12));
            if (first + count > segmentCount) {
                throw new IllegalArgumentException("corps " + index + " : segments " + first
                        + ".." + (first + count) + " hors des " + segmentCount + " déposés");
            }
            float[] segments = new float[(int) count * FLOATS_PER_SEGMENT];
            int base = segmentsAt + (int) first * SEGMENT_BYTES;
            for (int value = 0; value < segments.length; value++) {
                segments[value] = in.getFloat(base + value * Float.BYTES);
            }
            Body body = new Body(index, generation, in.getInt(at + 16), segments);
            bodies.add(body);
            byHandle.put(handleKey(index, generation), body);
        }
        return new DebugGeometry(List.copyOf(bodies), Map.copyOf(byHandle), flags, omitted);
    }

    /** {@return la clé d'un handle : génération en poids fort, index en poids faible} */
    static long handleKey(int index, int generation) {
        return (Integer.toUnsignedLong(generation) << 32) | Integer.toUnsignedLong(index);
    }

    /** {@return les corps tracés, du plus proche au plus lointain de la caméra} */
    public List<Body> bodies() {
        return bodies;
    }

    /**
     * {@return le corps d'un handle, ou {@code null} s'il n'est pas tracé}
     *
     * @param handleIndex index du handle
     * @param handleGeneration génération du handle
     */
    public Body bodyOf(int handleIndex, int handleGeneration) {
        return byHandle.get(handleKey(handleIndex, handleGeneration));
    }

    /** {@return vrai si des corps ont été omis faute de budget} */
    public boolean truncated() {
        return (flags & TRUNCATED) != 0;
    }

    /** {@return le nombre de corps omis faute de budget} */
    public int omittedBodies() {
        return omittedBodies;
    }
}
