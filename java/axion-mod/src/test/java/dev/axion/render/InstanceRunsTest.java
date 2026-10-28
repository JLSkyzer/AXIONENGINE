package dev.axion.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T-530 — la clé de regroupement et le seuil d'instancing (C-65, ADR-154 D6). */
class InstanceRunsTest {

    private static List<InstanceRuns.Run> runs(int[] keys, int threshold) {
        return InstanceRuns.of(keys.length, (a, b) -> keys[a] == keys[b], threshold);
    }

    @Test
    @DisplayName("T-530 : seules les séries d'au moins le seuil s'instancient")
    void seuil() {
        int[] keys = {7, 7, 7, 7, 7, 3, 3, 9};
        assertEquals(List.of(
                new InstanceRuns.Run(0, 5, true),
                new InstanceRuns.Run(5, 2, false),
                new InstanceRuns.Run(7, 1, false)), runs(keys, 4));
        assertEquals(new InstanceRuns.Run(0, 5, false), runs(keys, 6).get(0), "sous le seuil");
        assertEquals(new InstanceRuns.Run(7, 1, true), runs(keys, 0).get(2), "seuil 0 : tout s'instancie");
    }

    @Test
    @DisplayName("T-530 : deux dessins qui diffèrent d'un élément de clé ne sont jamais groupés")
    void cleDifferente() {
        int[] keys = {1, 2, 1, 2, 1, 2};
        List<InstanceRuns.Run> runs = runs(keys, 1);
        assertEquals(6, runs.size(), "aucune série : les clés alternent");
        for (InstanceRuns.Run run : runs) {
            assertEquals(1, run.count());
        }
        assertEquals(List.of(), runs(new int[0], 4));
    }
}
