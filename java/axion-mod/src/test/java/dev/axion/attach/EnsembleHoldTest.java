package dev.axion.attach;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-171 Q9 : un ensemble tenu par un joueur garde chargés les chunks de tous ses membres (CAP-37). */
class EnsembleHoldTest {

    private static EnsembleHold.Chunk chunk(int x, int z) {
        return new EnsembleHold.Chunk(x, z);
    }

    @Test
    @DisplayName("la voiture à portée d'un joueur tient le chunk de sa remorque, au-delà de la portée")
    void unMembreAPorteeTientLesAutres() {
        List<EnsembleHold.Chunk> membres = List.of(chunk(0, 0), chunk(11, 0));
        Set<EnsembleHold.Chunk> tenus = EnsembleHold.chunksToHold(membres, List.of(chunk(-5, 0)), 10);
        assertEquals(Set.of(chunk(0, 0), chunk(11, 0)), tenus);
    }

    @Test
    @DisplayName("sans joueur à portée d'aucun membre, rien n'est tenu : l'ensemble se décharge entier")
    void sansJoueurRienNEstTenu() {
        List<EnsembleHold.Chunk> membres = List.of(chunk(40, 0), chunk(41, 0));
        assertTrue(EnsembleHold.chunksToHold(membres, List.of(chunk(0, 0)), 10).isEmpty());
        assertTrue(EnsembleHold.chunksToHold(membres, List.of(), 10).isEmpty());
    }

    @Test
    @DisplayName("la portée est celle de la distance de simulation, en chunks, mesurée comme Minecraft (Chebyshev)")
    void laPorteeEstLaDistanceDeSimulation() {
        List<EnsembleHold.Chunk> membres = List.of(chunk(10, 10), chunk(30, 30));
        assertEquals(2, EnsembleHold.chunksToHold(membres, List.of(chunk(0, 0)), 10).size(), "à 10 chunks en diagonale");
        assertTrue(EnsembleHold.chunksToHold(membres, List.of(chunk(0, -1)), 10).isEmpty(), "à 11");
    }

    @Test
    @DisplayName("deux membres dans le même chunk ne le tiennent qu'une fois")
    void unChunkPartageUneFois() {
        List<EnsembleHold.Chunk> membres = List.of(chunk(3, 3), chunk(3, 3), chunk(4, 3));
        assertEquals(Set.of(chunk(3, 3), chunk(4, 3)), EnsembleHold.chunksToHold(membres, List.of(chunk(3, 3)), 2));
    }
}
