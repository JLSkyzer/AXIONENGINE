package dev.axion.render;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Les corps d'une assembly tels que le dessin les voit (ADR-151 D9) : le corps de chaque node, et le repère
 * de chaque corps dans l'asset.
 *
 * <p>Miroir de {@code Skeleton} côté natif ({@code crates/ax-ffi/src/assembly.rs}), qui range les colliders
 * de la même façon :
 *
 * <ul>
 *   <li>la pose de repos d'un node compose celle de son parent avec sa transformation locale — translation
 *       mise à l'échelle du parent puis tournée, rotations composées, échelles multipliées ; une échelle
 *       non uniforme rompt la chaîne (R-120) ;</li>
 *   <li>un node appartient au corps dont il est la racine, sinon au corps de son parent ; une racine de
 *       l'asset qu'aucun corps ne réclame appartient au corps racine ;</li>
 *   <li>le repère du corps racine est celui de l'asset ; celui d'un autre corps est la pose de repos de son
 *       node racine, sans son échelle.</li>
 * </ul>
 *
 * <p>Un node se dessine donc à la pose de son corps, composée avec l'inverse du repère de ce corps et avec
 * sa propre pose de repos : à la pose de repos de l'assembly, il est là où l'asset le place.
 */
public final class BodySkeleton {

    private static final int HEADER_BYTES = 8;
    private static final int NODE_BYTES = 80;
    private static final int PARENT_AT = 8;
    private static final int LOCAL_AT = 12;
    private static final int NO_PARENT = -1;

    /** Le corps de chaque node : 0 pour le corps racine, {@code i + 1} pour le corps de la racine {@code i}. */
    private final int[] owner;

    /** Le repère de chaque corps dans l'asset : translation {@code [x, y, z]} puis rotation {@code [x, y, z, w]}. */
    private final float[][] frames;

    private BodySkeleton(int[] owner, float[][] frames) {
        this.owner = owner;
        this.frames = frames;
    }

    /**
     * @param nodes section {@code NODE} de l'asset
     * @param rootNode node racine du corps racine
     * @param secondaryRoots nodes racines des autres corps, dans l'ordre où le dessin les nommera
     * @return les corps, ou {@code null} si la section ne se lit pas, si un parent suit son enfant, ou si une
     *     racine est hors de la table ou sur une chaîne qu'une échelle non uniforme rompt — comme le natif
     */
    public static BodySkeleton of(byte[] nodes, int rootNode, int[] secondaryRoots) {
        if (nodes == null || nodes.length < HEADER_BYTES) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.wrap(nodes).order(ByteOrder.LITTLE_ENDIAN);
        long count = Integer.toUnsignedLong(buffer.getInt(0));
        if (HEADER_BYTES + count * NODE_BYTES > nodes.length) {
            return null;
        }
        int size = (int) count;
        int[] parents = new int[size];
        float[][] poses = new float[size][];
        for (int node = 0; node < size; node++) {
            int at = HEADER_BYTES + node * NODE_BYTES;
            int parent = buffer.getInt(at + PARENT_AT);
            if (parent != NO_PARENT && (parent < 0 || parent >= node)) {
                return null;
            }
            parents[node] = parent;
            float[] base = parent == NO_PARENT ? new float[] {0f, 0f, 0f, 0f, 0f, 0f, 1f, 1f} : poses[parent];
            poses[node] = base == null ? null : then(base, buffer, at + LOCAL_AT);
        }

        int[] roots = new int[secondaryRoots.length + 1];
        roots[0] = rootNode;
        System.arraycopy(secondaryRoots, 0, roots, 1, secondaryRoots.length);
        for (int rank = 1; rank < roots.length; rank++) {
            if (roots[rank] < 0 || roots[rank] >= size) {
                return null;
            }
        }
        int[] owner = new int[size];
        for (int node = 0; node < size; node++) {
            int rank = indexOf(roots, node);
            owner[node] = rank >= 0 ? rank : parents[node] == NO_PARENT ? 0 : owner[parents[node]];
        }
        float[][] frames = new float[roots.length][];
        frames[0] = new float[] {0f, 0f, 0f, 0f, 0f, 0f, 1f};
        for (int rank = 1; rank < roots.length; rank++) {
            float[] pose = poses[roots[rank]];
            if (pose == null) {
                return null;
            }
            frames[rank] = new float[] {pose[0], pose[1], pose[2], pose[3], pose[4], pose[5], pose[6]};
        }
        return new BodySkeleton(owner, frames);
    }

    /** {@return le nombre de corps, le corps racine compris} */
    public int bodies() {
        return frames.length;
    }

