package dev.axion.diag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-133 D1 : les métriques Java entrent dans l'export des métriques natives. */
class JavaMetricsTest {

    /** Un export natif réduit : la consommation d'un budget de rendu, déclarée et nulle. */
    private static final String NATIVE = """
            {
              "schema_version": 1,
              "metric_count": 2,
              "metrics": [
                {"name": "axion.budget.render_prep_ns.consumed", "kind": "gauge", "unit": "ns", "value": 0},
                {"name": "axion.sim.worlds", "kind": "gauge", "unit": "count", "value": 1}
              ]
            }""";

    private static JsonObject entry(JsonObject root, String name) {
        for (JsonElement element : root.getAsJsonArray("metrics")) {
            JsonObject metric = element.getAsJsonObject();
            if (metric.get("name").getAsString().equals(name)) {
                return metric;
            }
        }
        return null;
    }

    @Test
    @DisplayName("Une métrique Java de même nom donne sa valeur, sous le type et l'unité du natif")
    void uneMetriqueNativeRecoitLaValeurJava() {
        JavaMetrics metrics = new JavaMetrics();
        metrics.budget("budgets.render_prep_ns", "ms").report(1_500_000, 2_000_000);

        JsonObject root = JsonParser.parseString(metrics.merge(NATIVE)).getAsJsonObject();
        JsonObject prep = entry(root, "axion.budget.render_prep_ns.consumed");
        assertEquals(1_500_000, prep.get("value").getAsLong());
        assertEquals("ns", prep.get("unit").getAsString(), "l'unité native fait foi");
        assertEquals(1, entry(root, "axion.sim.worlds").get("value").getAsLong(), "le natif reste intact");
    }

    @Test
    @DisplayName("Les autres métriques Java s'ajoutent, et metric_count suit")
    void lesAutresSAjoutent() {
        JavaMetrics metrics = new JavaMetrics();
        metrics.gauge("axion.render.backend", "id").set(2);
        metrics.counter("axion.render.gpu_evictions", "count").add(3);

        JsonObject root = JsonParser.parseString(metrics.merge(NATIVE)).getAsJsonObject();
        JsonArray list = root.getAsJsonArray("metrics");
        assertEquals(list.size(), root.get("metric_count").getAsInt());
        assertEquals(1, root.get("schema_version").getAsInt());
        JsonObject backend = entry(root, "axion.render.backend");
        assertEquals("gauge", backend.get("kind").getAsString());
        assertEquals(2, backend.get("value").getAsLong());
        assertEquals("counter", entry(root, "axion.render.gpu_evictions").get("kind").getAsString());
    }

    @Test
    @DisplayName("Un export illisible est rendu tel quel, un export absent le reste")
    void unExportIllisibleEstRenduTelQuel() {
        JavaMetrics metrics = new JavaMetrics();
        metrics.gauge("axion.render.backend", "id").set(1);
        assertEquals("pas du json", metrics.merge("pas du json"));
        assertEquals("[1, 2]", metrics.merge("[1, 2]"));
        assertNull(metrics.merge(null));
    }

    @Test
    @DisplayName("INV-19 : un budget compte un dépassement au-delà de son plafond, jamais sans plafond")
    void unBudgetCompteSesDepassements() {
        JavaMetrics metrics = new JavaMetrics();
        JavaMetrics.Budget budget = metrics.budget("budgets.max_visible_instances", "count");
        assertFalse(budget.report(10, 64));
        assertTrue(budget.report(65, 64));
        assertFalse(budget.report(1_000_000, 0), "un plafond nul n'en est pas un");
        assertEquals(1_000_000, budget.consumed().value());
        assertEquals(1, budget.overruns().value());
        assertEquals("axion.budget.max_visible_instances.overruns", budget.overruns().name());
    }

    @Test
    @DisplayName("Un même nom désigne une même métrique ; sous un autre type, il est refusé")
    void unMemeNomUneMemeMetrique() {
        JavaMetrics metrics = new JavaMetrics();
        assertSame(metrics.gauge("axion.render.backend", "id"), metrics.gauge("axion.render.backend", "id"));
        assertThrows(IllegalArgumentException.class, () -> metrics.counter("axion.render.backend", "id"));
    }
}
