package dev.axion.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Le codec des messages AXION (C-51, ADR-150) : ce qui s'écrit se relit à l'identique, et ce qui vient
 * du réseau est validé avant d'être cru (R-1650, T-422).
 */
class WireCodecTest {

    private static byte[] bytes(int... values) {
        byte[] bytes = new byte[values.length];
        for (int index = 0; index < values.length; index++) {
            bytes[index] = (byte) values[index];
        }
        return bytes;
    }

    @Test
    @DisplayName("Chaque primitive se relit telle qu'elle a été écrite, bornes comprises")
    void lesPrimitivesFontLAllerRetour() throws MalformedPacketException {
        byte[] wire = new WireWriter()
                .u8(0).u8(255).bool(true).bool(false)
                .u16(0).u16(65_535).i16(Short.MIN_VALUE).i16(Short.MAX_VALUE)
                .i32(Integer.MIN_VALUE).i32(-1).i64(Long.MIN_VALUE).i64(Long.MAX_VALUE)
                .f32(1.5f).f32(Float.NEGATIVE_INFINITY)
                .varUInt(0).varUInt(127).varUInt(128).varUInt(Integer.MAX_VALUE).varULong(Long.MAX_VALUE)
                .bytes(bytes(1, 2, 3)).utf8("déjà vu € 😀").utf8("")
                .toByteArray();

        WireReader reader = new WireReader(wire);
        assertEquals(0, reader.u8());
        assertEquals(255, reader.u8());
        assertTrue(reader.bool());
        assertFalse(reader.bool());
        assertEquals(0, reader.u16());
        assertEquals(65_535, reader.u16());
        assertEquals(Short.MIN_VALUE, reader.i16());
        assertEquals(Short.MAX_VALUE, reader.i16());
        assertEquals(Integer.MIN_VALUE, reader.i32());
        assertEquals(-1, reader.i32());
        assertEquals(Long.MIN_VALUE, reader.i64());
        assertEquals(Long.MAX_VALUE, reader.i64());
        assertEquals(1.5f, reader.f32());
        assertEquals(Float.NEGATIVE_INFINITY, reader.f32());
        assertEquals(0, reader.varUInt());
        assertEquals(127, reader.varUInt());
        assertEquals(128, reader.varUInt());
        assertEquals(Integer.MAX_VALUE, reader.varUInt());
        assertEquals(Long.MAX_VALUE, reader.varULong());
        assertArrayEquals(bytes(1, 2, 3), reader.bytes(3));
        assertEquals("déjà vu € 😀", reader.utf8(64));
        assertEquals("", reader.utf8(0));
        reader.expectEnd();
    }

    @Test
    @DisplayName("Big-endian, et varints au codage minimal, octet pour octet")
    void leCodageEstCeluiAnnonce() {
        assertArrayEquals(bytes(0x12, 0x34), new WireWriter().u16(0x1234).toByteArray());
        assertArrayEquals(bytes(0x01, 0x02, 0x03, 0x04), new WireWriter().i32(0x01020304).toByteArray());
        assertArrayEquals(bytes(0x00), new WireWriter().varUInt(0).toByteArray());
        assertArrayEquals(bytes(0x7F), new WireWriter().varUInt(127).toByteArray());
        assertArrayEquals(bytes(0x80, 0x01), new WireWriter().varUInt(128).toByteArray());
        assertArrayEquals(bytes(0xAC, 0x02), new WireWriter().varUInt(300).toByteArray());
        assertArrayEquals(bytes(0xFF, 0xFF, 0xFF, 0xFF, 0x07),
                new WireWriter().varUInt(Integer.MAX_VALUE).toByteArray());
        assertEquals(9, new WireWriter().varULong(Long.MAX_VALUE).size());
    }

    @Test
    @DisplayName("L'écrivain refuse une valeur hors de son domaine : c'est une faute de l'émetteur")
    void lEcrivainRefuseLesValeursHorsDomaine() {
        assertThrows(IllegalArgumentException.class, () -> new WireWriter().u8(256));
        assertThrows(IllegalArgumentException.class, () -> new WireWriter().u8(-1));
        assertThrows(IllegalArgumentException.class, () -> new WireWriter().u16(65_536));
        assertThrows(IllegalArgumentException.class, () -> new WireWriter().varUInt(-1));
        assertThrows(IllegalArgumentException.class, () -> new WireWriter().varULong(-1));
    }

    @Test
    @DisplayName("T-422 : une lecture au-delà de la fin est une faute de paquet")
    void t422_uneLectureAuDelaDeLaFinEstRefusee() {
        assertThrows(MalformedPacketException.class, () -> new WireReader(bytes()).u8());
        assertThrows(MalformedPacketException.class, () -> new WireReader(bytes(1)).i16());
        assertThrows(MalformedPacketException.class, () -> new WireReader(bytes(1, 2, 3)).i32());
        assertThrows(MalformedPacketException.class, () -> new WireReader(bytes(1, 2, 3, 4, 5, 6, 7)).i64());
        assertThrows(MalformedPacketException.class, () -> new WireReader(bytes(0x80)).varUInt());
    }

