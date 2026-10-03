package dev.axion.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T-272 — règles d'une image avant qu'elle devienne texture (R-532, R-570, ADR-122 §7). */
class TextureRulesTest {

    @Test
    @DisplayName("R-532 : seule la signature PNG ouvre la porte, pas une autre image")
    void seuleLaSignaturePngEstAdmise() {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13};
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F'};
        assertTrue(TextureRules.isPng(png));
        assertFalse(TextureRules.isPng(jpeg), "le décodeur vanilla lirait ce JPEG");
        assertFalse(TextureRules.isPng(new byte[] {(byte) 0x89, 'P', 'N', 'G'}), "signature tronquée");
    }

    @Test
    @DisplayName("R-570 : 4096 par côté au plus, E-3006 au-delà")
    void auPlus4096ParCote() {
        assertNull(TextureRules.sizeRefusal(4096, 4096));
        assertNull(TextureRules.sizeRefusal(1, 1));
        assertTrue(TextureRules.sizeRefusal(4097, 16).startsWith("E-3006"));
        assertTrue(TextureRules.sizeRefusal(16, 4097).startsWith("E-3006"));
        assertTrue(TextureRules.sizeRefusal(0, 16).startsWith("E-3006"));
    }

    @Test
    @DisplayName("Les mipmaps suivent le réglage vanilla, sans descendre sous un pixel")
    void lesMipmapsSuiventLeReglageVanilla() {
        assertEquals(4, TextureRules.mipLevels(4, 256, 256));
        assertEquals(1, TextureRules.mipLevels(4, 2, 64), "le petit côté décide");
        assertEquals(2, TextureRules.mipLevels(4, 5, 8), "5 → 2 → 1");
        assertEquals(0, TextureRules.mipLevels(4, 1, 1));
        assertEquals(0, TextureRules.mipLevels(0, 256, 256), "mipmaps éteints dans les options");
    }

    @Test
    @DisplayName("La découpe binarise l'alpha au seuil du matériau, inclus")
    void laDecoupeBinariseAuSeuil() {
        assertEquals(255, TextureRules.cutoutAlpha(128, 0.5f), "128/255 atteint 0,5");
        assertEquals(0, TextureRules.cutoutAlpha(127, 0.5f));
        assertEquals(255, TextureRules.cutoutAlpha(255, 1.0f));
        assertEquals(0, TextureRules.cutoutAlpha(254, 1.0f));
        assertEquals(255, TextureRules.cutoutAlpha(0, 0.0f), "un seuil nul garde tout");
        assertEquals(0, TextureRules.cutoutAlpha(255, Float.POSITIVE_INFINITY), "un seuil infini coupe tout");
    }
}
