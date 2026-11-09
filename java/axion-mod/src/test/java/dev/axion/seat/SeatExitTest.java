package dev.axion.seat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-158 D5, T-441 : les sorties dans l'ordre, puis le siège, puis la place la plus proche. */
class SeatExitTest {

    private static final float DEMI = (float) Math.sqrt(0.5);

    /** Un siège en (10, 64, 20), sans rotation. */
    private static final SeatPlacement.Pose SIEGE = new SeatPlacement.Pose(10, 64, 20, new float[] {0, 0, 0, 1});

    private static final List<float[]> SORTIES = List.of(new float[] {-1.5f, 0f, 0f}, new float[] {1.5f, 0f, 0f});

    private static void place(double x, double y, double z, SeatExit.Spot spot) {
        assertEquals(x, spot.x(), 1e-5, "x");
        assertEquals(y, spot.y(), 1e-5, "y");
        assertEquals(z, spot.z(), 1e-5, "z");
    }

    @Test
    @DisplayName("T-441 : la première sortie libre, dans l'ordre déclaré")
    void dansLOrdre() {
        place(8.5, 64, 20, SeatExit.descend(SIEGE, SORTIES, 4, (x, y, z) -> true));
        place(11.5, 64, 20, SeatExit.descend(SIEGE, SORTIES, 4, (x, y, z) -> x > 10));
    }

    @Test
    @DisplayName("T-441 : une sortie se lit dans le repère du siège")
    void reperDuSiege() {
        // Un quart de tour autour de y : −x̂ du siège devient +ẑ dans le monde.
        SeatPlacement.Pose tourne = new SeatPlacement.Pose(10, 64, 20, new float[] {0, DEMI, 0, DEMI});
        place(10, 64, 21.5, SeatExit.descend(tourne, SORTIES, 4, (x, y, z) -> true));
    }

    @Test
    @DisplayName("T-441 : toutes les sorties prises, la position du siège")
    void lesiege() {
        place(10, 64, 20, SeatExit.descend(SIEGE, SORTIES, 4, (x, y, z) -> x == 10 && z == 20));
    }

    @Test
    @DisplayName("T-441 : le siège pris aussi, la place libre la plus proche")
    void laPlusProche() {
        // Tout est plein, sauf deux places : la plus proche l'emporte, pas la première de la grille.
        SeatExit.FreeSpot deux = (x, y, z) -> (x == 13 && y == 64 && z == 20) || (x == 10 && y == 65 && z == 18);
        place(10, 65, 18, SeatExit.descend(SIEGE, SORTIES, 4, deux));
    }

    @Test
    @DisplayName("T-441 : à distance égale, le plus haut d'abord, puis un ordre fixe")
    void ordreFixe() {
        List<double[]> vus = new ArrayList<>();
        SeatExit.descend(SIEGE, List.of(), 1, (x, y, z) -> {
            vus.add(new double[] {x, y, z});
            return false;
        });
        double[] premier = vus.get(1);
        assertEquals(65.0, premier[1], "après le siège, au-dessus de lui");
        assertEquals(10.0, premier[0]);
        assertEquals(20.0, premier[2]);
        double[] deuxieme = vus.get(2);
        assertEquals(9.0, deuxieme[0], "puis à la même hauteur, x croissant");
        assertEquals(64.0, deuxieme[1]);
        assertEquals(-1, Double.compare(vus.get(3)[2], vus.get(4)[2]), "z croissant, à x égal");
    }

    @Test
    @DisplayName("T-441 : rien de libre dans la recherche, le passager reste assis")
    void resteAssis() {
        assertNull(SeatExit.descend(SIEGE, SORTIES, 4, (x, y, z) -> false));
        // Hors du rayon, une place libre ne compte pas.
        assertNull(SeatExit.descend(SIEGE, List.of(), 4, (x, y, z) -> x == 15));
        // La recherche va de 2 blocs sous le siège à 3 au-dessus.
        assertNull(SeatExit.descend(SIEGE, List.of(), 4, (x, y, z) -> y == 61 || y == 68));
        place(10, 62, 20, SeatExit.descend(SIEGE, List.of(), 4, (x, y, z) -> y == 62));
        place(10, 67, 20, SeatExit.descend(SIEGE, List.of(), 4, (x, y, z) -> y == 67));
    }

    @Test
    @DisplayName("R-712 : une éjection monte à la verticale du siège, et n'est jamais refusée")
    void ejection() {
        place(8.5, 64, 20, SeatExit.eject(SIEGE, SORTIES, 4, 320, (x, y, z) -> true));
        place(10, 80, 20, SeatExit.eject(SIEGE, SORTIES, 4, 320, (x, y, z) -> y == 80));
        place(10, 320, 20, SeatExit.eject(SIEGE, SORTIES, 4, 320, (x, y, z) -> false));
        SeatPlacement.Pose auDessus = new SeatPlacement.Pose(10, 400, 20, new float[] {0, 0, 0, 1});
        place(10, 400, 20, SeatExit.eject(auDessus, List.of(), 1, 320, (x, y, z) -> false));
    }

    @Test
    @DisplayName("ADR-158 D5 : le rayon sort de l'emprise de l'assembly, et reste borné")
    void rayon() {
        assertEquals(5, SeatExit.radius(10, 20, 8, 19, 11, 21), "2 blocs d'emprise et 3 de marge");
        assertEquals(6, SeatExit.radius(10, 20, 9.5, 17, 10.5, 22.5), "le côté le plus loin l'emporte, arrondi");
        assertEquals(SeatExit.MAX_RADIUS, SeatExit.radius(0, 0, -500, -500, 500, 500));
    }
}
