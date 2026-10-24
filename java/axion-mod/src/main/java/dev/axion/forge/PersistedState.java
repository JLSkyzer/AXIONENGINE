package dev.axion.forge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.joml.Quaternionf;

/**
 * L'état d'une assembly que son NBT garde au schéma 3 (PARTIE 22.2, ADR-153 D2) : ce qui ne se dérive
 * pas (R-1700) — transformation et vitesses de chacun de ses corps, sommeil, état de chacun de ses joints.
 *
 * @param position position monde du corps racine, en blocs
 * @param rotation orientation du corps racine, quaternion {@code (x, y, z, w)}
 * @param linear vitesse linéaire monde du corps racine, en m/s
 * @param angular vitesse angulaire monde du corps racine, en rad/s
 * @param sleeping l'assembly dort
 * @param bodies les corps autres que le corps racine
 * @param joints les joints, dans l'ordre de la definition
 */
record PersistedState(
        double[] position,
        float[] rotation,
        float[] linear,
        float[] angular,
        boolean sleeping,
        List<Body> bodies,
        List<Joint> joints) {

    /** Position monde du corps racine : trois doubles. */
    static final String POSITION = "axion:pos";

    /** Vitesse linéaire du corps racine : trois flottants. */
    static final String LINEAR = "axion:lvel";

    /** Vitesse angulaire du corps racine : trois flottants. */
    static final String ANGULAR = "axion:avel";

    /** Drapeaux de l'assembly : un entier. */
    static final String FLAGS = "axion:flags";

    /** Corps autres que le corps racine : {@link Body#BYTES} octets chacun. */
    static final String BODIES = "axion:bodies";

    /** Joints : {@link Joint#BYTES} octets chacun. */
    static final String JOINTS = "axion:joints";

    /** Drapeau de {@link #FLAGS} : l'assembly dort. Les autres bits sont réservés. */
    static final int SLEEPING = 1;

    /**
     * Un corps autre que le corps racine : son node racine, sa position monde, son orientation et ses
     * vitesses monde.
     *
     * @param node node racine du corps dans l'asset
     * @param x position monde, en blocs
     * @param y idem
     * @param z idem
     * @param rotation orientation, quaternion {@code (x, y, z, w)}
     * @param linear vitesse linéaire, en m/s
     * @param angular vitesse angulaire, en rad/s
     */
    record Body(int node, double x, double y, double z, float[] rotation, float[] linear, float[] angular) {

        /** Taille d'un corps dans {@link #BODIES}, en octets : node, position, orientation, vitesses. */
        static final int BYTES = 4 + 3 * 8 + 4 * 4 + 3 * 4 + 3 * 4;
    }

    /**
     * Un joint : rompu ou non, et le désalignement que Java lui a commandé en dernier (ADR-153 D2).
     *
     * @param broken le joint est rompu
     * @param misalignment désalignement de son ancrage, en blocs
     */
    record Joint(boolean broken, float misalignment) {

        /** Taille d'un joint dans {@link #JOINTS}, en octets : drapeaux, trois réservés, désalignement. */
        static final int BYTES = 8;

        /** Drapeau d'un joint : rompu. Les autres bits sont réservés. */
        static final int BROKEN = 1;
    }

    /**
     * Écrit l'état dans les clés gardées d'une entité. Les bits réservés de {@link #FLAGS} qu'elles
     * portent déjà restent.
     */
    void write(CompoundTag stored) {
        ListTag where = new ListTag();
        for (double c : position) {
            where.add(DoubleTag.valueOf(c));
        }
        stored.put(POSITION, where);
        AssemblyTags.putRotation(stored, new Quaternionf(rotation[0], rotation[1], rotation[2], rotation[3]));
        stored.put(LINEAR, floats(linear));
        stored.put(ANGULAR, floats(angular));
        stored.putInt(FLAGS, (stored.getInt(FLAGS) & ~SLEEPING) | (sleeping ? SLEEPING : 0));
        ByteBuffer out = ByteBuffer.allocate(bodies.size() * Body.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (Body body : bodies) {
            out.putInt(body.node()).putDouble(body.x()).putDouble(body.y()).putDouble(body.z());
            put(out, body.rotation());
            put(out, body.linear());
            put(out, body.angular());
        }
        stored.putByteArray(BODIES, out.array());
        ByteBuffer joints = ByteBuffer.allocate(this.joints.size() * Joint.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (Joint joint : this.joints) {
            joints.put((byte) (joint.broken() ? Joint.BROKEN : 0)).put((byte) 0).put((byte) 0).put((byte) 0);
            joints.putFloat(joint.misalignment());
        }
        stored.putByteArray(JOINTS, joints.array());
    }

    /**
     * {@return l'état que les clés gardées décrivent, ou vide si elles n'en portent pas — NBT migré du
     * schéma 2, entité jamais simulée — ou si l'une est illisible : longueur, type ou valeur non finie}
     *
     * <p>Une assembly sans état lu repart de sa pose de repos, à la position de son entité.
     */
    static Optional<PersistedState> read(CompoundTag stored) {
        double[] position = doubles(stored.getList(POSITION, Tag.TAG_DOUBLE));
        float[] linear = floats(stored.getList(LINEAR, Tag.TAG_FLOAT));
        float[] angular = floats(stored.getList(ANGULAR, Tag.TAG_FLOAT));
        if (position == null || linear == null || angular == null || !stored.contains(FLAGS, Tag.TAG_INT)) {
            return Optional.empty();
        }
        Quaternionf q = AssemblyTags.rotation(stored);
        List<Body> bodies = new ArrayList<>();
        byte[] rawBodies = stored.getByteArray(BODIES);
        if (rawBodies.length % Body.BYTES != 0) {
            return Optional.empty();
        }
        ByteBuffer in = ByteBuffer.wrap(rawBodies).order(ByteOrder.LITTLE_ENDIAN);
        while (in.hasRemaining()) {
            Body body = new Body(in.getInt(), in.getDouble(), in.getDouble(), in.getDouble(),
                    take(in, 4), take(in, 3), take(in, 3));
            if (!finite(body.x(), body.y(), body.z()) || !finite(body.rotation()) || !finite(body.linear())
                    || !finite(body.angular()) || norm(body.rotation()) < 1e-6f) {
                return Optional.empty();
            }
            bodies.add(body);
        }
        List<Joint> joints = new ArrayList<>();
        byte[] rawJoints = stored.getByteArray(JOINTS);
        if (rawJoints.length % Joint.BYTES != 0) {
            return Optional.empty();
        }
        ByteBuffer jointsIn = ByteBuffer.wrap(rawJoints).order(ByteOrder.LITTLE_ENDIAN);
        while (jointsIn.hasRemaining()) {
            int flags = Byte.toUnsignedInt(jointsIn.get());
            jointsIn.position(jointsIn.position() + 3);
            float misalignment = jointsIn.getFloat();
            if (!Float.isFinite(misalignment)) {
                return Optional.empty();
            }
            joints.add(new Joint((flags & Joint.BROKEN) != 0, misalignment));
        }
        return Optional.of(new PersistedState(position, new float[] {q.x, q.y, q.z, q.w}, linear, angular,
                (stored.getInt(FLAGS) & SLEEPING) != 0, List.copyOf(bodies), List.copyOf(joints)));
    }

    private static ListTag floats(float[] values) {
        ListTag list = new ListTag();
        for (float value : values) {
            list.add(FloatTag.valueOf(value));
        }
        return list;
    }

    /** Trois doubles finis, ou {@code null}. */
    private static double[] doubles(ListTag list) {
        if (list.size() != 3) {
            return null;
        }
        double[] values = {list.getDouble(0), list.getDouble(1), list.getDouble(2)};
        return finite(values[0], values[1], values[2]) ? values : null;
    }

    /** Trois flottants finis, ou {@code null}. */
    private static float[] floats(ListTag list) {
        if (list.size() != 3) {
            return null;
        }
        float[] values = {list.getFloat(0), list.getFloat(1), list.getFloat(2)};
        return finite(values) ? values : null;
    }

    private static void put(ByteBuffer out, float[] values) {
        for (float value : values) {
            out.putFloat(value);
        }
    }

    private static float[] take(ByteBuffer in, int count) {
        float[] values = new float[count];
        for (int i = 0; i < count; i++) {
            values[i] = in.getFloat();
        }
        return values;
    }

    private static boolean finite(double x, double y, double z) {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
    }

    private static boolean finite(float[] values) {
        for (float value : values) {
            if (!Float.isFinite(value)) {
                return false;
            }
        }
        return true;
    }

    private static float norm(float[] q) {
        return q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3];
    }
}
