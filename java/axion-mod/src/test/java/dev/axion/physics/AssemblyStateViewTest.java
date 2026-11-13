package dev.axion.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-168 D5, précision 6c-B : l'écart entre deux blocs d'état, au format de {@code world::state}. */
class AssemblyStateViewTest {

    /** Un bloc de deux corps ; la queue (joints, véhicule) reste opaque et ne compte pas. */
    private static byte[] bloc(double[][] positions, float[][] rotations, float[][] vitesses) {
        int corps = positions.length;
        ByteBuffer b = ByteBuffer.allocate(16 + corps * 64 + 24).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort((short) 1).putShort((short) 0).putShort((short) corps).putShort((short) 0).putShort((short) 0);
        b.position(16);
        for (int i = 0; i < corps; i++) {
            for (double v : positions[i]) {
                b.putDouble(v);
            }
            for (float v : rotations[i]) {
                b.putFloat(v);
            }
            for (float v : vitesses[i]) {
                b.putFloat(v);
            }
            b.putFloat(0f).putFloat(0f).putFloat(0f);
        }
        return b.array();
    }

    private static final float[] IDENTITE = {0f, 0f, 0f, 1f};

    @Test
    @DisplayName("l'écart se lit au pire des corps : position, angle entre rotations, vitesse")
    void ecarts() {
        byte[] a = bloc(new double[][] {{100, 64, -2000}, {101, 64, -2000}}, new float[][] {IDENTITE, IDENTITE},
                new float[][] {{0, 0, -5}, {0, 0, -5}});
        // Le second corps décalé de 3-4-0 cm, tourné de 0,01 rad autour de Y, sa vitesse changée de 0,1 m/s.
        float demi = (float) Math.sin(0.005);
        byte[] b = bloc(new double[][] {{100, 64, -2000}, {101.03, 64.04, -2000}},
                new float[][] {IDENTITE, {0f, demi, 0f, (float) Math.cos(0.005)}},
                new float[][] {{0, 0, -5}, {0, 0, -5.1f}});
        AssemblyStateView.Errors e = AssemblyStateView.errors(a, b);
        assertEquals(0.05, e.position(), 1e-9);
        assertEquals(0.01, e.rotation(), 1e-6);
        assertEquals(0.1, e.velocity(), 1e-6);
        assertTrue(e.within(0.05 + 1e-9, 0.01 + 1e-6, 0.1 + 1e-6), "seuils atteints, pas dépassés");
        assertFalse(e.within(0.049, 1, 1), "position au-delà");
        assertFalse(e.within(1, 0.009, 1), "rotation au-delà");
        assertFalse(e.within(1, 1, 0.09), "vitesse au-delà");
        // Une rotation et son opposée sont la même : l'angle est nul.
        byte[] oppose = bloc(new double[][] {{100, 64, -2000}, {101, 64, -2000}},
                new float[][] {IDENTITE, {0f, 0f, 0f, -1f}}, new float[][] {{0, 0, -5}, {0, 0, -5}});
        assertEquals(0.0, AssemblyStateView.errors(a, oppose).rotation(), 1e-9);
    }

    @Test
    @DisplayName("des blocs aux corps différents, tronqués ou absents ne se comparent pas")
    void illisibles() {
        byte[] deux = bloc(new double[][] {{0, 0, 0}, {1, 0, 0}}, new float[][] {IDENTITE, IDENTITE},
                new float[][] {{0, 0, 0}, {0, 0, 0}});
        byte[] un = bloc(new double[][] {{0, 0, 0}}, new float[][] {IDENTITE}, new float[][] {{0, 0, 0}});
        assertNull(AssemblyStateView.errors(deux, un));
        assertNull(AssemblyStateView.errors(deux, Arrays.copyOf(deux, 16 + 64)));
        assertNull(AssemblyStateView.errors(deux, null));
        assertNull(AssemblyStateView.errors(new byte[3], new byte[3]));
        assertEquals(0.0, AssemblyStateView.errors(deux, deux.clone()).position());
    }
}
