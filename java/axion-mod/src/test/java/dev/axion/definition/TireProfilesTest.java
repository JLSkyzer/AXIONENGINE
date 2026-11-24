package dev.axion.definition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-159 Q1 : le registre des pneus. */
class TireProfilesTest {

    static final String GLACE = """
            {"schema": 1, "mu_long": 0.2, "mu_lat": 0.15,
             "long": {"peak_slip": 0.1, "peak_value": 1.0, "tail_slip": 0.4, "tail_value": 0.5},
             "lat": {"peak_slip": 0.2, "peak_value": 0.9, "tail_slip": 0.6, "tail_value": 0.0}}
            """;

    private static List<String> refus(String json) {
        return TireProfiles.load(VehicleSpecTest.source(Map.of("m:axion/tires/x.json", json))).refusals();
    }

    @Test
    @DisplayName("ADR-159 Q1 : le fichier tires/glace.json déclare le pneu <ns>:tire/glace")
    void identifiant() {
        TireProfiles pneus = TireProfiles.load(VehicleSpecTest.source(Map.of("mymod:axion/tires/neige/glace.json", GLACE)));
        assertTrue(pneus.knows("mymod:tire/neige/glace"), pneus.ids().toString());
        TireProfiles.Tire pneu = pneus.resolve("mymod:tire/neige/glace");
        assertEquals(0.2f, pneu.muLong());
        assertEquals(new TireProfiles.Curve(0.2f, 0.9f, 0.6f, 0f), pneu.lateral());
    }

    @Test
    @DisplayName("ADR-159 Q1 : un pneu inconnu, ou aucun, vaut le pneu générique")
    void generique() {
        assertSame(TireProfiles.GENERIC, TireProfiles.empty().resolve("mymod:tire/route"));
        assertSame(TireProfiles.GENERIC, TireProfiles.empty().resolve(null));
        assertFalse(TireProfiles.empty().knows("mymod:tire/route"));
    }

    @Test
    @DisplayName("R-580 : une faute refuse le pneu, nommé, et laisse les autres")
    void fautes() {
        TireProfiles pneus = TireProfiles.load(VehicleSpecTest.source(
                Map.of("m:axion/tires/bon.json", GLACE, "m:axion/tires/mauvais.json", "{")));
        assertTrue(pneus.knows("m:tire/bon"));
        assertEquals(1, pneus.refusals().size());
        assertTrue(pneus.refusals().get(0).contains("mauvais.json"), pneus.refusals().toString());
        for (String[] cas : new String[][] {
            {"\"schema\": 1", "\"schema\": 2", "$.schema"},
            {"\"mu_long\": 0.2", "\"mu_long\": 0", "$.mu_long"},
            {"\"mu_long\": 0.2", "\"mu_long\": 0.2, \"autre\": 1", "$.autre"},
            {"\"tail_slip\": 0.4", "\"tail_slip\": 0.05", "$.long.tail_slip"},
            {"\"tail_value\": 0.0", "\"tail_value\": -1", "$.lat.tail_value"},
        }) {
            List<String> refus = refus(GLACE.replace(cas[0], cas[1]));
            assertEquals(1, refus.size(), cas[1]);
            assertTrue(refus.get(0).contains(cas[2]), refus.toString());
        }
    }

    @Test
    @DisplayName("ADR-176 D5 : wet_grip est optionnel (1 par défaut), de 0,1 à 4")
    void adherenceSousLaPluie() {
        TireProfiles.Tire sans = TireProfiles.load(VehicleSpecTest.source(Map.of("m:axion/tires/x.json", GLACE)))
                .resolve("m:tire/x");
        assertEquals(1f, sans.wetGrip());
        assertEquals(1f, TireProfiles.GENERIC.wetGrip());

        String pluie = GLACE.replace("\"schema\": 1,", "\"schema\": 1, \"wet_grip\": 1.35,");
        assertEquals(1.35f, TireProfiles.load(VehicleSpecTest.source(Map.of("m:axion/tires/x.json", pluie)))
                .resolve("m:tire/x").wetGrip());

        for (String faute : new String[] {"0", "0.05", "4.5", "-1", "\"mouillé\""}) {
            List<String> refus = refus(GLACE.replace("\"schema\": 1,", "\"schema\": 1, \"wet_grip\": " + faute + ","));
            assertEquals(1, refus.size(), faute + " : " + refus);
            assertTrue(refus.get(0).contains("wet_grip"), refus.toString());
        }
    }

    @Test
    @DisplayName("ADR-176 D4 : wear est optionnel : sans lui le pneu ne s'use pas ; avec lui, un taux et une perte d'adhérence")
    void usure() {
        TireProfiles.Tire sans = TireProfiles.load(VehicleSpecTest.source(Map.of("m:axion/tires/x.json", GLACE)))
                .resolve("m:tire/x");
        assertEquals(0f, sans.wearRate());
        assertEquals(TireProfiles.DEFAULT_GRIP_LOSS, sans.wearGripLoss());
        assertEquals(0f, TireProfiles.GENERIC.wearRate());

        String avec = GLACE.replace("\"schema\": 1,", "\"schema\": 1, \"wear\": {\"rate\": 0.02, \"grip_loss\": 0.25},");
        TireProfiles.Tire use = TireProfiles.load(VehicleSpecTest.source(Map.of("m:axion/tires/x.json", avec)))
                .resolve("m:tire/x");
        assertEquals(0.02f, use.wearRate());
        assertEquals(0.25f, use.wearGripLoss());

        String taux = GLACE.replace("\"schema\": 1,", "\"schema\": 1, \"wear\": {\"rate\": 0.5},");
        assertEquals(TireProfiles.DEFAULT_GRIP_LOSS, TireProfiles.load(VehicleSpecTest.source(
                Map.of("m:axion/tires/x.json", taux))).resolve("m:tire/x").wearGripLoss(), "grip_loss par défaut");
        for (String faute : new String[] {"{\"rate\": -1}", "{\"grip_loss\": 1.5}", "{\"rate\": 1e9}",
            "{\"vitesse\": 1}", "3"}) {
            List<String> refus = refus(GLACE.replace("\"schema\": 1,", "\"schema\": 1, \"wear\": " + faute + ","));
            assertEquals(1, refus.size(), faute + " : " + refus);
            assertTrue(refus.get(0).contains("$.wear"), refus.toString());
        }
    }
}
