package dev.axion.diag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    @DisplayName("CAP-42 : une definition refusée dit pourquoi ; une inconnue dit quoi vérifier")
    void detailRefuseeOuInconnue() {
        List<String> refusee = DefinitionsReport.info(registre(), "mymod:plume", cle -> Optional.empty());
        assertEquals(1, refusee.size());
        assertTrue(refusee.get(0).startsWith("AXION : mymod:plume refusée — ") && refusee.get(0).contains("mass"),
                refusee.get(0));
        List<String> inconnue = DefinitionsReport.info(registre(), "mymod:fantome", cle -> Optional.empty());
        assertTrue(inconnue.get(0).contains("ni chargée ni refusée"), inconnue.get(0));
    }
}
