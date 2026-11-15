package dev.axion.definition;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-170 D3, D4 : les vues d'un véhicule et son tableau de bord, au schéma 5. */
class CamerasTest {

    private static final String MODELE = "mymod:axion/models/pickup.glb";
    private static final DefinitionRules REGLES = new DefinitionRules(true, Set.of(MODELE)::contains);

    private static final String SIEGES = "\"seats\": [{\"name\": \"conducteur\", \"node\": \"siege\", \"role\":"
            + " \"driver\"}, {\"name\": \"passager\", \"node\": \"siege_p\"}]";

    private static Definition lire(int schema, String corps) throws DefinitionException {
        String json = "{\"schema\": " + schema + ", \"asset\": \"mymod:models/pickup.glb\", \"kind\": \"rigid_object\", "
                + SIEGES + ", " + corps + "}";
        return DefinitionRegistry.read(
                "mymod:axion/definitions/pickup.json", json.getBytes(StandardCharsets.UTF_8), REGLES);
    }

    private static String refus(int schema, String corps) {
        DefinitionException refus = assertThrows(DefinitionException.class, () -> lire(schema, corps));
        return refus.path() + " : " + refus.getMessage();
    }

    @Test
    @DisplayName("D3 : les trois sortes de vues, et un tableau de bord complet, se lisent au schéma 5")
    void vuesEtTableauDeBord() {
        assertDoesNotThrow(() -> lire(5, "\"variables\": {\"boost\": {\"type\": \"float\"}, \"phares\": {\"type\":"
                + " \"bool\"}}, \"cameras\": ["
                + "{\"name\": \"capot\", \"kind\": \"fixed\", \"node\": \"cam_capot\", \"fov\": 80,"
                + " \"look\": {\"yaw\": 60, \"pitch\": 30}},"
                + "{\"name\": \"loin\", \"kind\": \"follow\", \"distance\": 9, \"height\": 2.5, \"smoothing_ms\": 250,"
                + " \"recenter_ms\": 1500},"
                + "{\"name\": \"pilote\", \"kind\": \"cockpit\", \"seats\": [\"conducteur\"], \"eye\": [0, 0.62, 0.05]}],"
                + "\"dashboard\": {\"default\": true, \"seats\": [\"conducteur\", \"passager\"], \"gauges\": ["
                + "{\"name\": \"turbo\", \"kind\": \"dial\", \"source\": \"var.boost\", \"min\": 0, \"max\": 2,"
                + " \"label\": \"gauge.mymod.turbo\", \"unit\": \"bar\", \"anchor\": \"bottom_right\","
                + " \"offset\": [-120, -40], \"size\": 64},"
                + "{\"name\": \"phares\", \"kind\": \"light\", \"source\": \"var.phares\"}],"
                + " \"widgets\": [\"mymod:compte_tours\"]}"));
    }

    @Test
    @DisplayName("D3 : le schéma 4 ne connaît ni les vues ni le tableau de bord")
    void schemaQuatre() {
        assertTrue(refus(4, "\"cameras\": []").startsWith("$.cameras"));
        assertTrue(refus(4, "\"dashboard\": {}").startsWith("$.dashboard"));
    }

    @Test
    @DisplayName("D3 : chaque sorte n'admet que ses champs, et chaque refus nomme le champ")
    void champsParSorte() {
        assertTrue(refus(5, "\"cameras\": [{\"name\": \"capot\", \"kind\": \"fixed\"}]")
                .startsWith("$.cameras[0].node : "), "une vue fixe nomme son node");
        assertTrue(refus(5, "\"cameras\": [{\"name\": \"loin\", \"kind\": \"follow\", \"node\": \"n\"}]")
                .startsWith("$.cameras[0].node : "));
        assertTrue(refus(5, "\"cameras\": [{\"name\": \"loin\", \"kind\": \"follow\", \"eye\": [0, 1, 0]}]")
                .startsWith("$.cameras[0].eye : "));
        assertTrue(refus(5, "\"cameras\": [{\"name\": \"loin\", \"kind\": \"follow\", \"look\": {\"yaw\": 10}}]")
                .startsWith("$.cameras[0].look : "));
        assertTrue(refus(5, "\"cameras\": [{\"name\": \"pilote\", \"kind\": \"cockpit\", \"distance\": 4}]")
                .startsWith("$.cameras[0].distance : "));
        assertTrue(refus(5, "\"cameras\": [{\"name\": \"pilote\", \"kind\": \"cockpit\", \"eye\": [0, 4.5, 0]}]")
                .startsWith("$.cameras[0].eye[1] : "), "l'œil reste à ±4 m du siège");
    }

