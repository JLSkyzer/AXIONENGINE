package dev.axion.physics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Les joints d'une definition, transcrits pour {@code CREATE_ASSEMBLY} (C-34, ADR-151 D3, D7).
 *
 * <p>La forme d'un joint est déjà vérifiée par le schéma ; reste sa cohérence avec son type, que
 * {@link #check} juge : un réglage propre à un autre type, des limites croisées, un axe nul, une
 * courbe de grippage mal ordonnée. Au schéma 2, le chargement de la definition la refuse ; au
 * schéma 1, la transcription. Les noms du schéma 1 — {@code break_force}, {@code jam_curve} — se
 * lisent comme {@code break.force} et {@code jam.curve}.
 */
public final class AssemblyJoints {

    /** Les types, dans l'ordre de {@code JointDescription.KIND_*}. */
    private static final List<String> KINDS =
            List.of("fixed", "revolute", "prismatic", "spherical", "generic", "rope", "spring");

    /** Les modes d'un axe de {@code generic}, dans l'ordre de {@code JointDescription.AXIS_*}. */
    private static final List<String> AXIS_MODES = List.of("locked", "free", "limited");

    /** Ce que tout joint admet. */
    private static final Set<String> COMMON =
            Set.of("name", "type", "a", "b_node", "anchor", "collide", "break", "break_force");

    /** Ce que chaque type admet de plus. */
    private static final Map<String, Set<String>> BY_KIND = Map.of(
            "fixed", Set.of(),
            "revolute", Set.of("axis", "limits", "motor", "friction", "jam", "jam_curve"),
            "prismatic", Set.of("axis", "limits", "motor", "friction", "jam", "jam_curve"),
            "spherical", Set.of("friction"),
            "generic", Set.of("axes", "friction"),
            "rope", Set.of("max_length"),
            "spring", Set.of("rest_length", "stiffness", "damping"));

    /** Ce que chaque type exige. */
    private static final Map<String, List<String>> REQUIRED = Map.of(
            "revolute", List.of("axis"),
            "prismatic", List.of("axis"),
            "generic", List.of("axes"),
            "rope", List.of("max_length"),
            "spring", List.of("rest_length", "stiffness"));

    private AssemblyJoints() {}

    /**
     * Une faute d'un joint.
     *
     * @param field champ fautif, relatif au joint ({@code limits}, {@code motor.target}…)
     * @param message ce qui ne va pas
     */
    public record Fault(String field, String message) {}

    /**
     * Les joints transcrits.
     *
     * @param joints un joint par entrée de {@code joints[]}, dans leur ordre
     * @param jamPoints les points des courbes de grippage, {@code [x, y]}, dans l'ordre des joints
     */
    public record Transcription(List<JointDescription> joints, List<float[]> jamPoints) {}

    /**
     * {@return la première faute d'un joint dont la forme est déjà vérifiée, ou {@code null}}
     *
     * @param joint l'entrée de {@code joints[]}
     */
    public static Fault check(JsonObject joint) {
        for (String field : new String[] {"type", "a", "b_node"}) {
            if (!joint.has(field)) {
                return new Fault(field, "champ obligatoire d'un joint");
            }
        }
        String type = joint.get("type").getAsString();
        Set<String> allowed = BY_KIND.get(type);
        for (String field : joint.keySet()) {
            if (!COMMON.contains(field) && !allowed.contains(field)) {
                return new Fault(field, "sans objet pour un joint " + type);
            }
        }
        for (String field : REQUIRED.getOrDefault(type, List.of())) {
            if (!joint.has(field)) {
                return new Fault(field, "obligatoire pour un joint " + type);
            }
        }
        if (joint.has("axis") && length(floats(joint.getAsJsonArray("axis"))) == 0.0f) {
            return new Fault("axis", "axe nul");
        }
        if (joint.has("limits") && crossed(floats(joint.getAsJsonArray("limits")))) {
            return new Fault("limits", "minimum au-delà du maximum");
        }
        if (joint.get("motor") instanceof JsonObject motor) {
            if (!motor.has("speed") && !motor.has("target")) {
                return new Fault("motor", "un moteur vise une vitesse ou une position");
            }
            if (motor.has("target") && !motor.has("stiffness")) {
                return new Fault("motor.stiffness", "un servo en position a une raideur");
            }
        }
        JsonElement curve = joint.get("jam") instanceof JsonObject jam ? jam.get("curve") : joint.get("jam_curve");
        if ((joint.has("jam") || joint.has("jam_curve")) && !joint.has("limits")) {
            return new Fault(joint.has("jam") ? "jam" : "jam_curve", "le grippage resserre des limites déclarées");
        }
        if (joint.get("jam") instanceof JsonObject jam && !jam.has("curve")) {
            return new Fault("jam.curve", "obligatoire pour un grippage");
        }
        if (curve instanceof JsonArray points) {
            Fault fault = checkCurve(points, joint.has("jam") ? "jam.curve" : "jam_curve");
            if (fault != null) {
                return fault;
            }
        }
        if (joint.get("axes") instanceof JsonArray axes) {
            for (int i = 0; i < axes.size(); i++) {
                JsonObject entry = axes.get(i).getAsJsonObject();
                boolean limited = entry.get("mode").getAsString().equals("limited");
                if (limited != entry.has("limits")) {
                    return new Fault("axes[" + i + "]", "des limites pour un axe limited, et pour lui seul");
                }
                if (limited && crossed(floats(entry.getAsJsonArray("limits")))) {
                    return new Fault("axes[" + i + "].limits", "minimum au-delà du maximum");
                }
            }
        }
        return null;
    }

    /** Une courbe de grippage : au moins un point, abscisses croissantes positives, fractions dans [0, 1]. */
    private static Fault checkCurve(JsonArray points, String field) {
        if (points.isEmpty()) {
            return new Fault(field, "au moins un point");
        }
        float previous = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < points.size(); i++) {
            float[] point = floats(points.get(i).getAsJsonArray());
            if (point[0] < 0.0f || point[0] <= previous) {
                return new Fault(field + "[" + i + "]", "désalignements positifs, strictement croissants");
            }
            if (point[1] < 0.0f || point[1] > 1.0f) {
                return new Fault(field + "[" + i + "]", "fraction de plage dans [0, 1]");
            }
            previous = point[0];
        }
        return null;
    }

    /**
     * {@return les joints de la definition, ou {@code null} si l'un est fautif ou ne se résout pas}
     *
     * @param definition racine JSON de la definition
     * @param nodeNames noms des nodes de l'asset, dans leur ordre
     * @param bodyRanks rang de chaque corps, par son nom
     * @param bodyRanksByRoot rang de chaque corps, par l'index de son node racine
     */
    public static Transcription of(
            JsonObject definition,
            List<String> nodeNames,
            Map<String, Integer> bodyRanks,
            Map<Integer, Integer> bodyRanksByRoot) {
        List<JointDescription> joints = new ArrayList<>();
        List<float[]> jamPoints = new ArrayList<>();
        if (!(definition.get("joints") instanceof JsonArray declared)) {
            return new Transcription(List.of(), List.of());
        }
        for (JsonElement element : declared) {
            JsonObject joint = element.getAsJsonObject();
            if (check(joint) != null) {
                return null;
            }
            Integer bodyA = bodyRanks.get(joint.get("a").getAsString());
            int bNode = nodeNames.indexOf(joint.get("b_node").getAsString());
            Integer bodyB = bodyRanksByRoot.get(bNode);
            if (bodyA == null || bNode < 0 || bodyB == null || bodyA.equals(bodyB)) {
                return null;
            }
            joints.add(transcribe(joint, bodyA, bodyB, bNode, jamPoints));
        }
        return new Transcription(List.copyOf(joints), List.copyOf(jamPoints));
    }

    private static JointDescription transcribe(
            JsonObject joint, int bodyA, int bodyB, int bNode, List<float[]> jamPoints) {
        int flags = 0;
        float[] limits = {0.0f, 0.0f};
        if (joint.has("limits")) {
            limits = floats(joint.getAsJsonArray("limits"));
            flags |= JointDescription.FLAG_LIMITS;
        }
        float speed = 0.0f;
        float target = 0.0f;
        float motorStiffness = 0.0f;
        float motorDamping = 0.0f;
        float maxForce = Float.POSITIVE_INFINITY;
        if (joint.get("motor") instanceof JsonObject motor) {
            if (motor.has("speed")) {
                speed = motor.get("speed").getAsFloat();
                flags |= JointDescription.FLAG_MOTOR_VELOCITY;
            }
            if (motor.has("target")) {
                target = motor.get("target").getAsFloat();
                flags |= JointDescription.FLAG_MOTOR_POSITION;
            }
            motorStiffness = number(motor, "stiffness", 0.0f);
            motorDamping = number(motor, "damping", 0.0f);
            maxForce = number(motor, "max_force", Float.POSITIVE_INFINITY);
        }
        float friction = number(joint, "friction", 0.0f);
        if (joint.has("friction")) {
            flags |= JointDescription.FLAG_FRICTION;
        }
        JsonObject breaking = joint.get("break") instanceof JsonObject b ? b : new JsonObject();
        float breakForce = joint.has("break_force")
                ? joint.get("break_force").getAsFloat()
                : number(breaking, "force", Float.POSITIVE_INFINITY);
        int jamFirst = 0;
        int jamCount = 0;
        float jamFriction = friction;
        JsonElement curve = joint.get("jam") instanceof JsonObject jam ? jam.get("curve") : joint.get("jam_curve");
        if (curve instanceof JsonArray points) {
            flags |= JointDescription.FLAG_JAM;
            jamFirst = jamPoints.size();
            jamCount = points.size();
            for (JsonElement point : points) {
                jamPoints.add(floats(point.getAsJsonArray()));
            }
            if (joint.get("jam") instanceof JsonObject jam) {
                jamFriction = number(jam, "friction", friction);
            }
        }
        if (joint.has("collide") && joint.get("collide").getAsBoolean()) {
            flags |= JointDescription.FLAG_COLLIDE;
        }
        int[] axes = new int[6];
        float[][] axisLimits = new float[6][2];
        if (joint.get("axes") instanceof JsonArray declared) {
            for (int i = 0; i < 6; i++) {
                JsonObject entry = declared.get(i).getAsJsonObject();
                axes[i] = AXIS_MODES.indexOf(entry.get("mode").getAsString());
                if (entry.has("limits")) {
                    axisLimits[i] = floats(entry.getAsJsonArray("limits"));
                }
            }
        }
        return new JointDescription(
                KINDS.indexOf(joint.get("type").getAsString()),
                flags,
                bodyA,
                bodyB,
                axes,
                bNode,
                joint.has("anchor") ? floats(joint.getAsJsonArray("anchor")) : new float[3],
                joint.has("axis") ? normalized(floats(joint.getAsJsonArray("axis"))) : new float[3],
                limits,
                speed,
                target,
                motorStiffness,
                motorDamping,
                maxForce,
                friction,
                breakForce,
                number(breaking, "torque", Float.POSITIVE_INFINITY),
                number(breaking, "energy", Float.POSITIVE_INFINITY),
                jamFriction,
                jamFirst,
                jamCount,
                axisLimits,
                number(joint, "max_length", 0.0f),
                number(joint, "rest_length", 0.0f),
                number(joint, "stiffness", 0.0f),
                number(joint, "damping", 0.0f));
    }

    private static float number(JsonObject object, String field, float absent) {
        return object.has(field) ? object.get(field).getAsFloat() : absent;
    }

    private static float[] floats(JsonArray array) {
        float[] values = new float[array.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = array.get(i).getAsFloat();
        }
        return values;
    }

    private static boolean crossed(float[] range) {
        return range[0] > range[1];
    }

    private static float length(float[] vector) {
        return (float) Math.sqrt((double) vector[0] * vector[0] + (double) vector[1] * vector[1]
                + (double) vector[2] * vector[2]);
    }

    private static float[] normalized(float[] vector) {
        float length = length(vector);
        return new float[] {vector[0] / length, vector[1] / length, vector[2] / length};
    }
}
