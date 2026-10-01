package dev.axion.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.axion.render.BackendSelection.Kind;
import org.junit.jupiter.api.Test;

/** Épingle le choix du backend de rendu (R-1490, fiche 5.48, ADR-118). */
class BackendSelectionTest {

    @Test
    void unShaderpackImposeVanillaQuelleQueSoitLaDemande() {
        for (String demande : new String[] {"auto", "native", "vanilla"}) {
            assertEquals(Kind.VANILLA, BackendSelection.select(demande, true, true).kind(), demande);
        }
    }

    @Test
    void vanillaDemandeEstRespecteMemeSiLeNatifExiste() {
        assertEquals(Kind.VANILLA, BackendSelection.select("vanilla", true, false).kind());
    }

    @Test
    void autoEtNativePrennentLeNatifQuandIlEstDisponible() {
        assertEquals(Kind.NATIVE, BackendSelection.select("auto", true, false).kind());
        assertEquals(Kind.NATIVE, BackendSelection.select("native", true, false).kind());
    }

    @Test
    void sansNatifToutRetombeSurVanilla() {
        // R-1491 : vanilla est le repli universel, jamais un chemin mort.
        assertEquals(Kind.VANILLA, BackendSelection.select("auto", false, false).kind());
        assertEquals(Kind.VANILLA, BackendSelection.select("native", false, false).kind());
    }

    @Test
    void uneValeurInconnueSeComporteCommeAuto() {
        assertEquals(Kind.NATIVE, BackendSelection.select("n'importe quoi", true, false).kind());
        assertEquals(Kind.VANILLA, BackendSelection.select(null, false, false).kind());
    }
}
