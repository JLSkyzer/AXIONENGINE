package dev.axion.definition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-170 D6 : les widgets qu'un tableau de bord nomme sont enregistrés par un mod, que le client doit avoir. */
class DashboardWidgetsTest {

    private static final String MODELE = "mymod:axion/models/pickup.glb";

    /** Un mod « cadrans » qui enregistre un widget. */
    private static final VehicleExtensions EXTENSIONS = new VehicleExtensions(Map.of(), Map.of(), Map.of(
            "cadrans:compte_tours", new VehicleExtensions.Widget("cadrans:compte_tours", "cadrans",
                    (toile, vue) -> toile.fill(0, 0, 1, 1, 0xFFFFFFFF))));

    private static final DefinitionRules REGLES = new DefinitionRules(true, Set.of(MODELE)::contains, false,
            CollisionGroupRegistry.empty(), PhysicsMaterials.empty(), TireProfiles.empty(), EXTENSIONS);

    private static Definition lire(String widget) throws DefinitionException {
        String json = "{\"schema\": 5, \"asset\": \"mymod:models/pickup.glb\", \"kind\": \"rigid_object\","
                + " \"seats\": [{\"name\": \"c\", \"node\": \"s\", \"role\": \"driver\"}],"
                + " \"dashboard\": {\"widgets\": [\"" + widget + "\"]}}";
        return DefinitionRegistry.read(
                "mymod:axion/definitions/pickup.json", json.getBytes(StandardCharsets.UTF_8), REGLES);
    }

    @Test
    @DisplayName("D6 : un widget enregistré se nomme ; un widget qu'aucun mod n'enregistre refuse la definition")
    void enregistreOuRefuse() throws DefinitionException {
        Definition definition = lire("cadrans:compte_tours");
        assertEquals("cadrans:compte_tours", definition.root().getAsJsonObject("dashboard")
                .getAsJsonArray("widgets").get(0).getAsString());
        DefinitionException refus = assertThrows(DefinitionException.class, () -> lire("cadrans:altimetre"));
        assertEquals("$.dashboard.widgets[0]", refus.path());
        assertTrue(refus.getMessage().contains("aucun mod ne l'enregistre"), refus.getMessage());
    }
}
