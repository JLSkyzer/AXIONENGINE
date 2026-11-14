package dev.axion.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-168 D8, R-1683 : la prédiction se coupe au-delà d'un taux de corrections, et se retente d'elle-même. */
class CorrectionRateTest {

    @Test
    @DisplayName("D8 : rien ne compte avant une fenêtre pleine")
    void fenetrePleine() {
        CorrectionRate rate = new CorrectionRate();
        for (int i = 0; i < CorrectionRate.WINDOW - 1; i++) {
            rate.record(true);
        }
        assertEquals(0.0, rate.rate());
        assertFalse(rate.cut(0.5), "quelques corrections ne disent rien d'un lien");
        rate.record(true);
        assertEquals(1.0, rate.rate());
    }

    @Test
    @DisplayName("D8 : au seuil, la fenêtre glisse ; au-delà, coupée")
    void auDelaDuSeuil() {
        CorrectionRate rate = new CorrectionRate();
        for (int i = 0; i < CorrectionRate.WINDOW; i++) {
            rate.record(i % 2 == 1);
        }
        assertEquals(0.5, rate.rate());
        assertFalse(rate.cut(0.5), "au seuil, pas au-delà");
        // La fenêtre glisse : une démentie remplace la plus ancienne, confirmée.
        rate.record(true);
        assertEquals(0.51, rate.rate(), 1e-12);
        assertTrue(rate.cut(0.5));
        assertEquals(0.51, rate.cutRate(), 1e-12);
    }

    @Test
    @DisplayName("D8 : coupée, elle se retente après une fenêtre, sur une fenêtre vide ; recoupée si la cause dure")
    void seRetente() {
        CorrectionRate rate = new CorrectionRate();
        for (int i = 0; i < CorrectionRate.WINDOW; i++) {
            rate.record(true);
        }
        assertTrue(rate.cut(0.5));
        for (int i = 0; i < CorrectionRate.WINDOW - 1; i++) {
            rate.whileCut();
            assertTrue(rate.cut(0.5), "coupée pendant le délai");
        }
        rate.whileCut();
        assertFalse(rate.cut(0.5), "retentée");
        assertEquals(0.0, rate.rate(), "sur une fenêtre vide");
        for (int i = 0; i < CorrectionRate.WINDOW; i++) {
            rate.record(i % 10 == 0);
        }
        assertFalse(rate.cut(0.5), "la cause a cessé : rétablie");
        for (int i = 0; i < CorrectionRate.WINDOW; i++) {
            rate.record(true);
        }
        assertTrue(rate.cut(0.5), "la cause revient : recoupée");
    }

    @Test
    @DisplayName("D8 : un seuil de 1 ne coupe jamais")
    void seuilUn() {
        CorrectionRate rate = new CorrectionRate();
        for (int i = 0; i < 3 * CorrectionRate.WINDOW; i++) {
            rate.record(true);
            assertFalse(rate.cut(1.0));
        }
    }
}
