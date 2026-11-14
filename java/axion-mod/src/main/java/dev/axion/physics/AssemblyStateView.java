package dev.axion.physics;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Une lecture seule d'un bloc d'état exporté (ADR-168 D1, format de {@code world::state}) : ses corps, pour mesurer
 * l'écart entre l'état que le client a prédit et celui du serveur (D5) ; son véhicule et ses roues, pour que les
 * contrôleurs du client lisent l'état du début du tick que le serveur a lu (D6). Le reste demeure opaque pour Java.
 *
 * <pre>
 * en-tête   16 o : version u16 | drapeaux u16 (1 : véhicule) | corps u16 | joints u16 | roues u16 | 6 o à zéro
 * corps     64 o : position monde f64×3 | rotation f32×4 | vitesse f32×3 | vitesse angulaire f32×3
 * joint      8 o : rompu u8 | 7 o à zéro
 * véhicule  56 o : entrée f32×5 | rapport demandé i8 | drapeaux u8 | conducteur u8 | 1 o | moteur rad/s f32
 *                  | rapport engagé i8 | visé i8 | 2 o | changement f32 | maintien f32 | direction f32 | patinage f32
 *                  | charge f32 | 4 o
 * roue      32 o : rotation f32 | compression f32 | braquage f32 | charge f32 | glissement long f32 | latéral f32
 *                  | couple posé f32 | au sol u8 | 3 o
 * </pre>
 */
public final class AssemblyStateView {

    private static final int HEADER_BYTES = 16;
    private static final int BODY_BYTES = 64;
    private static final int JOINT_BYTES = 8;
    private static final int VEHICLE_BYTES = 56;
    private static final int WHEEL_BYTES = 32;
    private static final int FLAGS_OFFSET = 2;
    private static final int BODIES_OFFSET = 4;
    private static final int JOINTS_OFFSET = 6;
    private static final int WHEELS_OFFSET = 8;
    private static final int FLAG_VEHICLE = 1;

    /** {@code ax_vehicle::engine::RPM_PER_RAD_S} : le natif rapporte le régime ainsi, en {@code f32}. */
    private static final float RPM_PER_RAD_S = 60.0f / 6.2831855f;

    /**
     * Le corps racine d'un bloc : sa vitesse et son orientation.
     *
     * @param linear sa vitesse monde, en m/s
     * @param rotation son orientation {@code (x, y, z, w)}
     */
    public record Root(float[] linear, float[] rotation) {}

    /**
     * L'état d'une roue, tel que le bloc le porte.
     *
     * @param spin vitesse de rotation, en rad/s
     * @param compression compression, de 0 à 1
     * @param steer braquage, en radians
     * @param slipLong glissement longitudinal
     * @param slipLat glissement latéral, en radians
     * @param torque couple posé par les contrôleurs (R-1082), en N·m
     * @param onGround le rayon touchait le sol
     */
    public record Wheel(float spin, float compression, float steer, float slipLong, float slipLat, float torque,
            boolean onGround) {}

    /**
     * Le véhicule d'un bloc.
     *
     * @param rpm régime du moteur, en tr/min, calculé comme le natif le rapporte
     * @param gear rapport engagé
     * @param wheels ses roues, dans l'ordre de la definition
     */
    public record Vehicle(float rpm, int gear, java.util.List<Wheel> wheels) {}

    /**
     * {@return le corps racine d'un bloc, {@code null} s'il est tronqué ou sans corps}
     *
     * @param state le bloc
     */
    public static Root root(byte[] state) {
        if (bodies(state) <= 0) {
            return null;
        }
        ByteBuffer b = ByteBuffer.wrap(state).order(ByteOrder.LITTLE_ENDIAN);
        int at = HEADER_BYTES;
        float[] rotation = {b.getFloat(at + 24), b.getFloat(at + 28), b.getFloat(at + 32), b.getFloat(at + 36)};
        float[] linear = {b.getFloat(at + 40), b.getFloat(at + 44), b.getFloat(at + 48)};
        return new Root(linear, rotation);
    }

