package dev.axion.entity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-181 : les matériaux sous les roues, pas à pas, au client qui prédit. */
class SurfaceHistoryTest {

    @Test
    @DisplayName("ADR-181 : un rejeu repart des matériaux du pas acquitté, pas des plus récents")
    void unRejeuRepartDesMateriauxDeSonPas() {
        SurfaceHistory histoire = new SurfaceHistory();
        assertNull(histoire.current());
        histoire.step(1, new String[] {"axion:stone"});
        histoire.step(2, new String[] {"axion:ice"});
        histoire.step(3, new String[] {"axion:snow"});
        histoire.restore(1);
        assertArrayEquals(new String[] {"axion:stone"}, histoire.current());
        // Rejoués, les pas suivants réécrivent les leurs.
        histoire.step(2, new String[] {"axion:stone"});
        histoire.restore(2);
        assertArrayEquals(new String[] {"axion:stone"}, histoire.current());
    }

    @Test
    @DisplayName("ADR-181 : un pas oublié, ou aucun, garde ce qui est là ; l'histoire est bornée et se vide")
    void unPasOublieGardeCeQuiEstLa() {
        SurfaceHistory histoire = new SurfaceHistory();
        for (long pas = 0; pas <= SurfaceHistory.KEPT; pas++) {
            histoire.step(pas, new String[] {"m" + pas});
        }
        histoire.restore(0);
        assertArrayEquals(new String[] {"m" + SurfaceHistory.KEPT}, histoire.current(), "le pas 0 est sorti");
        histoire.restore(-1);
        assertArrayEquals(new String[] {"m" + SurfaceHistory.KEPT}, histoire.current());
        histoire.restore(1);
        assertArrayEquals(new String[] {"m1"}, histoire.current());
        histoire.clear();
        assertNull(histoire.current());
        histoire.restore(1);
        assertNull(histoire.current());
    }
}
