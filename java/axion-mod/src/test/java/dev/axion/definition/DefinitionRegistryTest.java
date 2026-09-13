package dev.axion.definition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.axion.asset.AssetSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T-281 — C-27 : schéma versionné, champs obligatoires, refus individuel. */
class DefinitionRegistryTest {

    private static final String PICKUP = "mymod:axion/models/pickup.glb";

    private static final DefinitionRules RULES =
            new DefinitionRules(true, Set.of(PICKUP)::contains);

    /** Sources simulées, dans l'ordre où le test les ajoute. */
    private static final class FakeSource implements AssetSource {
        private final Map<String, byte[]> files = new LinkedHashMap<>();

        FakeSource put(String path, String content) {
            files.put(path, content.getBytes(StandardCharsets.UTF_8));
            return this;
        }

        @Override
        public List<String> list() {
            return List.copyOf(files.keySet());
        }

        @Override
        public byte[] read(String path) throws IOException {
            return files.get(path);
        }
    }

    private static Definition read(String json) throws DefinitionException {
        return DefinitionRegistry.read(
                "mymod:axion/definitions/pickup.json",
                json.getBytes(StandardCharsets.UTF_8),
                RULES);
    }

    private static DefinitionException refus(String json) {
        return assertThrows(DefinitionException.class, () -> read(json));
    }

    @Test
    @DisplayName("T-281 : asset et kind suffisent (R-1781)")
    void assetEtKindSuffisent() throws DefinitionException {
        Definition definition = read(
                "{\"schema\": 1, \"asset\": \"mymod:models/pickup.glb\", \"kind\": \"vehicle\"}");
        assertEquals("mymod:pickup", definition.id());
        assertEquals(PICKUP, definition.asset());
        assertEquals(AssemblyKind.VEHICLE, definition.kind());
        assertEquals(64, definition.sha256().length());
    }

    @Test
    @DisplayName("T-281 : schema absent ou mal typé → E-7001, version inconnue → E-7003")
    void versionDeSchema() {
        String suite = ", \"asset\": \"mymod:models/pickup.glb\", \"kind\": \"vehicle\"}";

        assertEquals(DefinitionException.INVALID,
                refus("{\"asset\": \"mymod:models/pickup.glb\", \"kind\": \"vehicle\"}").code());
        assertEquals(DefinitionException.INVALID, refus("{\"schema\": \"1\"" + suite).code());
        assertEquals(DefinitionException.INVALID, refus("{\"schema\": 1.5" + suite).code());

        DefinitionException inconnu = refus("{\"schema\": 2" + suite);
        assertEquals(DefinitionException.UNKNOWN_SCHEMA, inconnu.code());
        assertEquals("E-7003", inconnu.codeName());
        assertEquals("$.schema", inconnu.path());
    }

    @Test
    @DisplayName("T-281 : asset désigne un modèle découvert, espace de noms explicite")
    void referenceAuModele() {
        String debut = "{\"schema\": 1, \"kind\": \"vehicle\", \"asset\": ";
        assertTrue(refus(debut + "\"mymod:models/absent.glb\"}").getMessage()
                .contains("mymod:axion/models/absent.glb"));
        assertEquals("$.asset", refus(debut + "\"models/pickup.glb\"}").path());
        assertTrue(refus(debut + "\"mymod:axion/models/pickup.glb\"}").getMessage()
                .contains("mymod:models/pickup.glb"));
        assertEquals("$.asset", refus(debut + "\"MyMod:models/pickup.glb\"}").path());
        assertEquals("$.asset", refus(debut + "42}").path());
    }

