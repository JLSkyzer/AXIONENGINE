package dev.axion.seat;

import dev.axion.asset.Anatomy;
import dev.axion.definition.Seating;
import java.util.List;

/**
 * Où se tient un siège, et quel siège rendre à un passager (ADR-158 D3). Logique pure, sans Forge :
 * le serveur et le client l'appellent avec leurs propres poses de corps, et placent ainsi le passager
 * au même endroit.
 */
public final class SeatPlacement {

    private SeatPlacement() {}

    /**
     * La pose monde d'un corps : sa position et son orientation.
     *
     * @param x position monde, en blocs
     * @param y idem
     * @param z idem
     * @param rotation orientation monde, {@code x, y, z, w}
     */
    public record BodyPose(double x, double y, double z, float[] rotation) {}

    /**
     * La pose monde d'un siège.
     *
     * @param x position monde du node {@code SEAT}, en blocs
     * @param y idem
     * @param z idem
     * @param rotation orientation monde du node, {@code x, y, z, w}
     */
    public record Pose(double x, double y, double z, float[] rotation) {}

    /**
     * {@return la pose monde d'un node : celle de son corps, composée avec sa pose de repos dans ce
     * corps ; {@code null} s'il n'a pas de pose, ou si son corps n'en a pas}
     *
     * @param anatomy les nodes de l'asset
     * @param node index du node
     * @param bodies la pose monde de chaque corps, dans l'ordre de l'assembly ; {@code null} pour un
     *     corps dont la pose n'est pas connue
     */
    public static Pose place(Anatomy anatomy, int node, List<BodyPose> bodies) {
        int body = anatomy.body(node);
        if (body < 0 || body >= bodies.size() || bodies.get(body) == null) {
            return null;
        }
        BodyPose frame = bodies.get(body);
        float[] t = anatomy.translation(node);
        float[] moved = rotate(frame.rotation(), t);
        return new Pose(frame.x() + moved[0], frame.y() + moved[1], frame.z() + moved[2],
                multiply(frame.rotation(), anatomy.rotation(node)));
    }

    /**
     * {@return la pose monde d'un corps donné relativement au corps racine, dans le repère de celui-ci}
     *
     * @param root pose monde du corps racine
     * @param offset position du corps dans le repère du corps racine
     * @param rotation orientation du corps relative au corps racine
     */
    public static BodyPose compose(BodyPose root, double[] offset, float[] rotation) {
        float[] moved = rotate(root.rotation(), new float[] {(float) offset[0], (float) offset[1], (float) offset[2]});
        return new BodyPose(root.x() + moved[0], root.y() + moved[1], root.z() + moved[2],
                multiply(root.rotation(), rotation));
    }

    /**
     * {@return le siège à rendre à un passager qui remonte dans l'assembly : celui qu'il occupait, s'il
     * existe encore et qu'il est libre ; sinon le premier libre ; {@link SeatOccupancy#FREE} si tous
     * sont pris}
     *
     * @param seating les sièges de la definition
     * @param occupancy leur occupation
     * @param remembered nom du siège qu'il occupait, {@code null} sans
     */
    public static int seatFor(Seating seating, SeatOccupancy occupancy, String remembered) {
        if (remembered != null) {
            for (int seat = 0; seat < seating.seats().size(); seat++) {
                if (seating.seats().get(seat).name().equals(remembered)
                        && occupancy.occupant(seat) == SeatOccupancy.FREE) {
                    return seat;
                }
            }
        }
        return occupancy.firstFree();
    }

    /** {@return {@code v} tourné par le quaternion unitaire {@code q = [x, y, z, w]}} */
    static float[] rotate(float[] q, float[] v) {
        float qx = q[0];
        float qy = q[1];
        float qz = q[2];
        float qw = q[3];
        // t = 2 (q × v) ; v' = v + w t + q × t
        float tx = 2 * (qy * v[2] - qz * v[1]);
        float ty = 2 * (qz * v[0] - qx * v[2]);
        float tz = 2 * (qx * v[1] - qy * v[0]);
        return new float[] {
            v[0] + qw * tx + (qy * tz - qz * ty),
            v[1] + qw * ty + (qz * tx - qx * tz),
            v[2] + qw * tz + (qx * ty - qy * tx)
        };
    }

    /** {@return le produit {@code a · b} de deux quaternions {@code [x, y, z, w]}} */
    static float[] multiply(float[] a, float[] b) {
        return new float[] {
            a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
            a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
            a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
            a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2]
        };
    }
}
