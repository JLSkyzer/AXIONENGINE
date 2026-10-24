package dev.axion.debug;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Géométrie de debug déposée par le natif dans {@code DEBUG} (C-67, ADR-121 et son amendement
 * A1, schéma 2).
 *
 * <pre>
 * u32 range_count, u32 segment_count, u32 flags (TRUNCATED), u32 omitted_ranges
 * DebugRange[range_count]       32 octets : handle (index, génération), overlay (rang au §31.4),
 *                               repère, premier segment, nombre de segments, drapeaux, réservé
 * DebugSegment[segment_count]   24 octets : extrémités a et b, dans le repère de leur plage
 * </pre>
 *
 * <p>Une plage en <strong>repère du corps</strong> se dessine à la pose interpolée de l'entité,
 * comme son maillage. Une plage en <strong>repère d'origine</strong> est dans le monde, relative
 * à la position caméra passée à l'appel ({@link #originX()}…) : on la dessine décalée de cette
 * position moins la caméra courante, en {@code double}. Le natif garantit la cohérence avant de
 * déposer ; ce lecteur contrôle quand même la longueur annoncée, les plages et les repères — une
 * donnée venue de la frontière reste une donnée externe, et un écart doit se voir.
 *
 * <p>Les tableaux rendus par {@link Range#segments()} sont ceux de l'instance, sans copie : ils
 * ne doivent pas être modifiés.
 */
public final class DebugGeometry {

    /** Taille de l'en-tête de la charge utile. */
    public static final int HEADER_BYTES = 16;

    /** Taille d'un {@code DebugRange}. */
    public static final int RANGE_BYTES = 32;

    /** Taille d'un {@code DebugSegment}. */
    public static final int SEGMENT_BYTES = 24;

    /** Drapeau d'en-tête : des plages ont été omises faute de budget. */
    public static final int TRUNCATED = 1;

    /** Repère du corps : l'espace de l'asset, à la pose interpolée de l'entité. */
    public static final int FRAME_BODY = 0;

    /** Repère d'origine : le monde, relatif à la caméra de l'appel. */
    public static final int FRAME_ORIGIN = 1;

    /** Drapeau de corps : statique. */
    public static final int BODY_STATIC = 1;

    /** Drapeau de corps : cinématique. */
    public static final int BODY_KINEMATIC = 1 << 1;

    /** Drapeau de corps : endormi. */
    public static final int BODY_SLEEPING = 1 << 2;

    /** Drapeau d'une plage {@code joints} ou {@code limits} : le joint est rompu (ADR-151 D7). */
    public static final int JOINT_BROKEN = 1 << 3;

    /** Drapeau d'une plage {@code joints} ou {@code limits} : le joint est grippé (ADR-151 D7). */
    public static final int JOINT_JAMMED = 1 << 4;

    /** Flottants par segment : {@code a.x, a.y, a.z, b.x, b.y, b.z}. */
    public static final int FLOATS_PER_SEGMENT = 6;

    /**
     * Une plage tracée : les segments d'un overlay pour un corps ou une tuile.
     *
     * @param handleIndex index du handle de son assembly ; 0 pour une tuile du monde
     * @param handleGeneration génération du handle ; 0 pour une tuile du monde
     * @param overlay rang de l'overlay au §31.4
     * @param frame {@link #FRAME_BODY} ou {@link #FRAME_ORIGIN}
     * @param flags drapeaux {@code BODY_*}
     * @param segments {@link #FLOATS_PER_SEGMENT} flottants par segment, dans le repère de la
     *     plage
     */
    public record Range(
            int handleIndex, int handleGeneration, int overlay, int frame, int flags, float[] segments) {

        /** {@return le nombre de segments} */
        public int segmentCount() {
            return segments.length / FLOATS_PER_SEGMENT;
        }

        /** {@return vrai si la plage porte ce drapeau} */
        public boolean has(int flag) {
            return (flags & flag) != 0;
        }
    }

    private static final DebugGeometry EMPTY = new DebugGeometry(List.of(), Map.of(), List.of(), 0, 0, 0, 0, 0);

    private final List<Range> ranges;
    private final Map<Long, List<Range>> bodyRanges;
    private final List<Range> originRanges;
    private final int flags;
    private final int omittedRanges;
    private final double originX;
    private final double originY;
    private final double originZ;

    private DebugGeometry(
            List<Range> ranges,
            Map<Long, List<Range>> bodyRanges,
            List<Range> originRanges,
            int flags,
            int omittedRanges,
            double originX,
            double originY,
            double originZ) {
        this.ranges = ranges;
        this.bodyRanges = bodyRanges;
        this.originRanges = originRanges;
        this.flags = flags;
        this.omittedRanges = omittedRanges;
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
    }

    /** {@return une géométrie sans plage} */
    public static DebugGeometry empty() {
        return EMPTY;
    }

    /**
     * Lit une charge utile de {@code DEBUG}.
     *
     * @param bytes charge déposée par {@code axion_debug_fill}
     * @param originX position monde de la caméra passée à l'appel
     * @param originY position monde de la caméra passée à l'appel
     * @param originZ position monde de la caméra passée à l'appel
     * @return la géométrie
     * @throws IllegalArgumentException si la longueur contredit les dénombrements, si la plage
     *     de segments d'une plage sort du tableau, ou si son repère est inconnu
     */
    public static DebugGeometry parse(byte[] bytes, double originX, double originY, double originZ) {
        if (bytes.length < HEADER_BYTES) {
            throw new IllegalArgumentException("charge de debug de " + bytes.length
                    + " octets : en-tête tronqué");
        }
        ByteBuffer in = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        long rangeCount = Integer.toUnsignedLong(in.getInt(0));
        long segmentCount = Integer.toUnsignedLong(in.getInt(4));
        int flags = in.getInt(8);
        int omitted = in.getInt(12);
        long expected = HEADER_BYTES + rangeCount * RANGE_BYTES + segmentCount * SEGMENT_BYTES;
        if (expected != bytes.length) {
            throw new IllegalArgumentException("charge de debug de " + bytes.length
                    + " octets, " + expected + " annoncés par ses dénombrements");
        }

        int segmentsAt = HEADER_BYTES + (int) rangeCount * RANGE_BYTES;
        List<Range> ranges = new ArrayList<>((int) rangeCount);
        Map<Long, List<Range>> bodyRanges = new HashMap<>();
        List<Range> originRanges = new ArrayList<>();
        for (int rank = 0; rank < rangeCount; rank++) {
            int at = HEADER_BYTES + rank * RANGE_BYTES;
            int index = in.getInt(at);
            int generation = in.getInt(at + 4);
            int overlay = in.getInt(at + 8);
            int frame = in.getInt(at + 12);
            long first = Integer.toUnsignedLong(in.getInt(at + 16));
            long count = Integer.toUnsignedLong(in.getInt(at + 20));
            if (first + count > segmentCount) {
                throw new IllegalArgumentException("plage " + rank + " : segments " + first
                        + ".." + (first + count) + " hors des " + segmentCount + " déposés");
            }
            if (frame != FRAME_BODY && frame != FRAME_ORIGIN) {
                throw new IllegalArgumentException("plage " + rank + " : repère " + frame + " inconnu");
            }
            float[] segments = new float[(int) count * FLOATS_PER_SEGMENT];
            int base = segmentsAt + (int) first * SEGMENT_BYTES;
            for (int value = 0; value < segments.length; value++) {
                segments[value] = in.getFloat(base + value * Float.BYTES);
            }
            Range range = new Range(index, generation, overlay, frame, in.getInt(at + 24), segments);
            ranges.add(range);
            if (frame == FRAME_BODY) {
                bodyRanges.computeIfAbsent(handleKey(index, generation), key -> new ArrayList<>()).add(range);
            } else {
                originRanges.add(range);
            }
        }
        Map<Long, List<Range>> frozen = new HashMap<>();
        bodyRanges.forEach((key, list) -> frozen.put(key, List.copyOf(list)));
        return new DebugGeometry(
                List.copyOf(ranges),
                Map.copyOf(frozen),
                List.copyOf(originRanges),
                flags,
                omitted,
                originX,
                originY,
                originZ);
    }

    /** {@return la clé d'un handle : génération en poids fort, index en poids faible} */
    static long handleKey(int index, int generation) {
        return (Integer.toUnsignedLong(generation) << 32) | Integer.toUnsignedLong(index);
    }

    /** {@return les plages tracées, de la plus proche à la plus lointaine de la caméra} */
    public List<Range> ranges() {
        return ranges;
    }

    /**
     * {@return les plages en repère du corps d'une assembly, vide si elle n'en a pas}
     *
     * @param handleIndex index du handle
     * @param handleGeneration génération du handle
     */
    public List<Range> bodyRangesOf(int handleIndex, int handleGeneration) {
        return bodyRanges.getOrDefault(handleKey(handleIndex, handleGeneration), List.of());
    }

    /** {@return les plages en repère d'origine, dans l'ordre de la charge} */
    public List<Range> originRanges() {
        return originRanges;
    }

    /** {@return la position monde x de la caméra passée à l'appel} */
    public double originX() {
        return originX;
    }

    /** {@return la position monde y de la caméra passée à l'appel} */
    public double originY() {
        return originY;
    }

    /** {@return la position monde z de la caméra passée à l'appel} */
    public double originZ() {
        return originZ;
    }

    /** {@return vrai si des plages ont été omises faute de budget} */
    public boolean truncated() {
        return (flags & TRUNCATED) != 0;
    }

    /** {@return le nombre de plages omises faute de budget} */
    public int omittedRanges() {
        return omittedRanges;
    }
}
