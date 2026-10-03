package dev.axion.render;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** L'ordre de la passe 4 : du plus loin au plus près (R-150, R-1580). */
class DepthOrderTest {

    @Test
    @DisplayName("Les surfaces translucides vont de la plus lointaine à la plus proche")
    void duPlusLoinAuPlusPres() {
        assertArrayEquals(new int[] {1, 0, 2}, DepthOrder.farToNear(new double[] {4.0, 9.0, 1.0}));
    }

    @Test
    @DisplayName("À distance égale, l'ordre donné est gardé, d'une frame à l'autre")
    void aDistanceEgaleLOrdreDonneEstGarde() {
        assertArrayEquals(new int[] {1, 3, 0, 2}, DepthOrder.farToNear(new double[] {4.0, 9.0, 1.0, 9.0}));
        assertArrayEquals(new int[] {0, 1, 2}, DepthOrder.farToNear(new double[] {2.0, 2.0, 2.0}));
    }

    @Test
    @DisplayName("Rien à trier, rien à rendre")
    void rienATrier() {
        assertArrayEquals(new int[0], DepthOrder.farToNear(new double[0]));
    }
}
