package dev.axion.physics;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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
        if (json == null) {
            return null;
        }
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject() || !root.getAsJsonObject().has("metrics")
                    || !root.getAsJsonObject().get("metrics").isJsonArray()) {
                return null;
            }
            Long level = null;
            Long p95 = null;
            for (JsonElement element : root.getAsJsonObject().getAsJsonArray("metrics")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject metric = element.getAsJsonObject();
                if (!metric.has("name") || !metric.has("value")) {
                    continue;
                }
                String name = metric.get("name").getAsString();
                if (LEVEL.equals(name)) {
                    level = metric.get("value").getAsLong();
                } else if (P95.equals(name)) {
                    p95 = metric.get("value").getAsLong();
                }
            }
            if (level == null || p95 == null || level < 0 || level > Integer.MAX_VALUE) {
                return null;
            }
            return new DegradationGauges(level.intValue(), p95);
        } catch (RuntimeException unreadable) {
            // Syntaxe fautive, valeur non numérique : un export illisible ne dit rien du palier.
            return null;
        }
    }
}