    @Test
    @DisplayName("D3 : bornes, noms et références refusés avec leur chemin")
    void bornes() {
        assertTrue(refus(5, "\"cameras\": [{\"name\": \"loin\", \"kind\": \"follow\", \"distance\": 80}]")
                .startsWith("$.cameras[0].distance"));
        assertTrue(refus(5, "\"cameras\": [{\"name\": \"loin\", \"kind\": \"follow\", \"fov\": 150}]")
                .startsWith("$.cameras[0].fov"));
        assertTrue(refus(5, "\"cameras\": [{\"name\": \"x\", \"kind\": \"orbite\"}]")
                .startsWith("$.cameras[0].kind"));
        assertTrue(refus(5, "\"cameras\": [{\"name\": \"x\", \"kind\": \"follow\"}, {\"name\": \"x\", \"kind\":"
                + " \"follow\"}]").startsWith("$.cameras[1].name"), "deux vues du même nom");
        assertTrue(refus(5, "\"cameras\": [{\"name\": \"x\", \"kind\": \"cockpit\", \"seats\": [\"arriere\"]}]")
                .startsWith("$.cameras[0].seats[0]"), "un siège qui n'existe pas");
        StringBuilder dix_sept = new StringBuilder("\"cameras\": [");
        for (int i = 0; i < 17; i++) {
            dix_sept.append(i == 0 ? "" : ", ").append("{\"name\": \"v").append(i).append("\", \"kind\": \"follow\"}");
        }
        assertTrue(refus(5, dix_sept + "]").startsWith("$.cameras"), "16 vues au plus");
    }

    @Test
    @DisplayName("D4 : une jauge lit une source de l'ANNEXE A.4 ; un cadran a des bornes croissantes")
    void jauges() {
        assertTrue(refus(5, "\"dashboard\": {\"gauges\": [{\"name\": \"g\", \"kind\": \"dial\", \"source\":"
                + " \"vehicle.altitude\", \"min\": 0, \"max\": 1}]}").startsWith("$.dashboard.gauges[0].source"));
        assertTrue(refus(5, "\"dashboard\": {\"gauges\": [{\"name\": \"g\", \"kind\": \"dial\", \"source\":"
                + " \"vehicle.speed\"}]}").startsWith("$.dashboard.gauges[0] : "), "un cadran sans bornes");
        assertTrue(refus(5, "\"dashboard\": {\"gauges\": [{\"name\": \"g\", \"kind\": \"bar\", \"source\":"
                + " \"engine.rpm\", \"min\": 7000, \"max\": 0}]}").startsWith("$.dashboard.gauges[0].max"));
        assertTrue(refus(5, "\"dashboard\": {\"gauges\": [{\"name\": \"g\", \"kind\": \"text\", \"source\":"
                + " \"vehicle.speed\", \"anchor\": \"milieu\"}]}").startsWith("$.dashboard.gauges[0].anchor"));
        assertTrue(refus(5, "\"dashboard\": {\"widgets\": [\"mymod:a\", \"mymod:a\"]}")
                .startsWith("$.dashboard.widgets[1]"), "un widget nommé deux fois");
        assertTrue(refus(5, "\"dashboard\": {\"widgets\": [\"compte_tours\"]}")
                .startsWith("$.dashboard.widgets[0]"), "un widget se nomme <mod>:<nom>");
    }
}
