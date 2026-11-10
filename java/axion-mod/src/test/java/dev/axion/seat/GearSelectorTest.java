package dev.axion.seat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-159 D6 : les rangs qu'une boîte accepte de son conducteur, et ce que ses deux touches en choisissent. */
class GearSelectorTest {

    private static JsonObject definition(String powertrain) {
        return JsonParser.parseString("{\"powertrain\": " + powertrain + "}").getAsJsonObject();
    }

    @Test
    @DisplayName("ADR-159 D6 : une boîte manuelle accepte ses rangs, comptés depuis son rapport nul")
    void bornes() {
        assertEquals(new GearRange(-1, 5),
                GearRange.of(definition("{\"transmission\": {\"gears\": [-3.2, 0, 3.5, 2.1, 1.4, 1.0, 0.8]}}")));
        assertEquals(new GearRange(-2, 1),
                GearRange.of(definition("{\"transmission\": {\"type\": \"manual\", \"gears\": [-3, -2, 0, 3]}}")));
        assertEquals(GearRange.NONE,
                GearRange.of(definition("{\"transmission\": {\"type\": \"automatic\", \"gears\": [-3, 0, 3]}}")),
                "une automatique choisit seule");
        assertEquals(GearRange.NONE, GearRange.of(definition("{\"engine\": {}}")), "sans boîte : directe");
        assertEquals(GearRange.NONE, GearRange.of(new JsonObject()), "sans groupe motopropulseur");
    }

    @Test
    @DisplayName("ADR-159 D6 : les touches montent et descendent dans les bornes, un autre véhicule repart du point mort")
    void touches() {
        GearSelector boite = new GearSelector();
        GearRange bornes = new GearRange(-1, 3);
        assertEquals(0, boite.select(7, 0, 0, bornes), "au point mort");
        assertEquals(1, boite.select(7, 1, 0, bornes));
        assertEquals(3, boite.select(7, 5, 0, bornes), "borné en haut");
        assertEquals(-1, boite.select(7, 0, 9, bornes), "borné en bas");
        assertEquals(1, boite.select(8, 1, 0, bornes), "un autre véhicule repart du point mort");
        boite.reset();
        assertEquals(0, boite.select(8, 0, 0, bornes), "descendu, il oublie son rapport");
        assertEquals(0, boite.select(8, 2, 0, GearRange.NONE));
    }
}
