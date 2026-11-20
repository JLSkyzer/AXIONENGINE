package dev.axion.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-159 D9, ADR-176 D4 : le décodage du rapport d'une roue, aux offsets figés du natif (40 octets). */
class WheelReportTest {

    private static void poser(ByteBuffer buffer, int base) {
        buffer.putInt(base, 7); // handle.index @0
        buffer.putInt(base + 4, 3); // handle.generation @4
        buffer.putShort(base + 8, (short) 2); // index @8
        buffer.putShort(base + 10, (short) WheelReport.ON_GROUND); // flags @10
        buffer.putFloat(base + 12, -12.5f); // spin @12
        buffer.putFloat(base + 16, 0.25f); // compression @16
        buffer.putFloat(base + 20, 0.5f); // steer @20
        buffer.putFloat(base + 24, 0.125f); // slip_long @24
        buffer.putFloat(base + 28, -0.0625f); // slip_lat @28
        buffer.putFloat(base + 32, 0.375f); // wear @32
        buffer.putInt(base + 36, 0x5A5A5A5A); // réservé @36, ignoré
    }

    @Test
    @DisplayName("ADR-176 D4 : 40 octets, l'usure à l'offset 32")
    void decodeLitLUsure() {
        assertEquals(40, WheelReport.BYTES);
        ByteBuffer buffer = ByteBuffer.allocate(WheelReport.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        poser(buffer, 0);
        assertEquals(new WheelReport(7, 3, 2, WheelReport.ON_GROUND, -12.5f, 0.25f, 0.5f, 0.125f, -0.0625f, 0.375f),
                WheelReport.decode(buffer, 0));
        List<WheelReport> section = WheelReport.decodeSection(
                (ByteBuffer) ByteBuffer.allocate(WheelReport.SECTION_HEADER_BYTES + 2 * WheelReport.BYTES)
                        .order(ByteOrder.LITTLE_ENDIAN).putInt(0, 2), 0);
        assertTrue(section.size() == 2, "deux enregistrements annoncés, deux lus");
    }
}
