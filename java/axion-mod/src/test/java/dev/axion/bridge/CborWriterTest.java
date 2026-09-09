package dev.axion.bridge;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Vérifie l'encodeur CBOR contre les vecteurs de la RFC 8949.
 *
 * <p>Un encodeur écrit à la main ne vaut que par sa confrontation à une
 * référence extérieure. Les octets attendus ci-dessous viennent de l'annexe A
 * de la RFC, pas de ce que l'implémentation produit : un test qui se contenterait
 * de figer la sortie courante validerait aussi bien un encodage faux.
 */
class CborWriterTest {

    /** Encode une seule paire et rend la partie « valeur », en-tête de map ôté. */
    private static byte[] encodeValue(Object value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("", value);
        byte[] full = CborWriter.encodeMap(map);
        // 0xA1 (map d'un élément) puis 0x60 (chaîne vide) : la valeur suit.
        assertEquals((byte) 0xA1, full[0]);
        assertEquals((byte) 0x60, full[1]);
        byte[] out = new byte[full.length - 2];
        System.arraycopy(full, 2, out, 0, out.length);
        return out;
    }

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int index = 0; index < values.length; index++) {
            out[index] = (byte) values[index];
        }
        return out;
    }

    @Test
    @DisplayName("Entiers positifs : les quatre largeurs de la RFC 8949")
    void entiersPositifs() {
        assertArrayEquals(bytes(0x00), encodeValue(0L));
        assertArrayEquals(bytes(0x0A), encodeValue(10L));
        // 23 tient dans l'octet de tête, 24 non : c'est la première bascule.
        assertArrayEquals(bytes(0x17), encodeValue(23L));
        assertArrayEquals(bytes(0x18, 0x18), encodeValue(24L));
        assertArrayEquals(bytes(0x18, 0x64), encodeValue(100L));
        assertArrayEquals(bytes(0x19, 0x03, 0xE8), encodeValue(1000L));
        assertArrayEquals(bytes(0x1A, 0x00, 0x0F, 0x42, 0x40), encodeValue(1000000L));
        assertArrayEquals(
                bytes(0x1B, 0x00, 0x00, 0x00, 0xE8, 0xD4, 0xA5, 0x10, 0x00),
                encodeValue(1000000000000L));
    }

    @Test
    @DisplayName("Entiers négatifs : encodés comme -1 - n")
    void entiersNegatifs() {
        assertArrayEquals(bytes(0x20), encodeValue(-1L));
        assertArrayEquals(bytes(0x29), encodeValue(-10L));
        assertArrayEquals(bytes(0x38, 0x63), encodeValue(-100L));
        assertArrayEquals(bytes(0x39, 0x03, 0xE7), encodeValue(-1000L));
    }

    @Test
    @DisplayName("Flottants : toujours sur 64 bits, en big-endian")
    void flottants() {
        assertArrayEquals(
                bytes(0xFB, 0x3F, 0xF8, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00), encodeValue(1.5));
        assertArrayEquals(
                bytes(0xFB, 0x40, 0x09, 0x21, 0xFB, 0x54, 0x44, 0x2D, 0x18),
                encodeValue(3.141592653589793));
        assertArrayEquals(
                bytes(0xFB, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00), encodeValue(0.0));
        // Le signe se lit dans le bit de poids fort, pas dans une valeur nulle.
        assertArrayEquals(
                bytes(0xFB, 0x80, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00), encodeValue(-0.0));
    }

    @Test
    @DisplayName("Booléens et chaînes")
    void booleensEtChaines() {
        assertArrayEquals(bytes(0xF4), encodeValue(false));
        assertArrayEquals(bytes(0xF5), encodeValue(true));

        assertArrayEquals(bytes(0x60), encodeValue(""));
        assertArrayEquals(bytes(0x61, 0x61), encodeValue("a"));
        assertArrayEquals(bytes(0x64, 0x49, 0x45, 0x54, 0x46), encodeValue("IETF"));
        // La longueur est celle des octets UTF-8, pas des caractères : « é »
        // en occupe deux.
        assertArrayEquals(bytes(0x62, 0xC3, 0xA9), encodeValue("é"));
    }

    @Test
    @DisplayName("Maps : en-tête, ordre d'insertion, et bascule au-delà de 23 entrées")
    void maps() {
        assertArrayEquals(bytes(0xA0), CborWriter.encodeMap(new LinkedHashMap<>()));

        Map<String, Object> une = new LinkedHashMap<>();
        une.put("a", 1L);
        assertArrayEquals(bytes(0xA1, 0x61, 0x61, 0x01), CborWriter.encodeMap(une));

        // 24 entrées : l'en-tête de map passe sur deux octets.
        Map<String, Object> large = new LinkedHashMap<>();
        for (int index = 0; index < 24; index++) {
            large.put("k" + index, (long) index);
        }
        byte[] encoded = CborWriter.encodeMap(large);
        assertEquals((byte) 0xB8, encoded[0]);
        assertEquals((byte) 24, encoded[1]);
    }

    @Test
    @DisplayName("Un type non supporté est refusé, pas encodé approximativement")
    void typeNonSupporteRefuse() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("liste", new int[] {1, 2, 3});
        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> CborWriter.encodeMap(map));
        // Le message doit nommer le chemin fautif, sinon il n'aide pas.
        assertEquals(true, error.getMessage().contains("liste"), error.getMessage());

        Map<String, Object> nul = new LinkedHashMap<>();
        nul.put("absent", null);
        assertThrows(IllegalArgumentException.class, () -> CborWriter.encodeMap(nul));
    }

    @Test
    @DisplayName("Integer et Float sont acceptés comme Long et Double")
    void typesEtroitsAcceptes() {
        assertArrayEquals(encodeValue(42L), encodeValue(42));
        assertArrayEquals(encodeValue(1.5), encodeValue(1.5f));
    }
}
