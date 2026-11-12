package dev.axion.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import dev.axion.api.content.DefinitionBuilder;
import dev.axion.api.content.DefinitionDocument;
import dev.axion.asset.AssetSource;
import dev.axion.definition.CanonicalJson;
import dev.axion.definition.Definition;
import dev.axion.definition.DefinitionRegistry;
import dev.axion.definition.DefinitionRules;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-162 D4, CAP-47 : le builder généré écrit le même document que le JSON. */
class DefinitionBuilderTest {

    private static final String LIEU = "axion:axion/definitions/example/buggy.json";

    /** Le buggy d'exemple, écrit avec le builder, champ pour champ comme {@code buggy.json}. */
    static DefinitionDocument buggy() {
        return DefinitionBuilder.vehicle("axion:models/example/buggy.glb")
                .physics(p -> p.mass(340.0).centerOfMass(0.0, 0.45, 0.0).linearDamping(0.05).angularDamping(0.3)
                        .ccd(true).collisionGroup("assembly")
                        .collidesWith("world", "assembly", "entity_proxy", "debris"))
                .body("caisse", b -> b.rootNode("chassis"))
                .joint("charniere_capot", j -> j.type("revolute").a("caisse").bNode("capot").axis(-1, 0, 0)
                        .limits(0.0, 1.2).break_(b -> b.force(6000.0)))
                .wheel("roue_avant_gauche", w -> roue(w.steering(true).powered(false)))
                .wheel("roue_avant_droite", w -> roue(w.steering(true).powered(false)))
                .wheel("roue_arriere_gauche", w -> roue(w.steering(false).powered(true)))
                .wheel("roue_arriere_droite", w -> roue(w.steering(false).powered(true)))
                .powertrain(t -> t
                        .engine(e -> e.torqueCurve(new double[] {0, 0}, new double[] {1500, 90},
                                        new double[] {3500, 120}, new double[] {5500, 105}, new double[] {6500, 80})
                                .idleRpm(900).maxRpm(6500).inertia(0.15).brakingTorque(15.0))
                        .transmission(x -> x.type("manual").gears(-3.0, 0.0, 3.2, 2.0, 1.4, 1.0).finalDrive(4.1)
                                .shiftTime(0.3).autoShiftUpRpm(5800).autoShiftDownRpm(2200).efficiency(0.9))
                        .differential(d -> d.type("open"))
                        .driveWheels("roue_arriere_gauche", "roue_arriere_droite"))
                .steering(s -> s.maxAngle(0.6).speed(3.0).returnSpeed(4.0).speedSensitivity(0.5).ackermann(1.0)
                        .steeredWheels("roue_avant_gauche", "roue_avant_droite"))
                .brakes(b -> b.maxTorqueFront(1200.0).maxTorqueRear(800.0).handbrakeTorque(1500.0)
                        .handbrakeWheels("roue_arriere_gauche", "roue_arriere_droite"))
                .part("chassis", p -> p.rootNode("chassis").material("axion:steel").health(600.0)
                        .structuralCapacity(60000.0).mass(220.0).flags("structural", "critical"))
                .part("carrosserie", p -> p.rootNode("carrosserie").material("axion:steel").health(250.0)
                        .structuralCapacity(15000.0).propagation(0.3).mass(60.0).flags("repairable"))
                .part("capot", p -> p.rootNode("capot").material("axion:steel").health(80.0)
                        .structuralCapacity(5000.0).detachThreshold(0.0).propagation(0.25).mass(12.0)
                        .flags("detachable", "repairable"))
                .structuralLink("carrosserie_sur_chassis", l -> l.a("chassis").b("carrosserie").kind("weld")
                        .capacity(20000.0).tensile(40000.0).shear(30000.0).propagation(0.3).flags("load_bearing"))
                .structuralLink("capot_sur_carrosserie", l -> l.a("carrosserie").b("capot").kind("hinge")
                        .capacity(4000.0).tensile(9000.0).shear(6000.0).torque(400.0).joint("charniere_capot")
                        .propagation(0.35).flags("severable"))
                .deformation(d -> d.enabled(true).minQuality("low"))
                .seat("conducteur", s -> s.node("siege_conducteur").role("driver")
                        .exitOffsets(new double[] {1.0, 0.0, 0.0}, new double[] {-1.0, 0.0, 0.0})
                        .controls("throttle", "brake", "steer", "gear", "handbrake")
                        .binding(1, "attelage_sorti", "toggle", b -> b.label("axion.example.buggy.attelage")))
                .variable("attelage_sorti", "bool",
                        v -> v.default_(false).persist(true).replicate("all").write("driver"))
                .sockets("attelage")
                .procedural("translation", "var.attelage_sorti", p -> p.node("attelage").axis("z").scale(0.2))
                .procedural("rotation", "vehicle.steer", p -> p.node("volant").axis("y").scale(-3.0))
                .procedural("emissive", "world.time", p -> p.node("phares")
                        .map(new double[] {0, 1}, new double[] {0.25, 1}, new double[] {0.27, 0},
                                new double[] {0.77, 0}, new double[] {0.79, 1}, new double[] {1, 1})
                        .smoothing(0.5))
                .procedural("rotation", "wheel.roue_avant_gauche.spin_angle", p -> p.node("roue_avant_gauche").axis("x"))
                .procedural("rotation", "wheel.roue_avant_droite.spin_angle", p -> p.node("roue_avant_droite").axis("x"))
                .procedural("rotation", "wheel.roue_arriere_gauche.spin_angle",
                        p -> p.node("roue_arriere_gauche").axis("x"))
                .procedural("rotation", "wheel.roue_arriere_droite.spin_angle",
                        p -> p.node("roue_arriere_droite").axis("x"))
                .interaction("sit", i -> i.node("siege_conducteur").seat("conducteur"))
                .render(r -> r.shadow("map"))
                .build();
    }

