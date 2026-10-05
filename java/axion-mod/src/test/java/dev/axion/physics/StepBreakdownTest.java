package dev.axion.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** C-15 : le dernier pas décomposé se lit dans l'export des métriques natives (R-502). */
class StepBreakdownTest {

    /** Un export à la forme du natif ({@code ax_telemetry::to_json}). */
    private static String export(String... metriques) {
        return "{\n  \"schema_version\": 1,\n  \"metric_count\": " + metriques.length
                + ",\n  \"metrics\": [\n" + String.join(",\n", metriques) + "\n  ]\n}";
    }

    private static String jauge(String nom, String valeur) {
        return "    {\"name\": \"" + nom + "\", \"kind\": \"gauge\", \"unit\": \"ns\", \"value\": "
                + valeur + "}";
    }

    /** Les cinq métriques du pas, avec le temps CPU donné. */
    private static String[] pas(String cpu) {
        return new String[] {
            "    {\"name\": \"axion.sim.step_ns\", \"kind\": \"duration\", \"unit\": \"ns\","
                    + " \"value\": 9000, \"count\": 3, \"max\": 4000}",
            jauge(StepBreakdown.STEP, "4100000"),
            jauge(StepBreakdown.CPU, cpu),
            jauge(StepBreakdown.INTEGRATION, "3900000"),
            jauge(StepBreakdown.CONTACTS, "120000"),
            jauge(StepBreakdown.COLLIDERS, "285"),
        };
    }

    @Test
    void lesCinqMetriquesSontLuesParmiLesAutres() {
        StepBreakdown lu = StepBreakdown.parse(export(pas("310000")));

        assertEquals(new StepBreakdown(4_100_000L, 310_000L, 3_900_000L, 120_000L, 285L), lu);
        assertTrue(lu.cpuMeasured());
    }

    @Test
    void unTempsCpuNulEstUnTempsNonMesure() {
        // Le natif ne mesure le temps CPU qu'après un dépassement du budget.
        assertFalse(StepBreakdown.parse(export(pas("0"))).cpuMeasured());
    }

    @Test
    void unExportIncompletOuIllisibleNeDitRien() {
        assertNull(StepBreakdown.parse(export(jauge(StepBreakdown.STEP, "4100000"))), "incomplet");
        assertNull(StepBreakdown.parse(export()));
        assertNull(StepBreakdown.parse(null));
        assertNull(StepBreakdown.parse("pas du JSON {"));
        assertNull(StepBreakdown.parse("{}"));
        assertNull(StepBreakdown.parse(export(pas("\"beaucoup\""))), "valeur non entière");
    }
}
