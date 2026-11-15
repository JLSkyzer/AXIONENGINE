package dev.axion.definition;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-171 D2, D4 : les sockets d'attache et les interactions attach et detach, au schéma 6. */
class SocketsTest {

    private static final String MODELE = "mymod:axion/models/tracteur.glb";
    private static final DefinitionRules REGLES = new DefinitionRules(true, Set.of(MODELE)::contains, false);

    private static Definition lire(int schema, String corps) throws DefinitionException {
        String json = "{\"schema\": " + schema + ", \"asset\": \"mymod:models/tracteur.glb\", \"kind\": \"machine\", "
                + "\"variables\": {\"treuil\": {\"type\": \"float\", \"min\": -1, \"max\": 1}, \"pdf\": {\"type\":"
                + " \"float\"}}, " + corps + "}";
        return DefinitionRegistry.read(
                "mymod:axion/definitions/tracteur.json", json.getBytes(StandardCharsets.UTF_8), REGLES);
    }

    private static String refus(int schema, String corps) {
        DefinitionException refus = assertThrows(DefinitionException.class, () -> lire(schema, corps));
        return refus.path() + " : " + refus.getMessage();
    }

    @Test
    @DisplayName("D2 : un socket complet de chaque sorte, et les interactions qui le visent, se lisent au schéma 6")
    void socketsComplets() {
        assertDoesNotThrow(() -> lire(6, "\"sockets\": ["
                + "{\"name\": \"boule\", \"node\": \"socket_towbar\", \"accepts\": [\"hitch\", \"rope\"],"
                + " \"tag\": \"ball_50\", \"mates\": [\"coupler_50\"], \"max_force\": 60000, \"max_torque\": 8000,"
                + " \"misalignment\": 0.3, \"hitch\": {\"cone\": 40}, \"rope\": {\"length\": 6}},"
                + "{\"name\": \"treuil\", \"accepts\": [\"winch\"], \"anchor\": true,"
                + " \"winch\": {\"speed\": 0.8, \"min_length\": 0.5, \"max_length\": 30, \"variable\": \"treuil\"},"
                + " \"transfer\": {\"power\": {\"pdf\": \"pto_in\"}, \"damage\": 0.25}},"
                + "{\"name\": \"aimant\", \"accepts\": [\"magnet\", \"rigid\"], \"magnet\": {\"variable\": \"pdf\"}}],"
                + "\"interaction\": ["
                + "{\"node\": \"socket_towbar\", \"action\": \"attach\", \"socket\": \"boule\", \"kinds\": [\"hitch\"],"
                + " \"distance\": 3},"
                + "{\"node\": \"socket_towbar\", \"action\": \"detach\", \"socket\": \"boule\"}]"));
    }

    @Test
    @DisplayName("D2 : au schéma 5 un socket reste un nom ; au schéma 6 c'est un objet qui nomme ses types")
    void formesSelonLeSchema() {
        assertDoesNotThrow(() -> lire(5, "\"sockets\": [\"socket_towbar\"]"));
        assertTrue(refus(6, "\"sockets\": [\"socket_towbar\"]").startsWith("$.sockets[0]"));
        assertTrue(refus(6, "\"sockets\": [{\"name\": \"boule\"}]").startsWith("$.sockets[0]"),
                "accepts est obligatoire");
        assertTrue(refus(6, "\"sockets\": [{\"name\": \"boule\", \"accepts\": []}]").startsWith("$.sockets[0].accepts"));
        assertTrue(refus(6, "\"sockets\": [{\"name\": \"boule\", \"accepts\": [\"seated\"]}]")
                .startsWith("$.sockets[0].accepts[0]"), "SEATED est retiré (Q1)");
    }

    @Test
    @DisplayName("D2 : un bloc de paramètres d'un type que le socket n'accepte pas se refuse en le nommant")
    void parametresDUnAutreType() {
        String refus = refus(6, "\"sockets\": [{\"name\": \"boule\", \"accepts\": [\"hitch\"], \"rope\":"
                + " {\"length\": 6}}]");
        assertTrue(refus.startsWith("$.sockets[0].rope : ") && refus.contains("rope"), refus);
    }

