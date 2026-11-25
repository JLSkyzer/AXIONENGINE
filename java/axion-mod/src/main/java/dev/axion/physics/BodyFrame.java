package dev.axion.physics;

/**
 * Le repère d'un corps posé (ADR-178 D2, CAP-14) : passer d'un point du monde à sa place dans le corps, et retour — ce
 * qu'il faut pour qu'un joueur posé sur un plateau y garde sa place quand le plateau bouge. Logique pure.
 *
 * <p>Une pose est celle que lit le balayage de collision : position (3) puis rotation {@code xyzw} (4), sept valeurs, au rang
 * du corps dans un tableau à la queue leu leu.
 */
public final class BodyFrame {

    /** Le nombre de valeurs d'une pose. */
    public static final int POSE = 7;

    private BodyFrame() {}

    /**
     * {@return la place d'un point du monde dans le repère d'un corps : {@code rotation⁻¹ · (point − position)}}
     *
     * @param poses les poses, à la queue leu leu
     * @param body le rang du corps
     * @param x le point
     * @param y le point
     * @param z le point
     */
    public static double[] toLocal(double[] poses, int body, double x, double y, double z) {
        int at = body * POSE;
        double dx = x - poses[at];
        double dy = y - poses[at + 1];
        double dz = z - poses[at + 2];
        // La rotation inverse : le conjugué.
        return rotate(-poses[at + 3], -poses[at + 4], -poses[at + 5], poses[at + 6], dx, dy, dz);
    }

    /**
     * {@return la place dans le monde d'un point du repère d'un corps : {@code position + rotation · local}}
     *
     * @param poses les poses, à la queue leu leu
     * @param body le rang du corps
     * @param local le point dans le repère du corps, trois valeurs
     */
    public static double[] toWorld(double[] poses, int body, double[] local) {
        int at = body * POSE;
        double[] turned = rotate(poses[at + 3], poses[at + 4], poses[at + 5], poses[at + 6], local[0], local[1], local[2]);
        turned[0] += poses[at];
        turned[1] += poses[at + 1];
        turned[2] += poses[at + 2];
        return turned;
    }

    /**
     * {@return le lacet de Minecraft, en degrés, de l'avant (son {@code −Z}, §6.5) d'un corps : ce dont un passager tourne avec
     * lui}
     *
     * @param poses les poses, à la queue leu leu
     * @param body le rang du corps
     */
    public static float yaw(double[] poses, int body) {
        int at = body * POSE;
        double[] forward = rotate(poses[at + 3], poses[at + 4], poses[at + 5], poses[at + 6], 0.0, 0.0, -1.0);
        return (float) Math.toDegrees(Math.atan2(-forward[0], forward[2]));
    }

    /** Un vecteur tourné par un quaternion unitaire {@code (x, y, z, w)} : {@code v + 2w(q×v) + 2q×(q×v)}. */
    private static double[] rotate(double qx, double qy, double qz, double qw, double vx, double vy, double vz) {
        double tx = 2.0 * (qy * vz - qz * vy);
        double ty = 2.0 * (qz * vx - qx * vz);
        double tz = 2.0 * (qx * vy - qy * vx);
        return new double[] {
            vx + qw * tx + (qy * tz - qz * ty),
            vy + qw * ty + (qz * tx - qx * tz),
            vz + qw * tz + (qx * ty - qy * tx),
        };
    }
}
