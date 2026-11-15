package dev.axion.camera;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.axion.definition.VehicleViews;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-170 D1 : les prises de vue — poursuite lissée, orbite et retour, obstacle, regard borné. */
class ViewRigTest {

    private static final ViewRig.Clip LIBRE = (from, to) -> 1.0;
    private static final double[] ORIGINE = {0, 64, 0};
    private static final double[] ARRET = {0, 0, 0};

    private static VehicleViews.View poursuite(double distance, double height, double smoothing, double recenter) {
        return new VehicleViews.View("p", VehicleViews.Kind.FOLLOW, -1, VehicleViews.ALL_SEATS, null, distance,
                height, Double.NaN, smoothing, recenter, 0, 0);
    }

    @Test
    @DisplayName("D1 : à l'arrêt, la poursuite se pose derrière le cap, au recul fait à la taille, et regarde le véhicule")
    void derriereLeCap() {
        ViewRig rig = new ViewRig();
        // Cap au lacet 0 : l'avant est +Z ; la caméra est donc vers -Z.
        ViewRig.Shot shot = rig.follow(poursuite(Double.NaN, Double.NaN, 200, 1500), ORIGINE, 0, ARRET, 4, 0, 0, 16,
                LIBRE);
        double recul = ViewRig.FOLLOW_BASE + ViewRig.FOLLOW_PER_SIZE * 4;
        assertEquals(0, shot.x(), 1e-9);
        assertEquals(-recul, shot.z(), 1e-9);
        assertEquals(64 + ViewRig.HEIGHT_BASE + ViewRig.HEIGHT_PER_SIZE * 4, shot.y(), 1e-9);
        assertEquals(0, shot.yaw(), 1e-4, "elle regarde vers l'avant du véhicule");
        assertTrue(shot.pitch() > 0, "et vers le bas : " + shot.pitch());
    }

    @Test
    @DisplayName("D1 : en roulant, la direction rejoint la trajectoire en douceur ; en marche arrière, elle garde le cap")
    void lissage() {
        ViewRig rig = new ViewRig();
        VehicleViews.View vue = poursuite(10, 2, 200, 1500);
        rig.follow(vue, ORIGINE, 0, ARRET, 4, 0, 0, 16, LIBRE);
        // Le véhicule part vers +X, cap toujours +Z (un dérapage) : la caméra tourne vers -X, pas d'un coup.
        double[] versX = {10, 0, 1};
        ViewRig.Shot premiere = rig.follow(vue, ORIGINE, 0, versX, 4, 0, 16, 16, LIBRE);
        assertTrue(premiere.x() < 0 && premiere.x() > -5, "un premier pas, pas un saut : " + premiere.x());
        ViewRig.Shot plus = premiere;
        for (int i = 2; i < 200; i++) {
            plus = rig.follow(vue, ORIGINE, 0, versX, 4, 0, 16 * i, 16, LIBRE);
        }
        assertTrue(plus.x() < -9.5, "puis derrière la trajectoire : " + plus.x());

        ViewRig arriere = new ViewRig();
        arriere.follow(vue, ORIGINE, 0, ARRET, 4, 0, 0, 16, LIBRE);
        ViewRig.Shot recule = null;
        for (int i = 1; i < 100; i++) {
            recule = arriere.follow(vue, ORIGINE, 0, new double[] {0, 0, -8}, 4, 0, 16 * i, 16, LIBRE);
        }
        assertEquals(-10, recule.z(), 1e-6, "en marche arrière, toujours derrière le cap");
    }

    @Test
    @DisplayName("D1 et Q3 : la souris fait tourner la vue autour du véhicule ; sans elle, elle revient derrière")
    void orbiteEtRetour() {
        ViewRig rig = new ViewRig();
        VehicleViews.View vue = poursuite(10, 2, 0, 1500);
        rig.follow(vue, ORIGINE, 0, ARRET, 4, 0, 0, 16, LIBRE);
        // Le joueur tourne la tête de 90° vers sa gauche (lacet +90 : l'avant passe de +Z à -X).
        ViewRig.Shot cote = rig.follow(vue, ORIGINE, 0, ARRET, 4, 90, 16, 16, LIBRE);
        assertEquals(10, cote.x(), 1e-6, "la caméra passe du côté opposé au regard");
        ViewRig.Shot tient = rig.follow(vue, ORIGINE, 0, ARRET, 4, 90, 1000, 984, LIBRE);
        assertEquals(10, tient.x(), 1e-6, "avant recenter_ms, elle tient");
        ViewRig.Shot revient = null;
        for (double t = 1600; t < 5000; t += 16) {
            revient = rig.follow(vue, ORIGINE, 0, ARRET, 4, 90, t, 16, LIBRE);
        }
        assertEquals(0, revient.x(), 1e-3, "puis revient derrière, la tête du joueur restant tournée");
        assertEquals(-10, revient.z(), 1e-3);
    }

    @Test
    @DisplayName("D1 : un bloc entre le véhicule et la caméra la rapproche, avec une marge")
    void obstacle() {
        ViewRig rig = new ViewRig();
        ViewRig.Shot shot = rig.follow(poursuite(10, 0, 0, 1500), ORIGINE, 0, ARRET, 4, 0, 0, 16,
                (from, to) -> 0.5);
        double longueur = Math.hypot(10, ViewRig.FOCUS_HEIGHT);
        assertEquals(-10 * (0.5 - ViewRig.CLIP_MARGIN / longueur), shot.z(), 1e-6);
    }

    @Test
    @DisplayName("D1 : le regard d'un cockpit ou d'une vue fixe reste dans ses bornes ; 180 le laisse libre")
    void regardBorne() {
        assertArrayEquals(new float[] {30f, 10f}, ViewRig.bounded(0, 0, 50, 40, 30, 10), 1e-5f);
        assertArrayEquals(new float[] {-30f, -10f}, ViewRig.bounded(0, 0, -50, -40, 30, 10), 1e-5f);
        assertArrayEquals(new float[] {100f, 0f}, ViewRig.bounded(100, 0, 70, 40, 0, 0), 1e-5f, "figée");
        assertArrayEquals(new float[] {150f, 45f}, ViewRig.bounded(0, 0, 150, 45, 180, 90), 1e-5f, "libre");
    }

    @Test
    @DisplayName("Un point du repère d'un siège tourné : un quart de tour autour de Y envoie +Z sur +X")
    void repereTourne() {
        float s = (float) Math.sqrt(0.5);
        double[] point = ViewRig.transform(new double[] {1, 2, 3}, new float[] {0, s, 0, s}, new float[] {0, 0, 1});
        assertEquals(2, point[0], 1e-6);
        assertEquals(2, point[1], 1e-6);
        assertEquals(3, point[2], 1e-6);
        float[] angles = ViewRig.angles(0, 0, 1);
        assertEquals(0, angles[0], 1e-6);
        assertEquals(90, ViewRig.angles(-1, 0, 0)[0], 1e-4, "l'avant -X : lacet 90");
    }
}
