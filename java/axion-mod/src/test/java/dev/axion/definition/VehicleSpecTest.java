package dev.axion.definition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.axion.asset.AssetSource;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-159, tranche 1 : les règles d'un véhicule, ses valeurs par défaut et sa section de {@code DEFN}. */
class VehicleSpecTest {

    private static final String MODELE = "mymod:axion/models/pickup.glb";
    private static final DefinitionRules REGLES = new DefinitionRules(true, Set.of(MODELE)::contains);

    private static String vehicule(String corps) {
        return "{\"schema\": 1, \"asset\": \"mymod:models/pickup.glb\", \"kind\": \"vehicle\", " + corps + "}";
    }

    private static Definition lire(String json) throws DefinitionException {
        return DefinitionRegistry.read(
                "mymod:axion/definitions/pickup.json", json.getBytes(StandardCharsets.UTF_8), REGLES);
    }

    private static String refus(String json) {
        return assertThrows(DefinitionException.class, () -> lire(json)).path();
    }

    private static final String ROUE = "\"wheels\": [{\"node\": \"a\", \"radius\": 0.3}]";

    static AssetSource source(Map<String, String> fichiers) {
        return new AssetSource() {
            @Override
            public List<String> list() {
                return List.copyOf(fichiers.keySet());
            }

            @Override
            public byte[] read(String path) throws IOException {
                return fichiers.get(path).getBytes(StandardCharsets.UTF_8);
            }
        };
    }

    @Test
    @DisplayName("ADR-159 D3 : des roues n'existent que sur un véhicule, et une roue déclare son rayon")
    void formeDeLaRoue() throws DefinitionException {
        lire(vehicule(ROUE));
        assertEquals("$.wheels", refus(vehicule(ROUE).replace("\"vehicle\"", "\"rigid_object\"")));
        assertEquals("$.wheels[0]", refus(vehicule("\"wheels\": [{\"node\": \"a\"}]")));
        assertEquals("$.wheels[0].radius", refus(vehicule("\"wheels\": [{\"node\": \"a\", \"radius\": 0}]")));
        assertEquals("$.wheels[0].suspension.max_travel", refus(vehicule(
                "\"wheels\": [{\"node\": \"a\", \"radius\": 0.3, \"suspension\": {\"max_travel\": 0}}]")));
    }

    @Test
    @DisplayName("R-1061 : un essieu compte deux roues, l'anti-roulis exige un essieu et s'accorde dessus")
    void essieux() throws DefinitionException {
        String anti = "\"suspension\": {\"anti_roll\": 0.5}";
        lire(vehicule("\"wheels\": [{\"node\": \"a\", \"radius\": 0.3, \"axle\": \"av\", " + anti + "},"
                + "{\"node\": \"b\", \"radius\": 0.3, \"axle\": \"av\", " + anti + "}]"));
        assertEquals("$.wheels[0].suspension.anti_roll",
                refus(vehicule("\"wheels\": [{\"node\": \"a\", \"radius\": 0.3, " + anti + "}]")));
        assertEquals("$.wheels[0].axle",
                refus(vehicule("\"wheels\": [{\"node\": \"a\", \"radius\": 0.3, \"axle\": \"av\"}]")));
        assertEquals("$.wheels[1].suspension.anti_roll", refus(vehicule(
                "\"wheels\": [{\"node\": \"a\", \"radius\": 0.3, \"axle\": \"av\", " + anti + "},"
                        + "{\"node\": \"b\", \"radius\": 0.3, \"axle\": \"av\"}]")));
    }

    @Test
    @DisplayName("ADR-159 D7 : le drapeau d'une roue ne contredit pas la liste de son bloc")
    void contradictions() throws DefinitionException {
        String roue = "\"wheels\": [{\"node\": \"a\", \"radius\": 0.3, \"steering\": true}]";
        lire(vehicule(roue + ", \"steering\": {\"steered_wheels\": [\"a\"]}"));
        assertEquals("$.wheels[0].steering", refus(vehicule(roue + ", \"steering\": {\"steered_wheels\": []}")));
    }

    private static final String MOTEUR = "\"engine\": {\"torque_curve\": [[0, 0], [1000, 180]]}";
    private static final String MOTRICE = "\"wheels\": [{\"node\": \"a\", \"radius\": 0.3, \"powered\": true}]";

