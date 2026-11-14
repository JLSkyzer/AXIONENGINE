package dev.axion.entity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import dev.axion.definition.Seating;
import dev.axion.definition.Variables;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-168 D6 : les variables prédites basculent à l'instant, s'accordent avec la correction, et se rejouent. */
class PredictedVariablesTest {

    private static final Variables DECLAREES = Variables.of(JsonParser.parseString("""
            { "variables": {
                "gyrophare": { "type": "bool" },
                "bip": { "type": "bool" } } }
            """).getAsJsonObject());

    private static final List<Seating.Binding> COMMANDES = List.of(
            new Seating.Binding(1, "gyrophare", 0, Seating.Mode.TOGGLE, new double[] {0, 1}),
            new Seating.Binding(2, "bip", 1, Seating.Mode.PRESS, new double[] {0, 1}));

    private static final long TOUTES = 0b11;

    @Test
    @DisplayName("la commande bascule la variable au pas même, sans attendre le serveur")
    void basculeALInstant() {
        PredictedVariables p = new PredictedVariables(DECLAREES, new double[] {0, 0}, TOUTES);
        p.step(10, 0, COMMANDES, 0b01);
        assertEquals(1.0, p.values()[0]);
        p.step(11, 0, COMMANDES, 0b10);
        assertArrayEquals(new double[] {1, 1}, p.values(), "press posé à ce pas");
        p.step(12, 0, COMMANDES, 0);
        assertArrayEquals(new double[] {1, 0}, p.values(), "press revenu au pas suivant, comme au serveur");
    }

    @Test
    @DisplayName("la correction s'accorde avec l'état gardé pour l'entrée qu'elle acquitte, ou non")
    void accord() {
        PredictedVariables p = new PredictedVariables(DECLAREES, new double[] {0, 0}, TOUTES);
        p.step(10, 0, COMMANDES, 0b01);
        p.step(11, 0, COMMANDES, 0);
        p.correction(new double[] {1, 0}, TOUTES);
        assertTrue(p.agrees(10));
        assertTrue(p.agrees(99), "sans état gardé, la physique juge seule");
        p.correction(new double[] {0, 0}, TOUTES);
        assertFalse(p.agrees(10), "le serveur a refusé ou défait la bascule");
        p.correction(new double[] {0, 0}, 0b10);
        assertTrue(p.agrees(10), "hors du masque, rien ne se compare");
    }

    @Test
    @DisplayName("le rejeu repart de l'état gardé recalé sur le serveur, et rebascule les entrées postérieures")
    void rejeu() {
        PredictedVariables p = new PredictedVariables(DECLAREES, new double[] {0, 0}, TOUTES);
        p.step(10, 0, COMMANDES, 0b01);
        p.step(11, 0, COMMANDES, 0);
        p.step(12, 0, COMMANDES, 0b01);
        assertEquals(0.0, p.values()[0], "deux fronts : éteint");
        // Le serveur a vu l'entrée 10 et l'a gardée éteinte (une autre écriture l'a emporté).
        p.correction(new double[] {0, 0}, TOUTES);
        p.restore(10);
        assertEquals(0.0, p.values()[0]);
        p.step(11, 0, COMMANDES, 0);
        p.step(12, 0, COMMANDES, 0b01);
        assertEquals(1.0, p.values()[0], "rejouée depuis l'état du serveur, l'entrée 12 rallume");
    }
}