    @Test
    @DisplayName("T-281 : kind connu, en minuscules, et module véhicules actif")
    void genreDAssembly() throws DefinitionException {
        String debut = "{\"schema\": 1, \"asset\": \"mymod:models/pickup.glb\", \"kind\": ";

        assertEquals("$.kind", refus(debut + "\"tank\"}").path());
        // ADR-109 : la graphie du DM n'est pas celle du JSON.
        assertEquals("$.kind", refus(debut + "\"VEHICLE\"}").path());
        assertEquals(AssemblyKind.RIGID_OBJECT, read(debut + "\"rigid_object\"}").kind());

        DefinitionRules sansVehicules = new DefinitionRules(false, RULES.assetExists());
        DefinitionException refus = assertThrows(DefinitionException.class,
                () -> DefinitionRegistry.read(
                        "mymod:axion/definitions/pickup.json",
                        (debut + "\"vehicle\"}").getBytes(StandardCharsets.UTF_8),
                        sansVehicules));
        assertTrue(refus.getMessage().contains("modules.vehicles"));
    }

    @Test
    @DisplayName("T-281 : une definition refusée n'empêche pas les autres (R-580)")
    void refusIndividuel() {
        FakeSource source = new FakeSource()
                .put("mymod:axion/definitions/a.json",
                        "{\"schema\": 1, \"asset\": \"mymod:models/pickup.glb\", \"kind\": \"visual\"}")
                .put("mymod:axion/definitions/b.json", "{\"schema\": 9}")
                .put("mymod:axion/definitions/c.json",
                        "{\"schema\": 1, \"asset\": \"mymod:models/pickup.glb\", \"kind\": \"static\"}");

        DefinitionRegistry registry = DefinitionRegistry.load(source, RULES);

        assertEquals(List.of("mymod:a", "mymod:c"), registry.ids());
        assertEquals(1, registry.refusals().size());
        assertTrue(registry.refusals().get(0).startsWith("E-7003 mymod:axion/definitions/b.json"),
                registry.refusals().get(0));
    }

    @Test
    @DisplayName("T-281 : l'empreinte ne dépend que du contenu, pas de l'ordre des packs")
    void empreinteDeRegistry() {
        String a = "{\"schema\": 1, \"asset\": \"mymod:models/pickup.glb\", \"kind\": \"visual\"}";
        String b = "{\"schema\": 1, \"asset\": \"mymod:models/pickup.glb\", \"kind\": \"static\"}";

        DefinitionRegistry une = DefinitionRegistry.load(new FakeSource()
                .put("mymod:axion/definitions/a.json", a)
                .put("mymod:axion/definitions/b.json", b), RULES);
        DefinitionRegistry deux = DefinitionRegistry.load(new FakeSource()
                .put("mymod:axion/definitions/b.json", b)
                .put("mymod:axion/definitions/a.json", a), RULES);
        DefinitionRegistry modifiee = DefinitionRegistry.load(new FakeSource()
                .put("mymod:axion/definitions/a.json", a.replace("visual", "character"))
                .put("mymod:axion/definitions/b.json", b), RULES);

        assertEquals(une.fingerprint(), deux.fingerprint());
        assertNotEquals(une.fingerprint(), modifiee.fingerprint());
        assertNotEquals(une.fingerprint(), DefinitionRegistry.empty().fingerprint());
    }

    @Test
    @DisplayName("T-281 : une ressource hors d'axion/definitions n'est pas une definition")
    void identifiant() throws DefinitionException {
        assertEquals("mymod:vehicles/pickup",
                DefinitionRegistry.idOf("mymod:axion/definitions/vehicles/pickup.json"));
        assertThrows(DefinitionException.class,
                () -> DefinitionRegistry.idOf("mymod:axion/models/pickup.json"));
        assertThrows(DefinitionException.class,
                () -> DefinitionRegistry.idOf("mymod:axion/definitions/.json"));
    }

    @Test
    @DisplayName("T-281 : la definition acceptée ne se modifie pas de l'extérieur")
    void documentImmuable() throws DefinitionException {
        Definition definition = read(
                "{\"schema\": 1, \"asset\": \"mymod:models/pickup.glb\", \"kind\": \"visual\"}");
        definition.root().addProperty("kind", "vehicle");
        assertEquals("visual", definition.root().get("kind").getAsString());
    }
}
