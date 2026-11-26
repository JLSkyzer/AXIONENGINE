package dev.axion.diag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.axion.asset.AssetSource;
import dev.axion.definition.DefinitionRegistry;
import dev.axion.definition.DefinitionRules;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** CAP-42, C-71 : {@code /axion defs list | info} — les definitions chargées, et pourquoi une autre est refusée. */
class DefinitionsReportTest {

    private static final String CAISSE = "mymod:axion/models/caisse.glb";

    private static DefinitionRegistry registre() {
        Map<String, byte[]> fichiers = new LinkedHashMap<>();
        fichiers.put("mymod:axion/definitions/caisse.json", ("{\"schema\": 3, \"asset\": \"mymod:models/caisse.glb\","
                + " \"kind\": \"rigid_object\"}").getBytes(StandardCharsets.UTF_8));
        fichiers.put("mymod:axion/definitions/plume.json", ("{\"schema\": 3, \"asset\": \"mymod:models/caisse.glb\","
                + " \"kind\": \"rigid_object\", \"physics\": {\"mass\": -5}}").getBytes(StandardCharsets.UTF_8));
        fichiers.put("autre:axion/definitions/rocher.json", ("{\"schema\": 3, \"asset\": \"mymod:models/caisse.glb\","
                + " \"kind\": \"static\"}").getBytes(StandardCharsets.UTF_8));
        AssetSource source = new AssetSource() {
            @Override
            public List<String> list() {
                return List.copyOf(fichiers.keySet());
            }

            @Override
            public byte[] read(String path) {
                return fichiers.get(path);
            }
        };
        return DefinitionRegistry.load(source, new DefinitionRules(true, CAISSE::equals, false));
    }

    @Test
    @DisplayName("CAP-42 : la liste compte, montre les refus d'abord, puis les definitions mod par mod")
    void liste() {
        List<String> lignes = DefinitionsReport.list(registre());
        assertEquals("AXION ENGINE — definitions : 2 chargée(s), 1 refusée(s)", lignes.get(0));
        assertTrue(lignes.get(1).startsWith("  refusée : ") && lignes.get(1).contains("mymod:axion/definitions/plume.json")
                && lignes.get(1).contains("$.physics.mass"), lignes.get(1));
        assertEquals("  autre (1) : rocher", lignes.get(2));
        assertEquals("  mymod (1) : caisse", lignes.get(3));
    }

    @Test
    @DisplayName("CAP-42 : le détail d'une definition chargée, avec le mod qui l'enregistre")
    void detailChargee() {
        List<String> lignes = DefinitionsReport.info(registre(), "mymod:caisse",
                cle -> Optional.of("mymod — axion/definitions/caisse.json (" + cle + ")"));
        assertEquals("AXION ENGINE — mymod:caisse", lignes.get(0));
        assertTrue(lignes.contains("  genre : RIGID_OBJECT"), lignes::toString);
        assertTrue(lignes.contains("  modèle : " + CAISSE), lignes::toString);
        assertTrue(lignes.stream().anyMatch(ligne -> ligne.startsWith("  enregistrée par : mymod — ")), lignes::toString);
    }

    @Test
    @DisplayName("ADR-170 : le détail d'un véhicule dit ses vues et son tableau de bord, déclarés ou par défaut")
    void vuesEtTableau() {
        String siege = "\"seats\": [{\"name\": \"pilote\", \"node\": \"n\", \"role\": \"driver\"}]";
        JsonObject parDefaut = JsonParser.parseString("{" + siege + "}").getAsJsonObject();
        List<String> lignes = new java.util.ArrayList<>();
        DefinitionsReport.seated(parDefaut, lignes);
        assertEquals(List.of("  vues : cockpit et poursuite, par défaut", "  tableau de bord : défaut ; conducteur seul"),
                lignes);
        JsonObject declare = JsonParser.parseString("{" + siege + ", \"cameras\": [{\"name\": \"capot\", \"kind\":"
                + " \"fixed\", \"node\": \"cam\"}], \"dashboard\": {\"default\": false, \"seats\": [\"pilote\"],"
                + " \"gauges\": [{\"name\": \"turbo\", \"kind\": \"text\", \"source\": \"var.boost\"}],"
                + " \"widgets\": [\"mymod:compte_tours\"]}}").getAsJsonObject();
        lignes.clear();
        DefinitionsReport.seated(declare, lignes);
        assertEquals(List.of("  vues : capot (fixed)",
                "  tableau de bord : jauges turbo ; widgets mymod:compte_tours ; sièges pilote"), lignes);
        lignes.clear();
        DefinitionsReport.seated(JsonParser.parseString("{}").getAsJsonObject(), lignes);
        assertTrue(lignes.isEmpty(), "sans siège, rien : " + lignes);
    }

