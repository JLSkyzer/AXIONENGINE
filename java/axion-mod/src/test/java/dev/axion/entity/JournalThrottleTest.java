package dev.axion.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Le débit d'un journal : la première ligne passe toujours, les suivantes une par intervalle, les tues comptées. */
class JournalThrottleTest {

    @Test
    @DisplayName("la première ligne passe, quel que soit le tick ; puis une par intervalle, les tues dites avec la suivante")
    void unParIntervalle() {
        for (int depart : new int[] {0, 7, 1_000_000, Integer.MIN_VALUE}) {
            JournalThrottle debit = new JournalThrottle(20);
            assertEquals(0, debit.admit(depart), "la première passe, au tick " + depart);
            assertEquals(-1, debit.admit(depart + 1));
            assertEquals(-1, debit.admit(depart + 19));
            assertEquals(2, debit.admit(depart + 20), "deux tues, dites avec elle");
            assertEquals(0, debit.admit(depart + 45));
        }
    }

    @Test
    @DisplayName("un intervalle nul ou négatif est refusé")
    void intervallePositif() {
        assertThrows(IllegalArgumentException.class, () -> new JournalThrottle(0));
    }
}
