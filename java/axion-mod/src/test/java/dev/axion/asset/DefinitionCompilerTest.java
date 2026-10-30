package dev.axion.asset;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-156 D4 : ce que Java lit de la réponse du natif, dans la disposition d'{@code ax_model::dm::definition}. */
class DefinitionCompilerTest {

    private static byte[] lignes(ByteBuffer out, String... lines) {
        out.putInt(lines.length);
        for (String line : lines) {
            byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
            out.putInt(bytes.length);
            out.put(bytes);
        }
        return Arrays.copyOf(out.array(), out.position());
    }

    @Test
    @DisplayName("ADR-156 D4 : le résumé — corps, joints, animation serveur, avertissements")
    void resume() {
        ByteBuffer out = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(2).putInt(1).putInt(1).putInt(0).putInt(0).putInt(4);
        byte[] payload = lignes(out, "racine animée");
        DefinitionCompiler.Summary summary = DefinitionCompiler.Summary.read(payload);
        assertArrayEquals(new int[] {0, 4}, summary.roots());
        assertEquals(0, summary.rootNode());
        assertEquals(1, summary.joints());
        assertTrue(summary.serverAnimated());
        assertEquals(List.of("racine animée"), summary.warnings());

        assertNull(DefinitionCompiler.Summary.read(Arrays.copyOf(payload, payload.length - 1)), "tronqué");
        assertNull(DefinitionCompiler.Summary.read(Arrays.copyOf(payload, payload.length + 1)), "prolongé");
    }

    @Test
    @DisplayName("ADR-156 D4 : le refus — code, référence fautive, motif ; un refus illisible le dit")
    void refus() {
        ByteBuffer out = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(-7001).putInt(3);
        byte[] payload = lignes(out, "aucun node de ce nom dans l'asset");
        DefinitionCompiler.Result result = DefinitionCompiler.Result.readRefusal(-7001, payload);
        assertTrue(result.refused());
        assertFalse(result.compiled());
        assertEquals(3, result.reference());
        assertEquals("aucun node de ce nom dans l'asset", result.message());

        DefinitionCompiler.Result autreCode = DefinitionCompiler.Result.readRefusal(-3050, payload);
        assertEquals(-1, autreCode.reference(), "le code déposé n'est pas celui de l'appel");
        assertEquals("refus illisible", autreCode.message());
        assertFalse(new DefinitionCompiler.Result(-2002, null, -1, "").refused(), "un échec de la frontière");
    }
}
