package dev.axion.physics;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.IntFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-185 D2 : la place envoyée concorde-t-elle avec la position que le serveur a jugée ? */
class CarriedPlaceTest {

    /** Un corps qui avance de 0,8 bloc par tick le long de x ; six poses gardées, la plus récente en tête. */
    private static final IntFunction<double[]> POSES =
            retard -> retard < 6 ? new double[] {10.0 - 0.8 * retard, 64.0, 0.0, 0, 0, 0, 1} : null;

    private static final double[] PLACE = {0.0, 1.0, 0.5};

    private static boolean concorde(double x, double y, double z) {
        return CarriedPlace.agrees(POSES, 0, PLACE, new double[] {x, y, z}, 0.25);
    }

    @Test
    @DisplayName("ADR-185 D2 : à l'un des retards gardés, entre deux compris, la place concorde à la tolérance près")
    void adr185Concorde() {
        assertTrue(concorde(10.0, 65.0, 0.5), "au retard 0");
        assertTrue(concorde(7.6, 65.0, 0.5), "au retard 3");
        assertTrue(concorde(8.0, 65.0, 0.5), "entre les retards 2 et 3, à 0,4 bloc des deux");
        assertTrue(concorde(6.0, 65.2, 0.5), "au dernier gardé, à 0,2 bloc");
    }

    @Test
    @DisplayName("ADR-185 D2 : une place que la position ne confirme à aucun retard est refusée")
    void adr185Refusee() {
        assertFalse(concorde(8.0, 65.0, 1.0), "à côté, de 0,5 bloc");
        assertFalse(concorde(8.0, 65.4, 0.5), "au-dessus");
        assertFalse(concorde(10.5, 65.0, 0.5), "devant la pose la plus récente");
        assertFalse(concorde(4.0, 65.0, 0.5), "plus ancienne que les poses gardées");
        assertFalse(CarriedPlace.agrees(retard -> null, 0, PLACE, new double[] {10.0, 65.0, 0.5}, 0.25), "sans pose");
        assertFalse(CarriedPlace.agrees(POSES, 3, PLACE, new double[] {10.0, 65.0, 0.5}, 0.25), "corps sans pose");
    }
}
