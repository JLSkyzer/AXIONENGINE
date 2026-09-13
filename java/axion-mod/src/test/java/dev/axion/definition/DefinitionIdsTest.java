package dev.axion.definition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.axion.asset.AssetSource;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T-400 — C-50 : l'identifiant 64 bits d'une definition, tel que le NBT le porte. */
class DefinitionIdsTest {

    private static final DefinitionRules RULES =
            new DefinitionRules(true, Set.of("demo:axion/models/caisse.obj")::contains);

    private static AssetSource source(String... ids) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        for (String id : ids) {
            files.put("demo:axion/definitions/" + id + ".json",
                    "{\"schema\": 1, \"asset\": \"demo:models/caisse.obj\", \"kind\": \"rigid_object\"}"
                            .getBytes(StandardCharsets.UTF_8));
        }
        return new AssetSource() {
            @Override
            public List<String> list() {
                return List.copyOf(files.keySet());
            }

            @Override
            public byte[] read(String path) {
                return files.get(path);
            }
        };
    }

    @Test
    @DisplayName("T-400 : FNV-1a 64 bits, comme name_hash côté Rust")
    void vecteursDeReference() {
        // Mêmes vecteurs que le test de ax_model::dm::scene::name_hash.
        assertEquals(0xcbf2_9ce4_8422_2325L, DefinitionIds.hash(""));
        assertEquals(0xaf63_dc4c_8601_ec8cL, DefinitionIds.hash("a"));
        assertEquals(0x8594_4171_f739_67e8L, DefinitionIds.hash("foobar"));
    }

    @Test
    @DisplayName("T-400 : une definition se retrouve par son identifiant 64 bits")
    void rechercheParEmpreinte() {
        DefinitionRegistry registry = DefinitionRegistry.load(source("caisse"), RULES);
        Definition caisse = registry.get("demo:caisse").orElseThrow();

        assertEquals(DefinitionIds.hash("demo:caisse"), caisse.hash());
        assertEquals(caisse, registry.byHash(caisse.hash()).orElseThrow());
        assertTrue(registry.byHash(caisse.hash() + 1).isEmpty());
    }

    @Test
    @DisplayName("T-400 : deux definitions de même empreinte sont refusées (E-3010)")
    void collisionDEmpreinte() {
        DefinitionRegistry registry =
                DefinitionRegistry.load(source("a", "b", "c"), RULES, id -> id.endsWith("c") ? 7 : 42);

        assertEquals(List.of("demo:c"), registry.ids());
        assertEquals(2, registry.refusals().size());
        assertTrue(registry.refusals().stream().allMatch(refus -> refus.startsWith("E-3010")),
                registry.refusals()::toString);
        assertTrue(registry.byHash(42).isEmpty());
    }
}
