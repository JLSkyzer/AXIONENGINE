package dev.axion.physics;

import java.util.function.IntFunction;

/**
 * La place qu'un joueur porté envoie, jugée par le serveur (ADR-185 D2) : elle concorde si, à l'un des retards des poses
 * gardées, le point qu'elle désigne sur le corps est à la tolérance près de la position que le serveur a jugée. Entre deux
 * poses de retards voisins, la distance se prend au segment qui les joint : le retard réel d'un joueur tombe entre deux
 * ticks. Un client ne peut ainsi se montrer qu'à une place compatible avec sa position. Logique pure.
 */
public final class CarriedPlace {

    private CarriedPlace() {}

    /**
     * {@return vrai si la place concorde avec la position à l'un des retards gardés}
     *
     * @param history les poses d'un retard, à la queue leu leu comme {@link BodyFrame}, {@code null} au-delà de ce qui
     *     est gardé ; 0 est la plus récente
     * @param body rang du corps
     * @param local la place envoyée, dans le repère du corps
     * @param position la position jugée, dans le monde
     * @param tolerance écart admis, en blocs ({@code net.carry_tolerance})
     */
    public static boolean agrees(IntFunction<double[]> history, int body, double[] local, double[] position,
            double tolerance) {
        double[] newer = point(history.apply(0), body, local);
        for (int lag = 1; newer != null; lag++) {
            double[] older = point(history.apply(lag), body, local);
            if (distance(newer, older == null ? newer : older, position) <= tolerance) {
                return true;
            }
            newer = older;
        }
        return false;
    }

    private static double[] point(double[] poses, int body, double[] local) {
        return poses == null || body >= poses.length / BodyFrame.POSE ? null : BodyFrame.toWorld(poses, body, local);
    }

    /** La distance d'un point au segment {@code [a, b]}. */
    private static double distance(double[] a, double[] b, double[] p) {
        double dx = b[0] - a[0];
        double dy = b[1] - a[1];
        double dz = b[2] - a[2];
        double length = dx * dx + dy * dy + dz * dz;
        double part = length == 0.0 ? 0.0
                : Math.max(0.0, Math.min(1.0,
                        ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy + (p[2] - a[2]) * dz) / length));
        double ex = a[0] + part * dx - p[0];
        double ey = a[1] + part * dy - p[1];
        double ez = a[2] + part * dz - p[2];
        return Math.sqrt(ex * ex + ey * ey + ez * ez);
    }
}
