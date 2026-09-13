package dev.axion.definition;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T-282, T-283 — C-27 : structure du schéma 1 et références internes (ADR-109). */
class DefinitionSchemaTest {

    private static final String PICKUP = "mymod:axion/models/pickup.glb";

    private static final DefinitionRules RULES =
            new DefinitionRules(true, Set.of(PICKUP)::contains);

    /**
     * L'exemple du §23.4, rendu cohérent : chaque nom référencé y est déclaré,
     * et les objets vides renvoyés à d'autres sections sont remplis selon elles.
     */
    private static final String COMPLETE = """
            {
              "schema": 1,
              "asset": "mymod:models/pickup.glb",
              "kind": "vehicle",
              "physics": { "mass": 1650.0, "center_of_mass": [0, 0.45, -0.1],
                           "linear_damping": 0.05, "angular_damping": 0.3, "ccd": true,
                           "collision_group": "assembly",
                           "collides_with": ["world", "assembly", "entity_proxy", "debris"] },
              "bodies": [ { "name": "chassis", "root_node": "body", "colliders": "auto_compound" } ],
              "joints": [ { "name": "hood_hinge", "type": "revolute", "a": "chassis",
                            "b_node": "hood", "axis": [1, 0, 0], "limits": [0.0, 1.4],
                            "motor": { "speed": 2.0, "max_force": 200.0 },
                            "break_force": 8000.0,
                            "jam_curve": [[0, 1.0], [0.05, 0.5], [0.12, 0.0]] } ],
              "wheels": [ { "node": "wheel_fl", "steering": true, "powered": false,
                            "radius": 0.36, "width": 0.24, "mass": 20.0, "tire": "mymod:tire/road",
                            "suspension": { "rest_length": 0.35, "max_travel": 0.25,
                                            "stiffness": 30.0, "damping_compression": 2.3,
                                            "damping_rebound": 2.8, "max_force": 60000.0,
                                            "anti_roll": 0.0 } },
                          { "node": "wheel_rl", "powered": true } ],
              "powertrain": {
                "engine": { "torque_curve": [[0, 0], [1000, 180]], "idle_rpm": 800,
                            "max_rpm": 7000, "inertia": 0.3, "braking_torque": 25.0 },
                "transmission": { "type": "manual", "gears": [-3.2, 0.0, 3.5],
                                  "final_drive": 3.7, "shift_time": 0.25,
                                  "auto_shift_up_rpm": 6200, "auto_shift_down_rpm": 2200,
                                  "efficiency": 0.92 },
                "differential": { "type": "open", "front_rear_split": 0.0, "lock": 0.0 },
                "drive_wheels": ["wheel_rl"] },
              "steering": { "max_angle": 0.61, "speed": 3.0, "return_speed": 4.0,
                            "speed_sensitivity": 0.6, "ackermann": 1.0,
                            "steered_wheels": ["wheel_fl"] },
              "brakes": { "max_torque_front": 3000.0, "max_torque_rear": 1800.0,
                          "handbrake_torque": 4000.0, "handbrake_wheels": ["wheel_rl"],
                          "abs": { "enabled": false, "slip_threshold": 0.15 } },
              "aero": { "drag_coefficient": 0.32, "frontal_area": 2.2,
                        "downforce_front": 0.0, "downforce_rear": 0.0,
                        "surfaces": [ { "node": "wing_l", "area": 1.2, "cl": 0.8, "cd": 0.05 } ] },
              "parts": [
                { "name": "body", "root_node": "body", "material": "axion:steel", "mass": 1200.0 },
                { "name": "hood", "root_node": "hood", "material": "axion:steel",
                  "health": 100.0, "structural_capacity": 9000.0,
                  "detach_threshold": 0.0, "propagation": 0.25, "mass": 18.0,
                  "meshes": { "intact": "hood", "damaged": null, "destroyed": "hood_wrecked" },
                  "flags": ["detachable"],
                  "deform_regions": ["hood_front", "hood_rear"],
                  "collider_variants": { "HEAVY": "hood_collider_crushed" },
                  "repair_item": "minecraft:iron_ingot" } ],
              "structural_links": [
                { "name": "hood_to_body", "a": "body", "b": "hood", "kind": "hinge",
                  "capacity": 5000.0, "tensile": 14000.0, "shear": 9000.0, "torque": 600.0,
                  "joint": "hood_hinge", "propagation": 0.35, "flags": ["load_bearing"] } ],
              "damage_zones": [
                { "name": "front", "part": "hood", "region": "hood_front",
                  "multiplier": 1.5, "deform_multiplier": 1.6, "node": "zone_front" },
                { "name": "front_center", "parent": "front", "multiplier": 1.2 } ],
              "deformation": { "enabled": true, "min_quality": "low",
                               "extras": { "bones": [ { "bone": "door_l_bend",
                                                        "driven_by": "region:door_l",
                                                        "axis": "z", "scale": 0.8,
                                                        "max_angle": 0.35 } ],
                                           "morphs": [ { "target": "hood_crumple",
                                                         "driven_by": "region:hood.max_strain",
                                                         "map": [[0, 0], [0.3, 1.0]] } ] } },
              "wear": { "profile": "axion:painted_steel" },
              "particles": [
                { "name": "tow_rope", "kind": "rope", "authority": "server_simple",
                  "from": { "assembly": "self", "socket": "socket_towbar" },
                  "to": { "attachment": "current" },
                  "segments": 24, "server_segments": 12, "max_length": 6.0,
                  "stiffness": 0.9, "break_force": 20000.0, "radius": 0.03,
                  "material": "axion:rope" } ],
              "seats": [ { "name": "driver", "node": "seat_driver", "role": "driver",
                           "exit_offsets": [[-1.2, 0, 0], [1.2, 0, 0]],
                           "controls": ["throttle", "brake", "steer", "gear"] } ],
              "sockets": ["socket_towbar", "socket_exhaust"],
              "procedural": [
                { "node": "wheel_fl", "channel": "rotation", "axis": "x",
                  "source": "wheel.wheel_fl.spin_angle" },
                { "node": "needle_rpm", "channel": "rotation", "axis": "z",
                  "source": "engine.rpm", "map": [[0, 0.0], [7000, -4.2]] },
                { "bone": "door_l_bend", "channel": "rotation", "axis": "z",
                  "source": "region.door_l.mean_disp", "scale": 2.0, "clamp": [-0.35, 0.35] } ],
              "animations": { "hood_open": { "clip": "hood_open", "layer": 1,
                                             "drives_joint": "hood_hinge",
                                             "trigger": "interact:hood" } },
              "on_part_stage": { "hood": { "HEAVY": { "jam_joint": "hood_hinge" },
                                           "DESTROYED": { "set_variable": { "engine_power": 0.0 },
                                                          "emit_event": "hood_lost" } } },
              "on_detach": { "hood": { "emit_event": "hood_detached" } },
              "render": { "shadow": "map", "max_distance": 128, "lod_bias": 0, "decals": true },
              "interaction": [ { "node": "hood", "action": "toggle_animation",
                                 "animation": "hood_open" },
                               { "node": "seat_driver", "action": "sit", "seat": "driver" } ],
              "repair_rule": "axion:default_vehicle",
              "custom": { "anything": [1, { "x": "y" }] }
            }
            """;

