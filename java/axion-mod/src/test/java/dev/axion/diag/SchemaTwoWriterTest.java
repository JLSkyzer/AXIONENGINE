package dev.axion.diag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

    @Test
    @DisplayName("--json n'écrit que sous <gameDir>/axion/bench/, et ne prend qu'un nom simple")
    void laSortieResteSousLeRepertoireDeJeu(@TempDir Path jeu) throws IOException {
        Path cible = SchemaTwoWriter.targetIn(jeu, "b07-ffi.json");
        assertEquals(jeu.resolve("axion").resolve("bench").resolve("b07-ffi.json"), cible);
        SchemaTwoWriter.write("B-07", "ffi", "abc123", BenchRunner.measure(() -> 2, 100, 8, 1000), cible);
        assertTrue(Files.isRegularFile(cible), "résultat non écrit");

        for (String refuse : new String[] {
            "../mods/axion.jar", "..", ".", ".cache", "", "sous/dossier.json", "sous\\dossier.json",
            "/etc/passwd", "C:\\Windows\\x.json", "x:y.json", "a b.json"
        }) {
            assertThrows(IllegalArgumentException.class, () -> SchemaTwoWriter.targetIn(jeu, refuse), refuse);
        }
    }
}

