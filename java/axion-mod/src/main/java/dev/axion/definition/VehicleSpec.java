package dev.axion.definition;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.axion.api.assembly.Parameters;
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
    static final int HEADER_BYTES = 148;

    /** Capacité maximale d'un réservoir, en litres (ADR-176 D3). */
    static final float MAX_FUEL_CAPACITY = 100_000f;

    /** Masse maximale d'un litre de carburant, en kg (ADR-176 D3). */
    static final float MAX_UNIT_MASS = 100f;

    /** Consommation maximale, en litres par heure (ADR-176 D3). */
    static final float MAX_CONSUMPTION = 1_000_000f;

    /** Capacité maximale d'une batterie, en unités de charge (ADR-176 D3). */
    static final float MAX_BATTERY = 1_000_000f;

    /** Au plus ce nombre de points de consommation (ADR-176 D3) : {@code EnergyDesc::MAX_POINTS} du natif. */
    static final int MAX_CONSUMPTION_POINTS = 64;

    /** Durée minimale du démarreur, en secondes (ADR-176 D1). */
    static final float MIN_STARTER_TIME = 0.05f;

    /** Durée maximale du démarreur, en secondes (ADR-176 D1). */
    static final float MAX_STARTER_TIME = 10f;

    /** Taille d'une roue, en octets. */
    static final int WHEEL_BYTES = 100;

    /** Taille d'une surface portante, en octets. */
    static final int SURFACE_BYTES = 16;

    private static final int NONE_U16 = 0xFFFF;
    private static final int WHEEL_STEERED = 1;
    private static final int WHEEL_DRIVEN = 2;
    private static final int WHEEL_HANDBRAKE = 4;
    private static final int VEHICLE_POWERTRAIN = 1;
    private static final int VEHICLE_ABS = 2;
    private static final int VEHICLE_TRACTION_CONTROL = 4;
    private static final int VEHICLE_STABILITY_CONTROL = 8;

    /**
     * Seuil de l'antipatinage par défaut : le glissement d'une roue motrice, rapporté à la vitesse du sol,
     * au-delà duquel le couple du moteur diminue. Choix d'ADR-159 (tranche 3), faute de valeur au CDC.
     */
    static final float TRACTION_SLIP_THRESHOLD = 0.2f;

    /**
     * Tolérance du contrôle de stabilité par défaut, en rad/s : l'écart au lacet visé au-delà duquel une roue
     * est freinée. Choix d'ADR-159 (tranche 3), faute de valeur au CDC.
     */
    static final float STABILITY_YAW_TOLERANCE = 0.15f;

    /*
     * Les défauts des grandeurs réglables (ADR-167 D1) : l'encodage de la section et la table des paramètres les lisent
     * tous deux, pour que la base que Java compose soit exactement celle que le natif reçoit.
     */
    private static final float IDLE_RPM = 800f;
    private static final float MAX_RPM = 7000f;
    private static final float FINAL_DRIVE = 3.7f;
    private static final float FINAL_DRIVE_DIRECT = 1f;
    private static final float SHIFT_TIME = 0.25f;
    private static final float SHIFT_TIME_DIRECT = 0f;
    private static final float MAX_ANGLE = 0.61f;
    private static final float BRAKE_FRONT = 3000f;
    private static final float BRAKE_REAR = 1800f;
    private static final float HANDBRAKE = 4000f;
    private static final float DRAG = 0f;
    private static final float DOWNFORCE = 0f;

    /** Le braquage maximal admis, exclu : la règle de forme de {@code steering.max_angle}. */
    private static final float MAX_ANGLE_LIMIT = (float) (Math.PI / 2);

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
            // ADR-182 : une réserve vit au natif, dans la section d'un véhicule.
            if (object(root, "energy") != null && object(root, "energy").get("stores") != null) {
                fault.accept("$.energy.stores", "les réserves vivent sur un véhicule : la definition déclare des roues"
                        + " (wheels)");
            }
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
        checkEnergy(root, fault);
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

    /** La consommation d'un moteur : charge de 0 à 1, strictement croissante, unités par heure. */
    private static void checkConsumption(JsonObject owner, String at, BiConsumer<String, String> fault) {
        if (!(owner.get("consumption") instanceof JsonArray curve) || curve.isEmpty()) {
            fault.accept(at + ".consumption", "un réservoir déclare sa consommation : un point au moins");
        } else if (curve.size() > MAX_CONSUMPTION_POINTS) {
            fault.accept(at + ".consumption", "au plus " + MAX_CONSUMPTION_POINTS + " points");
        } else {
            double previous = -1;
            for (int i = 0; i < curve.size(); i++) {
                JsonArray point = curve.get(i).getAsJsonArray();
                double load = point.get(0).getAsDouble();
                if (load < 0 || load > 1 || load <= previous) {
                    fault.accept(at + ".consumption[" + i + "]", "les charges sont dans [0, 1], strictement croissantes");
                }
                if (point.get(1).getAsDouble() < 0 || point.get(1).getAsDouble() > MAX_CONSUMPTION) {
                    fault.accept(at + ".consumption[" + i + "]", "la consommation est de 0 à " + MAX_CONSUMPTION
                            + " litres (ou unités) par heure");
                }
                previous = load;
            }
        }
    }

    /** ADR-182 D1 : la capacité, la masse et le nom de chaque réserve, et leur nombre, batterie comprise. */
    private static void checkStores(JsonObject root, JsonObject energy, BiConsumer<String, String> fault) {
        JsonArray stores = energy.getAsJsonArray("stores");
        boolean dryMass = object(root, "physics") != null && object(root, "physics").get("mass") != null;
        boolean battery = energy.get("battery") != null;
        if (battery && stores.size() >= EnergySpec.MAX_STORES) {
            fault.accept("$.energy.stores", "au plus " + EnergySpec.MAX_STORES + " réserves, la batterie comprise");
        }
        for (int i = 0; i < stores.size(); i++) {
            JsonObject store = stores.get(i).getAsJsonObject();
            String at = "$.energy.stores[" + i + "]";
            if (!(number(store, "capacity", 0f) > 0f) || number(store, "capacity", 0f) > MAX_FUEL_CAPACITY) {
                fault.accept(at + ".capacity", "la capacité d'une réserve est de 0 (exclu) à " + MAX_FUEL_CAPACITY);
            }
            if (number(store, "unit_mass", 0f) < 0f || number(store, "unit_mass", 0f) > MAX_UNIT_MASS) {
                fault.accept(at + ".unit_mass", "la masse d'une unité est de 0 à " + MAX_UNIT_MASS + " kg");
            }
            if (number(store, "unit_mass", 0f) > 0f && !dryMass) {
                fault.accept(at + ".unit_mass", "la masse suit la réserve : la definition déclare physics.mass, la masse"
                        + " à vide");
            }
            if (battery && EnergySpec.BATTERY.equals(string(store, "name"))) {
                fault.accept(at + ".name", "« battery » est la batterie qu'energy.battery déclare");
            }
        }
    }

    /**
     * ADR-182 D1 : la réserve que brûle le moteur ({@code engine.fuel}) et celle du démarreur ({@code starter.store}), qui
     * n'existent qu'avec {@code stores}, distinctes.
     */
    private static void checkEngineStores(JsonObject engine, boolean stores, JsonObject energy,
            BiConsumer<String, String> fault) {
        JsonObject fuel = object(engine, "fuel");
        JsonObject starter = object(engine, "starter");
        String starterStore = string(starter, "store");
        if (fuel != null) {
            if (!stores) {
                fault.accept("$.powertrain.engine.fuel", "le moteur brûle une réserve d'energy.stores");
            }
            checkConsumption(fuel, "$.powertrain.engine.fuel", fault);
        }
        if (starter == null) {
            return;
        }
        String at = "$.powertrain.engine.starter";
        if (starterStore == null) {
            if (starter.get("draw") != null || starter.get("charge_rate") != null) {
                fault.accept(at, "« draw » et « charge_rate » vont avec « store » ; une batterie energy.battery déclare"
                        + " les siens");
            }
            return;
        }
        if (!stores) {
            fault.accept(at + ".store", "le démarreur tire une réserve d'energy.stores");
        }
        if (energy != null && energy.get("battery") != null) {
            fault.accept(at + ".store", "le démarreur tire energy.battery ou une réserve, pas les deux");
        }
        if (starterStore.equals(string(fuel, "store"))) {
            fault.accept(at + ".store", "le moteur et le démarreur ont chacun leur réserve");
        }
        atLeast(starter, "draw", at, true, fault);
        atLeast(starter, "charge_rate", at, true, fault);
    }

    /**
     * ADR-176 D3 : l'énergie d'un véhicule. Un réservoir déclare sa capacité et sa consommation (charge de 0 à 1,
     * strictement croissante, litres par heure) ; une batterie, sa capacité, ce que le démarreur en tire et ce que le
     * moteur lui rend, et exige un démarreur ; la masse par litre exige une masse déclarée, qui est la masse à sec.
     */
    private static void checkEnergy(JsonObject root, BiConsumer<String, String> fault) {
        JsonObject energy = object(root, "energy");
        JsonObject powertrain = object(root, "powertrain");
        JsonObject engine = object(powertrain, "engine");
        boolean stores = energy != null && energy.get("stores") instanceof JsonArray;
        checkEngineStores(engine, stores, energy, fault);
        if (energy == null) {
            return;
        }
        if (stores) {
            checkStores(root, energy, fault);
            if (energy.get("fuel") != null) {
                fault.accept("$.energy.fuel", "avec « stores », le réservoir est une réserve que le moteur nomme"
                        + " (powertrain.engine.fuel)");
            }
        }
        // Une réserve se déclare sans moteur (ADR-182 D1) ; le réservoir et la batterie d'avant nourrissent un moteur.
        if (powertrain == null) {
            if (!stores || energy.get("fuel") != null || energy.get("battery") != null) {
                fault.accept("$.energy", "l'énergie nourrit un groupe motopropulseur : la definition en déclare un");
            }
            return;
        }
        JsonObject fuel = stores ? null : object(energy, "fuel");
        if (fuel != null) {
            String at = "$.energy.fuel";
            if (!(number(fuel, "capacity", 0f) > 0f) || number(fuel, "capacity", 0f) > MAX_FUEL_CAPACITY) {
                fault.accept(at + ".capacity", "la capacité du réservoir est de 0 (exclu) à " + MAX_FUEL_CAPACITY
                        + " litres");
            }
            if (number(fuel, "unit_mass", 0f) < 0f || number(fuel, "unit_mass", 0f) > MAX_UNIT_MASS) {
                fault.accept(at + ".unit_mass", "la masse d'un litre est de 0 à " + MAX_UNIT_MASS + " kg");
            }
            boolean dryMass = object(root, "physics") != null && object(root, "physics").get("mass") != null;
            if (number(fuel, "unit_mass", 0f) > 0f && !dryMass) {
                fault.accept(at + ".unit_mass", "la masse suit le carburant : la definition déclare physics.mass, la masse"
                        + " à sec");
            }
            checkConsumption(fuel, at, fault);
        }
        JsonObject battery = object(energy, "battery");
        if (battery != null) {
            String at = "$.energy.battery";
            if (!(number(battery, "capacity", 0f) > 0f) || number(battery, "capacity", 0f) > MAX_BATTERY) {
                fault.accept(at + ".capacity", "la capacité de la batterie est de 0 (exclu) à " + MAX_BATTERY);
            }
            atLeast(battery, "starter_draw", at, true, fault);
            atLeast(battery, "charge_rate", at, true, fault);
            if (object(powertrain, "engine") == null || object(object(powertrain, "engine"), "starter") == null) {
                fault.accept(at, "une batterie nourrit le démarreur : le moteur déclare powertrain.engine.starter");
            }
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
        if (number(engine, "max_rpm", MAX_RPM) <= number(engine, "idle_rpm", IDLE_RPM)) {
            fault.accept(eat + ".max_rpm", "doit dépasser idle_rpm");
        }
        JsonObject starter = object(engine, "starter");
        if (starter != null) {
            double time = number(starter, "time", 0f);
            if (!(time >= MIN_STARTER_TIME && time <= MAX_STARTER_TIME)) {
                fault.accept(eat + ".starter.time", "le démarreur tourne de " + MIN_STARTER_TIME + " à " + MAX_STARTER_TIME
                        + " secondes (ADR-176 D1)");
            }
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
        atLeast(object(powertrain, "traction_control"), "slip_threshold", "$.powertrain.traction_control", false, fault);

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
        if (number(steering, "max_angle", MAX_ANGLE) >= MAX_ANGLE_LIMIT) {
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
        atLeast(object(brakes, "stability_control"), "yaw_tolerance", at + ".stability_control", false, fault);
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
                + count(aero, "surfaces") * SURFACE_BYTES
                + consumption(root).size() * 8;
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
        JsonObject traction = object(powertrain, "traction_control");
        JsonObject stability = object(brakes, "stability_control");
        JsonObject aero = object(root, "aero");

        out.putShort((short) wheels.size());
        out.putShort((short) count(engine, "torque_curve"));
        out.putShort((short) count(transmission, "gears"));
        out.putShort((short) count(aero, "surfaces"));
        out.putShort((short) ((powertrain != null ? VEHICLE_POWERTRAIN : 0) | (bool(abs, "enabled") ? VEHICLE_ABS : 0)
                | (bool(traction, "enabled") ? VEHICLE_TRACTION_CONTROL : 0)
                | (bool(stability, "enabled") ? VEHICLE_STABILITY_CONTROL : 0)));
        String type = transmission == null ? "direct" : stringOr(transmission, "type", "manual");
        out.put((byte) TRANSMISSIONS.indexOf(type));
        out.put((byte) DIFFERENTIALS.indexOf(stringOr(differential, "type", "open")));

        put(out, number(engine, "idle_rpm", IDLE_RPM), number(engine, "max_rpm", MAX_RPM),
                number(engine, "inertia", 0.3f), number(engine, "braking_torque", 25f),
                number(object(engine, "starter"), "time", 0f));
        boolean direct = transmission == null;
        put(out, number(transmission, "final_drive", direct ? FINAL_DRIVE_DIRECT : FINAL_DRIVE),
                number(transmission, "shift_time", direct ? SHIFT_TIME_DIRECT : SHIFT_TIME),
                number(transmission, "auto_shift_up_rpm", 6200f), number(transmission, "auto_shift_down_rpm", 2200f),
                number(transmission, "efficiency", direct ? 1f : 0.92f));
        put(out, number(differential, "front_rear_split", 0f), number(differential, "lock", 0f));
        put(out, number(steering, "max_angle", MAX_ANGLE), number(steering, "speed", 3f), number(steering, "return_speed", 4f),
                number(steering, "speed_sensitivity", 0.6f), number(steering, "ackermann", 1f));
        put(out, number(brakes, "max_torque_front", BRAKE_FRONT), number(brakes, "max_torque_rear", BRAKE_REAR),
                number(brakes, "handbrake_torque", HANDBRAKE), number(abs, "slip_threshold", 0.15f));
        put(out, number(aero, "drag_coefficient", DRAG), number(aero, "frontal_area", 0f),
                number(aero, "downforce_front", DOWNFORCE), number(aero, "downforce_rear", DOWNFORCE));
        put(out, number(traction, "slip_threshold", TRACTION_SLIP_THRESHOLD),
                number(stability, "yaw_tolerance", STABILITY_YAW_TOLERANCE));
        // ADR-176 D3, ADR-182 : les réserves, celle que le moteur brûle et celle du démarreur (255 : aucune).
        EnergySpec reserves = EnergySpec.of(root);
        JsonObject starter = object(engine, "starter");
        boolean ownStore = string(starter, "store") != null;
        JsonObject battery = ownStore ? starter : object(object(root, "energy"), "battery");
        out.putShort((short) consumption(root).size());
        out.put((byte) reserves.fuelStore());
        out.put((byte) reserves.batteryStore());
        for (int slot = 0; slot < EnergySpec.MAX_STORES; slot++) {
            out.putFloat(reserves.capacity(slot));
        }
        put(out, number(battery, ownStore ? "draw" : "starter_draw", 0f), number(battery, "charge_rate", 0f));

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
            // ADR-176 D4 : l'usure du pneu, puis D5 son adhérence sous la pluie.
            put(out, tire.wearRate(), tire.wearGripLoss(), tire.wetGrip());
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
        for (JsonElement point : consumption(root)) {
            put(out, point.getAsJsonArray().get(0).getAsFloat(), point.getAsJsonArray().get(1).getAsFloat());
        }
    }

    /**
     * {@return les points de consommation de la réserve que le moteur brûle, charge puis unités par heure ; vide sans} Au
     * schéma 8, ceux de {@code powertrain.engine.fuel} ; avant, ceux du réservoir {@code energy.fuel}.
     */
    private static JsonArray consumption(JsonObject root) {
        JsonObject engine = object(object(root, "powertrain"), "engine");
        JsonObject fuel = object(engine, "fuel") != null ? object(engine, "fuel") : object(object(root, "energy"), "fuel");
        return fuel != null && fuel.get("consumption") instanceof JsonArray curve ? curve : new JsonArray();
    }

    /**
     * Ajoute les paramètres réglables d'un véhicule (ADR-167 D1), leur base lue avec les défauts mêmes de
     * {@link #write} : le moteur et la boîte s'il a un groupe motopropulseur — un rapport par rang de {@code gears},
     * hors boîte {@code direct} —, puis les freins, les pneus, les suspensions, le braquage et l'aérodynamique. Les
     * limites sont les règles de forme de {@link #check}.
     *
     * @param root une definition qui déclare un véhicule, ses règles de forme passées
     * @param out reçoit les paramètres, dans l'ordre fixe de {@link dev.axion.api.assembly.Parameters}
     */
    static void parameters(JsonObject root, List<Tuning.Parameter> out) {
        JsonObject powertrain = object(root, "powertrain");
        if (powertrain != null) {
            JsonObject engine = object(powertrain, "engine");
            float peak = 0f;
            if (engine != null && engine.get("torque_curve") instanceof JsonArray curve) {
                for (JsonElement point : curve) {
                    peak = Math.max(peak, point.getAsJsonArray().get(1).getAsFloat());
                }
            }
            // Un moteur sans couple n'a pas de courbe à mettre à l'échelle.
            if (peak > 0f) {
                out.add(Tuning.above(Parameters.ENGINE_TORQUE, "N·m", peak, 0, true));
            }
            out.add(Tuning.above(Parameters.ENGINE_MAX_RPM, "tr/min", number(engine, "max_rpm", MAX_RPM),
                    number(engine, "idle_rpm", IDLE_RPM), false));
            JsonObject transmission = object(powertrain, "transmission");
            boolean direct = transmission == null;
            out.add(Tuning.above(Parameters.FINAL_DRIVE, "",
                    number(transmission, "final_drive", direct ? FINAL_DRIVE_DIRECT : FINAL_DRIVE), 0, false));
            String type = direct ? "direct" : stringOr(transmission, "type", "manual");
            if (!"direct".equals(type)) {
                out.add(Tuning.above(Parameters.SHIFT_TIME, "s", number(transmission, "shift_time", SHIFT_TIME), 0,
                        true));
                JsonArray gears = transmission.getAsJsonArray("gears");
                int neutral = 0;
                while (gears.get(neutral).getAsFloat() != 0f) {
                    neutral++;
                }
                for (int i = 0; i < gears.size(); i++) {
                    float ratio = gears.get(i).getAsFloat();
                    if (i == neutral) {
                        continue;
                    }
                    // Un rapport garde son signe : sinon le point mort ou la marche avant changeraient de rang.
                    out.add(new Tuning.Parameter(Parameters.gear(i - neutral), "", ratio, ratio > 0
                            ? new Parameters.Range(0, false, Double.POSITIVE_INFINITY, false)
                            : new Parameters.Range(Double.NEGATIVE_INFINITY, false, 0, false), Tuning.unbounded()));
                }
            }
        }
        JsonObject brakes = object(root, "brakes");
        out.add(Tuning.above(Parameters.BRAKES_FRONT, "N·m", number(brakes, "max_torque_front", BRAKE_FRONT), 0, true));
        out.add(Tuning.above(Parameters.BRAKES_REAR, "N·m", number(brakes, "max_torque_rear", BRAKE_REAR), 0, true));
        out.add(Tuning.above(Parameters.BRAKES_HANDBRAKE, "N·m", number(brakes, "handbrake_torque", HANDBRAKE), 0,
                true));
        out.add(Tuning.above(Parameters.TIRES_GRIP, "×", 1, 0, false));
        out.add(Tuning.above(Parameters.SUSPENSION_STIFFNESS, "×", 1, 0, true));
        out.add(Tuning.above(Parameters.SUSPENSION_DAMPING, "×", 1, 0, true));
        out.add(new Tuning.Parameter(Parameters.STEERING_MAX_ANGLE, "rad",
                number(object(root, "steering"), "max_angle", MAX_ANGLE),
                new Parameters.Range(0, false, MAX_ANGLE_LIMIT, false), Tuning.unbounded()));
        JsonObject aero = object(root, "aero");
        out.add(Tuning.above(Parameters.AERO_DRAG, "", number(aero, "drag_coefficient", DRAG), 0, true));
        out.add(new Tuning.Parameter(Parameters.AERO_DOWNFORCE_FRONT, "N", number(aero, "downforce_front", DOWNFORCE),
                Tuning.unbounded(), Tuning.unbounded()));
        out.add(new Tuning.Parameter(Parameters.AERO_DOWNFORCE_REAR, "N", number(aero, "downforce_rear", DOWNFORCE),
                Tuning.unbounded(), Tuning.unbounded()));
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
