package dev.axion.definition;

import dev.axion.definition.SchemaNode.Any;
import dev.axion.definition.SchemaNode.Arr;
import dev.axion.definition.SchemaNode.Bool;
import dev.axion.definition.SchemaNode.Dict;
import dev.axion.definition.SchemaNode.Field;
import dev.axion.definition.SchemaNode.Nullable;
import dev.axion.definition.SchemaNode.Num;
import dev.axion.definition.SchemaNode.Obj;
import dev.axion.definition.SchemaNode.Text;
import dev.axion.definition.SchemaNode.TextRule;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Le schéma de definition 1 (PARTIE 23.4), lu selon l'ADR-109.
 *
 * <p>Chaque liste fermée, plage et plafond vient d'un passage du cahier des
 * charges, cité à côté. Un champ sans contrainte écrite n'est vérifié que par
 * son type.
 */
final class SchemaOne {

    /** Étapes de dommage, DM-11 : clés de {@code collider_variants} et {@code on_part_stage}. */
    static final Set<String> STAGES =
            ordered("INTACT", "SCRATCHED", "DAMAGED", "HEAVY", "DESTROYED", "DETACHED");

    /**
     * Source procédurale de l'ANNEXE A.4.
     *
     * @param prefix famille, par exemple {@code wheel}
     * @param named la famille désigne un élément nommé : {@code wheel.<n>.spin_angle}
     * @param category catégorie du nom désigné, ou {@code null} s'il n'est pas
     *     vérifiable à la lecture (attache runtime, variable)
     * @param fields champs admis ; vide pour {@code var.<n>}, dont le nom est tout
     */
    record SourceFamily(String prefix, boolean named, NameCategory category, Set<String> fields) {}

    /** ANNEXE A.4, liste fermée V1.0 (R-1450). */
    static final List<SourceFamily> SOURCES = List.of(
            fixed("vehicle", "speed", "forward_speed", "throttle", "brake", "steer", "handbrake"),
            fixed("engine", "rpm", "load", "running"),
            fixed("transmission", "gear", "shifting"),
            named("wheel", NameCategory.WHEEL, "spin_angle", "steer_angle", "compression",
                    "slip_long", "slip_lat", "on_ground", "damage"),
            fixed("body", "linear_speed", "angular_speed", "pitch", "roll", "yaw",
                    "on_ground", "in_fluid"),
            named("part", NameCategory.PART, "health", "integrity", "stage", "absorbed",
                    "jammed", "revealed"),
            named("region", NameCategory.REGION, "mean_disp", "max_disp", "max_strain",
                    "energy", "version"),
            named("deform", NameCategory.PART, "mean_disp", "max_disp", "max_strain"),
            named("link", NameCategory.LINK, "integrity", "broken"),
            fixed("structure", "connected_parts_ratio"),
            named("wear", NameCategory.PART, "scratch", "soil", "burn", "rust"),
            named("joint", NameCategory.JOINT, "position", "velocity", "force", "jammed"),
            named("socket", NameCategory.SOCKET, "misalignment"),
            named("attach", null, "length", "tension", "attached"),
            named("seat", NameCategory.SEAT, "occupied"),
            named("particles", NameCategory.PARTICLES, "max_stretch", "torn_ratio"),
            fixed("time", "seconds"),
            new SourceFamily("var", true, null, Set.of()));

    /** Racine du schéma 1. */
    static final SchemaNode ROOT = root();

    private SchemaOne() {}

    private static SchemaNode root() {
        return obj()
                // Vérifiés avant le parcours, avec leurs codes propres (R-1780).
                .req("schema", any())
                .req("asset", any())
                .req("kind", any())
                .opt("physics", physics())
                // R-190 : 64 bodies, 128 joints, 64 parts, 16 sièges, 8 ensembles
                // de particules par assembly. C-22 : 256 liaisons, 128 animations.
                .opt("bodies", cap(body(), 64))
                .opt("joints", cap(joint(), 128))
                // R-1052 : de 1 à 32 roues.
                .opt("wheels", new Arr(wheel(), 1, 32, false))
                .opt("powertrain", powertrain())
                .opt("steering", steering())
                .opt("brakes", brakes())
                .opt("aero", aero())
                .opt("parts", cap(part(), 64))
                .opt("structural_links", cap(link(), 256))
                .opt("damage_zones", list(zone()))
                .opt("deformation", deformation())
                .opt("wear", obj().opt("profile", id()).build())
                .opt("particles", cap(particles(), 8))
                .opt("seats", cap(seat(), 16))
                .opt("sockets", list(declares(NameCategory.SOCKET)))
                .opt("procedural", list(procedural()))
                .opt("animations",
                        new Dict(new TextRule.Declares(NameCategory.ANIMATION), animation(), 128))
                .opt("on_part_stage", new Dict(new TextRule.Refers(NameCategory.PART),
                        new Dict(new TextRule.OneOf(STAGES), actions(), Integer.MAX_VALUE),
                        Integer.MAX_VALUE))
                .opt("on_detach",
                        new Dict(new TextRule.Refers(NameCategory.PART), actions(), Integer.MAX_VALUE))
                .opt("render", render())
                .opt("interaction", list(interaction()))
                .opt("repair_rule", id())
                .opt("custom", new Dict(new TextRule.Free(), any(), Integer.MAX_VALUE))
                .build();
    }

