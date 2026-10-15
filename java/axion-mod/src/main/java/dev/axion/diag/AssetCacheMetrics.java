package dev.axion.diag;

import dev.axion.asset.AssetCache;

/**
 * Les métriques du cache d'assets (C-25), publiées dans l'export de {@code /axion metrics}.
 *
 * <p>La fiche C-25 n'en nomme aucune ; R-500 veut, pour chaque composant, ses opérations, ses
 * erreurs, sa mémoire et sa durée. Les compteurs s'accumulent sur la vie du processus, à travers
 * les caches qu'ouvrent les rechargements successifs ; les jauges décrivent le dernier ouvert.
 */
public final class AssetCacheMetrics implements AssetCache.Observer {

    /** Préfixe des métriques du cache. */
    public static final String PREFIX = "axion.asset.cache.";

    private final JavaMetrics.Metric hits;
    private final JavaMetrics.Metric misses;
    private final JavaMetrics.Metric writes;
    private final JavaMetrics.Metric evictions;
    private final JavaMetrics.Metric invalidations;
    private final JavaMetrics.Metric errors;
    private final JavaMetrics.Metric diskNanos;
    private final JavaMetrics.Metric entries;
    private final JavaMetrics.Metric bytes;
    private final JavaMetrics.Metric enabled;

    /**
     * Déclare les métriques du cache.
     *
     * @param metrics registre où les publier, {@link JavaMetrics#GLOBAL} en jeu
     */
    public AssetCacheMetrics(JavaMetrics metrics) {
        hits = metrics.counter(PREFIX + "hits", "count");
        misses = metrics.counter(PREFIX + "misses", "count");
        writes = metrics.counter(PREFIX + "writes", "count");
        evictions = metrics.counter(PREFIX + "evictions", "count");
        invalidations = metrics.counter(PREFIX + "invalidations", "count");
        errors = metrics.counter(PREFIX + "errors", "count");
        diskNanos = metrics.counter(PREFIX + "disk_ns", "ns");
        entries = metrics.gauge(PREFIX + "entries", "count");
        bytes = metrics.gauge(PREFIX + "bytes", "bytes");
        enabled = metrics.gauge(PREFIX + "enabled", "bool");
    }

    /**
     * Dit si le cache est en service. Désactivé — en mode développeur seulement (§31.3) —, il
     * n'occupe rien.
     *
     * @param value vrai si un cache est ouvert
     */
    public void enabled(boolean value) {
        enabled.set(value ? 1 : 0);
        if (!value) {
            entries.set(0);
            bytes.set(0);
        }
    }

    @Override
    public void hit() {
        hits.increment();
    }

    @Override
    public void miss() {
        misses.increment();
    }

    @Override
    public void written() {
        writes.increment();
    }

    @Override
    public void evicted() {
        evictions.increment();
    }

    @Override
    public void invalidated() {
        invalidations.increment();
    }

    @Override
    public void failed() {
        errors.increment();
    }

    @Override
    public void diskNanos(long nanos) {
        diskNanos.add(Math.max(0, nanos));
    }

    @Override
    public void occupancy(int entryCount, long byteCount) {
        entries.set(entryCount);
        bytes.set(byteCount);
    }
}
