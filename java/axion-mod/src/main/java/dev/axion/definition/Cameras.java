package dev.axion.definition;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Les vues d'un véhicule et son tableau de bord (ADR-170 D3, D4, schéma 5) : ce que JSON Schema n'exprime pas, jugé
 * avec la vue ou la jauge entière. Le schéma a déjà jugé les formes, les bornes de chaque champ et les noms.
 *
 * <ul>
 *   <li>Chaque sorte de vue n'admet que ses champs : une vue {@code fixed} nomme son node, une {@code cockpit} part du
 *       siège et peut décaler l'œil, une {@code follow} règle son recul ; un champ d'une autre sorte se refuse en le
 *       nommant, plutôt que d'être ignoré.</li>
 *   <li>L'œil d'une vue {@code cockpit} reste à 4 m au plus du siège, sur chaque axe.</li>
 *   <li>Une jauge {@code dial} ou {@code bar} a des bornes, la plus basse sous la plus haute.</li>
 *   <li>Un widget ne se nomme qu'une fois.</li>
 * </ul>
 *
 * <p>Logique pure, sans Forge.
 */
final class Cameras {

    /** ADR-170 D3 : l'œil d'une vue cockpit, à tant de mètres au plus du siège sur chaque axe. */
    static final double MAX_EYE = 4.0;

    private static final Set<String> FOLLOW_ONLY = Set.of("distance", "height", "smoothing_ms", "recenter_ms");

    private Cameras() {}

    /**
     * Juge les vues et le tableau de bord d'une definition de schéma 5.
     *
     * @param root la definition
     * @param fault reçoit le chemin et le message de chaque faute
     */
    static void check(JsonObject root, BiConsumer<String, String> fault) {
        if (root.get("cameras") instanceof JsonArray cameras) {
            for (int index = 0; index < cameras.size(); index++) {
                if (cameras.get(index) instanceof JsonObject camera) {
                    checkCamera(camera, "$.cameras[" + index + "]", fault);
                }
            }
        }
        if (root.get("dashboard") instanceof JsonObject dashboard) {
            checkDashboard(dashboard, fault);
        }
    }

    private static void checkCamera(JsonObject camera, String path, BiConsumer<String, String> fault) {
        String kind = camera.get("kind").getAsString();
        String name = camera.get("name").getAsString();
        if (kind.equals("fixed") && !camera.has("node")) {
            fault.accept(path + ".node", "la vue fixe « " + name + " » nomme le node qui la porte");
        }
        if (!kind.equals("fixed") && camera.has("node")) {
            fault.accept(path + ".node", "seule une vue fixed se pose sur un node ; « " + name + " » est " + kind);
        }
        if (!kind.equals("cockpit") && camera.has("eye")) {
            fault.accept(path + ".eye", "seule une vue cockpit décale l'œil ; « " + name + " » est " + kind);
        }
        if (kind.equals("follow") && camera.has("look")) {
            fault.accept(path + ".look", "une vue follow tourne autour du véhicule, sans bornes de regard ; « " + name
                    + " »");
        }
        if (!kind.equals("follow")) {
            for (String field : FOLLOW_ONLY) {
                if (camera.has(field)) {
                    fault.accept(path + "." + field, "seule une vue follow a « " + field + " » ; « " + name + " » est "
                            + kind);
                }
            }
        }
        if (camera.get("eye") instanceof JsonArray eye) {
            for (int axis = 0; axis < eye.size(); axis++) {
                double value = eye.get(axis).getAsDouble();
                if (Math.abs(value) > MAX_EYE) {
                    fault.accept(path + ".eye[" + axis + "]", "l'œil s'écarte du siège de " + value
                            + " m, au-delà de ±" + MAX_EYE + " m");
                }
            }
        }
    }

    private static void checkDashboard(JsonObject dashboard, BiConsumer<String, String> fault) {
        if (dashboard.get("gauges") instanceof JsonArray gauges) {
            for (int index = 0; index < gauges.size(); index++) {
                if (gauges.get(index) instanceof JsonObject gauge) {
                    checkGauge(gauge, "$.dashboard.gauges[" + index + "]", fault);
                }
            }
        }
        if (dashboard.get("widgets") instanceof JsonArray widgets) {
            Set<String> seen = new HashSet<>();
            for (int index = 0; index < widgets.size(); index++) {
                String widget = widgets.get(index).getAsString();
                if (!seen.add(widget)) {
                    fault.accept("$.dashboard.widgets[" + index + "]", "widget « " + widget + " » nommé deux fois");
                }
            }
        }
    }

    private static void checkGauge(JsonObject gauge, String path, BiConsumer<String, String> fault) {
        String kind = gauge.get("kind").getAsString();
        if (!kind.equals("dial") && !kind.equals("bar")) {
            return;
        }
        JsonElement min = gauge.get("min");
        JsonElement max = gauge.get("max");
        String name = gauge.get("name").getAsString();
        if (min == null || max == null) {
            fault.accept(path, "la jauge " + kind + " « " + name + " » a ses bornes min et max");
        } else if (min.getAsDouble() >= max.getAsDouble()) {
            fault.accept(path + ".max", "la jauge « " + name + " » va de " + min.getAsDouble() + " à "
                    + max.getAsDouble() + " : max doit dépasser min");
        }
    }
}
