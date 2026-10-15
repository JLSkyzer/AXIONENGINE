package dev.axion.diag;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** R-500 — les métriques des definitions (C-27) et de l'entité d'assembly (C-50). */
class ComponentMetricsTest {

    @Test
    @DisplayName("R-500 : un rechargement des definitions publie chargées, refusées, durée et octets")
    void lesDefinitionsSeMesurent() {
        JavaMetrics metrics = new JavaMetrics();
        DefinitionMetrics definitions = new DefinitionMetrics(metrics);
        definitions.reloaded(5, 2, 1_200, 4_096);
        definitions.reloaded(6, 0, -1, 5_000);

        String p = DefinitionMetrics.PREFIX;
        assertEquals(2, metrics.counter(p + "reloads", "count").value());
        assertEquals(6, metrics.gauge(p + "loaded", "count").value(), "le dernier rechargement fait foi");
        assertEquals(0, metrics.gauge(p + "refused", "count").value());
        assertEquals(0, metrics.gauge(p + "load_ns", "ns").value(), "une durée négative ne s'écrit pas");
        assertEquals(5_000, metrics.gauge(p + "source_bytes", "bytes").value());
    }

    @Test
    @DisplayName("R-500 : l'entité publie entrées, sorties, inertes, refus, corps et durée d'application")
    void lEntiteSeMesure() {
        JavaMetrics metrics = new JavaMetrics();
        EntityMetrics entity = new EntityMetrics(metrics);
        entity.joined(false);
        entity.joined(true);
        entity.left();
        entity.spawnRefused();
        entity.bodies(3);
        entity.applied(800);

        String p = EntityMetrics.PREFIX;
        assertEquals(2, metrics.counter(p + "joined", "count").value());
        assertEquals(1, metrics.counter(p + "inert", "count").value(), "R-704 : une entrée inerte");
        assertEquals(1, metrics.counter(p + "left", "count").value());
        assertEquals(1, metrics.counter(p + "spawn_refused", "count").value());
        assertEquals(3, metrics.gauge(p + "bodies", "count").value());
        assertEquals(800, metrics.gauge(p + "apply_states_ns", "ns").value());
    }
}
