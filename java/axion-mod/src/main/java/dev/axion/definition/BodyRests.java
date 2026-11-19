package dev.axion.definition;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Où chaque corps d'une assembly repose, dans le repère de son corps racine (ADR-175 D2) : ce que la restauration d'un
 * état donne à un corps que la sauvegarde ne connaissait pas — il prend sa pose de repos, relative à la racine
 * restaurée. Calculé de la section {@code NODE} de l'asset comme la compilation native pose ses corps
 * ({@code Skeleton::new}, ADR-151 D2) : le repère du corps racine est celui de l'asset, celui d'un autre corps la pose
 * de son node racine dans l'asset, sans son échelle. Logique pure, sans Forge.
 *
 * @param rests la pose de repos de chaque corps, dans l'ordre de la definition ; le premier, le corps racine, est
 *     l'identité ; {@code null} pour un corps dont la chaîne de nodes porte une échelle non uniforme
 */
public record BodyRests(List<Rest> rests) {

    /** Taille d'un node dans la section {@code NODE}, en octets (A3D). */
    private static final int NODE_BYTES = 80;

    /** Taille de l'en-tête de la section {@code NODE} : nombre de nodes, taille des noms. */
    private static final int HEADER_BYTES = 8;

    /** Décalage du parent d'un node. */
    private static final int PARENT_AT = 8;

    /** Décalage de la transformation locale d'un node : translation, rotation, échelle. */
    private static final int LOCAL_AT = 12;

    /** Aucun corps. */
    public static final BodyRests NONE = new BodyRests(List.of());

    /**
     * La pose de repos d'un corps dans le repère du corps racine.
     *
     * @param offset sa position, en blocs
     * @param rotation son orientation, quaternion {@code (x, y, z, w)}
     */
    public record Rest(double[] offset, float[] rotation) {

        /** Copie les tableaux. */
        public Rest {
            offset = offset.clone();
            rotation = rotation.clone();
        }

        @Override
        public double[] offset() {
            return offset.clone();
        }

        @Override
        public float[] rotation() {
            return rotation.clone();
        }
    }

    /** Copie la liste. */
    public BodyRests {
        rests = java.util.Collections.unmodifiableList(new ArrayList<>(rests));
    }

    /**
     * {@return la pose de repos de chaque corps}
     *
     * @param nodes la section {@code NODE} de l'asset, ou {@code null}
     * @param roots le node racine de chaque corps, le corps racine d'abord (résumé de la compilation)
     */
    public static BodyRests of(byte[] nodes, int[] roots) {
        if (nodes == null || nodes.length < HEADER_BYTES) {
            return NONE;
        }
        ByteBuffer buffer = ByteBuffer.wrap(nodes).order(ByteOrder.LITTLE_ENDIAN);
        long count = Integer.toUnsignedLong(buffer.getInt(0));
        if (HEADER_BYTES + count * NODE_BYTES > nodes.length) {
            return NONE;
        }
        // La pose de chaque node dans l'asset : translation, rotation, échelle uniforme ; null si rompue.
        double[][] translation = new double[(int) count][];
        float[][] rotation = new float[(int) count][];
        double[] scale = new double[(int) count];
        for (int node = 0; node < count; node++) {
            int at = HEADER_BYTES + node * NODE_BYTES;
            int parent = buffer.getInt(at + PARENT_AT);
            if (parent >= node || parent < -1) {
                return NONE;
            }
            double[] lt = {buffer.getFloat(at + LOCAL_AT), buffer.getFloat(at + LOCAL_AT + 4),
                    buffer.getFloat(at + LOCAL_AT + 8)};
            float[] lr = {buffer.getFloat(at + LOCAL_AT + 12), buffer.getFloat(at + LOCAL_AT + 16),
                    buffer.getFloat(at + LOCAL_AT + 20), buffer.getFloat(at + LOCAL_AT + 24)};
            float sx = buffer.getFloat(at + LOCAL_AT + 28);
            float sy = buffer.getFloat(at + LOCAL_AT + 32);
            float sz = buffer.getFloat(at + LOCAL_AT + 36);
            boolean uniform = sx == sy && sy == sz && sx > 0f;
            if (parent < 0) {
                translation[node] = uniform ? lt : null;
                rotation[node] = lr;
                scale[node] = sx;
            } else if (translation[parent] == null || !uniform) {
                translation[node] = null;
            } else {
                double[] moved = rotate(rotation[parent], new double[] {lt[0] * scale[parent], lt[1] * scale[parent],
                        lt[2] * scale[parent]});
                translation[node] = new double[] {translation[parent][0] + moved[0], translation[parent][1] + moved[1],
                        translation[parent][2] + moved[2]};
                rotation[node] = multiply(rotation[parent], lr);
                scale[node] = scale[parent] * sx;
            }
        }
        List<Rest> rests = new ArrayList<>(roots.length);
        for (int body = 0; body < roots.length; body++) {
            int root = roots[body];
            if (body == 0) {
                rests.add(new Rest(new double[3], new float[] {0f, 0f, 0f, 1f}));
            } else if (root < 0 || root >= count || translation[root] == null) {
                rests.add(null);
            } else {
                rests.add(new Rest(translation[root], rotation[root]));
            }
        }
        return new BodyRests(rests);
    }

    /** {@return {@code v} tourné par le quaternion {@code q} (x, y, z, w)} */
    public static double[] rotate(float[] q, double[] v) {
        double x = q[0];
        double y = q[1];
        double z = q[2];
        double w = q[3];
        // v' = v + 2w (q × v) + 2 q × (q × v)
        double cx = y * v[2] - z * v[1];
        double cy = z * v[0] - x * v[2];
        double cz = x * v[1] - y * v[0];
        return new double[] {
            v[0] + 2 * (w * cx + y * cz - z * cy),
            v[1] + 2 * (w * cy + z * cx - x * cz),
            v[2] + 2 * (w * cz + x * cy - y * cx)};
    }

    /** {@return le produit {@code a · b} de deux quaternions (x, y, z, w)} */
    public static float[] multiply(float[] a, float[] b) {
        return new float[] {
            a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
            a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
            a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
            a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2]};
    }
}
