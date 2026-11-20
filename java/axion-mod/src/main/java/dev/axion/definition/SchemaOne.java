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
import dev.axion.physics.CollisionFilter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Les schémas de definition 1 (PARTIE 23.4, lu selon l'ADR-109) et 2 (ADR-151 D3).
 *
 * <p>Chaque liste fermée, plage et plafond vient d'un passage du cahier des
 * charges, cité à côté. Un champ sans contrainte écrite n'est vérifié que par
 * son type. Le schéma 2 est le schéma 1 et les réglages des joints de C-34, la masse
 * d'un corps et les collisions internes d'une assembly ; une definition {@code schema: 1}
 * reste lue comme avant.
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
     * @param since le premier schéma qui la connaît (R-2410)
     */
    record SourceFamily(String prefix, boolean named, NameCategory category, Set<String> fields, int since) {

        SourceFamily(String prefix, boolean named, NameCategory category, Set<String> fields) {
            this(prefix, named, category, fields, 1);
        }

        /** {@return cette famille, connue à partir de ce schéma} */
        SourceFamily since(int version) {
            return new SourceFamily(prefix, named, category, fields, version);
        }
    }

    /**
     * ANNEXE A.4, liste fermée (R-1450), en V1.1 : la V1.0 et les sources du monde (ADR-163 D1, amendement A9 du
     * registre 38.9, R-2410).
     */
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
            // ADR-174 D5 : au schéma 7, ce que compte un capteur déclaré.
            named("sensor", NameCategory.SENSOR, "count").since(7),
            named("particles", NameCategory.PARTICLES, "max_stretch", "torn_ratio"),
            fixed("time", "seconds"),
            fixed("world", "time", "sky_light", "rain"),
            new SourceFamily("var", true, null, Set.of()));

    /** ADR-170 D3 : les vues au plus, et les jauges et widgets d'un tableau de bord. */
    static final int MAX_CAMERAS = 16;
    static final int MAX_GAUGES = 32;
    static final int MAX_WIDGETS = 16;

    /** ADR-170 D4 : les ancres d'une jauge sur l'écran. */
    static final Set<String> ANCHORS = ordered("top_left", "top", "top_right", "left", "center", "right",
            "bottom_left", "bottom", "bottom_right");

    /** Racine du schéma 1. */
    static final SchemaNode ROOT = root(1);

    /** Racine du schéma 2 (ADR-151 D3). */
    static final SchemaNode ROOT_TWO = root(2);

    /** Racine du schéma 3 (ADR-163 D2) : le schéma 2, les variables et ce qui les écrit. */
    static final SchemaNode ROOT_THREE = root(3);

    /** Racine du schéma 4 (ADR-167) : le schéma 3, et le réglage des paramètres d'une instance. */
    static final SchemaNode ROOT_FOUR = root(4);

    /** Racine du schéma 5 (ADR-170) : le schéma 4, les vues du véhicule et son tableau de bord. */
    static final SchemaNode ROOT_FIVE = root(5);

    /** Racine du schéma 6 (ADR-171) : le schéma 5, et les sockets d'attache en objets. */
    static final SchemaNode ROOT_SIX = root(6);

    /** Racine du schéma 7 (ADR-174) : le schéma 6, et la liste ordonnée des actions d'une interaction. */
    static final SchemaNode ROOT_SEVEN = root(7);


    private SchemaOne() {}

    /**
     * {@return la racine du schéma {@code version}}
     *
     * @param version 1, 2, 3, 4, 5, 6 ou 7
     */
    static SchemaNode rootOf(int version) {
        return switch (version) {
            case 7 -> ROOT_SEVEN;
            case 6 -> ROOT_SIX;
            case 5 -> ROOT_FIVE;
            case 4 -> ROOT_FOUR;
            case 3 -> ROOT_THREE;
            case 2 -> ROOT_TWO;
            default -> ROOT;
        };
    }

    private static SchemaNode root(int version) {
        ObjBuilder root = obj()
                // Vérifiés avant le parcours, avec leurs codes propres (R-1780).
                .req("schema", any())
                .req("asset", any())
                .req("kind", any())
                .opt("physics", physics(version))
                // R-190 : 64 bodies, 128 joints, 64 parts, 16 sièges, 8 ensembles
                // de particules par assembly. C-22 : 256 liaisons, 128 animations.
                .opt("bodies", cap(body(version), 64))
                .opt("joints", cap(version >= 2 ? jointTwo() : joint(), 128))
                // R-1052 : de 1 à 32 roues.
                .opt("wheels", new Arr(wheel(), 1, 32, false))
                .opt("powertrain", powertrain(version))
                .opt("steering", steering())
                .opt("brakes", brakes())
                .opt("aero", aero())
                .opt("parts", cap(part(), 64))
                .opt("structural_links", cap(link(), 256))
                .opt("damage_zones", list(zone()))
                .opt("deformation", deformation())
                .opt("wear", obj().opt("profile", id()).build())
                .opt("particles", cap(particles(), 8))
                .opt("seats", cap(seat(version), 16))
                .opt("sockets", list(declares(NameCategory.SOCKET)))
                .opt("procedural", list(procedural(version)))
                .opt("animations",
                        new Dict(new TextRule.Declares(NameCategory.ANIMATION), animation(version), 128))
                .opt("on_part_stage", new Dict(new TextRule.Refers(NameCategory.PART),
                        new Dict(new TextRule.OneOf(STAGES), actions(version), Integer.MAX_VALUE),
                        Integer.MAX_VALUE))
                .opt("on_detach",
                        new Dict(new TextRule.Refers(NameCategory.PART), actions(version), Integer.MAX_VALUE))
                .opt("render", render())
                .opt("interaction", list(interaction(version)))
                .opt("repair_rule", id())
                .opt("custom", new Dict(new TextRule.Free(), any(), Integer.MAX_VALUE));
        if (version >= 3) {
            // ADR-163 D2 : 64 variables au plus, chacune par instance.
            root.opt("variables",
                    new Dict(new TextRule.Declares(NameCategory.VARIABLE), variable(version), Variables.MAX));
        }
        if (version >= 7) {
            // ADR-176 D3 : l'énergie d'un véhicule, un réservoir et une batterie ; la cohérence (un réservoir déclare sa
            // consommation, une batterie exige un démarreur, la masse par litre exige physics.mass) se juge ensemble
            // (VehicleSpec.checkEnergy).
            root.opt("energy", obj()
                    .opt("fuel", obj()
                            .req("capacity", number())
                            .opt("unit_mass", number())
                            .opt("consumption", curve())
                            .build())
                    .opt("battery", obj()
                            .req("capacity", number())
                            .opt("starter_draw", number())
                            .opt("charge_rate", number())
                            .build())
                    .build());
            // ADR-175 D3 : la révision, ce qui mène une sauvegarde d'une révision à la suivante, et les identifiants que
            // la definition remplace ; la cohérence des révisions se juge ensemble (DefinitionChecker.checkRevisions).
            root.opt("revision", new Num(1, 1_000_000, false, true));
            root.opt("migrations", cap(obj()
                    .req("from", new Num(1, 1_000_000, false, true))
                    .opt("rename", renames())
                    .build(), Revisions.MAX_MIGRATIONS));
            root.opt("replaces", new Arr(id(), 1, Revisions.MAX_REPLACES, true));
            // ADR-174 D2 : les événements de contenu, et à qui chacun part.
            root.opt("events", new Dict(new TextRule.Declares(NameCategory.EVENT),
                    obj().opt("replicate", oneOf("all", "occupants", "none")).build(), ContentEvents.MAX));
            // ADR-174 D5 : les capteurs, un volume posé à un node ; la forme et sa taille se jugent ensemble
            // (DefinitionChecker.checkSensors).
            root.opt("sensors", cap(obj()
                    .req("name", declares(NameCategory.SENSOR))
                    .req("node", refers(NameCategory.NODE))
                    .opt("shape", oneOf("box", "sphere"))
                    .opt("size", new Arr(positive(), 3, 3, false))
                    .opt("radius", positive())
                    .opt("replicate", oneOf("all", "occupants", "none"))
                    .build(), Sensors.MAX));
        }
        if (version >= 4) {
            // ADR-167 D2 : la plage qu'un réglage peut donner à chaque paramètre, dans son unité. Le nom du paramètre et
            // la base que la plage doit contenir se vérifient avec la definition entière (Tuning.check).
            // ADR-167 D4 : une variable règle un paramètre ; le paramètre, la variable replicate: all, values ou scale
            // et les bornes se vérifient avec la definition entière (Tuning.check).
            root.opt("tuning", obj()
                    .opt("ranges", new Dict(new TextRule.Free(),
                            obj().opt("min", number()).opt("max", number()).build(), Integer.MAX_VALUE))
                    .opt("modifiers", new Dict(new TextRule.Free(), obj()
                            .req("parameter", text())
                            .req("operation", oneOf("add", "multiply_base", "multiply_total"))
                            .req("variable", refers(NameCategory.VARIABLE))
                            .opt("values", list(number()))
                            .opt("scale", number())
                            .build(), Tuning.MAX_DECLARED))
                    .build());
            // ADR-167 D9 : les contrôleurs du véhicule, enregistrés par des mods ; qu'ils existent se vérifie avec le
            // registre (DefinitionRegistry), qu'il y ait des roues avec la definition entière (Tuning.check).
            root.opt("vehicle", obj().opt("controllers", cap(id(), Tuning.MAX_CONTROLLERS)).build());
        }
        if (version >= 5) {
            // ADR-170 D3 : les vues ; ce que chaque sorte admet, et l'œil dans ±4 m, se vérifient avec la vue entière
            // (Cameras.check).
            root.opt("cameras", cap(obj()
                    .req("name", declares(NameCategory.CAMERA))
                    .req("kind", oneOf("cockpit", "follow", "fixed"))
                    .opt("node", refers(NameCategory.NODE))
                    .opt("seats", list(refers(NameCategory.SEAT)))
                    .opt("eye", vec3())
                    .opt("distance", new Num(0.5, 64, false, false))
                    .opt("height", new Num(-8, 32, false, false))
                    .opt("fov", new Num(30, 110, false, false))
                    .opt("smoothing_ms", new Num(0, 5000, false, false))
                    .opt("recenter_ms", new Num(0, 5000, false, false))
                    .opt("look", obj()
                            .opt("yaw", new Num(0, 180, false, false))
                            .opt("pitch", new Num(0, 180, false, false))
                            .build())
                    .build(), MAX_CAMERAS));
            // ADR-170 D4 : le tableau de bord ; une jauge lit une source de l'ANNEXE A.4, ses bornes se jugent ensemble
            // (Cameras.check).
            root.opt("dashboard", obj()
                    .opt("default", bool())
                    .opt("seats", list(refers(NameCategory.SEAT)))
                    .opt("gauges", cap(obj()
                            .req("name", declares(NameCategory.GAUGE))
                            .req("kind", oneOf("dial", "bar", "text", "light"))
                            .req("source", new Text(new TextRule.Source(version)))
                            .opt("min", number())
                            .opt("max", number())
                            .opt("label", text())
                            .opt("unit", text())
                            .opt("anchor", new Text(new TextRule.OneOf(ANCHORS)))
                            .opt("offset", new Arr(new Num(-4096, 4096, false, false), 2, 2, false))
                            .opt("size", new Num(8, 512, false, false))
                            .build(), MAX_GAUGES))
                    .opt("widgets", cap(id(), MAX_WIDGETS))
                    .build());
        }
        if (version >= 6) {
            // ADR-171 D2 : un socket devient un objet ; son node, ou son nom sans node, se résout contre le modèle et
            // porte le rôle socket (natif). Ce que JSON Schema n'exprime pas — un bloc de paramètres pour un type que
            // le socket n'accepte pas, le treuil borné, les noms simples — se juge avec le socket (Sockets.check).
            root.opt("sockets", cap(socket(), Sockets.MAX));
        }
        return root.build();
    }

    /** DM-08. */
    private static SchemaNode physics(int version) {
        ObjBuilder physics = obj();
        if (version >= 2) {
            // §10.4, ADR-151 D2 : les corps d'une assembly se touchent entre eux.
            physics.opt("self_collision", bool());
        }
        return physics
                // DM-08 (ADR-130) : masse déclarée dans [0.001, 1e6] kg comme celle d'une part,
                // amortissements positifs ou nuls ; une valeur hors domaine se refuse ici, avec son
                // chemin, plutôt qu'à la frontière.
                .opt("mass", new Num(0.001, 1e6, false, false))
                .opt("center_of_mass", vec3())
                .opt("linear_damping", new Num(0, Double.POSITIVE_INFINITY, false, false))
                .opt("angular_damping", new Num(0, Double.POSITIVE_INFINITY, false, false))
                .opt("ccd", bool())
                // R-980 : groupes désignés par nom — un réservé, ou l'identifiant d'un groupe tiers
                // (axion:collision_group, ADR-132 A1). Le schéma juge la forme, le registre
                // l'existence ; un nom inconnu se refuse avec son chemin, jamais ignoré.
                .opt("collision_group", collisionGroup())
                .opt("collides_with", list(collisionGroup()))
                .build();
    }

    private static SchemaNode body(int version) {
        ObjBuilder body = obj()
                .req("name", declares(NameCategory.BODY))
                .opt("root_node", refers(NameCategory.NODE))
                .opt("colliders", text());
        if (version >= 2) {
            // ADR-151 D2 : la masse d'un corps, dans le domaine de celle d'une part.
            body.opt("mass", new Num(0.001, 1e6, false, false));
        }
        return body.build();
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

    /**
     * C-34, schéma 2 (ADR-151 D3) : tous les réglages de la fiche 5.26. Unités de R-102 ;
     * {@code anchor} et {@code axis} dans le repère du node {@code b_node} ; un réglage absent
     * n'agit pas. Ce que la forme seule ne dit pas — un réglage propre à un autre type, des
     * limites croisées — se juge à la compilation du joint.
     */
    private static SchemaNode jointTwo() {
        SchemaNode positive = new Num(0, Double.POSITIVE_INFINITY, false, false);
        SchemaNode strictlyPositive = new Num(0, Double.POSITIVE_INFINITY, true, false);
        return obj()
                .req("name", declares(NameCategory.JOINT))
                .opt("type", oneOf("fixed", "revolute", "prismatic", "spherical", "generic",
                        "rope", "spring"))
                .opt("a", refers(NameCategory.BODY))
                .opt("b_node", refers(NameCategory.NODE))
                .opt("anchor", vec3())
                .opt("axis", vec3())
                .opt("limits", pair())
                .opt("motor", obj()
                        .opt("speed", number())
                        .opt("target", number())
                        .opt("stiffness", positive)
                        .opt("damping", positive)
                        .opt("max_force", positive)
                        .build())
                .opt("friction", positive)
                .opt("break", obj()
                        .opt("force", strictlyPositive)
                        .opt("torque", strictlyPositive)
                        .opt("energy", strictlyPositive)
                        .build())
                .opt("jam", obj().opt("curve", curve()).opt("friction", positive).build())
                .opt("collide", bool())
                // generic : six axes, x, y, z linéaires puis x, y, z angulaires.
                .opt("axes", new Arr(obj()
                        .req("mode", oneOf("locked", "free", "limited"))
                        .opt("limits", pair())
                        .build(), 6, 6, false))
                .opt("max_length", strictlyPositive)
                .opt("rest_length", positive)
                .opt("stiffness", positive)
                .opt("damping", positive)
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
                // ADR-159 D5, R-1061 : le nom de l'essieu, le même sur ses deux roues.
                .opt("axle", text())
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
    private static SchemaNode powertrain(int version) {
        ObjBuilder engine = obj()
                .opt("torque_curve", curve())
                .opt("idle_rpm", number())
                .opt("max_rpm", number())
                .opt("inertia", number())
                .opt("braking_torque", number());
        if (version >= 7) {
            // ADR-176 D1 : le démarreur, et la variable booléenne qui est le contact.
            engine.opt("starter", obj()
                    .req("time", number())
                    .req("variable", refers(NameCategory.VARIABLE))
                    .build());
        }
        return obj()
                .opt("engine", engine.build())
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
                // R-1090, ADR-159 tranche 3 : l'antipatinage, désactivé par défaut.
                .opt("traction_control", obj().opt("enabled", bool()).opt("slip_threshold", number()).build())
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
                // R-1090, ADR-159 tranche 3 : le contrôle de stabilité, désactivé par défaut.
                .opt("stability_control", obj().opt("enabled", bool()).opt("yaw_tolerance", number()).build())
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

    /** C-53 ; au schéma 3, les commandes génériques qu'il lie à des variables (ADR-163 D2). */
    private static SchemaNode seat(int version) {
        ObjBuilder seat = obj()
                .req("name", declares(NameCategory.SEAT))
                .opt("node", refers(NameCategory.NODE))
                .opt("role", oneOf("driver", "passenger"))
                .opt("exit_offsets", list(vec3()))
                .opt("controls", list(text()));
        if (version >= 3) {
            seat.opt("bindings", cap(binding(), 8));
        }
        return seat.build();
    }

    /**
     * ADR-163 D2 : une variable de contenu. Le type, le défaut et les bornes se jugent ensemble dans
     * {@link DefinitionChecker} ; {@code scope} vaut l'assembly, et au schéma 7 aussi le siège (ADR-174 D2).
     */
    private static SchemaNode variable(int version) {
        return obj()
                .req("type", oneOf("bool", "int", "float"))
                .opt("default", any())
                .opt("min", number())
                .opt("max", number())
                .opt("persist", bool())
                .opt("replicate", oneOf("all", "driver", "none"))
                .opt("write", oneOf("driver", "seat", "none"))
                .opt("scope", version >= 7 ? oneOf("assembly", "seat") : oneOf("assembly"))
                .build();
    }

    /** ADR-163 D2 : une commande générique, de 1 à 8, liée à une variable. */
    private static SchemaNode binding() {
        return obj()
                .req("command", new Num(1, 8, false, true))
                .opt("label", text())
                .req("variable", refers(NameCategory.VARIABLE))
                .req("mode", oneOf("toggle", "hold", "press", "cycle"))
                .opt("values", list(any()))
                .build();
    }

    /**
     * §18.3 ; {@code source} dans l'ANNEXE A.4. Au schéma 3 (ADR-163 D3), les canaux forment une liste fermée — dont
     * {@code visibility}, {@code emissive} et {@code color} —, et une piste a une période, un lissage et une porte.
     */
    private static SchemaNode procedural(int version) {
        ObjBuilder track = obj()
                .opt("node", refers(NameCategory.NODE))
                .opt("bone", refers(NameCategory.BONE));
        if (version >= 3) {
            track.req("channel", oneOf("rotation", "translation", "visibility", "emissive", "color"))
                    .opt("axis", oneOf("x", "y", "z", "r", "g", "b"))
                    .opt("period", new Num(0, Double.POSITIVE_INFINITY, true, false))
                    .opt("smoothing", new Num(0, Double.POSITIVE_INFINITY, false, false))
                    .opt("gate", new Text(new TextRule.Source(version)));
        } else {
            track.opt("channel", text()).opt("axis", text());
        }
        return track
                .req("source", new Text(new TextRule.Source(version)))
                .opt("scale", number())
                .opt("map", curve())
                .opt("clamp", pair())
                .build();
    }

    /**
     * §18.2 : quatre couches (R-1440), indexées de 0 à 3. Au schéma 7, le poids qu'une source pilote au client et le
     * masque des nodes que la couche anime (ADR-174 D4).
     */
    private static SchemaNode animation(int version) {
        ObjBuilder animation = obj()
                .opt("clip", refers(NameCategory.CLIP))
                .opt("layer", new Num(0, 3, false, true))
                .opt("drives_joint", refers(NameCategory.JOINT))
                .opt("trigger", text());
        if (version >= 7) {
            animation.opt("weight_source", new Text(new TextRule.Source(version)))
                    .opt("mask", cap(refers(NameCategory.NODE), 256));
        }
        return animation.build();
    }

    /**
     * Actions de §13.4, liste fermée de R-1170. Les arguments dont le cahier des
     * charges donne un exemple ont sa forme ; les autres ne sont pas typés.
     */
    private static SchemaNode actions(int version) {
        return obj()
                // Au schéma 3, une variable posée est une variable déclarée (ADR-163 D2).
                .opt("set_variable", new Dict(version >= 3 ? new TextRule.Refers(NameCategory.VARIABLE)
                        : new TextRule.Free(), number(), Integer.MAX_VALUE))
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

    /** C-53 : actions d'interaction, liste fermée (R-1782) ; au schéma 3, la variable et la valeur qu'elle pose. */
    private static SchemaNode interaction(int version) {
        java.util.List<String> kinds = new java.util.ArrayList<>(java.util.List.of("sit", "toggle_animation",
                "play_animation", "open_container", "emit_event", "set_variable", "repair", "attach", "detach"));
        if (version >= 7) {
            // ADR-174 D3 : une interaction qui ne fait que jouer sa liste d'actions.
            kinds.add("actions");
        }
        ObjBuilder interaction = obj()
                .opt("node", refers(NameCategory.NODE))
                .req("action", version >= 7
                        // ADR-174 D6 : au schéma 7, une interaction qu'un mod enregistre, <modId>:<nom>.
                        ? new Text(new TextRule.OneOfOrAddon(new java.util.LinkedHashSet<>(kinds)))
                        : oneOf(kinds.toArray(String[]::new)))
                .opt("animation", refers(NameCategory.ANIMATION))
                .opt("seat", refers(NameCategory.SEAT));
        if (version >= 3) {
            interaction.opt("variable", refers(NameCategory.VARIABLE)).opt("value", any());
        }
        if (version >= 6) {
            // ADR-171 D4, CAP-16 : le socket qu'attach et detach visent, les types qu'attach permet, la distance où il
            // cherche l'autre socket (attachment.snap_distance sans) ; jugés ensemble par Sockets.check.
            interaction.opt("socket", refers(NameCategory.SOCKET))
                    .opt("kinds", new Arr(oneOf(Sockets.KINDS.toArray(String[]::new)), 1, Sockets.KINDS.size(), true))
                    .opt("distance", new Num(0.1, 16, false, false));
        }
        if (version >= 7) {
            // ADR-174 D3 : ce que l'interaction fait ensuite, dans l'ordre, au même tick ; D2 : l'événement
            // qu'emit_event émet, sa valeur dans « value ».
            interaction.opt("actions", new Arr(nodeAction(), 1, MAX_ACTIONS, true))
                    .opt("event", refers(NameCategory.EVENT))
                    // ADR-174 D6 : ce qu'une interaction d'addon reçoit, tel quel.
                    .opt("parameters", new Dict(new TextRule.Free(), any(), Integer.MAX_VALUE));
        }
        return interaction.build();
    }

    /** ADR-175 D3 : les noms qu'une migration change, par catégorie, ancien → nouveau. */
    private static SchemaNode renames() {
        ObjBuilder renames = obj();
        for (String kind : Revisions.KINDS) {
            renames.opt(kind, new Dict(new TextRule.Free(), text(), Revisions.MAX_RENAMES));
        }
        return renames.build();
    }

    /** Actions d'une interaction, au plus (ADR-174 D3). */
    static final int MAX_ACTIONS = 16;

    /**
     * ADR-174 D3 : une action sur l'état d'un node, au schéma 7 — un objet à une seule clé, pour que la liste garde son
     * ordre. {@code swap_mesh} désigne le maillage de remplacement par un node du même asset (décision de Killian,
     * tranche 9a2) ; sans {@code with}, l'original revient.
     */
    private static SchemaNode nodeAction() {
        return obj()
                .opt("hide_node", refers(NameCategory.NODE))
                .opt("show_node", refers(NameCategory.NODE))
                .opt("swap_mesh", obj()
                        .req("node", refers(NameCategory.NODE))
                        .opt("with", refers(NameCategory.NODE))
                        .build())
                .opt("emit_event", obj()
                        .req("event", refers(NameCategory.EVENT))
                        .opt("value", number())
                        .build())
                .opt("set_material_param", obj()
                        .req("node", refers(NameCategory.NODE))
                        .req("param", oneOf("emissive", "color"))
                        .req("value", any())
                        .build())
                // ADR-174 D6 : une action qu'un mod enregistre, <modId>:<nom>, et ses paramètres, tels quels.
                .addons(new Dict(new TextRule.Free(), any(), Integer.MAX_VALUE))
                .build();
    }

    /** ADR-171 D2 : un socket d'attache, au schéma 6. */
    private static SchemaNode socket() {
        SchemaNode length = new Num(0.1, 256, false, false);
        return obj()
                .req("name", declares(NameCategory.SOCKET))
                .opt("node", refers(NameCategory.SOCKET_NODE))
                .req("accepts", new Arr(oneOf(Sockets.KINDS.toArray(String[]::new)), 1, Sockets.KINDS.size(), true))
                .opt("tag", text())
                .opt("mates", cap(text(), 16))
                .opt("max_force", positive())
                .opt("max_torque", positive())
                .opt("misalignment", new Num(0.001, 16, false, false))
                .opt("anchor", bool())
                .opt("hitch", obj().opt("cone", new Num(0, 180, false, false)).build())
                .opt("rope", obj().opt("length", length).build())
                .opt("winch", obj()
                        .opt("speed", new Num(0, 20, false, false))
                        .opt("min_length", length)
                        .opt("max_length", length)
                        .opt("variable", refers(NameCategory.VARIABLE))
                        .build())
                .opt("magnet", obj().opt("variable", refers(NameCategory.VARIABLE)).build())
                .opt("transfer", obj()
                        .opt("power", new Dict(new TextRule.Refers(NameCategory.VARIABLE), text(), 16))
                        .opt("damage", new Num(0, 1, false, false))
                        .build())
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

        private SchemaNode addons;

        /** Admet aussi les clés {@code <modId>:<nom>}, chacune de cette forme (ADR-174 D6). */
        ObjBuilder addons(SchemaNode node) {
            addons = node;
            return this;
        }

        SchemaNode build() {
            return new Obj(Collections.unmodifiableMap(fields), addons);
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

    private static SchemaNode collisionGroup() {
        return new Text(new TextRule.CollisionGroup(ordered(CollisionFilter.RESERVED.toArray(String[]::new))));
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
