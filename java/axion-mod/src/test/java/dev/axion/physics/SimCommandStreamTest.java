package dev.axion.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

/**
 * Épingle l'encodage du flux {@code SIM_IN} sur la disposition figée d'ADR-114 :
 * {@code CommandStreamHeader} (8 o) puis, par commande, un {@code SimCommandHeader}
 * (opcode + payload_len, 8 o) et son payload, en little-endian.
 */
class SimCommandStreamTest {

    private static ByteBuffer wrap(byte[] bytes) {
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    @Test
    void encodeUneCommandeSetDimensionEnv() {
        SimCommandStream stream = new SimCommandStream()
                .setDimensionEnv(
                        7L,
                        new float[] {0.0f, -9.81f, 0.0f},
                        new float[] {1.0f, 0.0f, 2.0f},
                        63.0f,
                        1000.0f,
                        true);

        assertEquals(1, stream.count());
        byte[] bytes = stream.toBytes();
        // en-tête de flux (8) + en-tête de commande (8) + payload (48)
        assertEquals(8 + 8 + 48, bytes.length);

        ByteBuffer b = wrap(bytes);
        assertEquals(SimCommandStream.CURRENT_SCHEMA, b.getInt(0), "schema_version");
        assertEquals(0, b.getInt(4), "_pad");
        assertEquals(SimCommandStream.OP_SET_DIMENSION_ENV, b.getInt(8), "opcode");
        assertEquals(SimCommandStream.SET_DIMENSION_ENV_BYTES, b.getInt(12), "payload_len");
        assertEquals(7L, b.getLong(16), "dimension");
        assertEquals(0.0f, b.getFloat(24), "gravity.x");
        assertEquals(-9.81f, b.getFloat(28), "gravity.y");
        assertEquals(0.0f, b.getFloat(32), "gravity.z");
        assertEquals(1.0f, b.getFloat(36), "wind.x");
        assertEquals(0.0f, b.getFloat(40), "wind.y");
        assertEquals(2.0f, b.getFloat(44), "wind.z");
        assertEquals(63.0f, b.getFloat(48), "fluid_surface");
        assertEquals(1000.0f, b.getFloat(52), "fluid_density");
        assertEquals(SimCommandStream.FLUID_PRESENT, b.getInt(56), "flags");
    }

    @Test
    void sansFluideLeDrapeauEstNul() {
        byte[] bytes = new SimCommandStream()
                .setDimensionEnv(0L, new float[] {0, 0, 0}, new float[] {0, 0, 0}, 0, 0, false)
                .toBytes();
        assertEquals(0, wrap(bytes).getInt(56), "flags nul sans fluide");
    }

    @Test
    void deuxCommandesSeSuivent() {
        SimCommandStream stream = new SimCommandStream()
                .setDimensionEnv(0L, new float[] {0, -9.81f, 0}, new float[] {0, 0, 0}, 0, 0, false)
                .setDimensionEnv(1L, new float[] {0, -3.7f, 0}, new float[] {0, 0, 0}, 0, 0, false);

        assertEquals(2, stream.count());
        byte[] bytes = stream.toBytes();
        assertEquals(8 + 2 * (8 + 48), bytes.length);

        ByteBuffer b = wrap(bytes);
        // Seconde commande : après l'en-tête de flux et la première commande.
        int second = 8 + (8 + 48);
        assertEquals(SimCommandStream.OP_SET_DIMENSION_ENV, b.getInt(second));
        assertEquals(1L, b.getLong(second + 8), "dimension de la seconde commande");
    }

    @Test
    void fluxVideNaQuUnEntete() {
        SimCommandStream stream = new SimCommandStream();
        assertEquals(0, stream.count());
        assertEquals(8, stream.toBytes().length, "en-tête de flux seul");
    }
}
