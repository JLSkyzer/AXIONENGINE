package dev.axion.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonParser;
import dev.axion.world.BlockMaterials.Material;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Épingle la résolution data-driven des matériaux de bloc (C-38, R-643).
 *
 * <p>Test d'acceptance (ADR-125) : T-375 — matériau dominant résolu par données.
 */
class BlockMaterialsTest {

    @Test
    void resolutionParPrioriteBlocPuisTagPuisDefaut() {
        BlockMaterials mats =
                new BlockMaterials(
                        Map.of("minecraft:ice", new Material(0.05f, 0.0f)),
                        Map.of(
                                "minecraft:logs", new Material(0.7f, 0.0f),
                                "minecraft:slime", new Material(0.8f, 0.9f)),
                        new Material(0.6f, 0.0f));

        // Entrée directe par bloc.
        assertEquals(new Material(0.05f, 0.0f), mats.resolve("minecraft:ice", List.of()));
        // Héritage par tag quand le bloc n'a pas d'entrée directe.
        assertEquals(
                new Material(0.7f, 0.0f),
                mats.resolve("minecraft:oak_log", List.of("minecraft:logs")));
        // Premier tag correspondant dans l'ordre fourni.
        assertEquals(
                new Material(0.8f, 0.9f),
                mats.resolve("x:y", List.of("minecraft:slime", "minecraft:logs")));
        // Défaut générique sinon.
        assertEquals(new Material(0.6f, 0.0f), mats.resolve("minecraft:stone", List.of("x:unknown")));
    }

    @Test
    void mappageVideResoutVersLeDefautGenerique() {
        assertEquals(
                BlockMaterials.GENERIC_DEFAULT,
                BlockMaterials.empty().resolve("minecraft:stone", List.of("x:y")));
    }

    @Test
    void fromJsonLitLesSectionsEtBorneLesValeurs() {
        String json =
                "{\n"
                        + "  \"default\": { \"friction\": 0.5 },\n"
                        + "  \"blocks\": { \"minecraft:ice\": { \"friction\": 0.05, \"restitution\": 0.1 } },\n"
                        + "  \"tags\": { \"minecraft:slime\": { \"friction\": 9.0, \"restitution\": 9.0 } }\n"
                        + "}";
        BlockMaterials mats = BlockMaterials.fromJson(JsonParser.parseString(json));

        assertEquals(new Material(0.5f, 0.0f), mats.fallback());
        assertEquals(new Material(0.05f, 0.1f), mats.resolve("minecraft:ice", List.of()));
        // Bornage aux plages DM-07 : 9.0 → 2.0 (friction), 9.0 → 1.0 (restitution).
        assertEquals(new Material(2.0f, 1.0f), mats.resolve("x:y", List.of("minecraft:slime")));
    }

    @Test
    void fromJsonSansDefautUtiliseLeDefautGenerique() {
        BlockMaterials mats = BlockMaterials.fromJson(JsonParser.parseString("{}"));
        assertEquals(BlockMaterials.GENERIC_DEFAULT, mats.fallback());
    }

    @Test
    void fromJsonRefuseUneEntreeSansFriction() {
        String json = "{ \"blocks\": { \"minecraft:ice\": { \"restitution\": 0.1 } } }";
        assertThrows(
                IllegalArgumentException.class,
                () -> BlockMaterials.fromJson(JsonParser.parseString(json)));
    }

    @Test
    void fromJsonRefuseUnDocumentNonObjet() {
        assertThrows(
                IllegalArgumentException.class,
                () -> BlockMaterials.fromJson(JsonParser.parseString("[]")));
    }

    @Test
    void laRessourceLivreeEstValide() throws Exception {
        // La ressource par défaut expédiée avec le mod doit parser sans erreur : sa forme
        // est ainsi vérifiée à chaque build, pas seulement en jeu.
        try (InputStream in =
                BlockMaterialsTest.class.getResourceAsStream("/axion/world/block_materials.json")) {
            assertNotNull(in, "la ressource block_materials.json est présente");
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            BlockMaterials mats = BlockMaterials.fromJson(JsonParser.parseString(json));
            // Un bloc glissant y est déclaré, un bloc inconnu retombe sur le défaut.
            assertEquals(0.05f, mats.resolve("minecraft:ice", List.of()).friction(), 1e-6);
            assertEquals(
                    mats.fallback(), mats.resolve("modded:unknown_block", List.of("modded:unknown")));
        }
    }
}
