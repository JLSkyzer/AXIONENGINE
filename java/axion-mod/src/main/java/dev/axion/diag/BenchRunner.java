package dev.axion.diag;

import java.util.Arrays;
import java.util.function.IntSupplier;

/**
 * Harnais de benchmark en jeu (C-72), scénario aller-retour FFI.
 *
 * <p>Mesure le coût d'un aller-retour FFI <strong>par lot</strong> : un appel de
 * l'ordre de la nanoseconde est bien en dessous de la résolution de
 * {@link System#nanoTime()} (≈ 100 ns sous Windows), qu'un chronométrage par
 * appel — celui de la calibration de démarrage (R-330) — ne peut que buter sur
 * ce plancher. En chronométrant un lot de milliers d'appels puis en divisant, le
 * coût par appel passe sous la granularité du timer : c'est la méthode que le
 * benchmark JMH B-07 valide (~5 ns), là où la calibration naïve affiche 100 ns.
 *
 * <p>Ce runner ne dépend d'aucun type de plateforme : il prend l'aller-retour à
 * mesurer comme un {@link IntSupplier}, ce qui le rend testable avec un appel
 * factice.
 */
public final class BenchRunner {

    /** Appels d'échauffement, non mesurés, pour laisser le JIT compiler. */
    private static final int WARMUP_CALLS = 200_000;

    /** Nombre de lots mesurés ; chaque lot donne un échantillon. */
    private static final int RUNS = 64;

    /** Appels par lot ; le coût par appel est le temps du lot divisé par ce nombre. */
    private static final int BATCH = 50_000;

    private BenchRunner() {
        throw new AssertionError("classe utilitaire, non instanciable");
    }

    /** Statistiques d'un scénario, en nanosecondes par appel. */
    public record Result(double p50Ns, double p95Ns, double p99Ns, double minNs, double maxNs, long calls) {}

    /**
     * Mesure l'aller-retour FFI avec la configuration par défaut.
     *
     * @param roundtrip l'appel à mesurer (p. ex. la version d'ABI native)
     * @return les statistiques par appel
     */
    public static Result ffi(IntSupplier roundtrip) {
        return measure(roundtrip, WARMUP_CALLS, RUNS, BATCH);
    }

    /**
     * Mesure un aller-retour, paramétrable pour les tests.
     *
     * <p>Chaque lot est chronométré en bloc ; le coût par appel est le temps du
     * lot divisé par sa taille, ce qui descend sous la résolution du timer. Les
     * valeurs de retour sont accumulées et vérifiées pour empêcher le JIT
     * d'éliminer la boucle mesurée.
     */
    static Result measure(IntSupplier roundtrip, int warmup, int runs, int batch) {
        int sink = 0;
        for (int index = 0; index < warmup; index++) {
            sink ^= roundtrip.getAsInt();
        }
        double[] perCall = new double[runs];
        for (int run = 0; run < runs; run++) {
            long start = System.nanoTime();
            for (int call = 0; call < batch; call++) {
                sink ^= roundtrip.getAsInt();
            }
            long elapsed = System.nanoTime() - start;
            perCall[run] = (double) elapsed / batch;
        }
        // Empêche l'élimination de code mort : `sink` ne vaut jamais cette valeur,
        // mais la comparaison force la conservation de la boucle.
        if (sink == 0xDEAD_BEEF) {
            throw new IllegalStateException("valeur impossible");
        }
        Arrays.sort(perCall);
        return new Result(
                percentile(perCall, 50),
                percentile(perCall, 95),
                percentile(perCall, 99),
                perCall[0],
                perCall[perCall.length - 1],
                (long) runs * batch);
    }

    /** Centile au rang le plus proche, comme le crate ax-bench et le pont JMH. */
    private static double percentile(double[] sorted, int p) {
        int rank = (int) Math.ceil(p / 100.0 * sorted.length);
        rank = Math.max(1, Math.min(sorted.length, rank));
        return sorted[rank - 1];
    }
}
