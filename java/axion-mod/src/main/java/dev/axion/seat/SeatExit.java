package dev.axion.seat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Où pose-t-on un passager qui quitte son siège (ADR-158 D5, R-711, R-712). Logique pure, sans Forge :
 * ce qui rend une place libre — blocs, colliders d'assemblies — est jugé par {@link FreeSpot}.
 *
 * <p>Dans l'ordre : chaque position de sortie déclarée, dans le repère du siège ; la position du siège ;
 * la place libre la plus proche autour de lui. Une descente que rien de cela ne satisfait est refusée ;
 * une éjection ne peut pas l'être : elle monte à la verticale du siège jusqu'à trouver de la place.
 */
public final class SeatExit {

    /** Rayon de recherche au plus, en blocs : les plus grandes assemblies restent bornées. */
    public static final int MAX_RADIUS = 32;

    /** Ce que la recherche couvre au-delà de l'emprise de l'assembly, en blocs. */
    private static final int MARGIN = 3;

    /** Blocs sous le siège que la recherche couvre. */
    private static final int BELOW = 2;

    /** Blocs au-dessus du siège que la recherche couvre. */
    private static final int ABOVE = 3;

    private SeatExit() {}

    /** Juge une place. */
    @FunctionalInterface
    public interface FreeSpot {
        /**
         * @param x position des pieds du passager, en blocs
         * @param y idem
         * @param z idem
         * @return vrai si le passager y tient
         */
        boolean isFree(double x, double y, double z);
    }

    /**
     * Une place où poser un passager.
     *
     * @param x position de ses pieds, en blocs
     * @param y idem
     * @param z idem
     */
    public record Spot(double x, double y, double z) {}

    /**
     * {@return le rayon de recherche autour d'un siège : de quoi sortir de l'emprise de l'assembly, et
     * une marge, borné par {@link #MAX_RADIUS}}
     *
     * @param seatX position du siège
     * @param seatZ idem
     * @param minX emprise de l'assembly
     * @param minZ idem
     * @param maxX idem
     * @param maxZ idem
     */
    public static int radius(double seatX, double seatZ, double minX, double minZ, double maxX, double maxZ) {
        double reach = Math.max(Math.max(Math.abs(seatX - minX), Math.abs(seatX - maxX)),
                Math.max(Math.abs(seatZ - minZ), Math.abs(seatZ - maxZ)));
        return (int) Math.min(MAX_RADIUS, Math.ceil(reach) + MARGIN);
    }

    /**
     * {@return où descendre, ou {@code null} si rien n'est libre dans la recherche : le passager reste
     * assis (R-711)}
     *
     * @param seat pose monde du siège
     * @param exits positions de sortie, dans le repère du siège, dans l'ordre déclaré
     * @param radius rayon de recherche, en blocs
     * @param free ce qui juge une place
     */
    public static Spot descend(SeatPlacement.Pose seat, List<float[]> exits, int radius, FreeSpot free) {
        for (float[] exit : exits) {
            float[] moved = SeatPlacement.rotate(seat.rotation(), exit);
            Spot spot = new Spot(seat.x() + moved[0], seat.y() + moved[1], seat.z() + moved[2]);
            if (free.isFree(spot.x(), spot.y(), spot.z())) {
                return spot;
            }
        }
        if (free.isFree(seat.x(), seat.y(), seat.z())) {
            return new Spot(seat.x(), seat.y(), seat.z());
        }
        for (int[] step : searchOrder(radius)) {
            double x = seat.x() + step[0];
            double y = seat.y() + step[1];
            double z = seat.z() + step[2];
            if (free.isFree(x, y, z)) {
                return new Spot(x, y, z);
            }
        }
        return null;
    }

    /**
     * {@return où poser un passager éjecté : la place d'une descente s'il y en a une, sinon la première
     * libre en montant à la verticale du siège, sinon le sommet du monde — une éjection n'est jamais
     * refusée (R-712)}
     *
     * @param seat pose monde du siège
     * @param exits positions de sortie, dans le repère du siège
     * @param radius rayon de recherche, en blocs
     * @param top hauteur du sommet du monde
     * @param free ce qui juge une place
     */
    public static Spot eject(SeatPlacement.Pose seat, List<float[]> exits, int radius, double top, FreeSpot free) {
        Spot spot = descend(seat, exits, radius, free);
        if (spot != null) {
            return spot;
        }
        for (double y = seat.y() + ABOVE + 1; y < top; y += 1.0) {
            if (free.isFree(seat.x(), y, seat.z())) {
                return new Spot(seat.x(), y, seat.z());
            }
        }
        return new Spot(seat.x(), Math.max(top, seat.y()), seat.z());
    }

    /**
     * Les décalages de la recherche, du plus proche au plus lointain ; à distance égale, le plus haut
     * d'abord, puis un ordre fixe : deux recherches sur le même monde rendent la même place.
     */
    private static List<int[]> searchOrder(int radius) {
        List<int[]> steps = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -BELOW; dy <= ABOVE; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx != 0 || dy != 0 || dz != 0) {
                        steps.add(new int[] {dx, dy, dz});
                    }
                }
            }
        }
        steps.sort(Comparator.<int[]>comparingInt(s -> s[0] * s[0] + s[1] * s[1] + s[2] * s[2])
                .thenComparingInt(s -> -s[1])
                .thenComparingInt(s -> s[0])
                .thenComparingInt(s -> s[2]));
        return steps;
    }
}
