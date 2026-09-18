package dev.axion.diag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** La sortie schéma 2 du harnais en jeu (C-72), vérifiée sans écrire de fichier. */
class SchemaTwoWriterTest {

    @Test
    @DisplayName("build produit un schéma 2 à la provenance complète (R-2231)")
    void buildProducesValidSchemaTwo() {
        BenchRunner.Result result = BenchRunner.measure(() -> 2, 100, 8, 1000);
        JsonObject json = SchemaTwoWriter.build("B-07", "ffi", "abc123", result);

        assertEquals(2, json.get("schema").getAsInt());
        assertEquals("B-07", json.get("benchmark").getAsString());
        assertEquals("abc123", json.get("commit").getAsString());
        assertFalse(json.get("date").getAsString().isBlank(), "date vide");

        JsonObject platform = json.getAsJsonObject("platform");
        assertFalse(platform.get("os").getAsString().isBlank(), "os vide");
        assertFalse(platform.get("cpu").getAsString().isBlank(), "cpu vide");
        assertTrue(platform.get("cores").getAsInt() > 0, "cores nul");

        JsonObject config = json.getAsJsonObject("config");
        assertTrue(config.has("harness_version"), "harness_version absent");
        assertEquals("axion-ingame", config.get("harness").getAsString(), "harnais non distingué");

        assertTrue(
                json.getAsJsonArray("runs").get(0).getAsJsonObject().getAsJsonArray("samples_ns").size() > 0,
                "aucun échantillon");

        JsonObject stats = json.getAsJsonObject("stats");
        for (String field : new String[] {"p50_ns", "p95_ns", "p99_ns", "min_ns", "max_ns", "stddev_ns"}) {
            assertTrue(stats.has(field), field + " absent");
        }
    }
}