    @Test
    @DisplayName("ADR-159 D6 : la courbe de couple, les rapports, le type de boîte et les roues motrices")
    void motopropulseur() throws DefinitionException {
        lire(vehicule(MOTRICE + ", \"powertrain\": {" + MOTEUR + "}"));
        assertEquals("$.powertrain.engine", refus(vehicule(MOTRICE + ", \"powertrain\": {}")));
        assertEquals("$.powertrain.engine.torque_curve[1]", refus(vehicule(
                MOTRICE + ", \"powertrain\": {\"engine\": {\"torque_curve\": [[10, 0], [10, 5]]}}")));
        assertEquals("$.powertrain", refus(vehicule(ROUE + ", \"powertrain\": {" + MOTEUR + "}")));
        String boite = MOTRICE + ", \"powertrain\": {" + MOTEUR + ", \"transmission\": ";
        assertEquals("$.powertrain.transmission.type", refus(vehicule(boite + "{\"type\": \"cvt\"}}")));
        assertEquals("$.powertrain.transmission", refus(vehicule(boite + "{\"type\": \"manual\"}}")));
        assertEquals("$.powertrain.transmission.gears",
                refus(vehicule(boite + "{\"gears\": [1.0, 2.0]}}")));
        lire(vehicule(boite + "{\"type\": \"direct\"}}"));
    }

    @Test
    @DisplayName("ADR-159 D7 : un angle en radians, une surface portante a un node et une aire")
    void directionEtAero() {
        assertEquals("$.steering.max_angle", refus(vehicule(ROUE + ", \"steering\": {\"max_angle\": 2.0}")));
        assertEquals("$.aero.surfaces[0]", refus(vehicule(ROUE + ", \"aero\": {\"surfaces\": [{\"area\": 1.0}]}")));
    }

    private static ByteBuffer section(String json, TireProfiles pneus) throws DefinitionException {
        Definition definition = lire(json);
        byte[] octets = DefinitionEncoder.encode(
                definition, true, CollisionGroupRegistry.empty(), nom -> 0, pneus).bytes();
        assertEquals(DefinitionEncoder.FLAG_VEHICLE,
                ByteBuffer.wrap(octets).order(ByteOrder.LITTLE_ENDIAN).getShort(6) & DefinitionEncoder.FLAG_VEHICLE);
        int debut = octets.length - VehicleSpec.size(definition.root());
        return ByteBuffer.wrap(octets, debut, octets.length - debut).slice().order(ByteOrder.LITTLE_ENDIAN);
    }

    @Test
    @DisplayName("ADR-159 : sans bloc, les défauts de 12.3 et 12.6, le pneu générique, une boîte directe")
    void defauts() throws DefinitionException {
        ByteBuffer in = section(vehicule(ROUE), TireProfiles.empty());
        assertEquals(VehicleSpec.HEADER_BYTES + VehicleSpec.WHEEL_BYTES, in.remaining());
        assertEquals(1, in.getShort(0), "roues");
        assertEquals(0, in.getShort(8), "ni moteur ni ABS");
        assertEquals(2, in.get(10), "transmission directe");
        assertEquals(0.61f, in.getFloat(12 + 12 * 4), "angle maximal");
        assertEquals(3000f, in.getFloat(12 + 17 * 4), "frein avant");
        assertEquals(VehicleSpec.TRACTION_SLIP_THRESHOLD, in.getFloat(12 + 25 * 4), "seuil de l'antipatinage");
        assertEquals(VehicleSpec.STABILITY_YAW_TOLERANCE, in.getFloat(12 + 26 * 4), "tolérance de la stabilité");
        int roue = VehicleSpec.HEADER_BYTES;
        assertEquals(0, in.getInt(roue), "la seule référence : le node de la roue");
        assertEquals(4, in.getShort(roue + 4), "le frein à main serre toutes les roues");
        assertEquals((short) 0xFFFF, in.getShort(roue + 6), "sans essieu");
        assertEquals(0.3f, in.getFloat(roue + 8), "rayon");
        assertEquals(30f, in.getFloat(roue + 8 + 5 * 4), "raideur de 12.3");
        assertEquals(TireProfiles.GENERIC.muLong(), in.getFloat(roue + 48));
    }