    /** DM-08. */
    private static SchemaNode physics() {
        return obj()
                .opt("mass", number())
                .opt("center_of_mass", vec3())
                .opt("linear_damping", number())
                .opt("angular_damping", number())
                .opt("ccd", bool())
                // R-980 : groupes data-driven, liste extensible.
                .opt("collision_group", text())
                .opt("collides_with", list(text()))
                .build();
    }

    private static SchemaNode body() {
        return obj()
                .req("name", declares(NameCategory.BODY))
                .opt("root_node", refers(NameCategory.NODE))
                .opt("colliders", text())
                .build();
    }

    /** C-34. */
    private static SchemaNode joint() {
        return obj()
                .req("name", declares(NameCategory.JOINT))
                .opt("type", oneOf("fixed", "revolute", "prismatic", "spherical", "generic",
                        "rope", "spring"))
                .opt("a", refers(NameCategory.BODY))
                .opt("b_node", refers(NameCategory.NODE))
                .opt("axis", vec3())
                .opt("limits", pair())
                .opt("motor", obj().opt("speed", number()).opt("max_force", number()).build())
                .opt("break_force", number())
                .opt("jam_curve", curve())
                .build();
    }

    /** PARTIE 12, suspension selon §12.3. */
    private static SchemaNode wheel() {
        return obj()
                .req("node", declares(NameCategory.WHEEL))
                .opt("steering", bool())
                .opt("powered", bool())
                .opt("radius", number())
                .opt("width", number())
                .opt("mass", number())
                .opt("tire", id())
                .opt("suspension", obj()
                        .opt("rest_length", number())
                        .opt("max_travel", number())
                        .opt("stiffness", number())
                        .opt("damping_compression", number())
                        .opt("damping_rebound", number())
                        .opt("max_force", number())
                        .opt("anti_roll", number())
                        .build())
                .build();
    }

    /** §12.5. */
    private static SchemaNode powertrain() {
        return obj()
                .opt("engine", obj()
                        .opt("torque_curve", curve())
                        .opt("idle_rpm", number())
                        .opt("max_rpm", number())
                        .opt("inertia", number())
                        .opt("braking_torque", number())
                        .build())
                .opt("transmission", obj()
                        .opt("type", text())
                        .opt("gears", list(number()))
                        .opt("final_drive", number())
                        .opt("shift_time", number())
                        .opt("auto_shift_up_rpm", number())
                        .opt("auto_shift_down_rpm", number())
                        .opt("efficiency", number())
                        .build())
                // R-1081.
                .opt("differential", obj()
                        .opt("type", oneOf("open", "locked", "limited_slip"))
                        .opt("front_rear_split", number())
                        .opt("lock", number())
                        .build())
                .opt("drive_wheels", list(refers(NameCategory.WHEEL)))
                .build();
    }

    /** §12.6. */
    private static SchemaNode steering() {
        return obj()
                .opt("max_angle", number())
                .opt("speed", number())
                .opt("return_speed", number())
                .opt("speed_sensitivity", number())
                .opt("ackermann", number())
                .opt("steered_wheels", list(refers(NameCategory.WHEEL)))
                .build();
    }

    /** §12.6. */
    private static SchemaNode brakes() {
        return obj()
                .opt("max_torque_front", number())
                .opt("max_torque_rear", number())
                .opt("handbrake_torque", number())
                .opt("handbrake_wheels", list(refers(NameCategory.WHEEL)))
                .opt("abs", obj().opt("enabled", bool()).opt("slip_threshold", number()).build())
                .build();
    }

    /** §12.7. */
    private static SchemaNode aero() {
        return obj()
                .opt("drag_coefficient", number())
                .opt("frontal_area", number())
                .opt("downforce_front", number())
                .opt("downforce_rear", number())
                .opt("surfaces", list(obj()
                        .opt("node", refers(NameCategory.NODE))
                        .opt("area", number())
                        .opt("cl", number())
                        .opt("cd", number())
                        .build()))
                .build();
    }

