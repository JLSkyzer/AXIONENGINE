package dev.axion.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.axion.render.GpuDegradation.Level;
import dev.axion.render.GpuDegradation.Transition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T-484 — budget GPU dépassé : dégradation par paliers, journalisée (R-750, §19.12, R-1880). */
class GpuDegradationTest {

    private static final long BUDGET = 1_000;
    private static final long SECONDE = 1_000_000_000L;

    /** Une frame qui tient le budget sans rien décharger. */
    private static MeshCache.Residency tenue() {
        return new MeshCache.Residency(800, 0, false);
    }

    @Test
    @DisplayName("T-484 : sans dépassement, le palier reste nominal et rien ne se dit")
    void sansDepassementRienNeChange() {
        GpuDegradation gpu = new GpuDegradation(BUDGET);
        for (int i = 0; i < 100; i++) {
            assertNull(gpu.observe(tenue(), i * SECONDE));
        }
        assertEquals(Level.NOMINAL, gpu.level());
    }

    @Test
    @DisplayName("T-484 : un dépassement résorbé par déchargement fait descendre au palier 1, cause dite")
    void premierPalier() {
        GpuDegradation gpu = new GpuDegradation(BUDGET);
        Transition descente = gpu.observe(new MeshCache.Residency(900, 2, true), 0);
        assertEquals(Level.NOMINAL, descente.from());
        assertEquals(Level.UNLOADING, descente.to());
        assertTrue(descente.degrades());
        String journal = descente.describe();
        assertTrue(journal.contains("900") && journal.contains("1000") && journal.contains("2 asset(s)"),
                journal);
        // Un nouveau déchargement au même palier ne se redit pas : pas de journal à chaque frame.
        assertNull(gpu.observe(new MeshCache.Residency(950, 1, true), SECONDE));
        assertEquals(Level.UNLOADING, gpu.level());
    }

    @Test
    @DisplayName("T-484 : des assets tous visibles laissent le budget dépassé — palier insuffisant, dit une fois")
    void palierInsuffisant() {
        GpuDegradation gpu = new GpuDegradation(BUDGET);
        Transition descente = gpu.observe(new MeshCache.Residency(1_500, 0, true), 0);
        assertEquals(Level.UNRESOLVED, descente.to());
        assertTrue(descente.describe().contains("C-64"), descente.describe());
        assertNull(gpu.observe(new MeshCache.Residency(1_500, 0, true), SECONDE));
    }

    @Test
    @DisplayName("T-484 : la remontée attend 30 s sous le seuil, un palier à la fois, 10 s au moins entre deux (SM-02)")
    void remonteeAvecHysteresis() {
        GpuDegradation gpu = new GpuDegradation(BUDGET);
        gpu.observe(new MeshCache.Residency(1_500, 0, true), 0);
        assertEquals(Level.UNRESOLVED, gpu.level());

        long calme = SECONDE;
        assertNull(gpu.observe(tenue(), calme), "début du calme");
        assertNull(gpu.observe(tenue(), calme + 29 * SECONDE), "29 s : trop tôt");
        Transition premiere = gpu.observe(tenue(), calme + 30 * SECONDE);
        assertEquals(Level.UNRESOLVED, premiere.from());
        assertEquals(Level.UNLOADING, premiere.to(), "un seul palier à la fois");
        assertNull(gpu.observe(tenue(), calme + 35 * SECONDE), "moins de 10 s après la précédente");
        Transition seconde = gpu.observe(tenue(), calme + 40 * SECONDE);
        assertEquals(Level.NOMINAL, seconde.to());
        assertNull(gpu.observe(tenue(), calme + 60 * SECONDE), "au nominal, plus rien à remonter");
    }

    @Test
    @DisplayName("T-484 : un dépassement pendant le calme le remet à zéro")
    void unDepassementRemetLeCalmeAZero() {
        GpuDegradation gpu = new GpuDegradation(BUDGET);
        gpu.observe(new MeshCache.Residency(900, 1, true), 0);
        gpu.observe(tenue(), SECONDE);
        gpu.observe(new MeshCache.Residency(950, 1, true), 25 * SECONDE);
        assertNull(gpu.observe(tenue(), 26 * SECONDE));
        assertNull(gpu.observe(tenue(), 50 * SECONDE), "24 s de calme depuis le dépassement");
        assertEquals(Level.NOMINAL, gpu.observe(tenue(), 56 * SECONDE).to());
    }
}
