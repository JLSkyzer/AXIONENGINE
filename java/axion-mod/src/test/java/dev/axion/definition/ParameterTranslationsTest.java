package dev.axion.definition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.axion.api.assembly.Parameters;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-167 D10 : chaque paramètre d'AXION a son nom traduit, en français et en anglais. */
class ParameterTranslationsTest {

    /** Un véhicule qui déclare tout ce que règlent les paramètres d'AXION. */
    private static final String COMPLET = "{\"schema\": 4, \"asset\": \"mymod:models/pickup.glb\", \"kind\": \"vehicle\","
            + " \"physics\": {\"mass\": 1200}, \"wheels\": [{\"node\": \"a\", \"radius\": 0.3, \"powered\": true}],"
            + " \"powertrain\": {\"engine\": {\"torque_curve\": [[0, 100], [3000, 260]], \"idle_rpm\": 900,"
            + " \"max_rpm\": 6500}, \"transmission\": {\"type\": \"automatic\", \"gears\": [-3.1, 0, 3.4, 2.0],"
            + " \"final_drive\": 3.9}}, \"brakes\": {\"max_torque_front\": 2500}, \"aero\": {\"downforce_rear\": 150}}";

    private static JsonObject langue(String fichier) throws IOException {
        try (InputStream flux = ParameterTranslationsTest.class.getResourceAsStream("/assets/axion/lang/" + fichier)) {
            assertNotNull(flux, fichier + " absent des ressources");
            return JsonParser.parseReader(new InputStreamReader(flux, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    @Test
    @DisplayName("D10 : la clé de chaque paramètre d'AXION est traduite en français et en anglais")
    void chaqueParametreEstTraduit() throws IOException, DefinitionException {
        Definition vehicule = DefinitionRegistry.read("mymod:axion/definitions/pickup.json",
                COMPLET.getBytes(StandardCharsets.UTF_8),
                new DefinitionRules(true, Set.of("mymod:axion/models/pickup.glb")::contains));
        Tuning tuning = Tuning.of("mymod:pickup", vehicule.root());
        assertEquals(18, tuning.size(), "tous les paramètres d'AXION, trois rapports : " + tuning.names());
        for (String fichier : new String[] {"fr_fr.json", "en_us.json"}) {
            JsonObject textes = langue(fichier);
            for (String nom : tuning.names()) {
                String cle = Parameters.translationKey(nom);
                assertTrue(textes.has(cle), fichier + " : « " + cle + " » manque, pour " + nom);
            }
        }
    }

    @Test
    @DisplayName("D10 : un rapport partage une clé, son rang en argument ; un paramètre d'addon a celle de son mod")
    void formeDesCles() {
        assertEquals("axion.parameter.engine.torque", Parameters.translationKey(Parameters.ENGINE_TORQUE));
        assertEquals("axion.parameter.transmission.gear", Parameters.translationKey(Parameters.gear(-1)));
        assertEquals("turbo.parameter.boost_max", Parameters.translationKey("turbo:boost_max"));
    }
}
