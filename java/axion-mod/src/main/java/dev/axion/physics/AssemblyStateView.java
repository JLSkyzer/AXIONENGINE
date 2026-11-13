package dev.axion.physics;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Une lecture seule des corps d'un bloc d'état exporté (ADR-168 D1, format de {@code world::state}) : de quoi mesurer
 * l'écart entre l'état que le client a prédit et celui du serveur (D5). Le reste du bloc demeure opaque pour Java.
 *
 * <pre>
 * en-tête 16 o : version u16 | drapeaux u16 | corps u16 | …
 * corps   64 o : position monde f64×3 | rotation f32×4 | vitesse f32×3 | vitesse angulaire f32×3
 * </pre>
 */
public final class AssemblyStateView {

    private static final int HEADER_BYTES = 16;
    private static final int BODY_BYTES = 64;
    private static final int BODIES_OFFSET = 4;

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