    /** Ce que les quatre roues du buggy ont en commun. */
    private static DefinitionBuilder.Wheel roue(DefinitionBuilder.Wheel roue) {
        return roue.radius(0.30).width(0.22).mass(12.0).suspension(s -> s.restLength(0.25).maxTravel(0.18)
                .stiffness(25.0).dampingCompression(2.0).dampingRebound(2.5).maxForce(20000.0));
    }

    private static byte[] fichier() throws IOException {
        try (InputStream in = DefinitionBuilderTest.class.getClassLoader()
                .getResourceAsStream("axion/definitions/example/buggy.json")) {
            assertNotNull(in, "buggy.json absent des ressources");
            return in.readAllBytes();
        }
    }

    /** Une source d'un seul fichier, lue comme le jeu la lit. */
    private record Source(byte[] contenu) implements AssetSource {
        @Override
        public List<String> list() {
            return List.of(LIEU);
        }

        @Override
        public byte[] read(String path) {
            return contenu;
        }
    }

    @Test
    @DisplayName("CAP-47 : le buggy écrit avec le builder a la forme canonique de buggy.json")
    void leBuggyEnJavaEstLeBuggyEnJson() throws IOException {
        String json = CanonicalJson.of(JsonParser.parseString(new String(fichier(), StandardCharsets.UTF_8)));
        assertEquals(json, new String(ContentRegistry.canonicalBytes(buggy()), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("ADR-162 D4 : le buggy du builder passe le même schéma, avec la même empreinte que le fichier")
    void leBuggyEnJavaPasseLeSchema() throws Exception {
        // Le troisième argument, content.examples : sans lui, un exemple est ignoré sans refus.
        DefinitionRules regles = new DefinitionRules(true, asset -> true, true);
        DefinitionRegistry depuisLeFichier = DefinitionRegistry.load(new Source(fichier()), regles);
        DefinitionRegistry depuisJava = DefinitionRegistry.load(
                new Source(ContentRegistry.canonicalBytes(buggy())), regles);
        assertTrue(depuisJava.refusals().isEmpty(), depuisJava.refusals().toString());
        Definition java = depuisJava.get("axion:example/buggy").orElseThrow();
        Definition json = depuisLeFichier.get("axion:example/buggy").orElseThrow();
        assertEquals(json.sha256(), java.sha256());
        assertEquals(depuisLeFichier.fingerprint(), depuisJava.fingerprint());
    }

    @Test
    @DisplayName("ADR-162 D4 : un objet réglé deux fois se complète ; un élément de table aussi ; un tableau s'allonge")
    void lesReglagesSeCompletent() {
        DefinitionDocument document = DefinitionBuilder.rigidObject("m:models/a.glb")
                .physics(p -> p.mass(2.0))
                .physics(p -> p.ccd(true))
                .variable("v", "bool", v -> v.persist(true))
                .variable("v", "bool", v -> v.default_(true))
                .sockets("a")
                .body("b1")
                .body("b2")
                .onPartStage("capot", "destroyed", a -> a.hideNode("capot").setVariable("x", 1.0))
                .custom("monmod:reglage", 3.5)
                .build();
        Map<String, Object> racine = document.root();
        assertEquals(Map.of("mass", 2.0, "ccd", true), racine.get("physics"));
        assertEquals(Map.of("type", "bool", "persist", true, "default", true),
                ((Map<?, ?>) racine.get("variables")).get("v"));
        assertEquals(2, ((List<?>) racine.get("bodies")).size());
        assertEquals(Map.of("hide_node", "capot", "set_variable", Map.of("x", 1.0)),
                ((Map<?, ?>) ((Map<?, ?>) racine.get("on_part_stage")).get("capot")).get("destroyed"));
        assertEquals(Map.of("monmod:reglage", 3.5), racine.get("custom"));
        assertEquals(4L, racine.get("schema"));
        assertEquals(4, DefinitionBuilder.SCHEMA);
    }

    @Test
    @DisplayName("R-581 : le builder refuse un texte nul là où le schéma n'admet pas null")
    void unTexteNulEstRefuse() {
        assertThrows(NullPointerException.class, () -> DefinitionBuilder.vehicle(null));
        assertThrows(NullPointerException.class, () -> DefinitionBuilder.vehicle("m:a.glb").repairRule(null));
        assertEquals(java.util.Collections.singletonMap("intact", null), ((Map<?, ?>) ((List<?>) DefinitionBuilder
                .vehicle("m:a.glb").part("p", p -> p.meshes(m -> m.intact(null))).build().root().get("parts"))
                .get(0)).get("meshes"));
    }
}