    /**
     * {@return le véhicule d'un bloc, {@code null} si l'assembly n'en est pas un, ou si le bloc est tronqué}
     *
     * @param state le bloc
     */
    public static Vehicle vehicle(byte[] state) {
        int bodies = bodies(state);
        if (bodies < 0) {
            return null;
        }
        ByteBuffer b = ByteBuffer.wrap(state).order(ByteOrder.LITTLE_ENDIAN);
        if ((b.getShort(FLAGS_OFFSET) & FLAG_VEHICLE) == 0) {
            return null;
        }
        int joints = b.getShort(JOINTS_OFFSET) & 0xFFFF;
        int wheels = b.getShort(WHEELS_OFFSET) & 0xFFFF;
        int at = HEADER_BYTES + bodies * BODY_BYTES + joints * JOINT_BYTES;
        if (state.length < at + VEHICLE_BYTES + wheels * WHEEL_BYTES) {
            return null;
        }
        float rpm = b.getFloat(at + 24) * RPM_PER_RAD_S;
        int gear = b.get(at + 28);
        java.util.List<Wheel> list = new java.util.ArrayList<>(wheels);
        for (int i = 0; i < wheels; i++) {
            int w = at + VEHICLE_BYTES + i * WHEEL_BYTES;
            list.add(new Wheel(b.getFloat(w), b.getFloat(w + 4), b.getFloat(w + 8), b.getFloat(w + 16),
                    b.getFloat(w + 20), b.getFloat(w + 24), b.get(w + 28) != 0));
        }
        return new Vehicle(rpm, gear, java.util.List.copyOf(list));
    }

    /**
     * L'écart entre deux états, au pire des corps.
     *
     * @param position la plus grande distance entre deux positions, en mètres
     * @param rotation le plus grand angle entre deux rotations, en radians
     * @param velocity la plus grande différence de vitesse, en m/s
     */
    public record Errors(double position, double rotation, double velocity) {

        /**
         * {@return vrai si chaque écart reste en deçà de son seuil}
         *
         * @param position le seuil de position, en mètres
         * @param rotation le seuil de rotation, en radians
         * @param velocity le seuil de vitesse, en m/s
         */
        public boolean within(double position, double rotation, double velocity) {
            return this.position <= position && this.rotation <= rotation && this.velocity <= velocity;
        }
    }

    private AssemblyStateView() {
        throw new AssertionError("non instanciable");
    }

    /**
     * {@return l'écart entre deux blocs, ou {@code null} s'ils ne portent pas les mêmes corps, ou sont tronqués}
     *
     * @param a un bloc d'état
     * @param b un autre
     */
    public static Errors errors(byte[] a, byte[] b) {
        int bodies = bodies(a);
        if (bodies < 0 || bodies != bodies(b)) {
            return null;
        }
        ByteBuffer x = ByteBuffer.wrap(a).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer y = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        double position = 0;
        double rotation = 0;
        double velocity = 0;
        for (int i = 0; i < bodies; i++) {
            int at = HEADER_BYTES + i * BODY_BYTES;
            double dx = x.getDouble(at) - y.getDouble(at);
            double dy = x.getDouble(at + 8) - y.getDouble(at + 8);
            double dz = x.getDouble(at + 16) - y.getDouble(at + 16);
            position = Math.max(position, Math.sqrt(dx * dx + dy * dy + dz * dz));
            rotation = Math.max(rotation, angle(x, y, at + 24));
            double dv = 0;
            for (int k = 0; k < 3; k++) {
                double d = (double) x.getFloat(at + 40 + 4 * k) - y.getFloat(at + 40 + 4 * k);
                dv += d * d;
            }
            velocity = Math.max(velocity, Math.sqrt(dv));
        }
        return new Errors(position, rotation, velocity);
    }

    /**
     * {@return l'angle, en radians, de la rotation qui mène d'un quaternion (x, y, z, w) à l'autre} Par la partie
     * vectorielle du quaternion relatif et {@code atan2} : {@code acos} du produit scalaire perd sa précision près de 1,
     * précisément pour les petits angles qu'une tolérance compare (1e-5 rad d'erreur sur des quaternions {@code f32}).
     */
    private static double angle(ByteBuffer a, ByteBuffer b, int at) {
        double ax = a.getFloat(at);
        double ay = a.getFloat(at + 4);
        double az = a.getFloat(at + 8);
        double aw = a.getFloat(at + 12);
        double bx = b.getFloat(at);
        double by = b.getFloat(at + 4);
        double bz = b.getFloat(at + 8);
        double bw = b.getFloat(at + 12);
        // conj(a) · b
        double rw = aw * bw + ax * bx + ay * by + az * bz;
        double rx = aw * bx - ax * bw - ay * bz + az * by;
        double ry = aw * by + ax * bz - ay * bw - az * bx;
        double rz = aw * bz - ax * by + ay * bx - az * bw;
        return 2 * Math.atan2(Math.sqrt(rx * rx + ry * ry + rz * rz), Math.abs(rw));
    }

    /** {@return le nombre de corps du bloc, -1 s'il est trop court pour les porter} */
    private static int bodies(byte[] state) {
        if (state == null || state.length < HEADER_BYTES) {
            return -1;
        }
        int bodies = ByteBuffer.wrap(state).order(ByteOrder.LITTLE_ENDIAN).getShort(BODIES_OFFSET) & 0xFFFF;
        return state.length < HEADER_BYTES + bodies * BODY_BYTES ? -1 : bodies;
    }
}