    @Test
    @DisplayName("ADR-159 : le profil du pneu nommé est recopié dans sa roue, l'essieu et les listes dans ses drapeaux")
    void roueComplete() throws DefinitionException {
        TireProfiles pneus = TireProfiles.load(source(Map.of("mymod:axion/tires/glace.json", TireProfilesTest.GLACE)));
        ByteBuffer in = section(vehicule("\"wheels\": ["
                + "{\"node\": \"a\", \"radius\": 0.3, \"tire\": \"mymod:tire/glace\", \"axle\": \"av\"},"
                + "{\"node\": \"b\", \"radius\": 0.3, \"axle\": \"av\"}],"
                + "\"steering\": {\"steered_wheels\": [\"a\"]},"
                + "\"brakes\": {\"handbrake_wheels\": [\"b\"], \"abs\": {\"enabled\": true}},"
                + "\"powertrain\": {" + MOTEUR + ", \"drive_wheels\": [\"b\"],"
                + "\"differential\": {\"type\": \"locked\"},"
                + "\"transmission\": {\"type\": \"automatic\", \"gears\": [-3, 0, 2, 1]}}"), pneus);
        assertEquals(2, in.getShort(2), "points de couple");
        assertEquals(4, in.getShort(4), "rapports");
        assertEquals(3, in.getShort(8), "moteur et ABS");
        assertEquals(1, in.get(10), "automatique");
        assertEquals(1, in.get(11), "bloqué");
        int a = VehicleSpec.HEADER_BYTES;
        int b = a + VehicleSpec.WHEEL_BYTES;
        assertEquals(1, in.getShort(a + 4), "a braque, sans frein à main");
        assertEquals(6, in.getShort(b + 4), "b entraîne et serre le frein à main");
        assertEquals(in.getShort(a + 6), in.getShort(b + 6), "le même essieu");
        assertEquals(0.2f, in.getFloat(a + 48), "mu_long du pneu nommé");
        assertEquals(TireProfiles.GENERIC.muLong(), in.getFloat(b + 48), "sans nom, le générique");
        int courbe = b + VehicleSpec.WHEEL_BYTES;
        assertEquals(1000f, in.getFloat(courbe + 8), "second point de couple");
        assertEquals(-3f, in.getFloat(courbe + 16), "premier rapport");
    }

    @Test
    @DisplayName("ADR-182 D1 : au schéma 8, la section porte les réserves, leurs rangs, la consommation et le démarreur")
    void reservesDansLaSection() throws DefinitionException {
        ByteBuffer in = section("{\"schema\": 8, \"asset\": \"mymod:models/pickup.glb\", \"kind\": \"vehicle\","
                + " \"physics\": {\"mass\": 1000}, " + MOTRICE + ","
                + " \"variables\": {\"contact\": {\"type\": \"bool\", \"default\": false, \"replicate\": \"all\"}},"
                + " \"energy\": {\"stores\": [{\"name\": \"auxiliaire\", \"capacity\": 2},"
                + " {\"name\": \"gazole\", \"carrier\": \"axion:diesel\", \"capacity\": 60}]},"
                + " \"powertrain\": {\"engine\": {\"torque_curve\": [[0, 0], [1000, 180]],"
                + " \"fuel\": {\"store\": \"gazole\", \"consumption\": [[0, 0.5], [1, 9]]},"
                + " \"starter\": {\"time\": 1, \"variable\": \"contact\", \"store\": \"auxiliaire\","
                + " \"draw\": 0.25, \"charge_rate\": 0.125}}}}", TireProfiles.empty());
        assertEquals(2, in.getShort(120), "points de consommation");
        assertEquals(1, in.get(122), "le moteur brûle la réserve de rang 1");
        assertEquals(0, in.get(123), "le démarreur tire celle de rang 0");
        float[] capacites = {2f, 60f, 0f, 0f};
        for (int rang = 0; rang < capacites.length; rang++) {
            assertEquals(capacites[rang], in.getFloat(124 + rang * 4), "capacité du rang " + rang);
        }
        assertEquals(0.25f, in.getFloat(140), "draw, le tirage du démarreur");
        assertEquals(0.125f, in.getFloat(144), "charge_rate");
        int points = VehicleSpec.HEADER_BYTES + VehicleSpec.WHEEL_BYTES + 2 * 8;
        assertEquals(0.5f, in.getFloat(points + 4), "la consommation est celle d'engine.fuel");
        assertEquals(9f, in.getFloat(points + 12));
    }

    private static String electrique(String moteurs, String groupe) {
        return "{\"schema\": 8, \"asset\": \"mymod:models/pickup.glb\", \"kind\": \"vehicle\","
                + " \"wheels\": [{\"node\": \"a\", \"radius\": 0.3}, {\"node\": \"b\", \"radius\": 0.3,"
                + " \"powered\": true}], \"energy\": {\"stores\": [{\"name\": \"traction\", \"capacity\": 40}]},"
                + " \"powertrain\": {" + groupe + "\"electric_motors\": [" + moteurs + "]}}";
    }

