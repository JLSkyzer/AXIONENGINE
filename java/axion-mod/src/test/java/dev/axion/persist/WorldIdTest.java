package dev.axion.persist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** L'identifiant d'un monde pour son journal latéral (R-1743). */
class WorldIdTest {

    @Test
    @DisplayName("R-1743 : 16 chiffres hexadécimaux du SHA-256 du chemin, d'un octet nul et de la graine")
    void definition() throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        byte[] attendu = sha.digest("D:/mondes/essai\u0000-42".getBytes(StandardCharsets.UTF_8));
        assertEquals(HexFormat.of().formatHex(attendu, 0, 8), WorldId.of("D:/mondes/essai", -42));
        assertEquals(16, WorldId.of("x", 0).length());
    }

    @Test
    @DisplayName("R-1743 : stable, et distinct pour un autre chemin ou une autre graine")
    void stable() {
        assertEquals(WorldId.of("/srv/monde", 7), WorldId.of("/srv/monde", 7));
        assertNotEquals(WorldId.of("/srv/monde", 7), WorldId.of("/srv/monde", 8));
        assertNotEquals(WorldId.of("/srv/monde", 7), WorldId.of("/srv/monde2", 7));
    }
}
