package dev.axion.definition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.axion.api.assembly.Modifier;
import dev.axion.api.assembly.Parameters;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ADR-167 D1, D2, D4 : les paramètres réglables d'une definition, leurs bases, leurs limites, leurs plages, et les
 * modificateurs que ses variables déclarent.
 */
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
        Tuning tuning = Tuning.of("mymod:essai", lire(4, VEHICULE).root());
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
        Tuning sansMoteur = Tuning.of("mymod:essai", lire(4, "\"wheels\": [{\"node\": \"a\", \"radius\": 0.3}]").root());
        assertEquals(-1, sansMoteur.indexOf(Parameters.MASS));
        assertEquals(-1, sansMoteur.indexOf(Parameters.ENGINE_TORQUE));
        assertTrue(sansMoteur.indexOf(Parameters.BRAKES_FRONT) >= 0, "les freins ont leurs défauts");
        Tuning direct = Tuning.of("mymod:essai", lire(4, "\"wheels\": [{\"node\": \"a\", \"radius\": 0.3, \"powered\": true}],"
                + " \"powertrain\": {\"engine\": {\"torque_curve\": [[0, 50], [1000, 80]]}}").root());
        assertEquals(1f, base(direct, Parameters.FINAL_DRIVE), "le pont d'une boîte directe vaut 1 par défaut");
        assertEquals(-1, direct.indexOf(Parameters.SHIFT_TIME));
        assertFalse(direct.names().stream().anyMatch(name -> name.startsWith("transmission.gear.")));
        assertEquals(Tuning.NONE, Tuning.of("mymod:essai", lire(4, "\"physics\": {}").root()));
    }

    @Test
    @DisplayName("D1 : les bases sont celles que la section DEFN du natif reçoit, défauts compris")
    void basesEgalesALaSectionEncodee() throws DefinitionException {
        Definition definition = lire(4, VEHICULE);
        Tuning tuning = Tuning.of("mymod:essai", definition.root());
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
        Tuning tuning = Tuning.of("mymod:essai", lire(4, VEHICULE + ", \"tuning\": {\"ranges\": {\"engine.torque\": {\"min\": 200,"
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

    /** Quatre variables : un booléen, un entier de 0 à 2, un réel borné, et un booléen que seul le conducteur reçoit. */
    private static final String VARIABLES = ", \"variables\": {"
            + "\"sport\": {\"type\": \"bool\", \"default\": false, \"replicate\": \"all\"},"
            + " \"lest\": {\"type\": \"int\", \"default\": 0, \"min\": 0, \"max\": 2, \"replicate\": \"all\"},"
            + " \"pression\": {\"type\": \"float\", \"default\": 1, \"min\": 0.5, \"max\": 1.5,"
            + " \"replicate\": \"all\"},"
            + " \"secret\": {\"type\": \"bool\", \"default\": false, \"replicate\": \"driver\"}}";

    private static String modificateur(String nom, String parametre, String operation, String variable, String quantite) {
        return "\"" + nom + "\": {\"parameter\": \"" + parametre + "\", \"operation\": \"" + operation
                + "\", \"variable\": \"" + variable + "\"" + quantite + "}";
    }

    private static String avec(String... modificateurs) {
        return VEHICULE + VARIABLES + ", \"tuning\": {\"modifiers\": {" + String.join(", ", modificateurs) + "}}";
    }

    @Test
    @DisplayName("D4 : une variable règle un paramètre ; cause <id>/<nom>, quantité tirée de la valeur arrondie en f32")
    void modificateursDeclares() throws DefinitionException {
        Tuning tuning = Tuning.of("mymod:pickup", lire(4, avec(
                modificateur("sport", "engine.torque", "multiply_base", "sport", ", \"values\": [0, 0.3]"),
                modificateur("lest", "mass", "add", "lest", ", \"values\": [0, 50, 100]"),
                modificateur("pneus", "tires.grip", "multiply_total", "pression", ", \"scale\": 0.1"))).root());
        assertEquals(3, tuning.declared().size());
        Tuning.Declared sport = tuning.declared().get(0);
        assertEquals("mymod:pickup/sport", sport.cause());
        assertEquals(0.0, sport.amount(0.0));
        assertEquals(0.3, sport.amount(1.0));
        assertEquals(100.0, tuning.declared().get(1).amount(2.0), "values part du minimum de la variable");
        assertEquals(new Modifier("mymod:pickup/lest", Parameters.MASS, Modifier.Operation.ADD, 50.0),
                tuning.declared().get(1).modifier(1.0));
        assertEquals(0.1 * (float) 1.1, tuning.declared().get(2).amount(1.1),
                "la valeur arrondie en f32, celle que le client reçoit");
        assertEquals(1.0, tuning.declared().get(2).initial(), "la valeur par défaut de la variable");
    }

    @Test
    @DisplayName("D4, Q5 : refusés — paramètre absent, variable qui ne part pas à tous, values ou scale mal donnés,"
            + " quantité qui sort seule des bornes, déclarés hors bornes ensemble à leurs valeurs par défaut")
    void modificateursRefuses() {
        assertTrue(refus(4, avec(modificateur("m", "engine.turbo", "add", "sport", ", \"values\": [0, 1]")))
                .contains("inconnu de cette definition"));
        String prive = refus(4, avec(modificateur("m", "mass", "add", "secret", ", \"values\": [0, 1]")));
        assertTrue(prive.startsWith("$.tuning.modifiers.m") && prive.contains("replicate: all"), prive);
        assertTrue(refus(4, avec(modificateur("m", "mass", "add", "sport", ""))).contains("values ou scale"));
        assertTrue(refus(4, avec(modificateur("m", "mass", "add", "sport", ", \"values\": [0, 1], \"scale\": 2")))
                .contains("values ou scale"));
        assertTrue(refus(4, avec(modificateur("m", "mass", "add", "lest", ", \"values\": [0, 1]")))
                .contains("2 quantité(s) pour 3"));
        assertTrue(refus(4, avec(modificateur("m", "mass", "add", "pression", ", \"values\": [0, 1]")))
                .contains("float"));
        String seule = refus(4, avec(modificateur("m", "mass", "add", "sport", ", \"values\": [0, -1300]")));
        assertTrue(seule.contains("seule") && seule.contains("mass"), seule);
        assertTrue(refus(4, avec(modificateur("m", "mass", "add", "absente", ", \"values\": [0, 1]")))
                .startsWith("$.tuning.modifiers.m.variable"), "une variable que la definition ne déclare pas");
        assertTrue(refus(4, avec(modificateur("Turbo", "mass", "add", "sport", ", \"values\": [0, 1]")))
                .contains("minuscules"));
        // Seul, chacun laisse 500 kg ; ensemble, à la valeur par défaut de « lest », -200 kg.
        String ensemble = refus(4, avec(
                modificateur("a", "mass", "add", "lest", ", \"values\": [-700, 0, 0]"),
                modificateur("b", "mass", "add", "lest", ", \"values\": [-700, 0, 0]")));
        assertTrue(ensemble.contains("valeurs par défaut"), ensemble);
    }

    /** Un mod « turbo » qui enregistre un contrôleur et deux paramètres ; un mod « autre », un contrôleur sans. */
    private static final VehicleExtensions EXTENSIONS = new VehicleExtensions(
            java.util.Map.of(
                    "turbo:gestion", new VehicleExtensions.Controller("turbo:gestion", "turbo", (vue, sortie) -> { }),
                    "autre:abs", new VehicleExtensions.Controller("autre:abs", "autre", (vue, sortie) -> { })),
            java.util.Map.of(
                    "turbo:pression_max", new VehicleExtensions.Parameter("turbo:pression_max", "turbo", "bar", 1.2,
                            new Parameters.Range(0, true, 3, true)),
                    "turbo:delai", new VehicleExtensions.Parameter("turbo:delai", "turbo", "s", 0.4,
                            new Parameters.Range(0, true, 2, true))));

    private static final DefinitionRules AVEC_CONTROLEURS = new DefinitionRules(true, Set.of(MODELE)::contains, false,
            CollisionGroupRegistry.empty(), PhysicsMaterials.empty(), TireProfiles.empty(), EXTENSIONS);

    private static Definition lireAvec(String corps) throws DefinitionException {
        String json = "{\"schema\": 4, \"asset\": \"mymod:models/pickup.glb\", \"kind\": \"vehicle\", " + corps + "}";
        return DefinitionRegistry.read(
                "mymod:axion/definitions/pickup.json", json.getBytes(StandardCharsets.UTF_8), AVEC_CONTROLEURS);
    }

    private static String refusAvec(String corps) {
        DefinitionException refus = assertThrows(DefinitionException.class, () -> lireAvec(corps));
        return refus.path() + " : " + refus.getMessage();
    }

    @Test
    @DisplayName("D9, Q6 : les contrôleurs de la fiche, et les paramètres de leurs mods après ceux d'AXION, triés")
    void controleursDeLaFiche() throws DefinitionException {
        Definition definition = lireAvec(VEHICULE + ", \"vehicle\": {\"controllers\": [\"turbo:gestion\","
                + " \"autre:abs\"]}, \"tuning\": {\"ranges\": {\"turbo:pression_max\": {\"max\": 2}}}");
        Tuning tuning = Tuning.of("mymod:pickup", definition.root(), EXTENSIONS);
        assertEquals(List.of("turbo:gestion", "autre:abs"), tuning.controllers());
        List<String> noms = tuning.names();
        assertEquals(List.of("turbo:delai", "turbo:pression_max"), noms.subList(noms.size() - 2, noms.size()),
                "après ceux d'AXION, triés par nom ; le mod « autre » n'en enregistre pas");
        Tuning.Parameter pression = tuning.get(tuning.indexOf("turbo:pression_max"));
        assertEquals(1.2, pression.base());
        assertEquals(2.0, pression.bounds().max(), "la plage de la fiche borne aussi un paramètre d'addon");
        assertTrue(pression.refusal(3.0) != null);
        assertEquals(List.of(), Tuning.of("mymod:pickup", lire(4, VEHICULE).root(), EXTENSIONS).controllers());
    }

    @Test
    @DisplayName("Q6 : un contrôleur attaché par l'API ajoute les paramètres de son mod, sans doubler ceux déjà là")
    void parametresDUnControleurAttache() throws DefinitionException {
        Tuning sans = Tuning.of("mymod:pickup", lire(4, VEHICULE).root(), EXTENSIONS);
        Tuning avec = sans.with(EXTENSIONS.parametersFor(List.of("turbo:gestion")));
        assertEquals(sans.size() + 2, avec.size());
        assertEquals(sans.names(), avec.names().subList(0, sans.size()), "ceux d'AXION gardent leur rang");
        assertTrue(avec == avec.with(EXTENSIONS.parametersFor(List.of("turbo:gestion"))), "rien de neuf : la même");
        assertTrue(sans == sans.with(EXTENSIONS.parametersFor(List.of("autre:abs"))));
        assertEquals(List.of(), EXTENSIONS.parametersFor(List.of("inconnu:x")));
    }

    @Test
    @DisplayName("D9 : refusés — contrôleur qu'aucun mod n'enregistre, nommé deux fois, ou sans roues ; le paramètre"
            + " d'un addon dont aucun contrôleur n'est nommé")
    void controleursRefuses() {
        String inconnu = refusAvec(VEHICULE + ", \"vehicle\": {\"controllers\": [\"turbo:gestion\", \"x:y\"]}");
        assertTrue(inconnu.startsWith("$.vehicle.controllers[1]") && inconnu.contains("aucun mod"), inconnu);
        String double_ = refusAvec(VEHICULE + ", \"vehicle\": {\"controllers\": [\"turbo:gestion\","
                + " \"turbo:gestion\"]}");
        assertTrue(double_.startsWith("$.vehicle.controllers[1]") && double_.contains("déjà nommé"), double_);
        String sansRoues = refusAvec("\"physics\": {\"mass\": 10}, \"vehicle\": {\"controllers\":"
                + " [\"turbo:gestion\"]}");
        assertTrue(sansRoues.contains("des roues"), sansRoues);
        String plage = refusAvec(VEHICULE + ", \"tuning\": {\"ranges\": {\"turbo:pression_max\": {\"max\": 2}}}");
        assertTrue(plage.contains("inconnu de cette definition"), plage);
        String sansRegistre = refus(4, VEHICULE + ", \"vehicle\": {\"controllers\": [\"turbo:gestion\"]}");
        assertTrue(sansRegistre.contains("aucun mod"), "sans registre, tout contrôleur est inconnu : " + sansRegistre);
        assertTrue(refus(3, VEHICULE + ", \"vehicle\": {\"controllers\": [\"turbo:gestion\"]}")
                .startsWith("$.vehicle"), "le schéma 3 ne le connaît pas");
    }

    private static double base(Tuning tuning, String name) {
        int index = tuning.indexOf(name);
        assertTrue(index >= 0, () -> name + " absent de " + tuning.names());
        return tuning.get(index).base();
    }
}
