package dev.axion.definition;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntToDoubleFunction;

/**
 * Les moteurs électriques d'une definition (ADR-182 D3) : leurs noms, dans l'ordre où le natif les range, et la variable
 * qui autorise chacun. Logique pure.
 */
public final class ElectricMotors {

    private ElectricMotors() {}

    /**
     * {@return le nom de chaque moteur électrique, dans son ordre ; vide sans}
     *
     * @param root la definition
     */
    public static List<String> names(JsonObject root) {
        List<String> names = new ArrayList<>();
        for (JsonObject motor : declared(root)) {
            names.add(motor.get("name") instanceof JsonPrimitive name ? name.getAsString() : "");
        }
        return names;
    }

    /**
     * {@return le nom des moteurs qui tirent cette réserve, dans leur ordre}
     *
     * @param root la definition
     * @param store le nom de la réserve
     */
    public static List<String> drawing(JsonObject root, String store) {
        List<String> names = new ArrayList<>();
        for (JsonObject motor : declared(root)) {
            if (motor.get("store") instanceof JsonPrimitive drawn && drawn.getAsString().equals(store)
                    && motor.get("name") instanceof JsonPrimitive name) {
                names.add(name.getAsString());
            }
        }
        return names;
    }

    /**
     * {@return pour chaque moteur électrique, le rang de la variable qui l'autorise, -1 sans variable : il est alors
     * toujours autorisé}
     *
     * @param root la definition
     * @param variables ses variables déclarées
     */
    public static List<Integer> variables(JsonObject root, Variables variables) {
        List<Integer> ranks = new ArrayList<>();
        for (JsonObject motor : declared(root)) {
            ranks.add(motor.get("variable") instanceof JsonPrimitive named ? variables.indexOf(named.getAsString()) : -1);
        }
        return ranks;
    }

    /**
     * {@return les moteurs autorisés, un bit chacun : un moteur sans variable l'est toujours, un autre quand sa variable
     * est vraie}
     *
     * @param variables le rang de la variable de chaque moteur, voir {@link #variables}
     * @param value la valeur d'une variable, par son rang
     */
    public static int enabled(List<Integer> variables, IntToDoubleFunction value) {
        int mask = 0;
        for (int motor = 0; motor < variables.size(); motor++) {
            int variable = variables.get(motor);
            if (variable < 0 || value.applyAsDouble(variable) != 0.0) {
                mask |= 1 << motor;
            }
        }
        return mask;
    }

    private static List<JsonObject> declared(JsonObject root) {
        List<JsonObject> motors = new ArrayList<>();
        if (root.get("powertrain") instanceof JsonObject powertrain
                && powertrain.get("electric_motors") instanceof JsonArray list) {
            for (JsonElement motor : list) {
                if (motor instanceof JsonObject found && motors.size() < VehicleSpec.MAX_MOTORS) {
                    motors.add(found);
                }
            }
        }
        return motors;
    }
}
