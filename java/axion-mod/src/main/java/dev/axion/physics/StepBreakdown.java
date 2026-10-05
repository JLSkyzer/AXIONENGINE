package dev.axion.physics;

import java.util.Map;
import java.util.Set;

/**
 * Le dernier pas de simulation décomposé (C-15, R-661), lu dans l'export des métriques natives
 * (R-502) : ce qui explique un tick lent.
 *
 * @param stepNs temps mural du pas ({@code axion.budget.sim_ns_per_tick.consumed}), celui du
 *     budget et du gouverneur FM-21
 * @param cpuNs temps CPU du thread pendant le pas ({@code axion.sim.last_step_cpu_ns}) ; zéro
 *     s'il n'a pas été mesuré — le natif ne le mesure qu'après un pas au-delà du budget
 * @param integrationNs étape 4, l'intégration ({@code axion.sim.last_step_integration_ns})
 * @param contactsNs étape 6, les contacts ({@code axion.sim.last_step_contacts_ns})
 * @param colliders colliders de tous les mondes ({@code axion.sim.colliders})
 */
public record StepBreakdown(
        long stepNs, long cpuNs, long integrationNs, long contactsNs, long colliders) {

    /** Temps mural du dernier pas : la consommation du budget de simulation (INV-19). */
    public static final String STEP = "axion.budget.sim_ns_per_tick.consumed";

    /** Temps CPU du thread pendant le dernier pas. */
    public static final String CPU = "axion.sim.last_step_cpu_ns";

    /** Étape 4 du dernier pas. */
    public static final String INTEGRATION = "axion.sim.last_step_integration_ns";

    /** Étape 6 du dernier pas. */
    public static final String CONTACTS = "axion.sim.last_step_contacts_ns";

    /** Colliders de tous les mondes. */
    public static final String COLLIDERS = "axion.sim.colliders";

    private static final Set<String> NAMES = Set.of(STEP, CPU, INTEGRATION, CONTACTS, COLLIDERS);

    /** {@return vrai si le temps CPU du pas a été mesuré} */
    public boolean cpuMeasured() {
        return cpuNs > 0;
    }

    /**
     * Lit le dernier pas dans un export.
     *
     * @param json export JSON des métriques
     * @return le pas, ou {@code null} si l'export est illisible ou ne porte pas ses cinq métriques
     */
    public static StepBreakdown parse(String json) {
        Map<String, Long> values = MetricExport.values(json, NAMES);
        if (values == null || !values.keySet().containsAll(NAMES)) {
            return null;
        }
        return new StepBreakdown(
                values.get(STEP),
                values.get(CPU),
                values.get(INTEGRATION),
                values.get(CONTACTS),
                values.get(COLLIDERS));
    }
}
