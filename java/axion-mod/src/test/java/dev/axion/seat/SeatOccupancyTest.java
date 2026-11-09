package dev.axion.seat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-158 D3, T-444 : un occupant par siège, un siège par occupant. */
class SeatOccupancyTest {

    @Test
    @DisplayName("T-444 : un siège pris refuse, un occupant change de siège, un siège se libère")
    void occupation() {
        SeatOccupancy table = new SeatOccupancy(3);
        assertEquals(0, table.firstFree());
        assertTrue(table.sit(7, 0));
        assertFalse(table.sit(8, 0), "le siège est pris");
        assertTrue(table.sit(7, 0), "s'y rasseoir ne change rien");
        assertTrue(table.sit(8, 1));
        assertEquals(2, table.firstFree());

        assertTrue(table.sit(7, 2), "changer de siège");
        assertEquals(SeatOccupancy.FREE, table.occupant(0), "l'ancien se libère");
        assertEquals(2, table.seatOf(7));
        assertEquals(0, table.firstFree());

        assertEquals(1, table.release(8));
        assertEquals(SeatOccupancy.FREE, table.release(8), "déjà libéré");
        assertEquals(SeatOccupancy.FREE, table.seatOf(8));
        assertFalse(table.sit(9, 3), "hors de la table");
        assertFalse(table.sit(9, -1));
        assertFalse(table.sit(SeatOccupancy.FREE, 0), "personne ne s'assoit");
    }

    @Test
    @DisplayName("T-444 : seize sièges occupés, puis plus aucun libre")
    void seize() {
        SeatOccupancy table = new SeatOccupancy(16);
        for (int seat = 0; seat < 16; seat++) {
            assertTrue(table.sit(100 + seat, seat));
        }
        assertEquals(SeatOccupancy.FREE, table.firstFree());
        for (int seat = 0; seat < 16; seat++) {
            assertEquals(100 + seat, table.occupant(seat));
        }
    }

    @Test
    @DisplayName("R-712 : un siège perdu ne reçoit plus personne, et se transporte tel quel")
    void perdu() {
        SeatOccupancy table = new SeatOccupancy(3);
        table.lose(0);
        table.lose(2);
        table.lose(7);
        assertFalse(table.sit(4, 0), "perdu");
        assertEquals(1, table.firstFree(), "le premier libre l'évite");
        assertFalse(table.sit(SeatOccupancy.LOST, 1), "un siège perdu n'est pas un occupant");
        assertEquals(SeatOccupancy.FREE, table.seatOf(4));
        assertArrayEquals(new int[] {-2, -1, -2}, SeatOccupancy.of(3, table.toArray()).toArray(), "plusieurs sièges perdus");
    }

    @Test
    @DisplayName("ADR-158 D3 : la table relue d'une forme transportée garde la règle d'un siège par occupant")
    void forme() {
        SeatOccupancy table = new SeatOccupancy(3);
        table.sit(4, 1);
        assertArrayEquals(new int[] {-1, 4, -1}, table.toArray());
        assertArrayEquals(table.toArray(), SeatOccupancy.of(3, table.toArray()).toArray());

        SeatOccupancy fautive = SeatOccupancy.of(2, new int[] {5, 5, 6});
        assertArrayEquals(new int[] {5, -1}, fautive.toArray(), "doublon et surplus écartés");
        assertArrayEquals(new int[] {-1, -1, -1}, SeatOccupancy.of(3, new int[0]).toArray(), "ce qui manque est libre");
    }
}
