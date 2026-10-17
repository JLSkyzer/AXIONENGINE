package dev.axion.debug;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * Épingle la lecture de la charge de {@code DEBUG} (amendement A1 d'ADR-121, schéma 2).
 *
 * <p>Les octets sont écrits à la main d'après la disposition ratifiée, comme le test de
 * disposition côté Rust : c'est la disposition qui est vérifiée.
 */
class DebugGeometryTest {

    /**
     * Trois plages : les colliders du corps (7, 1) en repère du corps, à un segment ; une tuile
     * du monde en repère d'origine, à deux segments ; le centre de masse du corps (7, 1), à un
     * segment. Trois plages omises.
     */
    private static byte[] charge() {
        ByteBuffer out = ByteBuffer.allocate(16 + 3 * 32 + 4 * 24).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(3).putInt(4).putInt(DebugGeometry.TRUNCATED).putInt(3);
        // DebugRange : handle (index, génération), overlay, repère, premier segment, nombre,
        // drapeaux, réservé.
        out.putInt(7).putInt(1).putInt(0).putInt(DebugGeometry.FRAME_BODY)
                .putInt(0).putInt(1).putInt(DebugGeometry.BODY_SLEEPING).putInt(0);
        out.putInt(0).putInt(0).putInt(30).putInt(DebugGeometry.FRAME_ORIGIN)
                .putInt(1).putInt(2).putInt(0).putInt(0);
        out.putInt(7).putInt(1).putInt(31).putInt(DebugGeometry.FRAME_BODY)
                .putInt(3).putInt(1).putInt(DebugGeometry.BODY_SLEEPING).putInt(0);
        // DebugSegment : a puis b.
        float[][] segments = {
            {0, 0, 0, 1, 0, 0},
            {-0.5f, 0, -0.5f, 0.5f, 0, -0.5f},
            {-0.5f, 1, 0.5f, 0.5f, 1, 0.5f},
            {0, 0.4f, 0, 0, 0.6f, 0},
        };
        for (float[] segment : segments) {
            for (float value : segment) {
                out.putFloat(value);
            }
        }
        return out.array();
    }

    private static DebugGeometry lue(byte[] bytes) {
        return DebugGeometry.parse(bytes, 100.5, 64.0, -30.25);
    }

    @Test
    void laDispositionDuSchema2SeLit() {
        DebugGeometry geometrie = lue(charge());

        assertEquals(3, geometrie.ranges().size());
        assertTrue(geometrie.truncated());
        assertEquals(3, geometrie.omittedRanges());
        assertEquals(100.5, geometrie.originX());
        assertEquals(64.0, geometrie.originY());
        assertEquals(-30.25, geometrie.originZ());

        // Les plages en repère du corps se retrouvent par leur handle, dans l'ordre de la charge.
        var duCorps = geometrie.bodyRangesOf(7, 1);
        assertEquals(2, duCorps.size());
        DebugGeometry.Range colliders = duCorps.get(0);
        assertEquals(0, colliders.overlay());
        assertEquals(1, colliders.segmentCount());
        assertTrue(colliders.has(DebugGeometry.BODY_SLEEPING));
        assertArrayEquals(new float[] {0, 0, 0, 1, 0, 0}, colliders.segments(), 0.0f);
        assertEquals(31, duCorps.get(1).overlay());
        assertArrayEquals(new float[] {0, 0.4f, 0, 0, 0.6f, 0}, duCorps.get(1).segments(), 0.0f);
        assertTrue(geometrie.bodyRangesOf(7, 2).isEmpty(), "une autre génération n'est pas ce corps");

        // Les plages en repère d'origine sont à part : elles ne suivent aucune entité.
        assertEquals(1, geometrie.originRanges().size());
        DebugGeometry.Range tuile = geometrie.originRanges().get(0);
        assertEquals(30, tuile.overlay());
        assertEquals(DebugGeometry.FRAME_ORIGIN, tuile.frame());
        assertEquals(2, tuile.segmentCount());
        assertArrayEquals(
                new float[] {-0.5f, 1, 0.5f, 0.5f, 1, 0.5f},
                Arrays.copyOfRange(tuile.segments(), 6, 12),
                0.0f);
    }

    @Test
    void uneLongueurQuiContreditLesDenombrementsEstRefusee() {
        byte[] juste = charge();
        assertThrows(IllegalArgumentException.class, () -> lue(Arrays.copyOf(juste, juste.length + 1)));
        assertThrows(IllegalArgumentException.class, () -> lue(Arrays.copyOf(juste, juste.length - 1)));
        assertThrows(IllegalArgumentException.class, () -> lue(new byte[15]));
    }

    @Test
    void unePlageHorsDesSegmentsEstRefusee() {
        byte[] faussee = charge();
        // Seconde plage : premier segment 3, deux segments, sur quatre déposés.
        ByteBuffer.wrap(faussee).order(ByteOrder.LITTLE_ENDIAN).putInt(16 + 32 + 16, 3);
        assertThrows(IllegalArgumentException.class, () -> lue(faussee));
    }

    @Test
    void unRepereInconnuEstRefuse() {
        byte[] faussee = charge();
        // Première plage : repère 2, qui n'existe pas.
        ByteBuffer.wrap(faussee).order(ByteOrder.LITTLE_ENDIAN).putInt(16 + 12, 2);
        assertThrows(IllegalArgumentException.class, () -> lue(faussee));
    }

    @Test
    void uneChargeVideEstValide() {
        DebugGeometry vide = lue(new byte[16]);
        assertTrue(vide.ranges().isEmpty());
        assertTrue(vide.originRanges().isEmpty());
        assertEquals(0, vide.omittedRanges());
    }
}
