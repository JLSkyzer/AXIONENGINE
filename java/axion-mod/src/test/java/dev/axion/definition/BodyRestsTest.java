package dev.axion.definition;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-175 D2 : la pose de repos d'un corps dans le repère du corps racine, de la section NODE de l'asset. */
class BodyRestsTest {

    private static final float[] DROIT = {0f, 0f, 0f, 1f};

    /** Une section NODE : les parents, et pour chaque node translation, rotation et échelle uniforme locales. */
    private static byte[] section(int[] parents, float[][] translations, float[][] rotations, float[] scales) {
        ByteBuffer out = ByteBuffer.allocate(8 + parents.length * 80).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(0, parents.length);
        for (int node = 0; node < parents.length; node++) {
            int at = 8 + node * 80;
            out.putInt(at + 8, parents[node]);
            for (int i = 0; i < 3; i++) {
                out.putFloat(at + 12 + 4 * i, translations[node][i]);
            }
            for (int i = 0; i < 4; i++) {
                out.putFloat(at + 24 + 4 * i, rotations[node][i]);
            }
            for (int i = 0; i < 3; i++) {
                out.putFloat(at + 40 + 4 * i, scales[node]);
            }
        }
        return out.array();
    }

    @Test
    @DisplayName("Le corps racine est l'identité ; un autre corps, la pose de son node dans l'asset, composée le long des parents")
    void composeLesParents() {
        float c = (float) Math.sqrt(0.5);
        // 0 : racine ; 1 : enfant de 0, décalé de (1, 0, 0), tourné d'un quart de tour autour de y ;
        // 2 : enfant de 1, décalé de (2, 0, 0) dans le repère de 1.
        byte[] nodes = section(new int[] {-1, 0, 1},
                new float[][] {{0, 0, 0}, {1, 0, 0}, {2, 0, 0}},
                new float[][] {DROIT, {0f, c, 0f, c}, DROIT}, new float[] {1f, 1f, 1f});
        BodyRests rests = BodyRests.of(nodes, new int[] {0, 2});
        assertEquals(2, rests.rests().size());
        assertArrayEquals(new double[3], rests.rests().get(0).offset());
        BodyRests.Rest porte = rests.rests().get(1);
        // (1, 0, 0) + quartDeTour(y) · (2, 0, 0) = (1, 0, 0) + (0, 0, -2).
        assertArrayEquals(new double[] {1.0, 0.0, -2.0}, porte.offset(), 1e-6);
        assertArrayEquals(new float[] {0f, c, 0f, c}, porte.rotation(), 1e-6f);
    }

    @Test
    @DisplayName("L'échelle uniforme d'un parent multiplie la translation de ses enfants ; non uniforme, la pose manque")
    void echelle() {
        byte[] nodes = section(new int[] {-1, 0}, new float[][] {{0, 0, 0}, {1, 2, 3}},
                new float[][] {DROIT, DROIT}, new float[] {2f, 1f});
        assertArrayEquals(new double[] {2.0, 4.0, 6.0}, BodyRests.of(nodes, new int[] {0, 1}).rests().get(1).offset(),
                1e-6);
        ByteBuffer rompue = ByteBuffer.wrap(nodes.clone()).order(ByteOrder.LITTLE_ENDIAN);
        rompue.putFloat(8 + 40, 3f); // l'échelle x de la racine seule : non uniforme
        assertNull(BodyRests.of(rompue.array(), new int[] {0, 1}).rests().get(1));
    }

    @Test
    @DisplayName("Une section absente, tronquée ou aux parents incohérents ne donne rien")
    void sectionIllisible() {
        assertEquals(BodyRests.NONE, BodyRests.of(null, new int[] {0}));
        assertEquals(BodyRests.NONE, BodyRests.of(new byte[4], new int[] {0}));
        byte[] nodes = section(new int[] {1, -1}, new float[][] {{0, 0, 0}, {0, 0, 0}}, new float[][] {DROIT, DROIT},
                new float[] {1f, 1f});
        assertEquals(BodyRests.NONE, BodyRests.of(nodes, new int[] {0, 1}), "un parent qui suit son enfant");
    }
}