    @Test
    @DisplayName("D2 : bornes — longueurs, cône, vitesse, treuil ordonné, noms simples")
    void bornes() {
        assertTrue(refus(6, "\"sockets\": [{\"name\": \"c\", \"accepts\": [\"rope\"], \"rope\": {\"length\": 300}}]")
                .startsWith("$.sockets[0].rope.length"));
        assertTrue(refus(6, "\"sockets\": [{\"name\": \"c\", \"accepts\": [\"hitch\"], \"hitch\": {\"cone\": 200}}]")
                .startsWith("$.sockets[0].hitch.cone"));
        assertTrue(refus(6, "\"sockets\": [{\"name\": \"c\", \"accepts\": [\"winch\"], \"winch\": {\"speed\": 25}}]")
                .startsWith("$.sockets[0].winch.speed"));
        String treuil = refus(6, "\"sockets\": [{\"name\": \"c\", \"accepts\": [\"winch\"], \"winch\":"
                + " {\"min_length\": 5, \"max_length\": 2}}]");
        assertTrue(treuil.startsWith("$.sockets[0].winch.max_length : "), treuil);
        assertTrue(refus(6, "\"sockets\": [{\"name\": \"c\", \"accepts\": [\"rigid\"], \"max_force\": 0}]")
                .startsWith("$.sockets[0].max_force"));
        assertTrue(refus(6, "\"sockets\": [{\"name\": \"c\", \"accepts\": [\"rigid\"], \"tag\": \"Boule 50\"}]")
                .startsWith("$.sockets[0].tag : "));
        assertTrue(refus(6, "\"sockets\": [{\"name\": \"c\", \"accepts\": [\"rigid\"], \"mates\": [\"ok\", \"pas ok\"]}]")
                .startsWith("$.sockets[0].mates[1] : "));
        assertTrue(refus(6, "\"sockets\": [{\"name\": \"c\", \"accepts\": [\"winch\"], \"winch\": {\"variable\":"
                + " \"inconnue\"}}]").startsWith("$.sockets[0].winch.variable"), "la variable du treuil est déclarée");
    }

    @Test
    @DisplayName("D4, CAP-16 : attach et detach nomment un socket déclaré ; attach permet des types qu'il accepte")
    void interactions() {
        String socket = "\"sockets\": [{\"name\": \"boule\", \"accepts\": [\"hitch\"]}], ";
        assertTrue(refus(6, socket + "\"interaction\": [{\"node\": \"n\", \"action\": \"attach\"}]")
                .startsWith("$.interaction[0] : "), "attach sans socket");
        assertTrue(refus(6, socket + "\"interaction\": [{\"node\": \"n\", \"action\": \"attach\", \"socket\":"
                + " \"inconnu\"}]").startsWith("$.interaction[0].socket"));
        String type = refus(6, socket + "\"interaction\": [{\"node\": \"n\", \"action\": \"attach\", \"socket\":"
                + " \"boule\", \"kinds\": [\"rope\"]}]");
        assertTrue(type.startsWith("$.interaction[0].kinds[0] : ") && type.contains("rope"), type);
        assertTrue(refus(6, socket + "\"interaction\": [{\"node\": \"n\", \"action\": \"detach\", \"socket\":"
                + " \"boule\", \"distance\": 2}]").startsWith("$.interaction[0].distance"));
        assertTrue(refus(6, socket + "\"interaction\": [{\"node\": \"n\", \"action\": \"sit\", \"seat\": \"s\","
                + " \"socket\": \"boule\"}]").startsWith("$.interaction[0]"));
        assertTrue(refus(5, "\"interaction\": [{\"node\": \"n\", \"action\": \"attach\", \"socket\": \"x\"}]")
                .startsWith("$.interaction[0].socket"), "le schéma 5 ne connaît pas ces paramètres");
    }

    @Test
    @DisplayName("D2 : le node d'un socket, ou son nom sans node, part au natif en référence de rôle socket")
    void referencesAuModele() {
        List<DefinitionChecker.AssetReference> references = DefinitionChecker.assetReferences(JsonParser.parseString(
                "{\"schema\": 6, \"asset\": \"m:a.glb\", \"kind\": \"machine\", \"sockets\": ["
                        + "{\"name\": \"boule\", \"node\": \"socket_towbar\", \"accepts\": [\"hitch\"]},"
                        + "{\"name\": \"attelage\", \"accepts\": [\"rope\"]}]}").getAsJsonObject());
        assertEquals(List.of(
                new DefinitionChecker.AssetReference(NameCategory.SOCKET_NODE, "socket_towbar", "$.sockets[0].node"),
                new DefinitionChecker.AssetReference(NameCategory.SOCKET_NODE, "attelage", "$.sockets[1].name")),
                references);
    }
}
