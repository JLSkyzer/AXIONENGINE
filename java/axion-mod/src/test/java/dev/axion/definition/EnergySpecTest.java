package dev.axion.definition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-176 D3 : ce que la definition déclare de réservoir et de batterie, et la masse de ce que le réservoir porte. */
class EnergySpecTest {

    private static JsonObject root(String energy) {
        return JsonParser.parseString("{\"schema\": 7" + (energy == null ? "" : ", \"energy\": " + energy) + "}")
                .getAsJsonObject();
    }

    @Test
    @DisplayName("ADR-176 D3 : sans energy, rien ; avec, les capacités et la masse du litre")
    void lecture() {
        assertEquals(EnergySpec.NONE, EnergySpec.of(root(null)));
        assertFalse(EnergySpec.NONE.declared());
        EnergySpec plein = EnergySpec.of(root("{\"fuel\": {\"capacity\": 40, \"unit_mass\": 0.75},"
                + " \"battery\": {\"capacity\": 1}}"));
        assertEquals(new EnergySpec(40f, 0.75f, 1f), plein);
        assertTrue(plein.declared());
        assertTrue(EnergySpec.of(root("{\"battery\": {\"capacity\": 2}}")).declared());
    }

    @Test
    @DisplayName("ADR-176 D3 : la masse du carburant suit des crans de 1/255 de la capacité, jamais le f32 brut")
    void masseParCrans() {
        EnergySpec spec = new EnergySpec(40f, 0.75f, 1f);
        assertEquals(30.0, spec.fuelMass(40.0), 1e-9, "plein : 40 l × 0,75");
        assertEquals(0.0, spec.fuelMass(0.0));
        assertEquals(0.0, spec.fuelMass(-3.0), "jamais négatif");
        assertEquals(30.0, spec.fuelMass(400.0), "borné à la capacité");
        // Deux quantités dans le même cran donnent la même masse : le natif, qui la reçoit en réveillant, n'est dérangé
        // qu'au franchissement d'un cran.
        double cran = 40.0 / EnergySpec.MASS_STEPS;
        assertEquals(spec.fuelMass(20.0), spec.fuelMass(20.0 + cran * 0.2));
        assertTrue(spec.fuelMass(20.0 + cran * 1.2) > spec.fuelMass(20.0));
        assertEquals(0.0, new EnergySpec(40f, 0f, 1f).fuelMass(40.0), "sans masse par litre, la masse ne suit pas");
        assertEquals(0.0, new EnergySpec(0f, 0.75f, 1f).fuelMass(40.0), "sans réservoir non plus");
    }
}