    private static final String ARRIERE = "{\"name\": \"arriere\", \"store\": \"traction\","
            + " \"torque_curve\": [[0, 150], [9000, 60]], \"max_rpm\": 9000, \"regen\": {\"lift_off\": 20,"
            + " \"brake\": 60}, \"coupling\": {\"wheels\": [\"b\"], \"ratio\": 8}}";

    @Test
    @DisplayName("ADR-182 D3 : sans moteur à combustion, la section le dit et porte chaque moteur, ses défauts compris")
    void moteursElectriquesDansLaSection() throws DefinitionException {
        ByteBuffer in = section(electrique(ARRIERE, ""), TireProfiles.empty());
        assertEquals(1 | 16, in.getShort(8) & 0xFFFF, "groupe motopropulseur, sans moteur à combustion");
        assertEquals(1, in.getShort(148), "un moteur");
        assertEquals(0, in.getShort(150), "réservé");
        int moteur = VehicleSpec.HEADER_BYTES + 2 * VehicleSpec.WHEEL_BYTES;
        assertEquals(0, in.get(moteur), "sa réserve");
        assertEquals(1, in.get(moteur + 1), "couplé à des roues");
        assertEquals(2, in.getShort(moteur + 2), "points de couple");
        assertEquals(0b10, in.getInt(moteur + 4), "la roue de rang 1");
        float[] attendus = {9000f, VehicleSpec.MOTOR_EFFICIENCY, 8f, 20f, 60f, VehicleSpec.REGEN_EFFICIENCY};
        for (int i = 0; i < attendus.length; i++) {
            assertEquals(attendus[i], in.getFloat(moteur + 8 + 4 * i), "champ " + i);
        }
        assertEquals(60f, in.getFloat(moteur + VehicleSpec.MOTOR_BYTES + 12), "second point de la courbe");
        assertEquals(moteur + VehicleSpec.MOTOR_BYTES + 16, in.limit());

        String boite = ARRIERE.replace(", \"coupling\": {\"wheels\": [\"b\"], \"ratio\": 8}", "")
                .replace(", \"regen\": {\"lift_off\": 20, \"brake\": 60}", "");
        ByteBuffer couple = section(electrique(boite, ""), TireProfiles.empty());
        assertEquals(0, couple.get(moteur + 1), "couplé à la boîte");
        assertEquals(0f, couple.getFloat(moteur + 8 + 4 * 5), "sans regen, rien ne se récupère");
    }

    @Test
    @DisplayName("ADR-182 D3 : les refus d'un moteur électrique, chacun à son chemin")
    void refusDesMoteursElectriques() throws DefinitionException {
        lire(electrique(ARRIERE, ""));
        assertEquals("$.powertrain.electric_motors[0].torque_curve",
                refus(electrique(ARRIERE.replace(" \"torque_curve\": [[0, 150], [9000, 60]],", ""), "")));
        assertEquals("$.powertrain.electric_motors[0].torque_curve[1]",
                refus(electrique(ARRIERE.replace("[9000, 60]", "[0, 60]"), "")));
        assertEquals("$.powertrain.electric_motors[0].efficiency",
                refus(electrique(ARRIERE.replace("\"max_rpm\": 9000,", "\"max_rpm\": 9000, \"efficiency\": 1.2,"), "")));
        assertEquals("$.powertrain.electric_motors[0].coupling.wheels",
                refus(electrique(ARRIERE.replace("[\"b\"]", "[\"b\", \"b\"]"), "")));
        assertEquals("$.powertrain.electric_motors[0].regen.brake",
                refus(electrique(ARRIERE.replace("\"brake\": 60", "\"brake\": -1"), "")));
        // Un moteur à combustion, ou un moteur couplé à la boîte, exigent des roues motrices.
        String sansMotrice = electrique(ARRIERE.replace(", \"coupling\": {\"wheels\": [\"b\"], \"ratio\": 8}", ""), "")
                .replace(", \"powered\": true", "");
        assertEquals("$.powertrain", refus(sansMotrice));
        lire(electrique(ARRIERE, "").replace(", \"powered\": true", ""));
        // Le carburant ne nourrit pas un moteur électrique.
        String hybride = electrique(ARRIERE, "\"engine\": {\"torque_curve\": [[0, 0], [1000, 180]],"
                + " \"fuel\": {\"store\": \"traction\", \"consumption\": [[0, 1]]}}, ");
        assertEquals("$.powertrain.electric_motors[0].store", refus(hybride));
        assertEquals("$.powertrain.electric_motors",
                refus(electrique(String.join(", ", java.util.stream.IntStream.range(0, 5)
                        .mapToObj(i -> ARRIERE.replace("\"arriere\"", "\"m" + i + "\"")).toList()), "")));
        assertThrows(DefinitionException.class,
                () -> lire(electrique(ARRIERE, "").replace("\"schema\": 8", "\"schema\": 7")));
    }

