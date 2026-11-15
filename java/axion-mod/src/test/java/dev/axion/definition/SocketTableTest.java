package dev.axion.definition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-171 D2 : la table des sockets compilée, à leurs nodes résolus. */
class SocketTableTest {

    private static JsonObject definition(String sockets) {
        return JsonParser.parseString("{\"schema\": 6, \"sockets\": [" + sockets + "]}").getAsJsonObject();
    }

    @Test
    @DisplayName("D2 : chaque socket à son node, ses types dans l'ordre canonique, ses paramètres ; NaN sans")
    void compile() {
        SocketTable table = SocketTable.of(definition(
                "{\"name\": \"boule\", \"node\": \"socket_towbar\", \"accepts\": [\"rope\", \"hitch\"], \"tag\":"
                        + " \"ball_50\", \"mates\": [\"coupler_50\"], \"max_force\": 6000, \"hitch\": {\"cone\": 40},"
                        + " \"transfer\": {\"power\": {\"pdf\": \"pto_in\"}, \"damage\": 0.25}},"
                        + "{\"name\": \"treuil\", \"accepts\": [\"winch\"], \"anchor\": true, \"winch\": {\"speed\": 0.8,"
                        + " \"min_length\": 0.5, \"max_length\": 30, \"variable\": \"cmd\"}}"),
                Map.of("$.sockets[0].node", 4, "$.sockets[1].name", 7));
        assertEquals(2, table.sockets().size());
        SocketTable.Spec boule = table.named("boule").orElseThrow();
        assertEquals(4, boule.node());
        assertEquals(List.of("hitch", "rope"), List.copyOf(new java.util.TreeSet<>(boule.accepts())).stream()
                .sorted((a, b) -> Sockets.KINDS.stream().toList().indexOf(a) - Sockets.KINDS.stream().toList()
                        .indexOf(b)).toList());
        assertEquals("ball_50", boule.tag());
        assertEquals(6000.0, boule.maxForce());
        assertEquals(Double.POSITIVE_INFINITY, boule.maxTorque(), "sans seuil : jamais");
        assertEquals(40.0, boule.cone());
        assertTrue(Double.isNaN(boule.ropeLength()), "la longueur se décide à l'attache");
        assertEquals(Map.of("pdf", "pto_in"), boule.power());
        assertEquals(0.25, boule.damage());
        assertFalse(boule.anchor());
        SocketTable.Spec treuil = table.named("treuil").orElseThrow();
        assertEquals(7, treuil.node(), "sans node, celui de son nom");
        assertTrue(treuil.anchor());
        assertEquals("cmd", treuil.winchVariable());
        assertEquals(30.0, treuil.winchMax());
    }

    @Test
    @DisplayName("D2 : deux sockets s'épousent si chacun accepte la forme de l'autre ; sans mates, toutes")
    void formes() {
        SocketTable table = SocketTable.of(definition(
                "{\"name\": \"boule\", \"accepts\": [\"hitch\"], \"tag\": \"ball_50\"},"
                        + "{\"name\": \"timon\", \"accepts\": [\"hitch\"], \"tag\": \"coupler_50\", \"mates\":"
                        + " [\"ball_50\"]},"
                        + "{\"name\": \"crochet\", \"accepts\": [\"hitch\"], \"mates\": [\"ball_80\"]}"),
                Map.of("$.sockets[0].name", 1, "$.sockets[1].name", 2, "$.sockets[2].name", 3));
        SocketTable.Spec boule = table.named("boule").orElseThrow();
        SocketTable.Spec timon = table.named("timon").orElseThrow();
        SocketTable.Spec crochet = table.named("crochet").orElseThrow();
        assertTrue(timon.mates(boule) && boule.mates(timon));
        assertFalse(crochet.mates(boule));
        assertEquals(Set.of(), boule.mates());
    }

    @Test
    @DisplayName("D2 : avant le schéma 6, aucune table ; un node non résolu est un défaut de la frontière")
    void ancienSchemaEtDefaut() {
        assertTrue(SocketTable.of(JsonParser.parseString("{\"schema\": 5, \"sockets\": [\"attelage\"]}")
                .getAsJsonObject(), Map.of()).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> SocketTable.of(definition(
                "{\"name\": \"boule\", \"accepts\": [\"hitch\"]}"), Map.of()));
    }
}
