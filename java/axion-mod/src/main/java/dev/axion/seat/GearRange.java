package dev.axion.seat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Les rangs de rapport qu'une boîte {@code manual} accepte de son conducteur (12.5, ADR-159 D6), comptés depuis le
 * rapport nul de {@code gears} : 0 est le point mort, négatif la marche arrière. Une boîte {@code automatic} ou
 * {@code direct} choisit ses rapports elle-même : elle n'en accepte aucun. Logique pure, sans Forge.
 *
 * @param min le rang le plus bas, négatif ou nul
 * @param max le rang le plus haut, positif ou nul
 */
public record GearRange(int min, int max) {

    /** Aucun rapport à choisir : le point mort seul. */
    public static final GearRange NONE = new GearRange(0, 0);

    /**
     * {@return les rangs que la boîte d'une definition accepte de son conducteur}
     *
     * @param definition la racine JSON d'une definition jugée
     */
    public static GearRange of(JsonObject definition) {
        if (!(definition.get("powertrain") instanceof JsonObject powertrain)
                || !(powertrain.get("transmission") instanceof JsonObject transmission)
                || !(transmission.get("gears") instanceof JsonArray gears)) {
            return NONE;
        }
        // Le type par défaut d'une transmission déclarée est « manual » (VehicleSpec).
        JsonElement type = transmission.get("type");
        if (type != null && !"manual".equals(type.getAsString())) {
            return NONE;
        }
        for (int i = 0; i < gears.size(); i++) {
            if (gears.get(i).getAsDouble() == 0) {
                return new GearRange(-i, gears.size() - 1 - i);
            }
        }
        return NONE;
    }

    /**
     * {@return un rang ramené dans ces bornes}
     *
     * @param gear le rang
     */
    public int clamp(int gear) {
        return Math.max(min, Math.min(max, gear));
    }
}
