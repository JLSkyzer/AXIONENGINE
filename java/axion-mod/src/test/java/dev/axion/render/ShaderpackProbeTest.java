package dev.axion.render;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Épingle la lecture de l'état du shaderpack (R-1490, ADR-129). */
class ShaderpackProbeTest {

    /** L'API d'Iris telle que la sonde la lit : {@code getInstance()}, puis {@code isShaderPackInUse()}. */
    public static final class FausseApi {
        static boolean enUsage;
        static boolean echoue;

        public static FausseApi getInstance() {
            return new FausseApi();
        }

        public boolean isShaderPackInUse() {
            if (echoue) {
                throw new IllegalStateException("pipeline en cours de rechargement");
            }
            return enUsage;
        }
    }

    private static final String FAUSSE_API = FausseApi.class.getName();

    @AfterEach
    void remettre() {
        FausseApi.enUsage = false;
        FausseApi.echoue = false;
    }

    @Test
    void sansModDeShadersAucunPackNestEnUsage() {
        ShaderpackProbe sonde = ShaderpackProbe.of(false, FAUSSE_API);
        FausseApi.enUsage = true;
        // Sans mod de shaders, l'API n'est pas même cherchée.
        assertFalse(sonde.inUse());
        assertFalse(sonde.readable());
    }

    @Test
    void avecLeModLaSondeSuitLetatDuPack() {
        ShaderpackProbe sonde = ShaderpackProbe.of(true, FAUSSE_API);
        assertTrue(sonde.readable());
        assertFalse(sonde.inUse());
        FausseApi.enUsage = true;
        assertTrue(sonde.inUse());
        FausseApi.enUsage = false;
        assertFalse(sonde.inUse());
    }

    @Test
    void uneApiIntrouvableFaitTenirLePackPourActif() {
        // Mod de shaders présent, état illisible : le vanilla, obligatoire sous shaderpack, est le
        // seul choix sûr.
        ShaderpackProbe sonde = ShaderpackProbe.of(true, "dev.axion.render.ApiQuiNExistePas");
        assertFalse(sonde.readable());
        assertTrue(sonde.inUse());
    }

    @Test
    void uneApiQuiEchoueFaitTenirLePackPourActifEtNestPlusInterrogee() {
        ShaderpackProbe sonde = ShaderpackProbe.of(true, FAUSSE_API);
        FausseApi.echoue = true;
        assertTrue(sonde.inUse());
        assertFalse(sonde.readable());
        FausseApi.echoue = false;
        // Une API qui a échoué une fois n'est plus crue : l'état resterait incertain.
        assertTrue(sonde.inUse());
    }
}
