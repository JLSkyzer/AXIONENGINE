package dev.axion.diag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T-201 — lecture de l'export de métriques natif (C-15, R-502). */
class MetricsReportTest {

    private static String joined(List<String> lines) {
        return String.join("\n", lines);
    }

    @Test
    @DisplayName("T-201 : seules les métriques non nulles sont détaillées")
    void seulesLesMetriquesNonNullesSontDetaillees() {
        String json = """
                {
                  "schema_version": 1,
                  "metric_count": 3,
                  "metrics": [
                    {"name": "axion.native.panics", "kind": "gauge", "unit": "count", "value": 0},
                    {"name": "axion.jobs.workers", "kind": "gauge", "unit": "count", "value": 8},
                    {"name": "axion.budget.sim_ns_per_tick.overruns", "kind": "counter",
                     "unit": "count", "value": 2}
                  ]
                }""";

        String report = joined(MetricsReport.summary(json));

        assertTrue(report.contains("déclarées : 3, non nulles : 2"), report);
        assertTrue(report.contains("axion.jobs.workers : 8"), report);
        assertTrue(report.contains("axion.budget.sim_ns_per_tick.overruns : 2"), report);
        // Une métrique à zéro dit qu'un composant n'a rien fait, ce que la
        // liste des composants dit déjà.
        assertFalse(report.contains("axion.native.panics"), report);
    }

    @Test
    @DisplayName("T-201 : une durée montre son nombre de mesures et son maximum")
    void uneDureeMontreSesMesures() {
        String json = """
                {
                  "schema_version": 1,
                  "metric_count": 1,
                  "metrics": [
                    {"name": "axion.sim.tick_ns", "kind": "duration", "unit": "ns",
                     "value": 1200, "count": 4, "max": 500, "mean": 300}
                  ]
                }""";

        String report = joined(MetricsReport.summary(json));

        // Une somme seule ne dit pas si elle vient d'une mesure ou de mille.
        assertTrue(report.contains("axion.sim.tick_ns : 1200 ns"), report);
        assertTrue(report.contains("4 mesures"), report);
        assertTrue(report.contains("max 500 ns"), report);
    }

    @Test
    @DisplayName("T-201 : un export sans activité le dit plutôt que de rester muet")
    void unExportSansActiviteLeDit() {
        String json = """
                {
                  "schema_version": 1,
                  "metric_count": 2,
                  "metrics": [
                    {"name": "axion.native.panics", "kind": "gauge", "unit": "count", "value": 0},
                    {"name": "axion.jobs.workers", "kind": "gauge", "unit": "count", "value": 0}
                  ]
                }""";

        String report = joined(MetricsReport.summary(json));
        assertTrue(report.contains("aucune activité mesurée"), report);
        assertTrue(report.contains("déclarées : 2"), report);
    }

    @Test
    @DisplayName("T-201 : un export illisible est signalé, jamais avalé")
    void unExportIllisibleEstSignale() {
        // Le producteur est le natif : un document cassé est un défaut, et
        // afficher une liste vide le masquerait.
        String report = joined(MetricsReport.summary("{ceci n'est pas du JSON"));
        assertTrue(report.contains("illisible"), report);

        String tableau = joined(MetricsReport.summary("[1, 2, 3]"));
        assertTrue(tableau.contains("inattendu"), tableau);
    }

    @Test
    @DisplayName("T-201 : un export volumineux est tronqué en renvoyant au fichier")
    void unExportVolumineuxRenvoieAuFichier() {
        StringBuilder metrics = new StringBuilder();
        for (int index = 0; index < 40; index++) {
            if (index > 0) {
                metrics.append(',');
            }
            metrics.append("{\"name\": \"axion.test.m")
                    .append(index)
                    .append("\", \"kind\": \"counter\", \"unit\": \"count\", \"value\": 1}");
        }
        String json = "{\"schema_version\": 1, \"metric_count\": 40, \"metrics\": ["
                + metrics + "]}";

        List<String> lines = MetricsReport.summary(json);
        String report = joined(lines);

        assertTrue(report.contains("non nulles : 40"), report);
        assertTrue(report.contains("de plus"), report);
        assertTrue(report.contains("/axion metrics export"), report);
        // Un chat de Minecraft n'est pas un endroit où lire quarante lignes.
        assertEquals(27, lines.size(), report);
    }

    @Test
    @DisplayName("T-201 : un export sans tableau de métriques ne casse rien")
    void unExportSansMetriquesNeCassePas() {
        String report = joined(MetricsReport.summary("{\"schema_version\": 1}"));
        assertTrue(report.contains("déclarées : 0"), report);
    }
}