    private static JsonObject complete() {
        return JsonParser.parseString(COMPLETE).getAsJsonObject();
    }

    private static Definition read(JsonObject root) throws DefinitionException {
        return DefinitionRegistry.read(
                "mymod:axion/definitions/pickup.json",
                root.toString().getBytes(StandardCharsets.UTF_8),
                RULES);
    }

    private static DefinitionException refus(Consumer<JsonObject> mutation) {
        JsonObject root = complete();
        mutation.accept(root);
        return assertThrows(DefinitionException.class, () -> read(root));
    }

    private static void accepte(Consumer<JsonObject> mutation) {
        JsonObject root = complete();
        mutation.accept(root);
        assertDoesNotThrow(() -> read(root));
    }

    /** Descend dans le document : clés d'objet, ou index de tableau. */
    private static JsonObject at(JsonObject root, String... steps) {
        JsonElement element = root;
        for (String step : steps) {
            element = step.chars().allMatch(Character::isDigit)
                    ? element.getAsJsonArray().get(Integer.parseInt(step))
                    : element.getAsJsonObject().get(step);
        }
        return element.getAsJsonObject();
    }

    @Test
    @DisplayName("T-282 : l'exemple complet du §23.4, rendu cohérent, est accepté")
    void exempleComplet() throws DefinitionException {
        Definition definition = read(complete());
        assertEquals(AssemblyKind.VEHICLE, definition.kind());
    }

    @Test
    @DisplayName("T-282 : une clé inconnue est refusée, sauf sous custom")
    void clesInconnues() {
        DefinitionException refus = refus(root -> at(root, "physics").addProperty("mas", 1650));
        assertEquals("$.physics.mas", refus.path());
        assertTrue(refus.getMessage().contains("clé inconnue"));

        assertEquals("$.physique", refus(root -> root.add("physique", new JsonObject())).path());
        // R-1083 donne cette forme sans dire où elle vit : hors du schéma 1.
        assertEquals("$.powertrain.on_part_disabled",
                refus(root -> at(root, "powertrain").add("on_part_disabled", new JsonObject()))
                        .path());
        accepte(root -> at(root, "custom").addProperty("n_importe_quoi", "vraiment"));
    }

