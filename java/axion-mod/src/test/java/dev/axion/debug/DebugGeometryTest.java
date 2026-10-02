package dev.axion.debug;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * Épingle la lecture de la charge de {@code DEBUG} (ADR-121, schéma 1).
 *
 * <p>Les octets sont écrits à la main d'après la disposition ratifiée, comme le test de
 * disposition côté Rust : c'est la disposition qui est vérifiée.
 */
class DebugGeometryTest {

    /** Deux corps : le premier à un segment, le second à deux ; trois corps omis. */
    private static byte[] charge() {
        ByteBuffer out = ByteBuffer.allocate(16 + 2 * 24 + 3 * 24).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(2).putInt(3).putInt(DebugGeometry.TRUNCATED).putInt(3);
        // DebugBody : handle (index, génération), premier segment, nombre, drapeaux, réservé.
        out.putInt(7).putInt(1).putInt(0).putInt(1).putInt(DebugGeometry.BODY_SLEEPING).putInt(0);
        out.putInt(9).putInt(1).putInt(1).putInt(2).putInt(DebugGeometry.BODY_STATIC).putInt(0);
        // DebugSegment : a puis b.
        float[][] segments = {
            {0, 0, 0, 1, 0, 0},
            {-0.5f, 0, -0.5f, 0.5f, 0, -0.5f},
            {-0.5f, 1, 0.5f, 0.5f, 1, 0.5f},
        };
        for (float[] segment : segments) {
            for (float value : segment) {
                out.putFloat(value);
            }
        }
        return out.array();
    }

    @Test
    void laDispositionDAdr121SeLit() {
        DebugGeometry geometrie = DebugGeometry.parse(charge());

        assertEquals(2, geometrie.bodies().size());
        assertTrue(geometrie.truncated());
        assertEquals(3, geometrie.omittedBodies());

        DebugGeometry.Body premier = geometrie.bodies().get(0);
        assertEquals(7, premier.handleIndex());
        assertEquals(1, premier.segmentCount());
        assertTrue(premier.has(DebugGeometry.BODY_SLEEPING));
        assertArrayEquals(new float[] {0, 0, 0, 1, 0, 0}, premier.segments(), 0.0f);

        DebugGeometry.Body second = geometrie.bodyOf(9, 1);
        assertEquals(2, second.segmentCount());
        assertTrue(second.has(DebugGeometry.BODY_STATIC));
        assertArrayEquals(
                new float[] {-0.5f, 1, 0.5f, 0.5f, 1, 0.5f},
                Arrays.copyOfRange(second.segments(), 6, 12),
                0.0f);
        assertNull(geometrie.bodyOf(9, 2), "une autre génération n'est pas ce corps");
    }

    @Test
    void uneLongueurQuiContreditLesDenombrementsEstRefusee() {
        byte[] juste = charge();
        assertThrows(IllegalArgumentException.class,
                () -> DebugGeometry.parse(Arrays.copyOf(juste, juste.length + 1)));
        assertThrows(IllegalArgumentException.class,
                () -> DebugGeometry.parse(Arrays.copyOf(juste, juste.length - 1)));
        assertThrows(IllegalArgumentException.class, () -> DebugGeometry.parse(new byte[15]));
    }

    @Test
    void unePlageHorsDesSegmentsEstRefusee() {
        byte[] faussee = charge();
        // Second corps : premier segment 2, deux segments, sur trois déposés.
        ByteBuffer.wrap(faussee).order(ByteOrder.LITTLE_ENDIAN).putInt(16 + 24 + 8, 2);
        assertThrows(IllegalArgumentException.class, () -> DebugGeometry.parse(faussee));
    }

    @Test
    void uneChargeVideEstValide() {
        DebugGeometry vide = DebugGeometry.parse(new byte[16]);
        assertTrue(vide.bodies().isEmpty());
        assertEquals(0, vide.omittedBodies());
    }
}
