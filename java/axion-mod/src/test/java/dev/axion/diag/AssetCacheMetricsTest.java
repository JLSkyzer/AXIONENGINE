package dev.axion.diag;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** R-500 — les métriques du cache d'assets (C-25) : noms, types, et cache désactivé. */
class AssetCacheMetricsTest {

    private static long counter(JavaMetrics metrics, String name, String unit) {
        return metrics.counter(AssetCacheMetrics.PREFIX + name, unit).value();
    }

    private static long gauge(JavaMetrics metrics, String name, String unit) {
        return metrics.gauge(AssetCacheMetrics.PREFIX + name, unit).value();
    }

    @Test
    @DisplayName("R-500 : opérations, erreurs, durée et occupation du cache sont publiées sous axion.asset.cache.*")
    void lActiviteDuCacheEstPubliee() {
        JavaMetrics metrics = new JavaMetrics();
        AssetCacheMetrics cache = new AssetCacheMetrics(metrics);
        cache.enabled(true);
        cache.hit();
        cache.hit();
        cache.miss();
        cache.written();
        cache.evicted();
        cache.invalidated();
        cache.failed();
        cache.diskNanos(120);
        cache.diskNanos(-5);
        cache.occupancy(3, 4096);

        // Un nom redéclaré avec un autre type lèverait : ce sont bien des compteurs et des jauges.
        assertEquals(2, counter(metrics, "hits", "count"));
        assertEquals(1, counter(metrics, "misses", "count"));
        assertEquals(1, counter(metrics, "writes", "count"));
        assertEquals(1, counter(metrics, "evictions", "count"));
        assertEquals(1, counter(metrics, "invalidations", "count"));
        assertEquals(1, counter(metrics, "errors", "count"));
        assertEquals(120, counter(metrics, "disk_ns", "ns"), "une durée négative ne retranche rien");
        assertEquals(3, gauge(metrics, "entries", "count"));
        assertEquals(4096, gauge(metrics, "bytes", "bytes"));
        assertEquals(1, gauge(metrics, "enabled", "bool"));
    }

    @Test
    @DisplayName("§31.3 : un cache désactivé en mode développeur n'occupe rien")
    void unCacheDesactiveNOccupeRien() {
        JavaMetrics metrics = new JavaMetrics();
        AssetCacheMetrics cache = new AssetCacheMetrics(metrics);
        cache.occupancy(5, 9000);
        cache.enabled(false);
        assertEquals(0, gauge(metrics, "enabled", "bool"));
        assertEquals(0, gauge(metrics, "entries", "count"));
        assertEquals(0, gauge(metrics, "bytes", "bytes"));
    }
}
