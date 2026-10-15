package dev.axion.diag;

/**
 * Les métriques de l'entité d'assembly (C-50), publiées dans l'export de {@code /axion metrics}.
 *
 * <p>La fiche C-50 n'en nomme aucune ; R-500 veut, pour chaque composant, ses opérations, ses
 * erreurs, sa mémoire et sa durée :
 *
 * <ul>
 *   <li>opérations — assemblies entrées dans un monde et sorties ;
 *   <li>erreurs — assemblies entrées inertes (R-704 : definition inconnue, schéma autre),
 *       apparitions refusées par {@code /axion spawn} ;
 *   <li>mémoire — assemblies dotées d'un corps natif, ce que l'entité retient de la simulation ;
 *   <li>durée — l'application des états du dernier tick.
 * </ul>
 *
 * <p>Leur imputation à un budget et leur comportement en surcharge viennent avec C-77 (R-1850,
 * M5).
 */
public final class EntityMetrics {

    /** Préfixe des métriques de l'entité. */
    public static final String PREFIX = "axion.entity.";

    /** Les métriques du jeu, dans le registre global. */
    public static final EntityMetrics GLOBAL = new EntityMetrics(JavaMetrics.GLOBAL);

    private final JavaMetrics.Metric joined;
    private final JavaMetrics.Metric left;
    private final JavaMetrics.Metric inert;
    private final JavaMetrics.Metric spawnRefused;
    private final JavaMetrics.Metric bodies;
    private final JavaMetrics.Metric applyNanos;

    /**
     * Déclare les métriques de l'entité.
     *
     * @param metrics registre où les publier
     */
    public EntityMetrics(JavaMetrics metrics) {
        joined = metrics.counter(PREFIX + "joined", "count");
        left = metrics.counter(PREFIX + "left", "count");
        inert = metrics.counter(PREFIX + "inert", "count");
        spawnRefused = metrics.counter(PREFIX + "spawn_refused", "count");
        bodies = metrics.gauge(PREFIX + "bodies", "count");
        applyNanos = metrics.gauge(PREFIX + "apply_states_ns", "ns");
    }

    /**
     * Une assembly entre dans un monde (serveur).
     *
     * @param inertAssembly vrai si elle entre inerte (R-704)
     */
    public void joined(boolean inertAssembly) {
        joined.increment();
        if (inertAssembly) {
            inert.increment();
        }
    }

    /** Une assembly sort d'un monde (serveur). */
    public void left() {
        left.increment();
    }

    /** {@code /axion spawn} refuse une apparition. */
    public void spawnRefused() {
        spawnRefused.increment();
    }

    /**
     * Le nombre d'assemblies dotées d'un corps natif.
     *
     * @param count assemblies avec un corps
     */
    public void bodies(int count) {
        bodies.set(count);
    }

    /**
     * La durée de l'application des états d'un tick.
     *
     * @param nanos durée, en nanosecondes
     */
    public void applied(long nanos) {
        applyNanos.set(Math.max(0, nanos));
    }
}