    @Test
    @DisplayName("ADR-182 D3 : la variable d'un moteur est un booléen de l'assembly ; sans elle, il est toujours autorisé")
    void variableDUnMoteur() throws DefinitionException {
        String avec = electrique(ARRIERE.replace("\"store\": \"traction\",", "\"store\": \"traction\","
                + " \"variable\": \"marche\","), "").replace("\"schema\": 8,", "\"schema\": 8, \"variables\":"
                + " {\"marche\": {\"type\": \"bool\", \"default\": false, \"persist\": true, \"replicate\": \"all\"}},");
        Definition definition = lire(avec);
        List<Integer> rangs = ElectricMotors.variables(definition.root(), Variables.of(definition.root()));
        assertEquals(List.of(0), rangs);
        assertEquals(0, ElectricMotors.enabled(rangs, rang -> 0.0));
        assertEquals(1, ElectricMotors.enabled(rangs, rang -> 1.0));
        assertEquals(1, ElectricMotors.enabled(List.of(-1), rang -> 0.0), "sans variable");
        assertEquals("$.powertrain.electric_motors[0].variable",
                refus(avec.replace("\"type\": \"bool\", \"default\": false", "\"type\": \"int\", \"default\": 0")));
    }

    @Test
    @DisplayName("R-1090, ADR-159 tranche 3 : antipatinage et stabilité, désactivés par défaut, actifs par leur drapeau")
    void correcteurs() throws DefinitionException {
        ByteBuffer in = section(vehicule(MOTRICE + ", \"powertrain\": {" + MOTEUR
                + ", \"traction_control\": {\"enabled\": true, \"slip_threshold\": 0.3}},"
                + "\"brakes\": {\"stability_control\": {\"enabled\": true, \"yaw_tolerance\": 0.25}}"), TireProfiles.empty());
        assertEquals(1 | 4 | 8, in.getShort(8), "moteur, antipatinage, stabilité ; pas d'ABS");
        assertEquals(0.3f, in.getFloat(12 + 25 * 4));
        assertEquals(0.25f, in.getFloat(12 + 26 * 4));
        assertEquals("$.powertrain.traction_control.slip_threshold", refus(vehicule(MOTRICE + ", \"powertrain\": {" + MOTEUR
                + ", \"traction_control\": {\"slip_threshold\": 0}}")));
        assertEquals("$.brakes.stability_control.yaw_tolerance", refus(vehicule(ROUE
                + ", \"brakes\": {\"stability_control\": {\"yaw_tolerance\": -1}}")));
    }

    @Test
    @DisplayName("ADR-159 : la roue est une référence au node, que le natif résout")
    void reference() throws DefinitionException {
        List<DefinitionChecker.AssetReference> references = DefinitionChecker.assetReferences(lire(vehicule(ROUE)).root());
        assertEquals(new DefinitionChecker.AssetReference(NameCategory.NODE, "a", "$.wheels[0].node"), references.get(0));
    }

    @Test
    @DisplayName("ADR-159 Q1 : un pneu non déclaré avertit, sans refuser la definition")
    void pneuInconnu() {
        String json = vehicule("\"wheels\": [{\"node\": \"a\", \"radius\": 0.3, \"tire\": \"mymod:tire/route\"}]");
        DefinitionRegistry sans = DefinitionRegistry.load(
                source(Map.of("mymod:axion/definitions/pickup.json", json)), REGLES);
        assertEquals(1, sans.size());
        assertEquals(1, sans.warnings().size(), sans.warnings().toString());
        assertTrue(sans.warnings().get(0).contains("mymod:tire/route"));

        TireProfiles pneus = TireProfiles.load(source(Map.of("mymod:axion/tires/route.json", TireProfilesTest.GLACE)));
        DefinitionRegistry avec = DefinitionRegistry.load(
                source(Map.of("mymod:axion/definitions/pickup.json", json)),
                new DefinitionRules(true, Set.of(MODELE)::contains, false, CollisionGroupRegistry.empty(),
                        PhysicsMaterials.empty(), pneus));
        assertTrue(avec.warnings().isEmpty(), avec.warnings().toString());
        assertFalse(avec.tires().ids().isEmpty());
    }
}
