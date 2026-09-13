package dev.axion.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.axion.asset.AssetSource;
import dev.axion.definition.Definition;
import dev.axion.definition.DefinitionIds;
import dev.axion.definition.DefinitionRegistry;
import dev.axion.definition.DefinitionRules;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T-401, T-402 — C-50 : création par commande, et liaison au rechargement. */
class AssemblyEntityRulesTest {

    private static final DefinitionRegistry REGISTRY = DefinitionRegistry.load(
            new AssetSource() {
                @Override
                public List<String> list() {
                    return List.of("demo:axion/definitions/caisse.json");
                }

                @Override
                public byte[] read(String path) {
                    return "{\"schema\": 1, \"asset\": \"demo:models/caisse.obj\", \"kind\": \"rigid_object\"}"
                            .getBytes(StandardCharsets.UTF_8);
                }
            },
            new DefinitionRules(true, Set.of("demo:axion/models/caisse.obj")::contains));

    private static final long CAISSE = DefinitionIds.hash("demo:caisse");

    @Test
    @DisplayName("T-401 : une definition connue se crée, une inconnue est refusée")
    void definitionDemandee() {
        AssemblySpawns.Decision acceptee = AssemblySpawns.check(REGISTRY, "demo:caisse", 1, 64);
        assertTrue(acceptee.accepted());
        assertEquals("demo:caisse", acceptee.definition().id());

        AssemblySpawns.Decision refusee = AssemblySpawns.check(REGISTRY, "demo:barriere", 1, 64);
        assertFalse(refusee.accepted());
        assertTrue(refusee.refusal().contains("demo:barriere"), refusee.refusal());
    }

    @Test
    @DisplayName("T-401 : au-delà de limits.max_spawn_per_command, refus (R-811)")
    void plafondDeCreation() {
        AssemblySpawns.Decision refusee = AssemblySpawns.check(REGISTRY, "demo:caisse", 1, 0);
        assertFalse(refusee.accepted());
        assertTrue(refusee.refusal().contains("limits.max_spawn_per_command"), refusee.refusal());
        assertTrue(AssemblySpawns.check(REGISTRY, "demo:caisse", 64, 64).accepted());
        assertFalse(AssemblySpawns.check(REGISTRY, "demo:caisse", 65, 64).accepted());
    }

    @Test
    @DisplayName("T-402 : un NBT au schéma 2 et à definition connue se lie")
    void liaison() {
        AssemblyBinding binding =
                AssemblyBinding.resolve(OptionalInt.of(2), OptionalLong.of(CAISSE), REGISTRY);
        assertFalse(binding.isInert());
        assertEquals("demo:caisse", binding.bound().map(Definition::id).orElseThrow());
    }

    @Test
    @DisplayName("T-402 : definition inconnue, schéma autre ou NBT vide → inerte (R-704, R-1710)")
    void inertie() {
        AssemblyBinding inconnue =
                AssemblyBinding.resolve(OptionalInt.of(2), OptionalLong.of(CAISSE + 1), REGISTRY);
        assertTrue(inconnue.isInert());
        assertTrue(inconnue.inertReason().contains("R-704"), inconnue.inertReason());

        AssemblyBinding future =
                AssemblyBinding.resolve(OptionalInt.of(3), OptionalLong.of(CAISSE), REGISTRY);
        assertTrue(future.isInert());
        assertTrue(future.inertReason().contains("R-1710"), future.inertReason());

        assertTrue(AssemblyBinding.resolve(OptionalInt.of(1), OptionalLong.of(CAISSE), REGISTRY)
                .isInert());
        assertTrue(AssemblyBinding.resolve(OptionalInt.empty(), OptionalLong.empty(), REGISTRY)
                .isInert());
    }
}