    /**
     * @param node node de l'asset
     * @return son corps : 0 pour le corps racine, {@code i + 1} pour celui de la racine secondaire {@code i} ;
     *     0 pour un node hors de la table
     */
    public int bodyOf(int node) {
        return node >= 0 && node < owner.length ? owner[node] : 0;
    }

    /**
     * @param body corps
     * @return son repère dans l'asset, translation {@code [x, y, z]} puis rotation {@code [x, y, z, w]} ; copie
     */
    public float[] frame(int body) {
        return frames[body].clone();
    }

    /**
     * La transformation de repos d'un node exprimée dans le repère de son corps : l'inverse de ce repère,
     * composé avec elle. Posée à la pose de son corps, elle place le node là où le corps l'emporte.
     *
     * @param body corps du node
     * @param model transformation de repos du node dans l'asset, {@code mat4x3} colonne-major : trois axes
     *     puis la translation (ADR-119)
     * @return la même, dans le repère du corps ; {@code model} lui-même pour le corps racine, dont le repère
     *     est celui de l'asset
     */
    public float[] modelInBody(int body, float[] model) {
        if (body == 0) {
            return model;
        }
        float[] frame = frames[body];
        // L'inverse d'une rotation unitaire est sa conjuguée ; celle d'un repère sans échelle s'en déduit.
        float[] inverse = {0f, 0f, 0f, -frame[3], -frame[4], -frame[5], frame[6]};
        float[] relative = new float[12];
        for (int column = 0; column < 3; column++) {
            float[] axis = rotate(inverse, new float[] {model[3 * column], model[3 * column + 1], model[3 * column + 2]});
            System.arraycopy(axis, 0, relative, 3 * column, 3);
        }
        float[] translation = rotate(inverse, new float[] {model[9] - frame[0], model[10] - frame[1], model[11] - frame[2]});
        System.arraycopy(translation, 0, relative, 9, 3);
        return relative;
    }

    /**
     * La pose {@code base} (translation, rotation, échelle) suivie de la transformation locale lue à
     * {@code at} ; {@code null} si l'échelle locale n'est pas uniforme et positive, ou si le résultat n'est
     * pas fini.
     */
    private static float[] then(float[] base, ByteBuffer buffer, int at) {
        float sx = buffer.getFloat(at + 28);
        float sy = buffer.getFloat(at + 32);
        float sz = buffer.getFloat(at + 36);
        if (!(sx == sy && sy == sz && sx > 0f)) {
            return null;
        }
        float scale = base[7];
        float[] translation = rotate(base, new float[] {
            buffer.getFloat(at) * scale, buffer.getFloat(at + 4) * scale, buffer.getFloat(at + 8) * scale,
        });
        float[] rotation = normalize(multiply(base, new float[] {
            buffer.getFloat(at + 12), buffer.getFloat(at + 16), buffer.getFloat(at + 20), buffer.getFloat(at + 24),
        }));
        float[] pose = {
            base[0] + translation[0], base[1] + translation[1], base[2] + translation[2],
            rotation[0], rotation[1], rotation[2], rotation[3], scale * sx,
        };
        for (float value : pose) {
            if (!Float.isFinite(value)) {
                return null;
            }
        }
        return pose;
    }

    /** La rotation de {@code pose} (composantes 3 à 6) appliquée à {@code v}. */
    private static float[] rotate(float[] pose, float[] v) {
        float qx = pose[3];
        float qy = pose[4];
        float qz = pose[5];
        float qw = pose[6];
        float tx = 2 * (qy * v[2] - qz * v[1]);
        float ty = 2 * (qz * v[0] - qx * v[2]);
        float tz = 2 * (qx * v[1] - qy * v[0]);
        return new float[] {
            v[0] + qw * tx + (qy * tz - qz * ty), v[1] + qw * ty + (qz * tx - qx * tz), v[2] + qw * tz + (qx * ty - qy * tx),
        };
    }

    /** La rotation de {@code pose} (composantes 3 à 6) suivie de {@code q}. */
    private static float[] multiply(float[] pose, float[] q) {
        float ax = pose[3];
        float ay = pose[4];
        float az = pose[5];
        float aw = pose[6];
        return new float[] {
            aw * q[0] + ax * q[3] + ay * q[2] - az * q[1],
            aw * q[1] - ax * q[2] + ay * q[3] + az * q[0],
            aw * q[2] + ax * q[1] - ay * q[0] + az * q[3],
            aw * q[3] - ax * q[0] - ay * q[1] - az * q[2],
        };
    }

    private static float[] normalize(float[] q) {
        float norm = (float) Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]);
        return new float[] {q[0] / norm, q[1] / norm, q[2] / norm, q[3] / norm};
    }

    private static int indexOf(int[] values, int value) {
        for (int index = 0; index < values.length; index++) {
            if (values[index] == value) {
                return index;
            }
        }
        return -1;
    }
}
