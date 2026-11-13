package dev.axion.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-168 D2, D5, sous-tranche 6c-C : la pose affichée du véhicule prédit. */
class PredictedTrackTest {

    private static final float[] IDENTITE = {0f, 0f, 0f, 1f};

    /** Un véhicule à {@code x}, qui file à 20 m/s le long de X : un bloc par tick. */
    private static AssemblyState a(double x) {
        return AssemblyState.of(new double[] {x, 64, 0}, IDENTITE, new float[] {20f, 0f, 0f}, new float[3], null, null,
                false);
    }

    private static PredictedTrack piste(int pas) {
        PredictedTrack piste = new PredictedTrack(new Interpolation(250, 2.0));
        for (int seq = 1; seq <= pas; seq++) {
            piste.advance(seq, a(seq), 1000);
        }
        return piste;
    }

    @Test
    @DisplayName("D2 : l'image se dessine entre les deux derniers pas, à sa fraction de tick ; un pas neuf ne saute pas")
    void continuite() {
        assertNull(new PredictedTrack(new Interpolation(250, 2.0)).sample(0.5, 0));
        PredictedTrack piste = piste(5);
        assertEquals(4.0, piste.sample(0.0, 1000).x(), 1e-3);
        assertEquals(4.5, piste.sample(0.5, 1000).x(), 1e-3);
        assertEquals(5.0, piste.sample(1.0, 1000).x(), 1e-3, "l'entité se pose à son dernier pas");
        // Le pas 6 : l'image qui suit part de là où la précédente finissait.
        piste.advance(6, a(6), 1000);
        assertEquals(5.0, piste.sample(0.0, 1000).x(), 1e-3);
    }

    @Test
    @DisplayName("D5 : un rejeu qui déplace le véhicule se résorbe en 200 ms, sans saut")
    void rejeuLisse() {
        PredictedTrack piste = piste(5);
        double avant = piste.sample(1.0, 1000).x();
        // Le serveur corrige : les ticks 4 et 5 étaient un demi-bloc trop loin.
        piste.replay(4, a(3.5), 1000);
        piste.replay(5, a(4.5), 1000);
        assertEquals(avant, piste.sample(1.0, 1000).x(), 1e-3, "rien ne saute à l'instant du rejeu");
        assertEquals(4.5 + 0.25, piste.sample(1.0, 1100).x(), 1e-3, "la moitié de l'écart résorbée en 100 ms");
        assertEquals(4.5, piste.sample(1.0, 1000 + Interpolation.CORRECTION_MILLIS).x(), 1e-3,
                "la pose rejouée, à 200 ms");
    }

    @Test
    @DisplayName("21.7 : un écart au-delà de net.snap_threshold se téléporte")
    void teleportation() {
        PredictedTrack piste = piste(3);
        piste.replay(2, a(12), 1000);
        piste.replay(3, a(13), 1000);
        assertEquals(13.0, piste.sample(1.0, 1000).x(), 1e-3);
    }

    @Test
    @DisplayName("D2 : le passage de l'interpolation à la pose prédite se résorbe en 200 ms")
    void depuisInterpolation() {
        PredictedTrack piste = new PredictedTrack(new Interpolation(250, 2.0));
        piste.advance(1, a(10), 1000);
        piste.advance(2, a(11), 1000);
        Interpolation.Pose interpolee = new Interpolation.Pose(9.0, 64, 0, IDENTITE, null, null,
                Interpolation.Mode.INTERPOLATED);
        piste.blendFrom(interpolee, 1.0, 1000);
        assertEquals(9.0, piste.sample(1.0, 1000).x(), 1e-3, "elle part de ce qui s'affichait");
        assertEquals(11.0, piste.sample(1.0, 1000 + Interpolation.CORRECTION_MILLIS).x(), 1e-3);
    }
}
