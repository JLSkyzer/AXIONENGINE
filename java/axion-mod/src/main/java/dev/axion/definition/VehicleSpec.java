package dev.axion.definition;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Le véhicule d'une definition (PARTIE 12, ADR-159) : ses règles de forme, ses valeurs par défaut et
 * sa section de {@code DEFN}, la disposition d'{@code ax_model::dm::vehicle}.
 *
 * <p>Les valeurs par défaut sont celles des exemples de 12.3 et 12.6. Un rayon de roue n'en a pas : il
 * ne se devine pas.
 */
final class VehicleSpec {

    /** Taille de l'en-tête de la section, en octets. */
    static final int HEADER_BYTES = 108;

    /** Taille d'une roue, en octets. */
    static final int WHEEL_BYTES = 88;

    /** Taille d'une surface portante, en octets. */
    static final int SURFACE_BYTES = 16;

    private static final int NONE_U16 = 0xFFFF;
    private static final int WHEEL_STEERED = 1;
    private static final int WHEEL_DRIVEN = 2;
    private static final int WHEEL_HANDBRAKE = 4;
    private static final int VEHICLE_POWERTRAIN = 1;
    private static final int VEHICLE_ABS = 2;

    private static final List<String> TRANSMISSIONS = List.of("manual", "automatic", "direct");
    private static final List<String> DIFFERENTIALS = List.of("open", "locked", "limited_slip");

    private VehicleSpec() {}

    /** {@return vrai si la definition déclare au moins une roue} */
    static boolean declared(JsonObject root) {
        return root.get("wheels") instanceof JsonArray wheels && !wheels.isEmpty();
    }

    /**
     * Juge un véhicule dont la structure est déjà conforme au schéma.
     *
     * @param root la definition
     * @param fault reçoit le chemin JSON et le message de chaque faute
     */
    static void check(JsonObject root, BiConsumer<String, String> fault) {
        if (!declared(root)) {
            return;
        }
        if (!(root.get("kind") instanceof JsonPrimitive kind) || !"vehicle".equals(kind.getAsString())) {
            fault.accept("$.wheels", "des roues n'existent que sur une definition de kind « vehicle »");
        }
        JsonArray wheels = root.getAsJsonArray("wheels");
        Map<String, List<Integer>> axles = new LinkedHashMap<>();
        for (int i = 0; i < wheels.size(); i++) {
            checkWheel(wheels.get(i).getAsJsonObject(), i, axles, fault);
        }
        axles.forEach((axle, members) -> {
            if (members.size() != 2) {
                fault.accept("$.wheels[" + members.get(0) + "].axle", "l'essieu « " + axle + " » compte "
                        + members.size() + " roue(s), il en faut 2 (R-1061)");
            } else if (antiRoll(wheels, members.get(0)) != antiRoll(wheels, members.get(1))) {
                fault.accept("$.wheels[" + members.get(1) + "].suspension.anti_roll",
                        "les deux roues de l'essieu « " + axle + " » déclarent le même anti-roulis");
            }
        });
        checkAgreement(wheels, "steering", "steered_wheels", object(root, "steering"), "$.steering", fault);
        checkAgreement(wheels, "powered", "drive_wheels", object(root, "powertrain"), "$.powertrain", fault);
        if (object(root, "powertrain") != null) {
            checkPowertrain(object(root, "powertrain"), wheels, fault);
        }
        checkSteering(object(root, "steering"), fault);
        checkBrakes(object(root, "brakes"), fault);
        checkAero(object(root, "aero"), fault);
    }

    private static float antiRoll(JsonArray wheels, int index) {
        JsonObject suspension = object(wheels.get(index).getAsJsonObject(), "suspension");
        return number(suspension, "anti_roll", 0f);
    }