    /** DM-11. */
    private static SchemaNode part() {
        return obj()
                .req("name", declares(NameCategory.PART))
                .opt("root_node", refers(NameCategory.NODE))
                .opt("material", id())
                .opt("health", number())
                .opt("structural_capacity", number())
                .opt("detach_threshold", fraction())
                .opt("propagation", fraction())
                // C-22 : masse de part dans [0.001, 1e6] kg, comme ax-model.
                .opt("mass", new Num(0.001, 1e6, false, false))
                .opt("meshes", obj()
                        .opt("intact", new Nullable(refers(NameCategory.MESH)))
                        .opt("damaged", new Nullable(refers(NameCategory.MESH)))
                        .opt("destroyed", new Nullable(refers(NameCategory.MESH)))
                        .build())
                .opt("flags", list(oneOf("detachable", "critical", "hide_when_destroyed",
                        "structural", "internal", "no_deform", "repairable", "spawns_debris")))
                .opt("deform_regions", list(refers(NameCategory.REGION)))
                .opt("collider_variants", new Dict(new TextRule.OneOf(STAGES),
                        refers(NameCategory.COLLIDER), Integer.MAX_VALUE))
                .opt("repair_item", id())
                .build();
    }

    /** DM-14 ; capacités strictement positives, comme le validateur d'asset. */
    private static SchemaNode link() {
        return obj()
                .req("name", declares(NameCategory.LINK))
                .opt("a", refers(NameCategory.PART))
                .opt("b", refers(NameCategory.PART))
                .opt("kind", oneOf("weld", "bolt", "hinge", "glue", "organic", "snap"))
                .opt("capacity", positive())
                .opt("tensile", positive())
                .opt("shear", positive())
                .opt("torque", positive())
                .opt("joint", refers(NameCategory.JOINT))
                .opt("propagation", fraction())
                .opt("flags", list(oneOf("load_bearing", "severable", "reformable")))
                .build();
    }

    /** DM-11 ; sous-zones par {@code parent}. */
    private static SchemaNode zone() {
        return obj()
                .req("name", declares(NameCategory.ZONE))
                .opt("part", refers(NameCategory.PART))
                .opt("region", refers(NameCategory.REGION))
                .opt("multiplier", number())
                .opt("deform_multiplier", number())
                .opt("node", refers(NameCategory.NODE))
                .opt("parent", refers(NameCategory.ZONE))
                .build();
    }

    /** §14.8 et niveaux de qualité (PARTIE 24.2). */
    private static SchemaNode deformation() {
        return obj()
                .opt("enabled", bool())
                .opt("min_quality", oneOf("off", "low", "medium", "high", "ultra"))
                .opt("extras", obj()
                        .opt("bones", list(obj()
                                .opt("bone", refers(NameCategory.BONE))
                                .opt("driven_by", text())
                                .opt("axis", text())
                                .opt("scale", number())
                                .opt("max_angle", number())
                                .build()))
                        .opt("morphs", list(obj()
                                .opt("target", text())
                                .opt("driven_by", text())
                                .opt("map", curve())
                                .build()))
                        .build())
                .build();
    }

    /** §17.5, DM-16. */
    private static SchemaNode particles() {
        return obj()
                .req("name", declares(NameCategory.PARTICLES))
                .opt("kind", oneOf("cloth", "rope", "cable", "net", "soft"))
                .opt("authority", oneOf("visual", "server_simple", "server_full"))
                .opt("mesh", refers(NameCategory.MESH))
                .opt("anchors", list(refers(NameCategory.NODE)))
                .opt("stiffness", number())
                .opt("bending", number())
                .opt("damping", number())
                .opt("mass_per_particle", number())
                .opt("wind_influence", number())
                .opt("collision_proxies", list(obj()
                        .opt("type", text())
                        .opt("node", refers(NameCategory.NODE))
                        .opt("radius", number())
                        .opt("height", number())
                        .build()))
                .opt("max_distance_from_anchor", number())
                .opt("from", obj()
                        .opt("assembly", text())
                        .opt("socket", refers(NameCategory.SOCKET))
                        .build())
                .opt("to", obj().opt("attachment", text()).build())
                .opt("segments", integer())
                .opt("server_segments", integer())
                .opt("max_length", number())
                .opt("break_force", number())
                .opt("radius", number())
                .opt("material", id())
                .opt("tear_threshold", number())
                .opt("self_collide", bool())
                .build();
    }

    /** C-53. */
    private static SchemaNode seat() {
        return obj()
                .req("name", declares(NameCategory.SEAT))
                .opt("node", refers(NameCategory.NODE))
                .opt("role", oneOf("driver", "passenger"))
                .opt("exit_offsets", list(vec3()))
                .opt("controls", list(text()))
                .build();
    }

