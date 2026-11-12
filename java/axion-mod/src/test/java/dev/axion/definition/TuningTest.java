package dev.axion.definition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.axion.api.assembly.Parameters;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-167 D1, D2 : les paramètres réglables d'une definition, leurs bases, leurs limites et leurs plages. */
class TuningTest {

    private static final String MODELE = "mymod:axion/models/pickup.glb";
    private static final DefinitionRules REGLES = new DefinitionRules(true, Set.of(MODELE)::contains);

    private static final String BOITE = "\"powertrain\": {\"engine\": {\"torque_curve\": [[0, 100], [3000, 260],"
            + " [6000, 200]], \"idle_rpm\": 900, \"max_rpm\": 6500}, \"transmission\": {\"type\": \"automatic\","
            + " \"gears\": [-3.1, 0, 3.4, 2.0, 1.3], \"final_drive\": 3.9}}";

    private static final String VEHICULE = "\"physics\": {\"mass\": 1200}, \"wheels\": [{\"node\": \"a\","
            + " \"radius\": 0.3, \"powered\": true}], " + BOITE + ", \"brakes\": {\"max_torque_front\": 2500},"
            + " \"aero\": {\"downforce_rear\": 150}";

    private static Definition lire(int schema, String corps) throws DefinitionException {
        String json = "{\"schema\": " + schema + ", \"asset\": \"mymod:models/pickup.glb\", \"kind\": \"vehicle\", "
                + corps + "}";
        return DefinitionRegistry.read(
                "mymod:axion/definitions/pickup.json", json.getBytes(StandardCharsets.UTF_8), REGLES);
    }

    private static String refus(int schema, String corps) {
        DefinitionException refus = assertThrows(DefinitionException.class, () -> lire(schema, corps));
        return refus.path() + " : " + refus.getMessage();
    }

    @Test
    @DisplayName("D1 : un paramètre par grandeur déclarée, dans l'ordre fixe, un rapport par rang hors du point mort")
    void parametresDUnVehicule() throws DefinitionException {
        Tuning tuning = Tuning.of(lire(4, VEHICULE).root());
        assertEquals(List.of(Parameters.MASS, Parameters.ENGINE_TORQUE, Parameters.ENGINE_MAX_RPM,
                Parameters.FINAL_DRIVE, Parameters.SHIFT_TIME, Parameters.gear(-1), Parameters.gear(1),
                Parameters.gear(2), Parameters.gear(3), Parameters.BRAKES_FRONT, Parameters.BRAKES_REAR,
                Parameters.BRAKES_HANDBRAKE, Parameters.TIRES_GRIP, Parameters.SUSPENSION_STIFFNESS,
                Parameters.SUSPENSION_DAMPING, Parameters.STEERING_MAX_ANGLE, Parameters.AERO_DRAG,
                Parameters.AERO_DOWNFORCE_FRONT, Parameters.AERO_DOWNFORCE_REAR), tuning.names());
        assertEquals(1200, base(tuning, Parameters.MASS));
        assertEquals(260, base(tuning, Parameters.ENGINE_TORQUE), "le pic de la courbe");
        assertEquals(-3.1f, base(tuning, Parameters.gear(-1)));
        assertEquals(1.3f, base(tuning, Parameters.gear(3)));
        assertEquals(1, base(tuning, Parameters.TIRES_GRIP), "un facteur part de 1");
        assertEquals(1800, base(tuning, Parameters.BRAKES_REAR), "le défaut de 12.6");
    }

    @Test
    @DisplayName("D1 : sans masse déclarée ni moteur, ni mass ni moteur ; une boîte directe n'a ni rapports ni passage")
    void ceQuiNExistePas() throws DefinitionException {
        Tuning sansMoteur = Tuning.of(lire(4, "\"wheels\": [{\"node\": \"a\", \"radius\": 0.3}]").root());
        assertEquals(-1, sansMoteur.indexOf(Parameters.MASS));
        assertEquals(-1, sansMoteur.indexOf(Parameters.ENGINE_TORQUE));
        assertTrue(sansMoteur.indexOf(Parameters.BRAKES_FRONT) >= 0, "les freins ont leurs défauts");
        Tuning direct = Tuning.of(lire(4, "\"wheels\": [{\"node\": \"a\", \"radius\": 0.3, \"powered\": true}],"
                + " \"powertrain\": {\"engine\": {\"torque_curve\": [[0, 50], [1000, 80]]}}").root());
        assertEquals(1f, base(direct, Parameters.FINAL_DRIVE), "le pont d'une boîte directe vaut 1 par défaut");
        assertEquals(-1, direct.indexOf(Parameters.SHIFT_TIME));
        assertFalse(direct.names().stream().anyMatch(name -> name.startsWith("transmission.gear.")));
        assertEquals(Tuning.NONE, Tuning.of(lire(4, "\"physics\": {}").root()));
    }