    private static void checkWheel(
            JsonObject wheel, int index, Map<String, List<Integer>> axles, BiConsumer<String, String> fault) {
        String at = "$.wheels[" + index + "]";
        if (wheel.get("radius") == null) {
            fault.accept(at, "une roue déclare son rayon");
        } else {
            atLeast(wheel, "radius", at, false, fault);
        }
        atLeast(wheel, "width", at, true, fault);
        atLeast(wheel, "mass", at, false, fault);
        JsonObject suspension = object(wheel, "suspension");
        String sat = at + ".suspension";
        atLeast(suspension, "rest_length", sat, true, fault);
        atLeast(suspension, "max_travel", sat, false, fault);
        atLeast(suspension, "stiffness", sat, true, fault);
        atLeast(suspension, "damping_compression", sat, true, fault);
        atLeast(suspension, "damping_rebound", sat, true, fault);
        atLeast(suspension, "max_force", sat, false, fault);
        atLeast(suspension, "anti_roll", sat, true, fault);
        String axle = string(wheel, "axle");
        if (axle != null) {
            axles.computeIfAbsent(axle, key -> new ArrayList<>()).add(index);
        } else if (number(suspension, "anti_roll", 0f) != 0f) {
            fault.accept(sat + ".anti_roll", "l'anti-roulis agit entre les deux roues d'un essieu déclaré (R-1061)");
        }
    }

    private static void checkPowertrain(JsonObject powertrain, JsonArray wheels, BiConsumer<String, String> fault) {
        JsonObject engine = object(powertrain, "engine");
        if (engine == null || !(engine.get("torque_curve") instanceof JsonArray curve) || curve.size() < 2) {
            fault.accept("$.powertrain.engine", "un groupe motopropulseur déclare une torque_curve de 2 points au moins");
        } else {
            double previous = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < curve.size(); i++) {
                JsonArray point = curve.get(i).getAsJsonArray();
                if (point.get(0).getAsDouble() <= previous) {
                    fault.accept("$.powertrain.engine.torque_curve[" + i + "]", "les régimes sont strictement croissants");
                }
                if (point.get(1).getAsDouble() < 0) {
                    fault.accept("$.powertrain.engine.torque_curve[" + i + "]", "couple négatif");
                }
                previous = point.get(0).getAsDouble();
            }
        }
        String eat = "$.powertrain.engine";
        atLeast(engine, "idle_rpm", eat, true, fault);
        atLeast(engine, "max_rpm", eat, false, fault);
        atLeast(engine, "inertia", eat, false, fault);
        atLeast(engine, "braking_torque", eat, true, fault);
        if (number(engine, "max_rpm", 7000f) <= number(engine, "idle_rpm", 800f)) {
            fault.accept(eat + ".max_rpm", "doit dépasser idle_rpm");
        }

        JsonObject transmission = object(powertrain, "transmission");
        String tat = "$.powertrain.transmission";
        if (transmission != null) {
            String type = string(transmission, "type");
            if (type != null && !TRANSMISSIONS.contains(type)) {
                fault.accept(tat + ".type", "« " + type + " » inconnu ; admis : " + String.join(", ", TRANSMISSIONS));
            }
            if (!"direct".equals(type)) {
                checkGears(transmission, tat, fault);
            }
            atLeast(transmission, "final_drive", tat, false, fault);
            atLeast(transmission, "shift_time", tat, true, fault);
            atLeast(transmission, "auto_shift_up_rpm", tat, false, fault);
            atLeast(transmission, "auto_shift_down_rpm", tat, false, fault);
            fraction(transmission, "efficiency", tat, false, fault);
        }
        JsonObject differential = object(powertrain, "differential");
        fraction(differential, "front_rear_split", "$.powertrain.differential", true, fault);
        fraction(differential, "lock", "$.powertrain.differential", true, fault);

