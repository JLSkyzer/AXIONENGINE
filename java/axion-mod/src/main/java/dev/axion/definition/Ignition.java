package dev.axion.definition;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * Le contact du moteur d'une definition (ADR-176 D1) : la variable booléenne que son démarreur lit. Logique pure.
 */
public final class Ignition {

    private Ignition() {}

    /**
     * {@return le rang de la variable que le démarreur de la definition lit comme contact, -1 si elle n'a pas de
     * démarreur ou si la variable n'existe pas}
     *
     * @param root la definition
     * @param variables ses variables déclarées
     */
    public static int variable(JsonObject root, Variables variables) {
        if (root.get("powertrain") instanceof JsonObject powertrain
                && powertrain.get("engine") instanceof JsonObject engine
                && engine.get("starter") instanceof JsonObject starter
                && starter.get("variable") instanceof JsonPrimitive named) {
            return variables.indexOf(named.getAsString());
        }
        return -1;
    }
}
