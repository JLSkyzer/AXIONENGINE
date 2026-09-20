package dev.axion.physics;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

/**
 * Épingle le décodage de {@link BodyState} sur la disposition figée du DM-08
 * (80 octets, little-endian). Les octets sont posés aux offsets du natif ; si le
 * décodeur lisait ailleurs, ce test échouerait.
 */
class BodyStateTest {

    @Test
    void tailleFigee() {
        assertEquals(80, BodyState.BYTES, "DM-08 fige BodyState à 80 octets");
    }

    @Test
    void decodeLitLesChampsAuxOffsetsFiges() {
        ByteBuffer buffer = ByteBuffer.allocate(BodyState.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(7); // handleIndex @0
        buffer.putInt(3); // handleGeneration @4
        buffer.putDouble(1000.5); // position.x @8
        buffer.putDouble(-64.0); // position.y @16
        buffer.putDouble(2000.25); // position.z @24
        buffer.putFloat(0.1f); // rotation.x @32
        buffer.putFloat(0.2f); // rotation.y @36
        buffer.putFloat(0.3f); // rotation.z @40
        buffer.putFloat(0.4f); // rotation.w @44
        buffer.putFloat(1.5f); // linear.x @48
        buffer.putFloat(2.5f); // linear.y @52
        buffer.putFloat(3.5f); // linear.z @56
        buffer.putFloat(-1.0f); // angular.x @60
        buffer.putFloat(-2.0f); // angular.y @64
        buffer.putFloat(-3.0f); // angular.z @68
        buffer.putInt(0b1001); // flags @72 (SLEEPING | CLAMPED, selon le DM)
        buffer.putInt(0); // remplissage @76

        BodyState state = BodyState.decode(buffer, 0);

        assertEquals(7, state.handleIndex());
        assertEquals(3, state.handleGeneration());
        assertArrayEquals(new double[] {1000.5, -64.0, 2000.25}, state.position(), 0.0);
        assertArrayEquals(new float[] {0.1f, 0.2f, 0.3f, 0.4f}, state.rotation(), 0.0f);
        assertArrayEquals(new float[] {1.5f, 2.5f, 3.5f}, state.linearVelocity(), 0.0f);
        assertArrayEquals(new float[] {-1.0f, -2.0f, -3.0f}, state.angularVelocity(), 0.0f);
        assertEquals(0b1001, state.flags());
        assertEquals((3L << 32) | 7L, state.handleKey());
    }

    @Test
    void decodeEstRelatifAuBase() {
        // Deux états consécutifs se lisent en décalant simplement `base`.
        ByteBuffer buffer = ByteBuffer.allocate(BodyState.BYTES * 2).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(11); // état 0 : handleIndex
        buffer.position(BodyState.BYTES);
        buffer.putInt(22); // état 1 : handleIndex

        assertEquals(11, BodyState.decode(buffer, 0).handleIndex());
        assertEquals(22, BodyState.decode(buffer, BodyState.BYTES).handleIndex());
    }
}
