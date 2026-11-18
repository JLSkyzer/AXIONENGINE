package dev.axion.attach;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-171 D7 au client : l'ensemble d'une assembly, lu des attaches répliquées (CAP-37). */
class EnsembleLinksTest {

    @Test
    @DisplayName("seule, une assembly est son propre ensemble")
    void seule() {
        assertEquals(Set.of(7), EnsembleLinks.members(7, Map.of()));
    }

    @Test
    @DisplayName("l'ensemble suit les attaches dans les deux sens, de proche en proche")
    void deProcheEnProche() {
        // La voiture 1 tire la remorque 2 ; la remorque 3 a posé une corde vers la 2 ; la 9 est seule.
        Map<Integer, List<Integer>> posees = Map.of(1, List.of(2), 3, List.of(2), 9, List.of());
        assertEquals(Set.of(1, 2, 3), EnsembleLinks.members(1, posees));
        assertEquals(Set.of(1, 2, 3), EnsembleLinks.members(3, posees));
        assertEquals(Set.of(9), EnsembleLinks.members(9, posees));
    }

    @Test
    @DisplayName("une attache vers une assembly inconnue du client ne casse rien")
    void autreInconnue() {
        assertEquals(Set.of(1, 42), EnsembleLinks.members(1, Map.of(1, List.of(42))));
    }
}