    /** §18.3 ; {@code source} dans l'ANNEXE A.4. */
    private static SchemaNode procedural() {
        return obj()
                .opt("node", refers(NameCategory.NODE))
                .opt("bone", refers(NameCategory.BONE))
                .opt("channel", text())
                .opt("axis", text())
                .req("source", new Text(new TextRule.Source()))
                .opt("scale", number())
                .opt("map", curve())
                .opt("clamp", pair())
                .build();
    }

    /** §18.2 : quatre couches (R-1440), indexées de 0 à 3. */
    private static SchemaNode animation() {
        return obj()
                .opt("clip", refers(NameCategory.CLIP))
                .opt("layer", new Num(0, 3, false, true))
                .opt("drives_joint", refers(NameCategory.JOINT))
                .opt("trigger", text())
                .build();
    }

    /**
     * Actions de §13.4, liste fermée de R-1170. Les arguments dont le cahier des
     * charges donne un exemple ont sa forme ; les autres ne sont pas typés.
     */
    private static SchemaNode actions() {
        return obj()
                .opt("set_variable",
                        new Dict(new TextRule.Free(), number(), Integer.MAX_VALUE))
                .opt("emit_event", text())
                .opt("hide_node", refers(NameCategory.NODE))
                .opt("show_node", any())
                .opt("swap_mesh", refers(NameCategory.MESH))
                .opt("jam_joint", refers(NameCategory.JOINT))
                .opt("break_joint", any())
                .opt("detach_part", any())
                .opt("disable_seat", any())
                .opt("spawn_debris", id())
                .opt("play_animation", any())
                .opt("set_material_param", any())
                .build();
    }

    /** Clés de configuration homonymes (ANNEXE A.3) : liste et plage reprises. */
    private static SchemaNode render() {
        return obj()
                .opt("shadow", oneOf("map", "contact", "none"))
                .opt("max_distance", number())
                .opt("lod_bias", new Num(-2, 3, false, true))
                .opt("decals", bool())
                .build();
    }

    /** C-53 : actions d'interaction, liste fermée (R-1782). */
    private static SchemaNode interaction() {
        return obj()
                .opt("node", refers(NameCategory.NODE))
                .req("action", oneOf("sit", "toggle_animation", "play_animation",
                        "open_container", "emit_event", "set_variable", "repair", "attach",
                        "detach"))
                .opt("animation", refers(NameCategory.ANIMATION))
                .opt("seat", refers(NameCategory.SEAT))
                .build();
    }

    // Construction de l'arbre.

    private static final class ObjBuilder {
        private final Map<String, Field> fields = new LinkedHashMap<>();

        ObjBuilder req(String key, SchemaNode node) {
            fields.put(key, new Field(node, true));
            return this;
        }

        ObjBuilder opt(String key, SchemaNode node) {
            fields.put(key, new Field(node, false));
            return this;
        }

        SchemaNode build() {
            return new Obj(Collections.unmodifiableMap(fields));
        }
    }

    private static ObjBuilder obj() {
        return new ObjBuilder();
    }

    private static SchemaNode list(SchemaNode item) {
        return new Arr(item, 0, Integer.MAX_VALUE, false);
    }

    private static SchemaNode cap(SchemaNode item, int max) {
        return new Arr(item, 0, max, true);
    }

    private static SchemaNode number() {
        return new Num(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, false, false);
    }

    private static SchemaNode integer() {
        return new Num(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, false, true);
    }

    private static SchemaNode positive() {
        return new Num(0, Double.POSITIVE_INFINITY, true, false);
    }

    private static SchemaNode fraction() {
        return new Num(0, 1, false, false);
    }

    private static SchemaNode vec3() {
        return new Arr(number(), 3, 3, false);
    }

    private static SchemaNode pair() {
        return new Arr(number(), 2, 2, false);
    }

    private static SchemaNode curve() {
        return list(pair());
    }

    private static SchemaNode bool() {
        return new Bool();
    }

    private static SchemaNode any() {
        return new Any();
    }

    private static SchemaNode text() {
        return new Text(new TextRule.Free());
    }

    private static SchemaNode id() {
        return new Text(new TextRule.Id());
    }

    private static SchemaNode oneOf(String... values) {
        return new Text(new TextRule.OneOf(ordered(values)));
    }

    private static SchemaNode declares(NameCategory category) {
        return new Text(new TextRule.Declares(category));
    }

    private static SchemaNode refers(NameCategory category) {
        return new Text(new TextRule.Refers(category));
    }

    private static Set<String> ordered(String... values) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(List.of(values)));
    }

    private static SourceFamily fixed(String prefix, String... fields) {
        return new SourceFamily(prefix, false, null, ordered(fields));
    }

    private static SourceFamily named(String prefix, NameCategory category, String... fields) {
        return new SourceFamily(prefix, true, category, ordered(fields));
    }
}
