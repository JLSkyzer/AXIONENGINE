package dev.axion.physics;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-178 D2 : le repère d'un corps posé, vérifié contre JOML. */
class BodyFrameTest {

    /** Deux corps : le premier à l'origine sans rotation, le second déplacé et tourné. */
    private static double[] poses(Quaterniond q) {
        return new double[] {0, 0, 0, 0, 0, 0, 1, 100, 64, -20, q.x, q.y, q.z, q.w};
    }

    @Test
    @DisplayName("un point du monde revient à sa place après un aller et retour, au rang demandé")
    void allerRetour() {
        Quaterniond q = new Quaterniond().rotateXYZ(0.4, 1.9, -0.7);
        double[] poses = poses(q);
        double[] local = BodyFrame.toLocal(poses, 1, 101.5, 65.2, -18.9);
        Vector3d attendu = new Vector3d(101.5, 65.2, -18.9).sub(100, 64, -20).rotate(new Quaterniond(q).conjugate());
        assertArrayEquals(new double[] {attendu.x, attendu.y, attendu.z}, local, 1e-9);
        assertArrayEquals(new double[] {101.5, 65.2, -18.9}, BodyFrame.toWorld(poses, 1, local), 1e-9);
        assertArrayEquals(new double[] {3, 4, 5}, BodyFrame.toLocal(poses, 0, 3, 4, 5), 1e-12, "le premier corps");
    }

    @Test
    @DisplayName("un plateau qui avance et tourne emporte le point avec lui")
    void lePlateauEmporte() {
        double[] avant = {10, 0, 0, 0, 0, 0, 1};
        double[] local = BodyFrame.toLocal(avant, 0, 12, 1, 0);
        // Il avance de 3 et tourne d'un quart de tour autour de y : le point, à 2 devant en x, passe à 2 devant en -z.
        double c = Math.sqrt(0.5);
        double[] apres = {13, 0, 0, 0, c, 0, c};
        assertArrayEquals(new double[] {13, 1, -2}, BodyFrame.toWorld(apres, 0, local), 1e-9);
    }

    @Test
    @DisplayName("le lacet de Minecraft de l'avant (-Z) d'un corps")
    void leLacet() {
        double[] nord = {0, 0, 0, 0, 0, 0, 1};
        assertEquals(180f, Math.abs(BodyFrame.yaw(nord, 0)), 1e-3f, "-Z regarde le nord, 180 degrés");
        double c = Math.sqrt(0.5);
        double[] tourne = {0, 0, 0, 0, c, 0, c};
        assertEquals(90f, BodyFrame.yaw(tourne, 0), 1e-3f, "un quart de tour autour de y : -Z regarde l'ouest, 90 degrés");
    }
}
