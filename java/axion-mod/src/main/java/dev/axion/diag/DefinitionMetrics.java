package dev.axion.diag;

/**
 * Les métriques des definitions (C-27), publiées dans l'export de {@code /axion metrics}.
 *
 * <p>La fiche C-27 n'en nomme aucune ; R-500 veut, pour chaque composant, ses opérations, ses
 * erreurs, sa mémoire et sa durée : les definitions chargées et les rechargements, les refus, les
 * octets des sources retenues, la durée du dernier chargement. Leur imputation à un budget et leur
 * comportement en surcharge viennent avec C-77 (R-1850, M5).
 */
public final class DefinitionMetrics {

    /** Préfixe des métriques des definitions. */
    public static final String PREFIX = "axion.definition.";

    private final JavaMetrics.Metric loaded;
    private final JavaMetrics.Metric refused;
    private final JavaMetrics.Metric reloads;
    private final JavaMetrics.Metric loadNanos;
    private final JavaMetrics.Metric sourceBytes;

    /**
     * Déclare les métriques des definitions.
     *
     * @param metrics registre où les publier, {@link JavaMetrics#GLOBAL} en jeu
     */
    public DefinitionMetrics(JavaMetrics metrics) {
        loaded = metrics.gauge(PREFIX + "loaded", "count");
        refused = metrics.gauge(PREFIX + "refused", "count");
        reloads = metrics.counter(PREFIX + "reloads", "count");
        loadNanos = metrics.gauge(PREFIX + "load_ns", "ns");
        sourceBytes = metrics.gauge(PREFIX + "source_bytes", "bytes");
    }

    /**
     * Note un rechargement des definitions.
     *
     * @param loadedCount definitions acceptées
     * @param refusedCount definitions refusées (R-580)
     * @param nanos durée du chargement, en nanosecondes
     * @param bytes octets des sources de definitions retenues en mémoire
     */
    public void reloaded(int loadedCount, int refusedCount, long nanos, long bytes) {
        reloads.increment();
        loaded.set(loadedCount);
        refused.set(refusedCount);
        loadNanos.set(Math.max(0, nanos));
        sourceBytes.set(Math.max(0, bytes));
    }
}
