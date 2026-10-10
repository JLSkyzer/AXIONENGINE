package dev.axion.render;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-905 — R-760 : le cache binaire des shaders, valide, et invalidé au changement de pilote ou de
 * sources (ADR-127 §4). Clé ({@link ShaderCacheKey}) et fichier ({@link ShaderBinaryFile}).
 */
class ShaderCacheTest {

    private static final ShaderVariant CUTOUT = ShaderVariant.of(ShaderVariant.Define.CUTOUT);

    private static String key(String vendor, String renderer, String version) {
        return ShaderCacheKey.of("surface", CUTOUT, "#version 330 core\nvoid main() {}\n",
                "#version 330 core\nout vec4 o;\nvoid main() { o = vec4(1.0); }\n", vendor, renderer, version);
    }

    @Test
    @DisplayName("T-905 : la même source sous le même pilote donne la même clé, 64 chiffres hexadécimaux")
    void cleStable() {
        String a = key("NVIDIA Corporation", "RTX 4060", "4.6.0 NVIDIA 616.92");
        assertEquals(a, key("NVIDIA Corporation", "RTX 4060", "4.6.0 NVIDIA 616.92"));
        assertTrue(a.matches("[0-9a-f]{64}"), a);
    }

    @Test
    @DisplayName("T-905 : un autre pilote — fabricant, carte ou version — invalide le binaire")
    void autrePilote() {
        String a = key("NVIDIA Corporation", "RTX 4060", "4.6.0 NVIDIA 616.92");
        assertNotEquals(a, key("Mesa", "RTX 4060", "4.6.0 NVIDIA 616.92"));
        assertNotEquals(a, key("NVIDIA Corporation", "llvmpipe", "4.6.0 NVIDIA 616.92"));
        assertNotEquals(a, key("NVIDIA Corporation", "RTX 4060", "4.6.0 NVIDIA 620.01"));
    }

    @Test
    @DisplayName("T-905 : d'autres sources, d'autres définitions ou un autre programme invalident le binaire")
    void autresSources() {
        String vertex = "#version 330 core\nvoid main() {}\n";
        String fragment = "#version 330 core\nout vec4 o;\nvoid main() { o = vec4(1.0); }\n";
        String a = ShaderCacheKey.of("surface", CUTOUT, vertex, fragment, "v", "r", "4.6");
        assertNotEquals(a, ShaderCacheKey.of("surface", CUTOUT, vertex + " ", fragment, "v", "r", "4.6"));
        assertNotEquals(a, ShaderCacheKey.of("surface", CUTOUT, vertex, fragment + " ", "v", "r", "4.6"));
        assertNotEquals(a, ShaderCacheKey.of("surface", ShaderVariant.BASE, vertex, fragment, "v", "r", "4.6"));
        assertNotEquals(a, ShaderCacheKey.of("emission", CUTOUT, vertex, fragment, "v", "r", "4.6"));
    }

    @Test
    @DisplayName("T-905 : les champs entrent avec leur longueur — un autre découpage des mêmes caractères change la clé")
    void decoupage() {
        String a = ShaderCacheKey.of("p", CUTOUT, "ab", "c", "v", "r", "4.6");
        String b = ShaderCacheKey.of("p", CUTOUT, "a", "bc", "v", "r", "4.6");
        assertNotEquals(a, b);
    }

    @Test
    @DisplayName("T-905 : un binaire écrit se relit tel quel, format compris")
    void allerRetour() {
        byte[] binaire = new byte[300];
        for (int i = 0; i < binaire.length; i++) {
            binaire[i] = (byte) (i * 37);
        }
        Optional<ShaderBinaryFile.Binary> relu =
                ShaderBinaryFile.decode(ShaderBinaryFile.encode(new ShaderBinaryFile.Binary(0x8E21, binaire)));
        assertTrue(relu.isPresent());
        assertEquals(0x8E21, relu.get().format());
        assertArrayEquals(binaire, relu.get().bytes());

        Optional<ShaderBinaryFile.Binary> vide =
                ShaderBinaryFile.decode(ShaderBinaryFile.encode(new ShaderBinaryFile.Binary(7, new byte[0])));
        assertTrue(vide.isPresent());
        assertEquals(0, vide.get().bytes().length);
    }

    @Test
    @DisplayName("T-905 : un fichier tronqué, corrompu ou d'un autre schéma n'est pas relu")
    void fichiersRefuses() {
        byte[] fichier = ShaderBinaryFile.encode(new ShaderBinaryFile.Binary(1, new byte[] {1, 2, 3, 4, 5}));

        assertTrue(ShaderBinaryFile.decode(Arrays.copyOf(fichier, 10)).isEmpty(), "en-tête tronqué");
        assertTrue(ShaderBinaryFile.decode(Arrays.copyOf(fichier, fichier.length - 1)).isEmpty(), "binaire tronqué");

        byte[] corrompu = fichier.clone();
        corrompu[corrompu.length - 1] ^= 1;
        assertTrue(ShaderBinaryFile.decode(corrompu).isEmpty(), "CRC faux");

        assertTrue(ShaderBinaryFile.decode(champ(fichier, 0, 0x12345678)).isEmpty(), "autre magic");
        assertTrue(ShaderBinaryFile.decode(court(fichier, 4, (short) 2)).isEmpty(), "autre schéma");
        assertTrue(ShaderBinaryFile.decode(court(fichier, 6, (short) 1)).isEmpty(), "réserve non nulle");
        assertTrue(ShaderBinaryFile.decode(champ(fichier, 12, 6)).isEmpty(), "longueur fausse");
    }

    private static byte[] champ(byte[] fichier, int at, int valeur) {
        byte[] copie = fichier.clone();
        ByteBuffer.wrap(copie).order(ByteOrder.LITTLE_ENDIAN).putInt(at, valeur);
        return copie;
    }

    private static byte[] court(byte[] fichier, int at, short valeur) {
        byte[] copie = fichier.clone();
        ByteBuffer.wrap(copie).order(ByteOrder.LITTLE_ENDIAN).putShort(at, valeur);
        return copie;
    }
}
