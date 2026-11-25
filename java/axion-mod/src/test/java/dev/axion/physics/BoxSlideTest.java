package dev.axion.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-178 D1 : une boîte qui glisse contre des colliders, sans Minecraft ni natif. */
class BoxSlideTest {

    /** Des boîtes fixes, balayées par la méthode des dalles : le sol et les murs de ces tests. */
    private static BoxSlide.Sweeper walls(List<double[]> solids) {
        return (box, mx, my, mz) -> {
            BoxSlide.Hit first = null;
            double[] motion = {mx, my, mz};
            for (double[] solid : solids) {
                double enter = 0.0;
                double exit = 1.0;
                int axis = -1;
                int side = 0;
                boolean hit = true;
                for (int a = 0; a < 3 && hit; a++) {
                    double moving = motion[a];
                    // Distances d'entrée et de sortie le long de l'axe a.
                    double near;
                    double far;
                    int normalSide;
                    if (moving > 0) {
                        near = solid[a] - box[3 + a];
                        far = solid[3 + a] - box[a];
                        normalSide = -1;
                    } else if (moving < 0) {
                        near = solid[3 + a] - box[a];
                        far = solid[a] - box[3 + a];
                        normalSide = 1;
                    } else {
                        if (box[3 + a] <= solid[a] || box[a] >= solid[3 + a]) {
                            hit = false;
                        }
                        continue;
                    }
                    double t0 = near / moving;
                    double t1 = far / moving;
                    if (t0 > enter) {
                        enter = t0;
                        axis = a;
                        side = normalSide;
                    }
                    exit = Math.min(exit, t1);
                    if (enter > exit) {
                        hit = false;
                    }
                }
                if (hit && axis >= 0 && enter <= 1.0 && enter >= -1.0e-9
                        && (first == null || enter < first.fraction())) {
                    first = new BoxSlide.Hit(Math.max(0.0, enter), axis == 0 ? side : 0, axis == 1 ? side : 0,
                            axis == 2 ? side : 0);
                }
            }
            return first;
        };
    }

    /** Un joueur de 0,6 sur 1,8, les pieds en {@code (x, y, z)}. */
    private static double[] player(double x, double y, double z) {
        return new double[] {x - 0.3, y, z - 0.3, x + 0.3, y + 1.8, z + 0.3};
    }

    /** Un sol de 20 × 20, dessus à y = 0. */
    private static final double[] FLOOR = {-10, -1, -10, 10, 0, 10};

    @Test
    @DisplayName("tomber sur un sol : la boîte s'arrête à son jeu au-dessus, sans rien perdre à l'horizontale")
    void leSolArrete() {
        double[] out = BoxSlide.slide(player(0, 2, 0), new double[] {0.5, -3.0, 0.0}, walls(List.of(FLOOR)), 0.6, false);
        assertEquals(0.5, out[0], 1e-9, "l'horizontal glisse sur le sol");
        assertEquals(-2.0, out[1], 2 * BoxSlide.SKIN, "il reste le jeu au-dessus du sol");
        assertTrue(out[1] > -2.0 - 1e-12, "jamais dans le sol");
    }

    @Test
    @DisplayName("un mur arrête la marche et la boîte glisse le long de lui")
    void leMurLaisseGlisser() {
        double[] wall = {1.0, 0, -10, 2.0, 5, 10};
        double[] out = BoxSlide.slide(player(0, 0.0, 0), new double[] {1.0, 0.0, 1.0}, walls(List.of(FLOOR, wall)), 0.0,
                true);
        // Son bord est à 0,3 : il avance de 0,7 − le jeu en x, et tout z.
        assertEquals(0.7, out[0], 2 * BoxSlide.SKIN);
        assertEquals(1.0, out[2], 1e-3, "elle glisse, au jeu près");
        assertEquals(0.0, out[1], 1e-9);
    }

    @Test
    @DisplayName("un angle de deux murs l'arrête des deux côtés")
    void unAngleLArrete() {
        double[] east = {1.0, 0, -10, 2.0, 5, 10};
        double[] north = {-10, 0, 1.0, 10, 5, 2.0};
        double[] out = BoxSlide.slide(player(0, 0, 0), new double[] {2.0, 0.0, 2.0}, walls(List.of(FLOOR, east, north)),
                0.0, true);
        assertEquals(0.7, out[0], 3 * BoxSlide.SKIN);
        assertEquals(0.7, out[2], 3 * BoxSlide.SKIN);
    }

