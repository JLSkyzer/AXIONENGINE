package dev.axion.physics;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

/**
 * Épingle le décodage de {@link PhysicsEvent} sur la disposition figée de §10.7
 * (76 octets, little-endian), matériaux {@code u16} lus non signés.
 */
class PhysicsEventTest {

    @Test
    void tailleFigee() {
        assertEquals(76, PhysicsEvent.BYTES, "§10.7 fige PhysicsEvent à 76 octets");
    }

    @Test
    void decodeLitLesChampsAuxOffsetsFiges() {
        ByteBuffer buffer = ByteBuffer.allocate(PhysicsEvent.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(4); // kind @0
        buffer.putInt(10); // assemblyA.index @4
        buffer.putInt(1); // assemblyA.generation @8
        buffer.putInt(20); // assemblyB.index @12
        buffer.putInt(2); // assemblyB.generation @16
        buffer.putInt(5); // nodeA @20
        buffer.putInt(6); // nodeB @24
        buffer.putFloat(1.0f); // point.x @28
        buffer.putFloat(2.0f); // point.y @32
        buffer.putFloat(3.0f); // point.z @36
        buffer.putFloat(0.0f); // normal.x @40
        buffer.putFloat(1.0f); // normal.y @44
        buffer.putFloat(0.0f); // normal.z @48
        buffer.putFloat(12.5f); // impulse @52
        buffer.putFloat(3.25f); // tangentImpulse @56
        buffer.putFloat(-4.5f); // relativeVelocity @60
        buffer.putFloat(8.0f); // effectiveMass @64
        buffer.putShort((short) 0xFFFF); // materialA @68 (non signé → 65535)
        buffer.putShort((short) 7); // materialB @70
        buffer.putInt(0xCAFE); // data @72

        PhysicsEvent event = PhysicsEvent.decode(buffer, 0);

        assertEquals(4, event.kind());
        assertEquals(10, event.assemblyAIndex());
        assertEquals(1, event.assemblyAGeneration());
        assertEquals(20, event.assemblyBIndex());
        assertEquals(2, event.assemblyBGeneration());
        assertEquals(5, event.nodeA());
        assertEquals(6, event.nodeB());
        assertArrayEquals(new float[] {1.0f, 2.0f, 3.0f}, event.point(), 0.0f);
        assertArrayEquals(new float[] {0.0f, 1.0f, 0.0f}, event.normal(), 0.0f);
        assertEquals(12.5f, event.impulse());
        assertEquals(3.25f, event.tangentImpulse());
        assertEquals(-4.5f, event.relativeVelocity());
        assertEquals(8.0f, event.effectiveMass());
        assertEquals(65535, event.materialA(), "u16 lu non signé");
        assertEquals(7, event.materialB());
        assertEquals(0xCAFE, event.data());
    }
}