    @Test
    @DisplayName("CAP-42 : une definition refusée dit pourquoi ; une inconnue dit quoi vérifier")
    void detailRefuseeOuInconnue() {
        List<String> refusee = DefinitionsReport.info(registre(), "mymod:plume", cle -> Optional.empty());
        assertEquals(1, refusee.size());
        assertTrue(refusee.get(0).startsWith("AXION : mymod:plume refusée — ") && refusee.get(0).contains("mass"),
                refusee.get(0));
        List<String> inconnue = DefinitionsReport.info(registre(), "mymod:fantome", cle -> Optional.empty());
        assertTrue(inconnue.get(0).contains("ni chargée ni refusée"), inconnue.get(0));
    }

    @Test
    @org.junit.jupiter.api.DisplayName("ADR-171, axe Diagnostic : les sockets d'une definition et ce qu'ils déclarent")
    void lesSocketsSontDits() {
        List<String> lignes = new java.util.ArrayList<>();
        DefinitionsReport.sockets(JsonParser.parseString("{\"sockets\": [{\"name\": \"attelage\", \"accepts\": "
                + "[\"hitch\", \"rope\"], \"tag\": \"ball_50\", \"max_force\": 40000.0, \"hitch\": {\"cone\": 40.0}},"
                + " {\"name\": \"avant\", \"accepts\": [\"rope\"]}]}").getAsJsonObject(), lignes);
        assertEquals(List.of("  socket attelage : accepte hitch, rope ; tag ball_50 ; max_force 40000.0 ; hitch (cone 40.0)",
                "  socket avant : accepte rope"), lignes);
        lignes.clear();
        DefinitionsReport.sockets(JsonParser.parseString("{}").getAsJsonObject(), lignes);
        List<String> energies = new java.util.ArrayList<>();
        DefinitionsReport.energy(JsonParser.parseString("{\"schema\": 8, \"energy\": {\"stores\": ["
                + "{\"name\": \"pile\", \"carrier\": \"axion:electricity\", \"capacity\": 2.5},"
                + " {\"name\": \"cuve\", \"capacity\": 20}]}, \"powertrain\": {\"engine\": {\"generator\":"
                + " {\"store\": \"pile\", \"power_kw\": 20}}, \"electric_motors\": [{\"name\": \"arriere\","
                + " \"store\": \"pile\", \"coupling\": {\"wheels\": [\"g\", \"d\"], \"ratio\": 8}},"
                + " {\"name\": \"central\", \"store\": \"pile\"}]}}").getAsJsonObject(), energies);
        assertEquals(List.of("  réserves : pile (axion:electricity, 2.5), cuve (tout, 20)",
                "  prolongateur : charge pile à 20 kW",
                "  moteur électrique arriere : réserve pile, entraîne roues g, d",
                "  moteur électrique central : réserve pile, entraîne la boîte"), energies);
        assertEquals(List.of(), lignes, "sans socket, rien");
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("ADR-174, axe Diagnostic : le rapport dit les événements, les capteurs et ce que des mods font")
    void ceQuUneDefinitionEmetEtVoit() {
        com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString("{\"schema\": 7, \"events\":"
                + " {\"klaxon\": {\"replicate\": \"occupants\"}}, \"sensors\": [{\"name\": \"porte\", \"node\":"
                + " \"seuil\", \"size\": [1, 2, 0.5]}], \"interaction\": [{\"node\": \"d\", \"action\":"
                + " \"coffres:coffre\", \"actions\": [{\"cadrans:bip\": {}}]}], \"revision\": 3, \"migrations\":"
                + " [{\"from\": 1, \"rename\": {}}, {\"from\": 2, \"rename\": {}}], \"replaces\": [\"mymod:ancienne\"]}")
                .getAsJsonObject();
        java.util.List<String> lines = new java.util.ArrayList<>();
        DefinitionsReport.content(root, lines);
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of(
                "  révision : 3, migrations depuis 1, 2, remplace mymod:ancienne",
                "  événements : klaxon (occupants), sensor.porte.enter (all), sensor.porte.exit (all)",
                "  capteur porte : au node seuil, boîte 1.0 × 2.0 × 0.5",
                "  de mods : action cadrans:bip, interaction coffres:coffre"), lines);
    }
}