    @Test
    @DisplayName("T-282 : les types impliqués par l'exemple sont vérifiés")
    void types() {
        assertEquals("$.physics.mass",
                refus(root -> at(root, "physics").addProperty("mass", "lourd")).path());
        assertEquals("$.wheels[0].steering",
                refus(root -> at(root, "wheels", "0").addProperty("steering", "oui")).path());
        assertEquals("$.physics.center_of_mass", refus(root -> {
            JsonArray deux = new JsonArray();
            deux.add(0);
            deux.add(1);
            at(root, "physics").add("center_of_mass", deux);
        }).path());
        assertEquals("$.particles[0].segments",
                refus(root -> at(root, "particles", "0").addProperty("segments", 2.5)).path());
    }

    @Test
    @DisplayName("T-282 : listes fermées du cahier des charges, en snake_case")
    void listesFermees() {
        assertEquals("$.joints[0].type",
                refus(root -> at(root, "joints", "0").addProperty("type", "REVOLUTE")).path());
        assertEquals("$.parts[1].flags[1]", refus(root ->
                at(root, "parts", "1").getAsJsonArray("flags").add("fragile")).path());
        assertEquals("$.structural_links[0].kind", refus(root ->
                at(root, "structural_links", "0").addProperty("kind", "rivet")).path());
        assertEquals("$.seats[0].role",
                refus(root -> at(root, "seats", "0").addProperty("role", "pilot")).path());
        assertEquals("$.render.shadow",
                refus(root -> at(root, "render").addProperty("shadow", "soft")).path());
        assertEquals("$.deformation.min_quality", refus(root ->
                at(root, "deformation").addProperty("min_quality", "extreme")).path());
        assertEquals("$.interaction[0].action", refus(root ->
                at(root, "interaction", "0").addProperty("action", "explode")).path());
        assertEquals("$.on_part_stage.hood.HEAVY.explode", refus(root ->
                at(root, "on_part_stage", "hood", "HEAVY").addProperty("explode", 1)).path());
        assertEquals("$.on_part_stage.hood.BROKEN", refus(root ->
                at(root, "on_part_stage", "hood").add("BROKEN", new JsonObject())).path());
    }

    @Test
    @DisplayName("T-282 : plages écrites — fractions, masse de part, capacités, couches")
    void plages() {
        assertEquals("$.parts[1].detach_threshold", refus(root ->
                at(root, "parts", "1").addProperty("detach_threshold", 1.5)).path());
        assertEquals("$.parts[1].mass",
                refus(root -> at(root, "parts", "1").addProperty("mass", 0)).path());
        assertEquals("$.structural_links[0].shear", refus(root ->
                at(root, "structural_links", "0").addProperty("shear", 0)).path());
        assertEquals("$.animations.hood_open.layer", refus(root ->
                at(root, "animations", "hood_open").addProperty("layer", 4)).path());
        assertEquals("$.render.lod_bias",
                refus(root -> at(root, "render").addProperty("lod_bias", 4)).path());
    }

    @Test
    @DisplayName("T-282 : un plafond d'assembly dépassé porte E-3050 (R-190)")
    void plafonds() {
        DefinitionException bodies = refus(root -> {
            JsonArray trop = new JsonArray();
            for (int index = 0; index < 65; index++) {
                JsonObject body = new JsonObject();
                body.addProperty("name", "body_" + index);
                trop.add(body);
            }
            root.add("bodies", trop);
            // Le joint désignait « chassis », qui n'existe plus.
            root.remove("joints");
            root.remove("structural_links");
            root.remove("animations");
            at(root, "on_part_stage", "hood").remove("HEAVY");
        });
        assertEquals(DefinitionException.LIMIT, bodies.code());
        assertEquals("$.bodies", bodies.path());

        // R-1052 borne les roues, mais ce n'est pas un plafond de R-190.
        DefinitionException aucune = refus(root -> {
            root.add("wheels", new JsonArray());
            root.remove("powertrain");
            root.remove("steering");
            root.remove("brakes");
            root.remove("procedural");
        });
        assertEquals(DefinitionException.INVALID, aucune.code());
        assertEquals("$.wheels", aucune.path());
    }

