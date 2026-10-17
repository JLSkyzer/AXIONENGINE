package dev.axion.world;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.axion.world.BlockMaterials.Material;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Épingle la résolution data-driven du contact des blocs (C-38, R-643, R-1040).
 *
 * <p>Test d'acceptance (ADR-125) : T-375 — matériau dominant résolu par données. Le chargement des
 * fichiers {@code block_materials} et leur fusion sont dans {@code BlockMaterialMappingsTest}.
 */
class BlockMaterialsTest {

    @Test
    void resolutionParPrioriteBlocPuisTagPuisDefaut() {
        Map<String, Material> byTag = new LinkedHashMap<>();
        byTag.put("minecraft:slime", new Material(null, 0.8f, 0.9f));
        byTag.put("minecraft:logs", new Material(null, 0.7f, 0.0f));
        BlockMaterials mats = new BlockMaterials(
                Map.of("minecraft:ice", new Material(null, 0.05f, 0.0f)), byTag, new Material(null, 0.6f, 0.0f));

        // Entrée directe par bloc, avant ses étiquettes.
        assertEquals(new Material(null, 0.05f, 0.0f), mats.resolve("minecraft:ice", List.of("minecraft:logs")));
        // Héritage par étiquette quand le bloc n'a pas d'entrée directe.
        assertEquals(new Material(null, 0.7f, 0.0f), mats.resolve("minecraft:oak_log", List.of("minecraft:logs")));
        // L'ordre du mappage décide, pas celui des étiquettes du bloc.
        assertEquals(new Material(null, 0.8f, 0.9f),
                mats.resolve("x:y", List.of("minecraft:logs", "minecraft:slime")));
        assertEquals(new Material(null, 0.8f, 0.9f),
                mats.resolve("x:y", Set.of("minecraft:logs", "minecraft:slime")));
        // Défaut sinon.
        assertEquals(new Material(null, 0.6f, 0.0f), mats.resolve("minecraft:stone", List.of("x:unknown")));
    }

    @Test
    void mappageVideResoutVersLeContactDuSolveur() {
        assertEquals(BlockMaterials.SOLVER_DEFAULT,
                BlockMaterials.empty().resolve("minecraft:stone", List.of("x:y")));
        assertEquals(BlockMaterials.SOLVER_DEFAULT, BlockMaterials.empty().fallback());
    }

    @Test
    void lOrdreDesEtiquettesSurvitALaCopie() {
        // Le constructeur copie : modifier la table d'origine ne change ni l'ordre ni le contenu.
        Map<String, Material> byTag = new LinkedHashMap<>();
        byTag.put("a:premier", new Material(null, 0.1f, 0f));
        byTag.put("a:second", new Material(null, 0.2f, 0f));
        BlockMaterials mats = new BlockMaterials(Map.of(), byTag, BlockMaterials.SOLVER_DEFAULT);
        byTag.remove("a:premier");
        assertEquals(new Material(null, 0.1f, 0f), mats.resolve("b:c", List.of("a:second", "a:premier")));
    }
}
