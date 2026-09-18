package dev.axion.diag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Le harnais de benchmark en jeu (C-72), testé avec un aller-retour factice. */
class BenchRunnerTest {

    @Test
    @DisplayName("Tous les appels du lot sont comptés")
    void countsAllCalls() {
        BenchRunner.Result result = BenchRunner.measure(() -> 2, 100, 8, 1000);
        assertEquals(8L * 1000, result.calls());
    }

    @Test
    @DisplayName("Les statistiques sont ordonnées min <= p50 <= p95 <= p99 <= max")
    void statsAreOrdered() {
        BenchRunner.Result result = BenchRunner.measure(() -> 2, 100, 16, 1000);
        assertTrue(result.minNs() >= 0.0, "coût négatif");
        assertTrue(result.minNs() <= result.p50Ns(), "min > p50");
        assertTrue(result.p50Ns() <= result.p95Ns(), "p50 > p95");
        assertTrue(result.p95Ns() <= result.p99Ns(), "p95 > p99");
        assertTrue(result.p99Ns() <= result.maxNs(), "p99 > max");
    }

    @Test
    @DisplayName("La configuration par défaut mesure un lot non vide")
    void ffiDefaultConfig() {
        BenchRunner.Result result = BenchRunner.ffi(() -> 2);
        assertTrue(result.calls() > 0, "aucun appel mesuré");
        assertTrue(result.maxNs() >= result.minNs(), "max < min");
    }
}