    @Test
    @DisplayName("T-422 : un varint non minimal, trop long ou hors de ses bits est refusé")
    void t422_unVarintNonCanoniqueEstRefuse() {
        assertThrows(MalformedPacketException.class, () -> new WireReader(bytes(0x80, 0x00)).varUInt());
        assertThrows(MalformedPacketException.class, () -> new WireReader(bytes(0xFF, 0x80, 0x00)).varUInt());
        // 2^32 - 1 : cinq octets valides, mais au-delà des 31 bits d'un varUInt.
        assertThrows(MalformedPacketException.class,
                () -> new WireReader(bytes(0xFF, 0xFF, 0xFF, 0xFF, 0x0F)).varUInt());
        assertThrows(MalformedPacketException.class,
                () -> new WireReader(bytes(0x81, 0x81, 0x81, 0x81, 0x81, 0x01)).varUInt());
        assertThrows(MalformedPacketException.class, () -> new WireReader(
                bytes(0x81, 0x81, 0x81, 0x81, 0x81, 0x81, 0x81, 0x81, 0x81, 0x01)).varULong());
    }

    @Test
    @DisplayName("T-422 : un booléen autre que 0 ou 1 est refusé")
    void t422_unBooleenAutreQueZeroOuUnEstRefuse() {
        assertThrows(MalformedPacketException.class, () -> new WireReader(bytes(2)).bool());
        assertThrows(MalformedPacketException.class, () -> new WireReader(bytes(0xFF)).bool());
    }

    @Test
    @DisplayName("T-422 : une longueur au-delà de la borne ou de ce qui reste est refusée sans allouer")
    void t422_uneLongueurAnnonceeNAllouePasAvantDEtreVerifiee() {
        assertThrows(MalformedPacketException.class, () -> new WireReader(bytes(4, 1, 2, 3, 4)).bytes(3));
        assertThrows(MalformedPacketException.class, () -> new WireReader(bytes(4, 1, 2)).bytes(16));
        // Une longueur de 2^31 - 1 annoncée sur trois octets : refusée avant toute allocation.
        byte[] huge = new WireWriter().varUInt(Integer.MAX_VALUE).raw(bytes(1, 2, 3)).toByteArray();
        assertThrows(MalformedPacketException.class, () -> new WireReader(huge).bytes(Integer.MAX_VALUE));
    }

    @Test
    @DisplayName("T-422 : un UTF-8 mal formé est refusé, jamais remplacé")
    void t422_unUtf8MalFormeEstRefuse() {
        assertThrows(MalformedPacketException.class, () -> new WireReader(bytes(2, 0xC3, 0x28)).utf8(16));
        // Codage trop long de « / » : un décodeur laxiste l'accepterait.
        assertThrows(MalformedPacketException.class, () -> new WireReader(bytes(2, 0xC0, 0xAF)).utf8(16));
        assertThrows(MalformedPacketException.class, () -> new WireReader(bytes(1, 0xFF)).utf8(16));
    }

    @Test
    @DisplayName("T-422 : un octet de trop est une faute, pas un ajout ignoré")
    void t422_unOctetEnTropEstUneFaute() throws MalformedPacketException {
        WireReader reader = new WireReader(bytes(7, 8));
        reader.u8();
        assertThrows(MalformedPacketException.class, reader::expectEnd);
    }

    @Test
    @DisplayName("T-422 : des octets quelconques ne lèvent que des fautes de paquet, jamais autre chose")
    void t422_desOctetsQuelconquesNeLeventQueDesFautesDePaquet() {
        Random random = new Random(0x5EED_A410L);
        int faults = 0;
        for (int round = 0; round < 20_000; round++) {
            byte[] data = new byte[random.nextInt(48)];
            random.nextBytes(data);
            WireReader reader = new WireReader(data);
            try {
                for (int step = 0; step < 12; step++) {
                    switch (random.nextInt(11)) {
                        case 0 -> reader.u8();
                        case 1 -> reader.bool();
                        case 2 -> reader.u16();
                        case 3 -> reader.i32();
                        case 4 -> reader.i64();
                        case 5 -> reader.f32();
                        case 6 -> reader.varUInt();
                        case 7 -> reader.varULong();
                        case 8 -> reader.bytes(random.nextInt(64));
                        case 9 -> reader.utf8(random.nextInt(64));
                        default -> reader.expectEnd();
                    }
                }
            } catch (MalformedPacketException expected) {
                faults++;
            } catch (RuntimeException | Error other) {
                fail("octets " + java.util.Arrays.toString(data) + " : " + other);
            }
        }
        assertTrue(faults > 0, "aucune faute vue : le test ne lit pas assez");
    }
}
