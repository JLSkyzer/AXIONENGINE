package dev.axion.render;

import static dev.axion.asset.MaterialTransfer.SAMPLER_CLAMP_U;
import static dev.axion.asset.MaterialTransfer.SAMPLER_CLAMP_V;
import static dev.axion.asset.MaterialTransfer.SAMPLER_FILTER_LINEAR;
import static dev.axion.asset.MaterialTransfer.SAMPLER_FILTER_NEAREST;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Filtrage et répétition d'une texture (ADR-122 §7, décision 4). */
class TextureSamplingTest {

    @Test
    @DisplayName("Décision 4 : sans filtrage déclaré, le plus proche, comme Minecraft")
    void sansFiltrageDeclareLePlusProche() {
        assertEquals(new TextureSampling(false, false, false), TextureSampling.of(0, null, null));
        assertEquals(new TextureSampling(true, false, false), TextureSampling.of(SAMPLER_FILTER_LINEAR, null, null));
    }

    @Test
    @DisplayName("Le .mcmeta d'une ressource décide quand l'échantillonneur se tait, jamais contre lui")
    void leMcmetaNeDecideQueSiRienNEstDeclare() {
        assertEquals(true, TextureSampling.of(0, true, null).blur());
        assertEquals(false, TextureSampling.of(SAMPLER_FILTER_NEAREST, true, null).blur());
    }

    @Test
    @DisplayName("Écrêtage : les deux axes, ou la répétition signalée")
    void ecretageDesDeuxAxesOuRepetitionSignalee() {
        assertEquals(new TextureSampling(false, true, false),
                TextureSampling.of(SAMPLER_CLAMP_U | SAMPLER_CLAMP_V, null, null));
        assertEquals(new TextureSampling(false, false, true),
                TextureSampling.of(SAMPLER_CLAMP_U, null, null), "un seul axe : répété, et signalé");
        assertEquals(new TextureSampling(false, true, false),
                TextureSampling.of(SAMPLER_CLAMP_V, null, true), "le .mcmeta écrête les deux");
    }
}
