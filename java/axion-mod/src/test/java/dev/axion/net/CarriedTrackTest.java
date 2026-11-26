package dev.axion.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-184 D2 : la place des entités portées au temps de rendu. */
class CarriedTrackTest {

    private static AssemblyState.Carried portees(AssemblyState.CarriedEntity... entities) {
        return AssemblyState.Carried.of(List.of(entities));
    }

    private static AssemblyState.CarriedEntity portee(int id, int body, double x) {
        return AssemblyState.CarriedEntity.of(id, body, new double[] {x, 1.0, 0.0});
    }

    private static double millis(long tick) {
        return tick * ServerClock.TICK_MILLIS;
    }

    @Test
    @DisplayName("ADR-184 D2 : entre deux descripteurs, la place sur le corps s'interpole ; au-delà du dernier, elle est tenue")
    void adr184Interpolee() {
        CarriedTrack track = new CarriedTrack();
        assertTrue(track.at(millis(10)).isEmpty(), "rien de reçu : personne");
        track.push(10, portees(portee(7, 0, 1.0)));
        track.push(12, portees(portee(7, 0, 2.0)));
        assertArrayEquals(new double[] {1.5, 1.0, 0.0}, track.at(millis(11)).get(7).local(), 1e-9);
        assertArrayEquals(new double[] {1.0, 1.0, 0.0}, track.at(millis(9)).get(7).local(), 1e-9, "avant le premier");
        assertArrayEquals(new double[] {2.0, 1.0, 0.0}, track.at(millis(30)).get(7).local(), 1e-9, "tenue");
        assertEquals(12, track.at(millis(30)).get(7).tick(), "la place dit de quel tick elle vient");
    }

    @Test
    @DisplayName("ADR-184 D2 : qui n'est plus listé n'est plus porté ; un nouveau venu et un changement de corps ne s'interpolent pas")
    void adr184ListeQuiChange() {
        CarriedTrack track = new CarriedTrack();
        track.push(10, portees(portee(7, 0, 1.0), portee(8, 0, 4.0)));
        track.push(12, portees(portee(7, 1, 2.0), portee(9, 0, 6.0)));
        assertEquals(1, track.at(millis(11)).get(7).body(), "changé de corps : le nouveau");
        assertArrayEquals(new double[] {2.0, 1.0, 0.0}, track.at(millis(11)).get(7).local(), 1e-9);
        assertArrayEquals(new double[] {6.0, 1.0, 0.0}, track.at(millis(11)).get(9).local(), 1e-9, "nouveau venu");
        assertTrue(!track.at(millis(11)).containsKey(8), "descendu entre les deux : plus porté");
        track.push(14, AssemblyState.Carried.NONE);
        assertTrue(track.at(millis(13)).isEmpty(), "plus personne");
    }

    @Test
    @DisplayName("ADR-184 D2 : un descripteur plus ancien est ignoré, et la piste reste bornée")
    void adr184OrdreEtBorne() {
        CarriedTrack track = new CarriedTrack();
        track.push(20, portees(portee(7, 0, 3.0)));
        track.push(19, portees(portee(7, 0, 9.0)));
        track.push(20, portees(portee(7, 0, 9.0)));
        assertArrayEquals(new double[] {3.0, 1.0, 0.0}, track.at(millis(20)).get(7).local(), 1e-9);
        for (long tick = 21; tick < 200; tick++) {
            track.push(tick, portees(portee(7, 0, tick)));
        }
        assertEquals(CarriedTrack.MAX_SAMPLES, track.size(), "bornée sans lecture");
        track.at(millis(198));
        assertTrue(track.size() <= 3, "la lecture oublie ce qui précède le temps de rendu");
    }
}
