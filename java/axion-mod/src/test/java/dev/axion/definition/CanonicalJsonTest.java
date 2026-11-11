package dev.axion.definition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-162 D4, CAP-47 : la forme canonique d'une definition, et l'empreinte calculée sur elle. */
class CanonicalJsonTest {

    private static String canonique(String json) {
        return CanonicalJson.of(JsonParser.parseString(json));
    }

    @Test
    @DisplayName("ADR-162 D4 : clés triées à toute profondeur, tableaux dans leur ordre, aucun espace")
    void structure() {
        assertEquals("{\"a\":[3,1,{\"x\":true,\"y\":null}],\"b\":\"z\"}",
                canonique("{ \"b\" : \"z\",\n  \"a\" : [ 3, 1, { \"y\": null, \"x\": true } ] }"));
        assertEquals("{\"B\":1,\"_\":2,\"a\":3}", canonique("{\"a\":3,\"_\":2,\"B\":1}"), "ordre des unités UTF-16");
    }

    @Test
    @DisplayName("ADR-162 D4 : un nombre s'écrit par le plus court décimal de sa valeur double, comme en JavaScript")
    void nombres() {
        assertEquals("1", CanonicalJson.number(1.0));
        assertEquals("0", CanonicalJson.number(-0.0), "-0 s'écrit 0");
        assertEquals("-3", CanonicalJson.number(-3.0));
        assertEquals("0.1", CanonicalJson.number(0.1));
        assertEquals("123.456", CanonicalJson.number(123.456));
        assertEquals("0.000001", CanonicalJson.number(1e-6));
        assertEquals("1.5e-7", CanonicalJson.number(1.5e-7));
        assertEquals("100000000000000000000", CanonicalJson.number(1e20));
        assertEquals("1e+21", CanonicalJson.number(1e21));
        assertEquals("-7.85", CanonicalJson.number(-7.85));
        assertEquals("0.30000000000000004", CanonicalJson.number(0.1 + 0.2), "le plus court qui redonne la valeur");
        assertEquals("5e-324", CanonicalJson.number(Double.MIN_VALUE));
        assertEquals("1.7976931348623157e+308", CanonicalJson.number(Double.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.number(Double.NaN));
        assertEquals(canonique("[1, 1.0, 1e0, 10e-1]"), "[1,1,1,1]");
    }

    @Test
    @DisplayName("ADR-162 D4 : une chaîne n'échappe que les guillemets, la barre oblique inverse et les contrôles")
    void chaines() {
        assertEquals("\"a\\\"b\\\\c\\n\\t\\u0001é→\"", canonique("\"a\\\"b\\\\c\\n\\t\\u0001\\u00e9\\u2192\""));
        assertEquals("\"/\"", canonique("\"\\/\""), "la barre oblique ne s'échappe pas");
    }

    @Test
    @DisplayName("CAP-47 : même contenu, même empreinte ; contenu changé, empreinte changée")
    void empreinte() {
        String une = CanonicalJson.sha256(JsonParser.parseString("{\"mass\": 1900.0, \"kind\": \"vehicle\"}"));
        String deux = CanonicalJson.sha256(JsonParser.parseString("{\"kind\":\"vehicle\",\"mass\":1900}"));
        assertEquals(une, deux);
        assertEquals(64, une.length());
        assertNotEquals(une, CanonicalJson.sha256(JsonParser.parseString("{\"kind\":\"vehicle\",\"mass\":1901}")));
    }

    @Test
    @DisplayName("ADR-162 D4 : une definition lue de deux fichiers qui ne diffèrent que par la forme a la même empreinte")
    void definitionsEquivalentes() throws DefinitionException {
        String un = "{\"schema\": 1, \"asset\": \"mymod:models/pickup.glb\", \"kind\": \"rigid_object\","
                + " \"physics\": {\"mass\": 2.0}}";
        String deux = "{\"physics\":{\"mass\":2},\"kind\":\"rigid_object\",\"asset\":\"mymod:models/pickup.glb\","
                + "\"schema\":1}";
        DefinitionRules regles = new DefinitionRules(true, "mymod:axion/models/pickup.glb"::equals);
        Definition a = DefinitionRegistry.read("mymod:axion/definitions/pickup.json",
                un.getBytes(StandardCharsets.UTF_8), regles);
        Definition b = DefinitionRegistry.read("mymod:axion/definitions/pickup.json",
                deux.getBytes(StandardCharsets.UTF_8), regles);
        assertEquals(a.sha256(), b.sha256());
    }
}
