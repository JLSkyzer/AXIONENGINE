package dev.axion.asset;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ADR-139 — les octets que {@link NativeAssetCompiler} échange avec le natif, hors JNI.
 *
 * <p>Le dialogue complet, sur la vraie bibliothèque, est dans {@code NativeBridgeTest} ; le
 * natif vérifie de son côté la table qu'il reçoit ({@code asset_siblings_abi}). Ce test fixe
 * l'agencement que Java écrit et lit, octet par octet, tel qu'ADR-139 le décrit.
 */
class NativeAssetCompilerTest {

    private static ByteBuffer le(int capacity) {
        return ByteBuffer.allocate(capacity).order(ByteOrder.LITTLE_ENDIAN);
    }

    @Test
    @DisplayName("ADR-139 : la table des voisins, octet par octet — présent, puis absent")
    void laTableSuitLAgencementDeLADR() {
        TreeMap<String, byte[]> files = new TreeMap<>();
        files.put("sub/b.bin", null);
        files.put("a.mtl", new byte[] {7, 8});

        ByteBuffer attendu = le(4 + (16 + 5 + 2) + (16 + 9));
        attendu.putInt(2);
        attendu.putInt(5).putInt(0).putLong(2).put("a.mtl".getBytes(StandardCharsets.UTF_8));
        attendu.put(new byte[] {7, 8});
        attendu.putInt(9).putInt(1).putLong(0).put("sub/b.bin".getBytes(StandardCharsets.UTF_8));

        assertArrayEquals(attendu.array(), NativeAssetCompiler.table(files));
    }

    @Test
    @DisplayName("ADR-139 : une table vide n'est que son compte")
    void uneTableVideNEstQueSonCompte() {
        assertArrayEquals(new byte[4], NativeAssetCompiler.table(new TreeMap<>()));
    }

    @Test
    @DisplayName("ADR-139 : la liste de NEEDS_FILES se relit, une liste mal formée est refusée")
    void laListeDesNomsSeRelit() {
        ByteBuffer liste = le(4 + 4 + 5 + 4 + 9);
        liste.putInt(2);
        liste.putInt(5).put("a.mtl".getBytes(StandardCharsets.UTF_8));
        liste.putInt(9).put("sub/b.bin".getBytes(StandardCharsets.UTF_8));
        assertEquals(List.of("a.mtl", "sub/b.bin"), NativeAssetCompiler.decodeNames(liste.array()));
        assertEquals(List.of(), NativeAssetCompiler.decodeNames(new byte[4]));

        // Trop court pour un compte.
        assertNull(NativeAssetCompiler.decodeNames(new byte[3]));
        // Un octet de trop après le dernier nom.
        assertNull(NativeAssetCompiler.decodeNames(le(5).putInt(0).array()));
        // Plus de noms annoncés que d'octets pour les porter.
        assertNull(NativeAssetCompiler.decodeNames(le(8).putInt(2).putInt(0).array()));
        // Un compte négatif.
        assertNull(NativeAssetCompiler.decodeNames(le(4).putInt(-1).array()));
        // Une longueur de nom au-delà de ce qui reste, puis négative.
        assertNull(NativeAssetCompiler.decodeNames(le(9).putInt(1).putInt(2).put((byte) 'a').array()));
        assertNull(NativeAssetCompiler.decodeNames(le(8).putInt(1).putInt(-1).array()));
    }
}
