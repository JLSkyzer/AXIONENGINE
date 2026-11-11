package dev.axion.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Épingle le décodage de {@link VehicleReport} sur la disposition figée d'ADR-163 D1 (24 octets, little-endian) et
 * celui de sa section dans {@code SIM_OUT}. Les octets sont posés aux offsets du natif ; si le décodeur lisait
 * ailleurs, ce test échouerait.
 */
class VehicleReportTest {

    private static ByteBuffer tampon(int octets) {
        return ByteBuffer.allocate(octets).order(ByteOrder.LITTLE_ENDIAN);
    }

    /** Pose un rapport à l'octet {@code base}, champ par champ, aux offsets du natif. */
    private static void poser(ByteBuffer buffer, int base) {
        buffer.putInt(base, 7); // handle.index @0
        buffer.putInt(base + 4, 3); // handle.generation @4
        buffer.putFloat(base + 8, 3125.5f); // rpm @8
        buffer.putFloat(base + 12, 0.75f); // load @12
        buffer.put(base + 16, (byte) -1); // gear @16
        buffer.put(base + 17, (byte) (VehicleReport.SHIFTING | VehicleReport.DRIVER)); // flags @17
        buffer.put(base + 18, (byte) 0x5A); // réservé @18, ignoré
        buffer.put(base + 19, (byte) 0x5A); // réservé @19, ignoré
        buffer.putFloat(base + 20, -0.5f); // steer @20
    }

    @Test
    @DisplayName("ADR-163 D1 : 24 octets, drapeaux du natif")
    void tailleFigee() {
        assertEquals(24, VehicleReport.BYTES);
        assertEquals(8, VehicleReport.SECTION_HEADER_BYTES, "nombre u32 et 4 octets réservés");
        assertEquals(1, VehicleReport.SHIFTING, "vehicle_report_flags::SHIFTING");
        assertEquals(2, VehicleReport.RUNNING, "vehicle_report_flags::RUNNING");
        assertEquals(4, VehicleReport.DRIVER, "vehicle_report_flags::DRIVER");
    }

    @Test
    @DisplayName("ADR-163 D1 : chaque champ se lit à son offset ; le rapport est signé")
    void decodeLitLesChampsAuxOffsetsFiges() {
        ByteBuffer buffer = tampon(VehicleReport.BYTES);
        poser(buffer, 0);
        assertEquals(new VehicleReport(7, 3, 3125.5f, 0.75f, -1, VehicleReport.SHIFTING | VehicleReport.DRIVER, -0.5f),
                VehicleReport.decode(buffer, 0));
    }

    @Test
    @DisplayName("ADR-163 D1 : la section se lit après son en-tête, et se tait tronquée")
    void section() {
        ByteBuffer buffer = tampon(4 + VehicleReport.SECTION_HEADER_BYTES + 2 * VehicleReport.BYTES);
        buffer.putInt(4, 2);
        poser(buffer, 4 + VehicleReport.SECTION_HEADER_BYTES);
        poser(buffer, 4 + VehicleReport.SECTION_HEADER_BYTES + VehicleReport.BYTES);
        List<VehicleReport> lus = VehicleReport.decodeSection(buffer, 4);
        assertEquals(2, lus.size());
        assertEquals(lus.get(0), lus.get(1));

        ByteBuffer court = tampon(VehicleReport.SECTION_HEADER_BYTES + VehicleReport.BYTES);
        court.putInt(0, 2);
        assertTrue(VehicleReport.decodeSection(court, 0).isEmpty(), "deux annoncés, un seul couvert");
        assertTrue(VehicleReport.decodeSection(tampon(4), 0).isEmpty(), "en-tête tronqué");
        assertTrue(VehicleReport.decodeSection(tampon(64), -1).isEmpty(), "offset négatif");
        ByteBuffer immense = tampon(VehicleReport.SECTION_HEADER_BYTES);
        immense.putInt(0, -1);
        assertTrue(VehicleReport.decodeSection(immense, 0).isEmpty(), "u32::MAX véhicules annoncés");
    }
}
