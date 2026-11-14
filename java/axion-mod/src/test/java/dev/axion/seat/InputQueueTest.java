package dev.axion.seat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-168 D3 : une entrée par tick, dans l'ordre ; une file qui déborde rattrape. */
class InputQueueTest {

    @Test
    @DisplayName("D3 : une entrée par tick, dans l'ordre des séquences ; une file vide ne rend rien")
    void uneParTick() {
        InputQueue<String> queue = new InputQueue<>();
        assertNull(queue.next(), "file vide");
        assertEquals(-1, queue.lastApplied());
        assertTrue(queue.offer(4, "a"));
        assertTrue(queue.offer(5, "b"));
        InputQueue.Step<String> first = queue.next();
        assertEquals("a", first.applied());
        assertEquals(4, first.sequence());
        assertTrue(first.skipped().isEmpty());
        assertEquals(4, queue.lastApplied());
        assertEquals("b", queue.next().applied());
        assertNull(queue.next(), "rien de neuf : le siège garde la sienne");
        assertEquals(5, queue.lastApplied(), "la dernière appliquée reste");
    }

    @Test
    @DisplayName("D9 : une séquence qui ne croît pas est écartée, une négative est une faute")
    void sequenceQuiNeCroitPas() {
        InputQueue<String> queue = new InputQueue<>();
        assertTrue(queue.offer(10, "a"));
        assertFalse(queue.offer(10, "rejouée"));
        assertFalse(queue.offer(3, "rétrograde"));
        assertEquals(10, queue.lastOffered());
        assertEquals(1, queue.size());
        assertThrows(IllegalArgumentException.class, () -> queue.offer(-1, "négative"));
    }

    @Test
    @DisplayName("D3 : au-delà de deux en attente, la file saute aux plus récentes et rend les sautées dans l'ordre")
    void rattrapage() {
        InputQueue<String> queue = new InputQueue<>();
        for (int sequence = 1; sequence <= 5; sequence++) {
            queue.offer(sequence, "e" + sequence);
        }
        InputQueue.Step<String> step = queue.next();
        assertEquals(List.of("e1", "e2", "e3"), step.skipped(), "les plus anciennes, dans l'ordre");
        assertEquals("e4", step.applied());
        assertEquals(4, step.sequence());
        assertEquals(1, queue.size(), "la plus récente attend le tick suivant");
        assertEquals(3, queue.skippedTotal());
        InputQueue.Step<String> last = queue.next();
        assertEquals("e5", last.applied());
        assertTrue(last.skipped().isEmpty());
    }

    @Test
    @DisplayName("D3 : la gigue — deux dans un tick, aucune au suivant — ne saute rien")
    void gigue() {
        InputQueue<String> queue = new InputQueue<>();
        long sequence = 0;
        long applied = 0;
        // Deux arrivées, puis aucune, vingt fois de suite.
        for (int round = 0; round < 20; round++) {
            queue.offer(++sequence, "x");
            queue.offer(++sequence, "x");
            applied += queue.next() == null ? 0 : 1;
            applied += queue.next() == null ? 0 : 1;
        }
        assertEquals(0, queue.skippedTotal(), "rien de sauté");
        assertEquals(sequence, applied, "chacune appliquée à son tick");
        assertEquals(sequence, queue.lastApplied());
    }

    @Test
    @DisplayName("la cadence se relève : ticks sur une file vide, profondeur trouvée à chaque tick, rien avant la première")
    void cadence() {
        InputQueue<String> queue = new InputQueue<>();
        queue.next();
        assertEquals(0, queue.starvedTotal(), "avant la première entrée, rien ne compte");
        assertEquals(0, java.util.Arrays.stream(queue.depths()).sum());
        queue.offer(1, "a");
        queue.offer(2, "b");
        queue.next();
        queue.next();
        queue.next();
        assertEquals(1, queue.starvedTotal(), "le troisième tick n'a rien trouvé");
        long[] depths = queue.depths();
        assertEquals(1, depths[0]);
        assertEquals(1, depths[1]);
        assertEquals(1, depths[2]);
        for (int sequence = 3; sequence < 3 + 20; sequence++) {
            queue.offer(sequence, "x");
        }
        queue.next();
        assertEquals(1, queue.depths()[InputQueue.DEPTHS - 1], "les plus profondes au dernier indice");
    }
}
