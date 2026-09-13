package dev.axion.definition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T-280 — étape 1 de C-27 : JSON strict. */
class StrictJsonTest {

    private static JsonElement parse(String json) throws DefinitionException {
        return StrictJson.parse(json.getBytes(StandardCharsets.UTF_8));
    }

    private static DefinitionException refus(String json) {
        return assertThrows(DefinitionException.class, () -> parse(json));
    }

    @Test
    @DisplayName("T-280 : un document conforme se lit, nombres exacts compris")
    void unDocumentConformeSeLit() throws DefinitionException {
        JsonElement root = parse(
                "{\"schema\": 1, \"mass\": 1650.25, \"tags\": [\"a\", null, true]}");
        assertTrue(root.isJsonObject());
        assertEquals("1650.25",
                root.getAsJsonObject().get("mass").getAsBigDecimal().toPlainString());
    }

    @Test
    @DisplayName("T-280 : une clé en double est refusée, avec son chemin")
    void uneCleEnDoubleEstRefusee() {
        DefinitionException refus = refus(
                "{\"physics\": {\"mass\": 1650, \"mass\": 1200}}");
        assertEquals(DefinitionException.INVALID, refus.code());
        assertEquals("$.physics.mass", refus.path());
    }

    @Test
    @DisplayName("T-280 : les tolérances de Gson sont refusées")
    void lesTolerancesSontRefusees() {
        // Commentaire, clé sans guillemets, guillemets simples, NaN, virgule
        // finale, valeur après la racine : un analyseur permissif les accepte.
        for (String json : new String[] {
                "{\"a\": 1 /* commentaire */}",
                "{a: 1}",
                "{'a': 1}",
                "{\"a\": NaN}",
                "{\"a\": [1, 2,]}",
                "{\"a\": 1} {\"b\": 2}",
                "",
        }) {
            assertEquals(DefinitionException.INVALID, refus(json).code(), json);
        }
    }

    @Test
    @DisplayName("T-280 : UTF-8 invalide et marque d'ordre des octets sont refusés")
    void encodageStrict() {
        byte[] invalide = {'{', '"', 'a', '"', ':', '"', (byte) 0xC3, '"', '}'};
        assertThrows(DefinitionException.class, () -> StrictJson.parse(invalide));
        assertEquals("$", refus("﻿{\"a\": 1}").path());
    }

    @Test
    @DisplayName("T-280 : une imbrication hostile est refusée sans déborder la pile")
    void imbricationBornee() {
        String profond = "[".repeat(10_000) + "]".repeat(10_000);
        assertTrue(refus(profond).getMessage().contains("imbrication"));
    }

    @Test
    @DisplayName("T-280 : un nombre hors de la plage d'un double est refusé")
    void nombreHorsPlage() {
        assertTrue(refus("{\"a\": 1e400}").getMessage().contains("plage"));
    }

    @Test
    @DisplayName("T-280 : une clé singulière reste lisible dans le chemin")
    void cheminDesClesSingulieres() {
        assertEquals("$[\"a.b\"]", refus("{\"a.b\": 1, \"a.b\": 2}").path());
    }
}
