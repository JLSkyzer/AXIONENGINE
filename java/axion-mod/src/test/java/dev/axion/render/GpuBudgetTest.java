package dev.axion.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-501 — R-750 : toute allocation GPU est comptée ; au-delà du budget, les assets non visibles sont
 * déchargés, du plus anciennement vu au plus récent (fiche 5.49, ADR-127 §3).
 */
class GpuBudgetTest {

    @Test
    @DisplayName("Les allocations d'un asset s'additionnent, et se retirent avec lui")
    void lesAllocationsSontComptees() {
        GpuBudget<String> budget = new GpuBudget<>(1_000);
        budget.charge("cube", 300);
        budget.charge("cube", 100);
        budget.charge("roue", 200);
        assertEquals(600, budget.chargedBytes());
        assertEquals(400, budget.bytesOf("cube"));
        budget.discharge("cube");
        assertEquals(200, budget.chargedBytes());
        assertEquals(0, budget.bytesOf("cube"));
    }

    @Test
    @DisplayName("Le budget n'est dépassé qu'au-delà de sa valeur")
    void depasseAuDela() {
        GpuBudget<String> budget = new GpuBudget<>(1_000);
        assertFalse(budget.exceededBy(1_000));
        assertTrue(budget.exceededBy(1_001));
    }

    @Test
    @DisplayName("Au-delà du budget, le premier déchargé est le moins récemment vu")
    void leMoinsRecemmentVuEstDechargeEnPremier() {
        GpuBudget<String> budget = new GpuBudget<>(1_000);
        budget.charge("a", 100);
        budget.charge("b", 100);
        budget.charge("c", 100);
        budget.seen("a", 5);
        budget.seen("b", 8);
        budget.seen("c", 10);

        assertEquals("a", budget.evictionCandidate(10));
        budget.discharge("a");
        assertEquals("b", budget.evictionCandidate(10));
    }

    @Test
    @DisplayName("Un asset visible à la frame n'est jamais déchargé")
    void unAssetVisibleNEstJamaisDecharge() {
        GpuBudget<String> budget = new GpuBudget<>(1_000);
        budget.charge("a", 100);
        budget.seen("a", 10);
        assertNull(budget.evictionCandidate(10), "vu à cette frame : rien à décharger");
        assertEquals("a", budget.evictionCandidate(11));
    }

    @Test
    @DisplayName("Un asset chargé mais jamais vu passe avant tous les autres")
    void unAssetJamaisVuPasseAvant() {
        GpuBudget<String> budget = new GpuBudget<>(1_000);
        budget.charge("vu", 100);
        budget.seen("vu", 3);
        budget.charge("jamais vu", 100);
        assertEquals("jamais vu", budget.evictionCandidate(10));
    }

    @Test
    @DisplayName("À égalité, l'ordre de chargement départage : d'une frame à l'autre, le même choix")
    void aEgaliteLOrdreDeChargementDepartage() {
        GpuBudget<String> budget = new GpuBudget<>(1_000);
        budget.charge("b", 100);
        budget.charge("a", 100);
        budget.seen("a", 4);
        budget.seen("b", 4);
        assertEquals("b", budget.evictionCandidate(10));
    }

    @Test
    @DisplayName("Un budget nul ou négatif est refusé")
    void unBudgetNulEstRefuse() {
        assertThrows(IllegalArgumentException.class, () -> new GpuBudget<String>(0));
    }
}