        boolean driven = powertrain.get("drive_wheels") instanceof JsonArray list && !list.isEmpty();
        for (JsonElement wheel : wheels) {
            driven |= bool(wheel.getAsJsonObject(), "powered");
        }
        if (!driven) {
            fault.accept("$.powertrain", "un groupe motopropulseur entraîne au moins une roue");
        }
    }

    private static void checkGears(JsonObject transmission, String at, BiConsumer<String, String> fault) {
        if (!(transmission.get("gears") instanceof JsonArray gears)) {
            fault.accept(at, "une boîte manuelle ou automatique déclare ses rapports (gears)");
            return;
        }
        int neutral = 0;
        int forward = 0;
        for (JsonElement gear : gears) {
            if (gear.getAsDouble() == 0) {
                neutral++;
            } else if (gear.getAsDouble() > 0) {
                forward++;
            }
        }
        if (neutral != 1) {
            fault.accept(at + ".gears", "un seul rapport nul, le point mort ; " + neutral + " ici");
        }
        if (forward == 0) {
            fault.accept(at + ".gears", "au moins un rapport de marche avant");
        }
    }

    private static void checkSteering(JsonObject steering, BiConsumer<String, String> fault) {
        if (steering == null) {
            return;
        }
        String at = "$.steering";
        atLeast(steering, "max_angle", at, false, fault);
        if (number(steering, "max_angle", 0.61f) >= (float) (Math.PI / 2)) {
            fault.accept(at + ".max_angle", "en radians, sous π/2");
        }
        atLeast(steering, "speed", at, false, fault);
        atLeast(steering, "return_speed", at, false, fault);
        fraction(steering, "speed_sensitivity", at, true, fault);
        fraction(steering, "ackermann", at, true, fault);
    }

    private static void checkBrakes(JsonObject brakes, BiConsumer<String, String> fault) {
        if (brakes == null) {
            return;
        }
        String at = "$.brakes";
        atLeast(brakes, "max_torque_front", at, true, fault);
        atLeast(brakes, "max_torque_rear", at, true, fault);
        atLeast(brakes, "handbrake_torque", at, true, fault);
        atLeast(object(brakes, "abs"), "slip_threshold", at + ".abs", false, fault);
    }

    private static void checkAero(JsonObject aero, BiConsumer<String, String> fault) {
        if (aero == null) {
            return;
        }
        atLeast(aero, "drag_coefficient", "$.aero", true, fault);
        atLeast(aero, "frontal_area", "$.aero", true, fault);
        if (aero.get("surfaces") instanceof JsonArray surfaces) {
            for (int i = 0; i < surfaces.size(); i++) {
                JsonObject surface = surfaces.get(i).getAsJsonObject();
                String at = "$.aero.surfaces[" + i + "]";
                if (surface.get("node") == null) {
                    fault.accept(at, "une surface portante est portée par un node");
                }
                if (surface.get("area") == null) {
                    fault.accept(at, "une surface portante déclare son aire");
                } else {
                    atLeast(surface, "area", at, false, fault);
                }
            }
        }
    }

    /** ADR-159 D7 : le drapeau d'une roue et la liste de son bloc ne se contredisent pas. */
    private static void checkAgreement(
            JsonArray wheels, String flag, String listName, JsonObject block, String at,
            BiConsumer<String, String> fault) {
        if (block == null || !(block.get(listName) instanceof JsonArray list)) {
            return;
        }
        Set<String> listed = names(list);
        for (int i = 0; i < wheels.size(); i++) {
            JsonObject wheel = wheels.get(i).getAsJsonObject();
            if (wheel.get(flag) != null
                    && wheel.get(flag).getAsBoolean() != listed.contains(string(wheel, "node"))) {
                fault.accept("$.wheels[" + i + "]." + flag, "contredit " + at + "." + listName);
            }
        }
    }

    /** {@return la taille de la section d'une definition qui déclare un véhicule} */
    static int size(JsonObject root) {
        JsonObject powertrain = object(root, "powertrain");
        JsonObject engine = object(powertrain, "engine");
        JsonObject transmission = object(powertrain, "transmission");
        JsonObject aero = object(root, "aero");
        return HEADER_BYTES
                + root.getAsJsonArray("wheels").size() * WHEEL_BYTES
                + count(engine, "torque_curve") * 8
                + count(transmission, "gears") * 4
                + count(aero, "surfaces") * SURFACE_BYTES;
    }

    /**
     * Écrit la section d'un véhicule jugé.
     *
     * @param root la definition
     * @param tires les pneus qu'elle nomme
     * @param references le rang de chaque référence à l'asset, par chemin JSON
     * @param out tampon little-endian, à la position de la section
     */
    static void write(JsonObject root, TireProfiles tires, Map<String, Integer> references, ByteBuffer out) {
        JsonArray wheels = root.getAsJsonArray("wheels");
        JsonObject powertrain = object(root, "powertrain");
        JsonObject engine = object(powertrain, "engine");
        JsonObject transmission = object(powertrain, "transmission");
        JsonObject differential = object(powertrain, "differential");
        JsonObject steering = object(root, "steering");
        JsonObject brakes = object(root, "brakes");
        JsonObject abs = object(brakes, "abs");
        JsonObject aero = object(root, "aero");

        out.putShort((short) wheels.size());
        out.putShort((short) count(engine, "torque_curve"));
        out.putShort((short) count(transmission, "gears"));
        out.putShort((short) count(aero, "surfaces"));
        out.putShort((short) ((powertrain != null ? VEHICLE_POWERTRAIN : 0) | (bool(abs, "enabled") ? VEHICLE_ABS : 0)));
        String type = transmission == null ? "direct" : stringOr(transmission, "type", "manual");
        out.put((byte) TRANSMISSIONS.indexOf(type));
        out.put((byte) DIFFERENTIALS.indexOf(stringOr(differential, "type", "open")));

        put(out, number(engine, "idle_rpm", 800f), number(engine, "max_rpm", 7000f),
                number(engine, "inertia", 0.3f), number(engine, "braking_torque", 25f));
        boolean direct = transmission == null;
        put(out, number(transmission, "final_drive", direct ? 1f : 3.7f), number(transmission, "shift_time", direct ? 0f : 0.25f),
                number(transmission, "auto_shift_up_rpm", 6200f), number(transmission, "auto_shift_down_rpm", 2200f),
                number(transmission, "efficiency", direct ? 1f : 0.92f));
        put(out, number(differential, "front_rear_split", 0f), number(differential, "lock", 0f));
        put(out, number(steering, "max_angle", 0.61f), number(steering, "speed", 3f), number(steering, "return_speed", 4f),
                number(steering, "speed_sensitivity", 0.6f), number(steering, "ackermann", 1f));
        put(out, number(brakes, "max_torque_front", 3000f), number(brakes, "max_torque_rear", 1800f),
                number(brakes, "handbrake_torque", 4000f), number(abs, "slip_threshold", 0.15f));
        put(out, number(aero, "drag_coefficient", 0f), number(aero, "frontal_area", 0f),
                number(aero, "downforce_front", 0f), number(aero, "downforce_rear", 0f));

        Set<String> steered = listOr(steering, "steered_wheels");
        Set<String> driven = listOr(powertrain, "drive_wheels");
        Set<String> handbrake = listOr(brakes, "handbrake_wheels");
        Map<String, Integer> axles = new HashMap<>();
        for (int i = 0; i < wheels.size(); i++) {
            JsonObject wheel = wheels.get(i).getAsJsonObject();
            String node = string(wheel, "node");
            int flags = (steered != null ? steered.contains(node) : bool(wheel, "steering")) ? WHEEL_STEERED : 0;
            flags |= (driven != null ? driven.contains(node) : bool(wheel, "powered")) ? WHEEL_DRIVEN : 0;
            // ADR-159 : sans liste, le frein à main serre toutes les roues.
            flags |= handbrake == null || handbrake.contains(node) ? WHEEL_HANDBRAKE : 0;
            String axle = string(wheel, "axle");
            int axleRank = axle == null ? NONE_U16 : axles.computeIfAbsent(axle, key -> axles.size());
            out.putInt(references.get("$.wheels[" + i + "].node"));
            out.putShort((short) flags);
            out.putShort((short) axleRank);
            JsonObject suspension = object(wheel, "suspension");
            put(out, number(wheel, "radius", 0f), number(wheel, "width", 0.24f), number(wheel, "mass", 20f),
                    number(suspension, "rest_length", 0.35f), number(suspension, "max_travel", 0.25f),
                    number(suspension, "stiffness", 30f), number(suspension, "damping_compression", 2.3f),
                    number(suspension, "damping_rebound", 2.8f), number(suspension, "max_force", 60_000f),
                    number(suspension, "anti_roll", 0f));
            TireProfiles.Tire tire = tires.resolve(string(wheel, "tire"));
            put(out, tire.muLong(), tire.muLat());
            put(out, tire.longitudinal());
            put(out, tire.lateral());
        }
        if (engine != null && engine.get("torque_curve") instanceof JsonArray curve) {
            for (JsonElement point : curve) {
                put(out, point.getAsJsonArray().get(0).getAsFloat(), point.getAsJsonArray().get(1).getAsFloat());
            }
        }
        if (transmission != null && transmission.get("gears") instanceof JsonArray gears) {
            for (JsonElement gear : gears) {
                out.putFloat(gear.getAsFloat());
            }
        }
        if (aero != null && aero.get("surfaces") instanceof JsonArray surfaces) {
            for (int i = 0; i < surfaces.size(); i++) {
                JsonObject surface = surfaces.get(i).getAsJsonObject();
                out.putInt(references.get("$.aero.surfaces[" + i + "].node"));
                put(out, number(surface, "area", 0f), number(surface, "cl", 0f), number(surface, "cd", 0f));
            }
        }
    }

    private static void put(ByteBuffer out, float... values) {
        for (float value : values) {
            out.putFloat(value);
        }
    }

    private static void put(ByteBuffer out, TireProfiles.Curve curve) {
        put(out, curve.peakSlip(), curve.peakValue(), curve.tailSlip(), curve.tailValue());
    }

    private static void atLeast(
            JsonObject object, String key, String at, boolean zeroAllowed, BiConsumer<String, String> fault) {
        if (object == null || object.get(key) == null) {
            return;
        }
        float value = object.get(key).getAsFloat();
        if (!Float.isFinite(value) || (zeroAllowed ? value < 0f : value <= 0f)) {
            fault.accept(at + "." + key, zeroAllowed ? "doit être positif ou nul" : "doit être strictement positif");
        }
    }

    private static void fraction(
            JsonObject object, String key, String at, boolean zeroAllowed, BiConsumer<String, String> fault) {
        if (object == null || object.get(key) == null) {
            return;
        }
        float value = object.get(key).getAsFloat();
        if (!(value <= 1f) || (zeroAllowed ? value < 0f : value <= 0f)) {
            fault.accept(at + "." + key, zeroAllowed ? "attendu dans [0, 1]" : "attendu dans ]0, 1]");
        }
    }

    private static JsonObject object(JsonObject parent, String key) {
        return parent != null && parent.get(key) instanceof JsonObject child ? child : null;
    }

    private static int count(JsonObject parent, String key) {
        return parent != null && parent.get(key) instanceof JsonArray list ? list.size() : 0;
    }

    private static float number(JsonObject parent, String key, float fallback) {
        return parent != null && parent.get(key) != null ? parent.get(key).getAsFloat() : fallback;
    }

    private static boolean bool(JsonObject parent, String key) {
        return parent != null && parent.get(key) != null && parent.get(key).getAsBoolean();
    }

    private static String string(JsonObject parent, String key) {
        return parent != null && parent.get(key) instanceof JsonPrimitive primitive && primitive.isString()
                ? primitive.getAsString()
                : null;
    }

    private static String stringOr(JsonObject parent, String key, String fallback) {
        String value = string(parent, key);
        return value == null ? fallback : value;
    }

    private static Set<String> listOr(JsonObject parent, String key) {
        return parent != null && parent.get(key) instanceof JsonArray list ? names(list) : null;
    }

    private static Set<String> names(JsonArray list) {
        Set<String> names = new HashSet<>();
        for (JsonElement name : list) {
            names.add(name.getAsString());
        }
        return names;
    }
}