    @Test
    @DisplayName("D1 : les bases sont celles que la section DEFN du natif reçoit, défauts compris")
    void basesEgalesALaSectionEncodee() throws DefinitionException {
        Definition definition = lire(4, VEHICULE);
        Tuning tuning = Tuning.of(definition.root());
        byte[] octets = DefinitionEncoder.encode(
                definition, true, CollisionGroupRegistry.empty(), nom -> 0, TireProfiles.empty()).bytes();
        int debut = octets.length - VehicleSpec.size(definition.root());
        ByteBuffer in = ByteBuffer.wrap(octets, debut, octets.length - debut).slice().order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(in.getFloat(12 + 4), (float) base(tuning, Parameters.ENGINE_MAX_RPM));
        assertEquals(in.getFloat(12 + 4 * 4), (float) base(tuning, Parameters.FINAL_DRIVE));
        assertEquals(in.getFloat(12 + 5 * 4), (float) base(tuning, Parameters.SHIFT_TIME));
        assertEquals(in.getFloat(12 + 11 * 4), (float) base(tuning, Parameters.STEERING_MAX_ANGLE));
        assertEquals(in.getFloat(12 + 16 * 4), (float) base(tuning, Parameters.BRAKES_FRONT));
        assertEquals(in.getFloat(12 + 17 * 4), (float) base(tuning, Parameters.BRAKES_REAR));
        assertEquals(in.getFloat(12 + 18 * 4), (float) base(tuning, Parameters.BRAKES_HANDBRAKE));
        assertEquals(in.getFloat(12 + 20 * 4), (float) base(tuning, Parameters.AERO_DRAG));
        assertEquals(in.getFloat(12 + 22 * 4), (float) base(tuning, Parameters.AERO_DOWNFORCE_FRONT));
        assertEquals(in.getFloat(12 + 23 * 4), (float) base(tuning, Parameters.AERO_DOWNFORCE_REAR));
        int rapports = VehicleSpec.HEADER_BYTES + VehicleSpec.WHEEL_BYTES + 3 * 8;
        assertEquals(in.getFloat(rapports), (float) base(tuning, Parameters.gear(-1)));
        assertEquals(in.getFloat(rapports + 2 * 4), (float) base(tuning, Parameters.gear(1)));
    }

    @Test
    @DisplayName("D2 : les limites du schéma, et la plage de la definition qui les resserre")
    void bornes() throws DefinitionException {
        Tuning tuning = Tuning.of(lire(4, VEHICULE + ", \"tuning\": {\"ranges\": {\"engine.torque\": {\"min\": 200,"
                + " \"max\": 420}}}").root());
        Tuning.Parameter couple = tuning.get(tuning.indexOf(Parameters.ENGINE_TORQUE));
        assertNull(couple.refusal(420));
        assertTrue(couple.refusal(421).contains("plage de la definition"), couple.refusal(421));
        assertTrue(couple.refusal(199).contains("plage de la definition"));
        Tuning.Parameter regime = tuning.get(tuning.indexOf(Parameters.ENGINE_MAX_RPM));
        assertTrue(regime.refusal(900).contains("limite du schéma"), "le limiteur reste au-dessus du ralenti");
        assertNull(regime.refusal(900.5));
        Tuning.Parameter marcheArriere = tuning.get(tuning.indexOf(Parameters.gear(-1)));
        assertTrue(marcheArriere.refusal(0.5) != null, "un rapport garde son signe");
        Tuning.Parameter braquage = tuning.get(tuning.indexOf(Parameters.STEERING_MAX_ANGLE));
        assertTrue(braquage.refusal(Math.PI / 2) != null, "sous π/2");
        Tuning.Parameter appui = tuning.get(tuning.indexOf(Parameters.AERO_DOWNFORCE_FRONT));
        assertNull(appui.refusal(-50), "un appui négatif est une portance, que le schéma admet");
        assertTrue(appui.refusal(1e39) != null, "au-delà d'un f32");
        assertEquals(new Parameters.Range(200, true, 420, true), couple.bounds());
    }

    @Test
    @DisplayName("D2 : tuning.ranges nomme un paramètre de la definition, contient sa base ; le schéma 3 ne l'a pas")
    void plagesVerifiees() {
        assertTrue(refus(4, VEHICULE + ", \"tuning\": {\"ranges\": {\"engine.turbo\": {\"max\": 2}}}")
                .startsWith("$.tuning.ranges.engine.turbo"));
        assertTrue(refus(4, VEHICULE + ", \"tuning\": {\"ranges\": {\"mass\": {\"min\": 1500}}}")
                .contains("doit contenir la base"));
        assertTrue(refus(4, VEHICULE + ", \"tuning\": {\"ranges\": {\"mass\": {\"min\": 1300, \"max\": 1100}}}")
                .contains("min dépasse max"));
        assertTrue(refus(3, VEHICULE + ", \"tuning\": {}").startsWith("$.tuning"));
    }

    private static double base(Tuning tuning, String name) {
        int index = tuning.indexOf(name);
        assertTrue(index >= 0, () -> name + " absent de " + tuning.names());
        return tuning.get(index).base();
    }
}