    @Test
    @DisplayName("T-282 : toutes les fautes sont rendues en un seul refus")
    void toutesLesFautes() {
        DefinitionException refus = refus(root -> {
            at(root, "physics").addProperty("mass", "lourd");
            at(root, "render").addProperty("shadow", "soft");
        });
        assertEquals("$.physics.mass", refus.path());
        assertTrue(refus.getMessage().contains("1 autre(s) faute(s)"), refus.getMessage());
        assertTrue(refus.getMessage().contains("$.render.shadow"), refus.getMessage());
    }

    @Test
    @DisplayName("T-283 : une référence interne désigne un nom déclaré")
    void referencesInternes() {
        DefinitionException joint = refus(root ->
                at(root, "joints", "0").addProperty("a", "chassis2"));
        assertEquals("$.joints[0].a", joint.path());
        assertTrue(joint.getMessage().contains("chassis2"));

        assertEquals("$.powertrain.drive_wheels[0]", refus(root -> {
            JsonArray roues = new JsonArray();
            roues.add("wheel_rr");
            at(root, "powertrain").add("drive_wheels", roues);
        }).path());
        assertEquals("$.interaction[0].animation", refus(root ->
                at(root, "interaction", "0").addProperty("animation", "door_open")).path());
        assertEquals("$.on_part_stage.capot", refus(root ->
                at(root, "on_part_stage").add("capot", new JsonObject())).path());
        assertEquals("$.on_part_stage.hood.HEAVY.jam_joint", refus(root ->
                at(root, "on_part_stage", "hood", "HEAVY").addProperty("jam_joint", "door")).path());
        assertEquals("$.particles[0].from.socket", refus(root ->
                at(root, "particles", "0", "from").addProperty("socket", "socket_winch")).path());
    }

    @Test
    @DisplayName("T-283 : noms uniques par catégorie, ASCII imprimable, 64 octets")
    void noms() {
        DefinitionException doublon = refus(root ->
                at(root, "parts", "1").addProperty("name", "body"));
        assertEquals("$.parts[1].name", doublon.path());
        assertTrue(doublon.getMessage().contains("$.parts[0].name"));

        // Une part et une zone peuvent partager un nom. La sous-zone suit le
        // renommage de sa parente, sans quoi elle désignerait une zone disparue.
        accepte(root -> {
            at(root, "damage_zones", "0").addProperty("name", "hood");
            at(root, "damage_zones", "1").addProperty("parent", "hood");
        });
        assertEquals("$.bodies[0].name",
                refus(root -> at(root, "bodies", "0").addProperty("name", "")).path());
        assertEquals("$.seats[0].name", refus(root ->
                at(root, "seats", "0").addProperty("name", "x".repeat(65))).path());
        assertEquals("$.sockets[0]", refus(root ->
                root.getAsJsonArray("sockets").set(0, new JsonPrimitive("socket_ârrière"))).path());
    }

    @Test
    @DisplayName("T-283 : liaison vers elle-même et cycle de zones parentes refusés")
    void graphes() {
        assertEquals("$.structural_links[0].b", refus(root ->
                at(root, "structural_links", "0").addProperty("b", "body")).path());
        DefinitionException cycle = refus(root ->
                at(root, "damage_zones", "0").addProperty("parent", "front_center"));
        assertTrue(cycle.getMessage().contains("zones parentes"), cycle.getMessage());
    }

    @Test
    @DisplayName("T-283 : sources procédurales de l'ANNEXE A.4, noms résolus")
    void sources() {
        String[] refusees = {"moteur.rpm", "engine.torque", "part.capot.health", "wheel.health"};
        for (String source : refusees) {
            DefinitionException refus = refus(root ->
                    at(root, "procedural", "1").addProperty("source", source));
            assertEquals("$.procedural[1].source", refus.path(), source);
        }
        String[] acceptees = {"var.engine_power", "time.seconds", "attach.tow.length",
                "part.hood.integrity", "link.hood_to_body.broken", "seat.driver.occupied",
                "particles.tow_rope.torn_ratio", "socket.socket_towbar.misalignment"};
        for (String source : acceptees) {
            accepte(root -> at(root, "procedural", "1").addProperty("source", source));
        }
    }

    @Test
    @DisplayName("T-283 : un nom d'asset n'est pas encore résolu, seule sa forme compte")
    void nomsDAsset() {
        // Node, mesh et région se résolvent contre l'asset compilé (tranche 3).
        accepte(root -> at(root, "bodies", "0").addProperty("root_node", "n_existe_pas"));
        assertEquals("$.bodies[0].root_node",
                refus(root -> at(root, "bodies", "0").addProperty("root_node", "")).path());
    }
}
