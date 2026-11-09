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

    private static ByteBuffer ecrire(ByteBuffer out, String... lines) {
        out.putInt(lines.length);
        for (String line : lines) {
            byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
            out.putInt(bytes.length);
            out.put(bytes);
        }
        return out;
    }

    private static byte[] lignes(ByteBuffer out, String... lines) {
        ecrire(out, lines);
        return Arrays.copyOf(out.array(), out.position());
    }

    @Test
    @DisplayName("ADR-156 D4 : le résumé — corps, joints, animation serveur, avertissements, clips")
    void resume() {
        ByteBuffer out = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(2).putInt(1).putInt(1).putInt(0).putInt(0).putInt(4);
        ecrire(out, "racine animée");
        // Deux clips : « ouvrir » à 3 pistes, puis un clip sans nom à 1 piste.
        out.putInt(2);
        ecrire(out.putInt(3).putFloat(1.5f).putInt(1), "ouvrir");
        ecrire(out.putInt(1).putFloat(0.25f).putInt(0), "");
        // ADR-158 D1 : deux références, un node puis un clip.
        out.putInt(2).putInt(5).putInt(-1);
        // ADR-158 D3 : deux nodes, la racine au corps 0, l'enfant au corps 1 ; puis les porteurs des
        // colliders des deux corps.
        out.putInt(2);
        out.putInt(-1).putInt(0).putFloat(0f).putFloat(0f).putFloat(0f)
                .putFloat(0f).putFloat(0f).putFloat(0f).putFloat(1f);
        out.putInt(0).putInt(1).putFloat(0.5f).putFloat(-1f).putFloat(2f)
                .putFloat(0f).putFloat(1f).putFloat(0f).putFloat(0f);
        out.putInt(2).putInt(-1).putInt(0);
        out.putInt(1).putInt(1);
        byte[] payload = Arrays.copyOf(out.array(), out.position());
        DefinitionCompiler.Summary summary = DefinitionCompiler.Summary.read(payload);
        assertArrayEquals(new int[] {0, 4}, summary.roots());
        assertEquals(0, summary.rootNode());
        assertEquals(1, summary.joints());
        assertTrue(summary.serverAnimated());
        assertEquals(List.of("racine animée"), summary.warnings());
        assertEquals(new AnimClips(List.of("ouvrir", ""), new int[] {3, 1}, new float[] {1.5f, 0.25f},
                new boolean[] {true, false}), summary.clips());
        assertArrayEquals(new int[] {5, -1}, summary.nodes());
        Anatomy anatomy = summary.anatomy();
        assertEquals(2, anatomy.size());
        assertEquals(-1, anatomy.parent(0));
        assertEquals(0, anatomy.parent(1));
        assertEquals(1, anatomy.body(1));
        assertArrayEquals(new float[] {0.5f, -1f, 2f}, anatomy.translation(1));
        assertArrayEquals(new float[] {0f, 1f, 0f, 0f}, anatomy.rotation(1));
        assertEquals(-1, anatomy.carrier(0, 0), "le collider de l'asset entier");
        assertEquals(0, anatomy.carrier(0, 1));
        assertEquals(1, anatomy.carrier(1, 0));
        assertEquals(-1, anatomy.carrier(1, 1), "hors du corps");
        assertEquals(-1, anatomy.carrier(2, 0), "hors de l'assembly");

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
