package dev.axion.diag;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.axion.physics.SimulationTrace.Tile;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** R-500 — les métriques des definitions (C-27), de l'entité d'assembly (C-50) et des tuiles du monde (C-38). */
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

    @Test
    @DisplayName("R-500 : les tuiles du monde publient construites, libérées, replis, file, tenues et durée")
    void lesTuilesDuMondeSeMesurent() {
        JavaMetrics metrics = new JavaMetrics();
        WorldTileMetrics tiles = new WorldTileMetrics(metrics);
        tiles.tick(List.of(
                Tile.built(0, 0, 0, 12, 0, true),
                Tile.built(1, 0, 0, Tile.HEIGHTFIELD, 0, false),
                Tile.released(5, 0, 0)), 4, 9, 2_500);
        tiles.tick(List.of(Tile.built(2, 0, 0, 3, 1, false)), 0, 10, -7);
        tiles.unloadedSection();

        String p = WorldTileMetrics.PREFIX;
        assertEquals(3, metrics.counter(p + "tiles_built", "count").value());
        assertEquals(1, metrics.counter(p + "tiles_released", "count").value());
        assertEquals(1, metrics.counter(p + "tiles_heightfield", "count").value(), "R-641 : un repli");
        assertEquals(1, metrics.counter(p + "sections_unloaded", "count").value(), "R-640 : un repli");
        assertEquals(1, metrics.gauge(p + "tiles_built_last_tick", "count").value(), "le dernier tick fait foi");
        assertEquals(0, metrics.gauge(p + "tiles_pending", "count").value());
        assertEquals(1, metrics.counter(p + "ticks_with_backlog", "count").value(),
                "seul le premier tick a laissé des sections en file");
        assertEquals(10, metrics.gauge(p + "tiles_loaded", "count").value());
        assertEquals(0, metrics.gauge(p + "tile_build_ns", "ns").value(), "une durée négative ne s'écrit pas");
    }
}
