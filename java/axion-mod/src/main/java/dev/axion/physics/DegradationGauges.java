package dev.axion.physics;

import java.util.Map;
import java.util.Set;

/**
 * Jauges de la dégradation de la simulation (SM-02, ADR-123 §9), lues dans l'export des
 * métriques natives (R-502) : le palier courant et le p95 de la dernière fenêtre.
 *
 * <p>Le drapeau {@code AXION_SIM_DEGRADED} du collect dit seulement « pas {@code NORMAL} » ; le
 * palier et sa cause se lisent ici.
 *
 * @param level rang du palier ({@code axion.sim.degradation_level}), 0 pour {@code NORMAL}
 * @param p95Ns p95 de la dernière fenêtre de 100 ticks ({@code axion.sim.p95_ns}), en ns
 */
public record DegradationGauges(int level, long p95Ns) {

    /** Jauge du palier. */
    public static final String LEVEL = "axion.sim.degradation_level";

    /** Jauge du p95. */
    public static final String P95 = "axion.sim.p95_ns";

    /**
     * Lit les deux jauges dans un export.
     *
     * @param json export JSON des métriques
     * @return les jauges, ou {@code null} si l'export est illisible ou ne les porte pas
     */
    public static DegradationGauges parse(String json) {
        Map<String, Long> values = MetricExport.values(json, Set.of(LEVEL, P95));
        if (values == null) {
            return null;
        }
        Long level = values.get(LEVEL);
        Long p95 = values.get(P95);
        if (level == null || p95 == null || level < 0 || level > Integer.MAX_VALUE) {
            return null;
        }
        return new DegradationGauges(level.intValue(), p95);
    }
}
