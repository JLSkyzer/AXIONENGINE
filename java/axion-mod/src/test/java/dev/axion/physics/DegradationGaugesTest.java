package dev.axion.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** ADR-123 §9 : Java lit le palier et le p95 dans l'export des métriques natives (R-502). */
class DegradationGaugesTest {

    /** Un export à la forme du natif ({@code ax_telemetry::to_json}). */
    private static String export(String metriques) {
        return "{\n  \"schema_version\": 1,\n  \"metric_count\": 3,\n  \"metrics\": [\n"
                + metriques + "\n  ]\n}";
    }

    @Test
    void lesDeuxJaugesSontLuesParmiLesAutres() {
        String json = export(
                "    {\"name\": \"axion.sim.tick_ns\", \"kind\": \"duration\", \"unit\": \"ns\","
                        + " \"value\": 1200, \"count\": 1, \"max\": 1200},\n"
                        + "    {\"name\": \"axion.sim.degradation_level\", \"kind\": \"gauge\","
                        + " \"unit\": \"count\", \"value\": 2},\n"
                        + "    {\"name\": \"axion.sim.p95_ns\", \"kind\": \"gauge\", \"unit\": \"ns\","
                        + " \"value\": 3420000}");

        assertEquals(new DegradationGauges(2, 3_420_000L), DegradationGauges.parse(json));
    }

    @Test
    void unExportSansLesJaugesNeDitRien() {
        assertNull(DegradationGauges.parse(export(
                "    {\"name\": \"axion.sim.degradation_level\", \"kind\": \"gauge\","
                        + " \"unit\": \"count\", \"value\": 1}")));
        assertNull(DegradationGauges.parse(export("")));
        assertNull(DegradationGauges.parse("{}"));
    }

    @Test
    void unExportIllisibleNeDitRien() {
        assertNull(DegradationGauges.parse(null));
        assertNull(DegradationGauges.parse("pas du JSON {"));
        assertNull(DegradationGauges.parse("[1, 2]"));
        assertNull(DegradationGauges.parse(export(
                "    {\"name\": \"axion.sim.degradation_level\", \"value\": \"haut\"},\n"
                        + "    {\"name\": \"axion.sim.p95_ns\", \"value\": 10}")));
        assertNull(DegradationGauges.parse(export(
                "    {\"name\": \"axion.sim.degradation_level\", \"value\": -1},\n"
                        + "    {\"name\": \"axion.sim.p95_ns\", \"value\": 10}")));
    }
}
