package dev.axion.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.axion.api.ApiVersion;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-166 D1 : la version de l'API qu'un addon exige dans son {@code mods.toml}. */
class ApiRequirementsTest {

    private static final ApiVersion PRESENTE = new ApiVersion(1, 2, 0);

    @AfterEach
    void oublier() {
        ApiRequirements.publish(List.of());
    }

    @Test
    @DisplayName("ADR-166 D1 : une version au plus égale à celle de l'API, dans la même majeure, est acceptée")
    void accepte() {
        assertEquals(List.of(), ApiRequirements.check(Map.of("a", "1.0", "b", "1.2", "c", "1.2.0", "d", " 1.1 "),
                PRESENTE));
    }

    @Test
    @DisplayName("ADR-166 D1 : une version plus récente, ou d'une autre majeure, est refusée et le message dit quoi faire")
    void refuse() {
        List<ApiRequirements.Refusal> refus =
                ApiRequirements.check(Map.of("tracteurs", "1.3", "bateaux", "2.0", "avions", "0.9"), PRESENTE);
        assertEquals(List.of("avions", "bateaux", "tracteurs"), refus.stream().map(ApiRequirements.Refusal::modId).toList());
        assertEquals("tracteurs exige l'API AXION 1.3.0, AXION fournit 1.2.0 : son contenu n'est pas chargé ; "
                + "mettre AXION à jour", refus.get(2).describe());
    }

    @Test
    @DisplayName("ADR-166 D1 : une valeur mal écrite, ou un nombre TOML, est refusée en le disant")
    void malEcrite() {
        assertEquals(Optional.empty(), ApiRequirements.parse("1"));
        assertEquals(Optional.empty(), ApiRequirements.parse("1.x"));
        assertEquals(Optional.empty(), ApiRequirements.parse("v1.0"));
        // axion_api = 1.10 sans guillemets : TOML lit un nombre, qui dirait 1.1.
        assertEquals(Optional.empty(), ApiRequirements.parse(1.10));
        List<ApiRequirements.Refusal> refus = ApiRequirements.check(Map.of("addon", 1.10), PRESENTE);
        assertTrue(refus.get(0).describe().contains("entre guillemets"), refus.get(0).describe());
    }

    @Test
    @DisplayName("ADR-166 D1 : un addon refusé est écarté de l'enregistrement, les autres non")
    void publie() {
        ApiRequirements.publish(ApiRequirements.check(Map.of("tracteurs", "9.0", "bateaux", "1.0"), PRESENTE));
        assertTrue(ApiRequirements.isRefused("tracteurs"));
        assertFalse(ApiRequirements.isRefused("bateaux"));
        assertFalse(ApiRequirements.isRefused("sans_propriete"));
    }
}
