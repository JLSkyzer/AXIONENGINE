package dev.axion.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ADR-151 D9 : le client retrouve le node racine du corps racine dans la section NODE de son propre
 * asset. La liste des corps, elle, se résout côté natif (ADR-156 D3, {@code ax-ffi/src/definition.rs}).
 */
class AssemblyBodiesTest {

    /** Une section NODE réduite à ses noms : les nodes eux-mêmes ne sont pas lus ici. */
    private static byte[] nodes(String... names) {
        byte[][] encoded = new byte[names.length][];
        int size = 0;
        for (int i = 0; i < names.length; i++) {
            encoded[i] = names[i].getBytes(StandardCharsets.UTF_8);
            size += encoded[i].length;
        }
        ByteBuffer out = ByteBuffer.allocate(8 + names.length * (80 + 8) + size).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(names.length).putInt(size);
        out.position(8 + names.length * 80);
        int offset = 0;
        for (byte[] name : encoded) {
            out.putInt(offset).putInt(name.length);
            offset += name.length;
        }
        for (byte[] name : encoded) {
            out.put(name);
        }
        return out.array();
    }

    private static JsonObject definition(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    @DisplayName("ADR-151 D9 : le client résout le node racine du corps racine par son nom")
    void leNodeRacineSeResoutParSonNom() {
        JsonObject inverse = definition(
                "{\"bodies\": [{\"name\": \"b\", \"root_node\": \"b\"}, {\"name\": \"a\", \"root_node\": \"a\"}]}");
        byte[] section = nodes("a", "a_vue", "b");
        assertEquals(2, AssemblyBodies.rootNodeOf(inverse, section), "le premier corps déclaré");
        assertEquals(0, AssemblyBodies.rootNodeOf(definition("{}"), section), "sans bodies : la racine de l'asset");
        assertEquals(0, AssemblyBodies.rootNodeOf(definition("{\"bodies\": [{\"name\": \"a\"}]}"), section));
        assertEquals(-1, AssemblyBodies.rootNodeOf(
                definition("{\"bodies\": [{\"name\": \"a\", \"root_node\": \"absent\"}]}"), section), "introuvable");
        assertEquals(-1, AssemblyBodies.rootNodeOf(
                definition("{\"bodies\": [{\"name\": \"a\", \"root_node\": \"a\"}]}"), null), "sans section NODE");
    }

    @Test
    @DisplayName("Une section NODE tronquée ne donne aucun nom")
    void sectionTronquee() {
        byte[] complete = nodes("a", "b");
        assertEquals(List.of("a", "b"), AssemblyBodies.names(complete));
        assertEquals(List.of(), AssemblyBodies.names(Arrays.copyOf(complete, complete.length - 1)));
        assertEquals(List.of(), AssemblyBodies.names(new byte[3]));
    }
}