    @Test
    @DisplayName("une marche de 0,5 s'enjambe depuis le sol ; une de 1,2 non ; en l'air non plus")
    void laMarche() {
        double[] step = {0.5, 0, -10, 3.0, 0.5, 10};
        BoxSlide.Sweeper sweeper = walls(List.of(FLOOR, step));
        double[] out = BoxSlide.slide(player(0, 0, 0), new double[] {1.0, -0.0784, 0.0}, sweeper, 0.6, true);
        assertEquals(1.0, out[0], 1e-6, "elle passe la marche");
        assertTrue(out[1] > 0.4, "et monte dessus : " + out[1]);

        double[] tall = {0.5, 0, -10, 3.0, 1.2, 10};
        double[] blocked = BoxSlide.slide(player(0, 0, 0), new double[] {1.0, -0.0784, 0.0}, walls(List.of(FLOOR, tall)),
                0.6, true);
        assertTrue(blocked[0] < 0.25, "un mur de 1,2 ne s'enjambe pas : " + blocked[0]);

        double[] inTheAir = BoxSlide.slide(player(0, 0.01, 0), new double[] {1.0, 0.1, 0.0}, sweeper, 0.6, false);
        assertTrue(inTheAir[0] < 0.25, "en l'air, on ne monte pas : " + inTheAir[0]);
    }

    @Test
    @DisplayName("un plafond bas empêche d'enjamber, et un chemin libre rend le déplacement tel quel")
    void leCheminLibreEtLePlafond() {
        double[] free = BoxSlide.slide(player(0, 0, 0), new double[] {0.2, 0.0, -0.3}, walls(List.of()), 0.6, true);
        assertEquals(0.2, free[0], 1e-12);
        assertEquals(-0.3, free[2], 1e-12);

        double[] step = {0.5, 0, -10, 3.0, 0.5, 10};
        double[] ceiling = {-10, 1.9, -10, 10, 3.0, 10};
        double[] out = BoxSlide.slide(player(0, 0, 0), new double[] {1.0, -0.0784, 0.0},
                walls(List.of(FLOOR, step, ceiling)), 0.6, true);
        assertTrue(out[0] < 0.25, "sous un plafond de 1,9, la marche de 0,5 ne passe pas : " + out[0]);
    }

    /** Un plan incliné de 20°, qui se marche : il monte vers -x ; la boîte repose dessus. */
    private static final BoxSlide.Sweeper SLOPE = (box, mx, my, mz) -> {
        double nx = Math.sin(Math.toRadians(20));
        double ny = Math.cos(Math.toRadians(20));
        return mx * nx + my * ny < -1e-12 ? new BoxSlide.Hit(0.0, nx, ny, 0.0) : null;
    };

    @Test
    @DisplayName("la gravité ne fait pas glisser le long d'une pente qui se marche : une assembly qui penche ne se vide pas")
    void onNeGlissePasSurUnePenteQuiSeMarche() {
        double[] out = BoxSlide.slide(player(0, 0, 0), new double[] {0.0, -0.0784, 0.0}, SLOPE, 0.6, true);
        assertEquals(0.0, out[0], 1e-12, "aucun déplacement de côté");
        assertEquals(0.0, out[1], 1e-3, "elle reste posée");
    }

    @Test
    @DisplayName("marcher vers le haut d'une pente la monte")
    void onMonteUnePente() {
        double[] out = BoxSlide.slide(player(0, 0, 0), new double[] {-0.1, -0.0784, 0.0}, SLOPE, 0.6, true);
        assertTrue(out[0] < -0.08, "il avance : " + out[0]);
        assertTrue(out[1] > 0.02, "et monte : " + out[1]);
    }

    @Test
    @DisplayName("sur une pente trop raide pour se marcher, la boîte glisse vers le bas")
    void unePenteRaideFaitGlisser() {
        double nx = Math.sin(Math.toRadians(60));
        double ny = Math.cos(Math.toRadians(60));
        BoxSlide.Sweeper raide = (box, mx, my, mz) -> mx * nx + my * ny < -1e-12 ? new BoxSlide.Hit(0.0, nx, ny, 0.0)
                : null;
        double[] out = BoxSlide.slide(player(0, 0, 0), new double[] {0.0, -0.0784, 0.0}, raide, 0.6, true);
        assertTrue(out[0] > 0.02 && out[1] < -0.01, "elle glisse : " + out[0] + ", " + out[1]);
    }

    @Test
    @DisplayName("un déplacement nul ne bouge rien, et le résultat n'est jamais plus long que la demande")
    void jamaisPlusLong() {
        assertEquals(0.0, BoxSlide.slide(player(0, 0, 0), new double[3], walls(List.of(FLOOR)), 0.6, true)[1], 0.0);
        double[] wanted = {0.3, -0.9, 0.4};
        double[] out = BoxSlide.slide(player(0, 0.5, 0), wanted, walls(List.of(FLOOR)), 0.6, true);
        double asked = Math.sqrt(wanted[0] * wanted[0] + wanted[1] * wanted[1] + wanted[2] * wanted[2]);
        assertTrue(Math.sqrt(out[0] * out[0] + out[1] * out[1] + out[2] * out[2]) <= asked + 1e-9);
        assertNull(walls(List.of()).sweep(player(0, 0, 0), 1, 0, 0));
    }
}
